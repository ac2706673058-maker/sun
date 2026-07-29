package com.murmur.app.ui.chat

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.murmur.app.data.Repository
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.data.db.ChatEntity
import com.murmur.app.data.db.MessageEntity
import com.murmur.app.engine.BudgetReport
import com.murmur.app.net.StreamEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ChatUiState(
    val chat: ChatEntity? = null,
    val character: CharacterEntity? = null,
    val messages: List<MessageEntity> = emptyList(),
)

sealed interface GenMode {
    /** 生成一条全新回复 */
    data object New : GenMode
    /** 给已有消息追加一个候选（重roll） */
    data class Swipe(val messageId: String) : GenMode
    /** 接着已有消息往下写 */
    data class Continue(val messageId: String) : GenMode
    /** 以用户身份代写，结果回填到输入框 */
    data object Impersonate : GenMode
}

@Suppress("OPT_IN_USAGE")
class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository.get(app)

    private val chatIdFlow = MutableStateFlow<String?>(null)
    val chatId: String? get() = chatIdFlow.value

    private val chatFlow = chatIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(null) else repo.chats.observe(id)
    }

    private val messagesFlow = chatIdFlow.flatMapLatest { id ->
        if (id == null) flowOf(emptyList()) else repo.messages.observeForChat(id)
    }

    // 每条消息都会 touch 一次会话，所以这里必须去重，
    // 否则角色流会被反复重订阅，界面上看得到闪。
    private val characterFlow = chatFlow
        .map { it?.characterId }
        .distinctUntilChanged()
        .flatMapLatest { cid -> if (cid == null) flowOf(null) else repo.characters.observe(cid) }

    val ui = combine(chatFlow, characterFlow, messagesFlow) { chat, character, msgs ->
        ChatUiState(chat = chat, character = character, messages = msgs)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), ChatUiState())

    var input by mutableStateOf("")
    var streamingText by mutableStateOf("")
        private set
    var reasoningText by mutableStateOf("")
        private set
    var isGenerating by mutableStateOf(false)
        private set
    var generatingMode by mutableStateOf<GenMode?>(null)
        private set
    var error by mutableStateOf<String?>(null)
    var contextReport by mutableStateOf<BudgetReport?>(null)
        private set
    var lastPromptPreview by mutableStateOf<String?>(null)
        private set

    private var genJob: Job? = null
    private var drainJob: Job? = null
    private val pending = StringBuilder()
    private var smoothing = true

    fun open(id: String) {
        if (chatIdFlow.value == id) return
        cancelGeneration(persistPartial = true)
        chatIdFlow.value = id
        streamingText = ""
        reasoningText = ""
        error = null
    }

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || isGenerating) return
        input = ""
        val id = chatId ?: return
        viewModelScope.launch {
            repo.appendMessage(id, "user", text)
            generate(GenMode.New)
        }
    }

    /** 不输入内容，直接让角色继续说 —— 沉默也是一种回应。 */
    fun nudge() {
        if (isGenerating) return
        generate(GenMode.New)
    }

    fun regenerate(messageId: String) {
        if (isGenerating) return
        generate(GenMode.Swipe(messageId))
    }

    fun continueMessage(messageId: String) {
        if (isGenerating) return
        generate(GenMode.Continue(messageId))
    }

    fun impersonate() {
        if (isGenerating) return
        generate(GenMode.Impersonate)
    }

    fun generate(mode: GenMode) {
        val id = chatId ?: return
        genJob?.cancel()
        error = null
        streamingText = ""
        reasoningText = ""
        synchronized(pending) { pending.setLength(0) }
        isGenerating = true
        generatingMode = mode

        genJob = viewModelScope.launch {
            val startedAt = System.currentTimeMillis()
            val app = repo.settings.get()
            smoothing = app.streamingSmoothing

            val upToExclusive = when (mode) {
                is GenMode.Swipe -> repo.messages.get(mode.messageId)?.idx
                else -> null
            }
            val directive = when (mode) {
                is GenMode.Continue ->
                    "接着上面这条未写完的内容往下写。不要重新开头，不要复述已经写过的部分，直接从断掉的地方续下去。"
                GenMode.Impersonate -> com.murmur.app.engine.PromptTemplates.IMPERSONATE
                else -> null
            }

            val pair = repo.buildPrompt(id, upToExclusive, directive)
            if (pair == null) {
                error = "还没配置接口。到「设置 → 接口」里填一下地址、Key 和模型。"
                isGenerating = false
                generatingMode = null
                return@launch
            }
            val (built, profile) = pair
            contextReport = built.report
            lastPromptPreview = built.messages.joinToString("\n\n───────\n\n") {
                "【${it.role}】\n${it.content}"
            }

            if (smoothing) startDrain()

            var failed = false
            repo.client.chat(profile, built.messages).collect { ev ->
                when (ev) {
                    is StreamEvent.Delta -> {
                        if (smoothing) synchronized(pending) { pending.append(ev.text) }
                        else streamingText += ev.text
                    }
                    is StreamEvent.Reasoning -> reasoningText += ev.text
                    is StreamEvent.Failed -> {
                        failed = true
                        error = ev.message
                    }
                    is StreamEvent.Done -> Unit
                }
            }

            flushPending()
            drainJob?.cancel()
            drainJob = null

            val produced = streamingText.trim()
            val elapsed = System.currentTimeMillis() - startedAt

            if (!failed && produced.isNotEmpty()) {
                commit(mode, produced, profile.model, elapsed)
                streamingText = ""
                reasoningText = ""
                if (mode !is GenMode.Impersonate) {
                    launch { runCatching { repo.runMaintenance(id) } }
                }
            } else if (failed && produced.isNotEmpty()) {
                // 中途断了但已经有内容：留下来，别让用户白等
                commit(mode, produced, profile.model, elapsed)
                streamingText = ""
            }

            isGenerating = false
            generatingMode = null
        }
    }

    private suspend fun commit(mode: GenMode, text: String, model: String, elapsed: Long) {
        val id = chatId ?: return
        when (mode) {
            GenMode.New -> repo.appendMessage(id, "assistant", text, model, elapsed)
            is GenMode.Swipe -> repo.addSwipe(mode.messageId, text, model, elapsed)
            is GenMode.Continue -> {
                val m = repo.messages.get(mode.messageId) ?: return
                val joined = (m.content.trimEnd() + " " + text.trimStart()).trim()
                repo.updateMessageContent(mode.messageId, joined)
            }
            GenMode.Impersonate -> {
                input = if (input.isBlank()) text else input.trimEnd() + " " + text
            }
        }
    }

    private fun startDrain() {
        drainJob?.cancel()
        drainJob = viewModelScope.launch {
            while (isActive) {
                var chunk: String? = null
                synchronized(pending) {
                    if (pending.isNotEmpty()) {
                        // 缓冲越多吐得越快，既不会卡顿也不会落后太多
                        val n = (pending.length / 5).coerceIn(1, 24).coerceAtMost(pending.length)
                        chunk = pending.substring(0, n)
                        pending.delete(0, n)
                    }
                }
                chunk?.let { streamingText += it }
                delay(16)
            }
        }
    }

    private fun flushPending() {
        synchronized(pending) {
            if (pending.isNotEmpty()) {
                streamingText += pending.toString()
                pending.setLength(0)
            }
        }
    }

    fun stop() = cancelGeneration(persistPartial = true)

    private fun cancelGeneration(persistPartial: Boolean) {
        val job = genJob ?: return
        val mode = generatingMode
        val partial = streamingText.trim()
        job.cancel()
        genJob = null
        drainJob?.cancel()
        drainJob = null
        synchronized(pending) { pending.setLength(0) }
        isGenerating = false
        generatingMode = null
        if (persistPartial && partial.isNotEmpty() && mode != null && mode !is GenMode.Impersonate) {
            viewModelScope.launch {
                commit(mode, partial, "", 0)
                streamingText = ""
            }
        } else {
            streamingText = ""
        }
        reasoningText = ""
    }

    // ---- 消息操作 ----

    fun swipeTo(messageId: String, index: Int) = viewModelScope.launch {
        repo.setActiveSwipe(messageId, index)
    }

    fun editMessage(messageId: String, text: String) = viewModelScope.launch {
        repo.updateMessageContent(messageId, text)
    }

    fun deleteMessage(messageId: String) = viewModelScope.launch {
        repo.deleteMessage(messageId)
    }

    fun deleteFromHere(idx: Long) = viewModelScope.launch {
        chatId?.let { repo.deleteFrom(it, idx) }
    }

    fun togglePin(messageId: String) = viewModelScope.launch { repo.togglePinned(messageId) }

    fun toggleHide(messageId: String) = viewModelScope.launch { repo.toggleHidden(messageId) }

    fun deleteSwipe(messageId: String) = viewModelScope.launch { repo.deleteSwipe(messageId) }

    suspend fun branchFrom(idx: Long): String? = chatId?.let { repo.branchFrom(it, idx) }

    override fun onCleared() {
        super.onCleared()
        genJob?.cancel()
        drainJob?.cancel()
    }
}
