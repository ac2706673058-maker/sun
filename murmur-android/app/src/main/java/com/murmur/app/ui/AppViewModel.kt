package com.murmur.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.murmur.app.data.CharacterCardIO
import com.murmur.app.data.Repository
import com.murmur.app.data.AppSettings
import com.murmur.app.data.db.ApiProfileEntity
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.data.db.LoreEntity
import com.murmur.app.data.db.MemoryEntity
import com.murmur.app.data.db.PersonaEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = Repository.get(app)
    val repository get() = repo

    val settings = repo.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppSettings())

    /**
     * null = DataStore 还没读回来。
     * 导航的起始页取决于 onboarded，而 NavHost 只在首次组合时读一次 startDestination——
     * 要是这时候还在用默认值，已经引导过的用户每次冷启动都会重看一遍引导页。
     */
    val bootstrap = repo.settings.flow
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val characters = repo.characters.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val chats = repo.chats.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val personas = repo.personas.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val apiProfiles = repo.apiProfiles.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val activeProfile = repo.apiProfiles.observeActive()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    init {
        viewModelScope.launch { repo.seedIfNeeded() }
    }

    fun updateSettings(transform: (AppSettings) -> AppSettings) = viewModelScope.launch {
        repo.settings.update(transform)
    }

    // ---- 会话 ----

    suspend fun openOrCreateChat(characterId: String): String = repo.ensureChatFor(characterId)

    suspend fun newChat(characterId: String, greetingIndex: Int = 0): String =
        repo.createChat(characterId, greetingIndex)

    /** 自定义开局：不用角色自带的开场白，直接从一段情境开始。 */
    suspend fun newChatWithScenario(characterId: String, scenario: String): String =
        repo.createChat(characterId, openingScenario = scenario)

    suspend fun getCharacter(id: String) = repo.characters.get(id)

    suspend fun mostRecentChatId(): String? = repo.chats.mostRecent()?.id

    fun deleteChat(id: String) = viewModelScope.launch { repo.deleteChat(id) }

    fun togglePinChat(id: String) = viewModelScope.launch {
        repo.chats.get(id)?.let { repo.chats.upsert(it.copy(pinned = !it.pinned)) }
    }

    // ---- 角色 ----

    fun saveCharacter(c: CharacterEntity) = viewModelScope.launch { repo.upsertCharacter(c) }

    fun deleteCharacter(id: String) = viewModelScope.launch { repo.deleteCharacter(id) }

    suspend fun duplicateCharacter(id: String): String? = repo.duplicateCharacter(id)

    fun newCharacter(): CharacterEntity = CharacterEntity(
        id = UUID.randomUUID().toString(),
        name = "",
    )

    fun observeCharacter(id: String) = repo.characters.observe(id)

    fun observeLore(characterId: String) = repo.lore.observeForCharacter(characterId)

    fun saveLore(e: LoreEntity) = viewModelScope.launch { repo.upsertLore(e) }

    fun deleteLore(id: String) = viewModelScope.launch { repo.lore.deleteById(id) }

    private suspend fun readBytes(uri: Uri): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            getApplication<Application>().contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()
    }

    fun setAvatar(characterId: String, uri: Uri) = viewModelScope.launch {
        val bytes = readBytes(uri) ?: return@launch
        val path = repo.saveImage("avatars", characterId, bytes) ?: return@launch
        repo.characters.get(characterId)?.let { repo.upsertCharacter(it.copy(avatarUri = path)) }
    }

    fun setPortrait(characterId: String, uri: Uri) = viewModelScope.launch {
        val bytes = readBytes(uri) ?: return@launch
        val path = repo.saveImage("portraits", characterId, bytes) ?: return@launch
        repo.characters.get(characterId)?.let { repo.upsertCharacter(it.copy(portraitUri = path)) }
    }

    fun setCharacterBackground(characterId: String, uri: Uri) = viewModelScope.launch {
        val bytes = readBytes(uri) ?: return@launch
        val path = repo.saveImage("backgrounds", characterId, bytes) ?: return@launch
        repo.characters.get(characterId)?.let { repo.upsertCharacter(it.copy(backgroundUri = path)) }
    }

    fun clearCharacterImages(characterId: String, portrait: Boolean, background: Boolean) =
        viewModelScope.launch {
            repo.characters.get(characterId)?.let {
                repo.upsertCharacter(
                    it.copy(
                        portraitUri = if (portrait) null else it.portraitUri,
                        backgroundUri = if (background) null else it.backgroundUri,
                    )
                )
            }
        }

    fun setGlobalBackground(uri: Uri?) = viewModelScope.launch {
        if (uri == null) {
            repo.settings.update { it.copy(globalBackgroundUri = null) }
            return@launch
        }
        val bytes = readBytes(uri) ?: return@launch
        val path = repo.saveImage("backgrounds", "global", bytes) ?: return@launch
        repo.settings.update { it.copy(globalBackgroundUri = path) }
    }

    /** 导入角色卡：PNG（内嵌 JSON）或纯 JSON 都吃。 */
    suspend fun importCard(uri: Uri): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            val bytes = getApplication<Application>().contentResolver.openInputStream(uri)
                ?.use { it.readBytes() } ?: error("读不到这个文件")
            val card = if (CharacterCardIO.isPng(bytes)) {
                CharacterCardIO.parsePngCard(bytes)
                    ?: error("这张 PNG 里没有角色卡数据。确认是 SillyTavern / Chub 导出的卡片。")
            } else {
                CharacterCardIO.parseJsonCard(String(bytes, Charsets.UTF_8))
                    ?: error("解析不了这个文件。支持 SillyTavern 的 PNG 卡片和 JSON 角色卡。")
            }
            repo.importCard(card)
        }
    }

    suspend fun exportCharacter(id: String): String? = repo.exportCharacter(id)

    // ---- 人设 ----

    fun savePersona(p: PersonaEntity) = viewModelScope.launch {
        if (p.isDefault) repo.personas.clearDefault()
        repo.personas.upsert(p)
        if (p.isDefault) repo.settings.update { it.copy(activePersonaId = p.id) }
    }

    fun deletePersona(id: String) = viewModelScope.launch { repo.personas.deleteById(id) }

    fun newPersona() = PersonaEntity(id = UUID.randomUUID().toString(), name = "")

    // ---- 接口 ----

    fun saveProfile(p: ApiProfileEntity) = viewModelScope.launch {
        if (p.isActive) repo.apiProfiles.clearActive()
        repo.apiProfiles.upsert(p)
    }

    fun activateProfile(id: String) = viewModelScope.launch {
        repo.apiProfiles.clearActive()
        repo.apiProfiles.get(id)?.let { repo.apiProfiles.upsert(it.copy(isActive = true)) }
    }

    fun deleteProfile(id: String) = viewModelScope.launch { repo.apiProfiles.deleteById(id) }

    fun newProfile() = ApiProfileEntity(id = UUID.randomUUID().toString(), name = "新接口")

    suspend fun fetchModels(p: ApiProfileEntity): Result<List<String>> =
        repo.client.listModels(p.baseUrl, p.apiKey, p.extraHeadersJson)

    /** 发一句最小的请求，验证地址/Key/模型三件事是不是都对。 */
    suspend fun testProfile(p: ApiProfileEntity): Result<String> = runCatching {
        val r = repo.client.complete(
            profile = p.copy(stream = false),
            messages = listOf(com.murmur.app.net.WireMessage("user", "回复两个字：好的")),
            model = p.model,
            maxTokens = 24,
            temperature = 0.2f,
        )
        r.getOrThrow().ifBlank { "连上了，但模型没说话。" }
    }

    // ---- 记忆 ----

    fun observeMemories(chatId: String) = repo.memories.observeForChat(chatId)

    fun saveMemory(m: MemoryEntity) = viewModelScope.launch { repo.memories.upsert(m) }

    fun deleteMemory(id: String) = viewModelScope.launch { repo.memories.deleteById(id) }

    fun newMemory(chatId: String) = MemoryEntity(
        id = UUID.randomUUID().toString(),
        chatId = chatId,
        text = "",
    )

    fun observeChat(chatId: String) = repo.chats.observe(chatId)

    fun updateSummary(chatId: String, summary: String) = viewModelScope.launch {
        repo.chats.get(chatId)?.let { repo.chats.upsert(it.copy(rollingSummary = summary)) }
    }
}
