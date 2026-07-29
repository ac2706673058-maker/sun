package com.murmur.app.data

import android.content.Context
import com.murmur.app.data.db.ApiProfileEntity
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.data.db.ChatEntity
import com.murmur.app.data.db.LoreEntity
import com.murmur.app.data.db.MemoryEntity
import com.murmur.app.data.db.MessageEntity
import com.murmur.app.data.db.MurmurDb
import com.murmur.app.data.db.PersonaEntity
import com.murmur.app.data.db.SceneState
import com.murmur.app.engine.BuiltPrompt
import com.murmur.app.engine.ContextBuilder
import com.murmur.app.engine.ContextInput
import com.murmur.app.engine.ContextMaintainer
import com.murmur.app.engine.PromptTemplates
import com.murmur.app.net.OpenAiClient
import java.io.File
import java.util.UUID

class Repository private constructor(private val appContext: Context) {

    val db = MurmurDb.get(appContext)
    val settings = SettingsStore(appContext)
    val client = OpenAiClient()
    private val maintainer = ContextMaintainer(client)

    val characters get() = db.characters()
    val personas get() = db.personas()
    val chats get() = db.chats()
    val messages get() = db.messages()
    val memories get() = db.memories()
    val lore get() = db.lore()
    val apiProfiles get() = db.apiProfiles()

    // ---- 初始化 ----

    suspend fun seedIfNeeded() {
        if (db.characters().count() == 0) {
            BuiltinCharacters.all().forEach { (char, loreList) ->
                db.characters().upsert(char)
                if (loreList.isNotEmpty()) db.lore().upsertAll(loreList)
            }
        }
        if (db.personas().count() == 0) {
            db.personas().upsert(
                PersonaEntity(
                    id = UUID.randomUUID().toString(),
                    name = "我",
                    description = "",
                    isDefault = true,
                )
            )
        }
        if (db.apiProfiles().count() == 0) {
            db.apiProfiles().upsert(
                ApiProfileEntity(
                    id = UUID.randomUUID().toString(),
                    name = "我的接口",
                    isActive = true,
                )
            )
        }
    }

    // ---- 会话 ----

    suspend fun createChat(characterId: String, greetingIndex: Int = 0): String {
        val char = db.characters().get(characterId) ?: return ""
        val persona = activePersona()
        val chatId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.chats().upsert(
            ChatEntity(
                id = chatId,
                characterId = characterId,
                personaId = persona?.id,
                title = char.name,
                createdAt = now,
                updatedAt = now,
            )
        )
        val greeting = if (greetingIndex == 0) char.firstMessage
        else char.alternateGreetings.getOrElse(greetingIndex - 1) { char.firstMessage }
        if (greeting.isNotBlank()) {
            val vars = ContextBuilder.buildVars(char, persona)
            val allGreetings = (listOf(char.firstMessage) + char.alternateGreetings)
                .filter { it.isNotBlank() }
                .map { PromptTemplates.fill(it, vars) }
            db.messages().insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    chatId = chatId,
                    idx = 0,
                    role = "assistant",
                    swipes = allGreetings,
                    activeSwipe = greetingIndex.coerceIn(0, (allGreetings.size - 1).coerceAtLeast(0)),
                )
            )
            touchChat(chatId, PromptTemplates.fill(greeting, vars))
        }
        return chatId
    }

    suspend fun ensureChatFor(characterId: String): String =
        db.chats().latestForCharacter(characterId)?.id ?: createChat(characterId)

    suspend fun activePersona(): PersonaEntity? {
        val id = settings.get().activePersonaId
        return id?.let { db.personas().get(it) } ?: db.personas().getDefault()
    }

    suspend fun touchChat(chatId: String, preview: String) {
        val chat = db.chats().get(chatId) ?: return
        db.chats().upsert(
            chat.copy(
                updatedAt = System.currentTimeMillis(),
                lastPreview = preview.take(120).replace("\n", " "),
            )
        )
    }

    suspend fun appendMessage(
        chatId: String,
        role: String,
        content: String,
        model: String? = null,
        elapsedMs: Long = 0,
    ): MessageEntity {
        val idx = db.messages().maxIdx(chatId) + 1
        val m = MessageEntity(
            id = UUID.randomUUID().toString(),
            chatId = chatId,
            idx = idx,
            role = role,
            swipes = listOf(content),
            activeSwipe = 0,
            model = model,
            elapsedMs = elapsedMs,
        )
        db.messages().insert(m)
        touchChat(chatId, content)
        return m
    }

    suspend fun updateMessageContent(messageId: String, content: String) {
        val m = db.messages().get(messageId) ?: return
        val swipes = m.swipes.toMutableList()
        if (swipes.isEmpty()) swipes.add(content) else swipes[m.activeSwipe] = content
        db.messages().upsert(m.copy(swipes = swipes))
        touchChat(m.chatId, content)
    }

    suspend fun addSwipe(messageId: String, content: String, model: String?, elapsedMs: Long) {
        val m = db.messages().get(messageId) ?: return
        val swipes = m.swipes + content
        db.messages().upsert(
            m.copy(swipes = swipes, activeSwipe = swipes.size - 1, model = model, elapsedMs = elapsedMs)
        )
        touchChat(m.chatId, content)
    }

    suspend fun setActiveSwipe(messageId: String, index: Int) {
        val m = db.messages().get(messageId) ?: return
        if (index !in m.swipes.indices) return
        db.messages().upsert(m.copy(activeSwipe = index))
        touchChat(m.chatId, m.swipes[index])
    }

    suspend fun deleteSwipe(messageId: String) {
        val m = db.messages().get(messageId) ?: return
        if (m.swipes.size <= 1) return
        val swipes = m.swipes.toMutableList()
        swipes.removeAt(m.activeSwipe)
        val newIndex = m.activeSwipe.coerceAtMost(swipes.size - 1)
        db.messages().upsert(m.copy(swipes = swipes, activeSwipe = newIndex))
    }

    suspend fun deleteMessage(messageId: String) {
        val m = db.messages().get(messageId) ?: return
        db.messages().deleteById(messageId)
        val remaining = db.messages().listForChat(m.chatId)
        touchChat(m.chatId, remaining.lastOrNull()?.content ?: "")
    }

    suspend fun deleteFrom(chatId: String, fromIdx: Long) {
        db.messages().deleteFrom(chatId, fromIdx)
        val remaining = db.messages().listForChat(chatId)
        touchChat(chatId, remaining.lastOrNull()?.content ?: "")
    }

    suspend fun togglePinned(messageId: String) {
        val m = db.messages().get(messageId) ?: return
        db.messages().upsert(m.copy(pinned = !m.pinned))
    }

    suspend fun toggleHidden(messageId: String) {
        val m = db.messages().get(messageId) ?: return
        db.messages().upsert(m.copy(hidden = !m.hidden))
    }

    /** 从某条消息处分叉出一个新会话，原会话保持不动。 */
    suspend fun branchFrom(chatId: String, upToIdxInclusive: Long): String? {
        val chat = db.chats().get(chatId) ?: return null
        val all = db.messages().listForChat(chatId).filter { it.idx <= upToIdxInclusive }
        val newId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.chats().upsert(
            chat.copy(
                id = newId,
                title = chat.title + " · 分支",
                createdAt = now,
                updatedAt = now,
                pinned = false,
            )
        )
        db.messages().insertAll(
            all.map { it.copy(id = UUID.randomUUID().toString(), chatId = newId) }
        )
        db.memories().listAll(chatId).forEach { mem ->
            db.memories().upsert(mem.copy(id = UUID.randomUUID().toString(), chatId = newId))
        }
        touchChat(newId, all.lastOrNull()?.content ?: "")
        return newId
    }

    suspend fun deleteChat(chatId: String) {
        db.messages().deleteForChat(chatId)
        db.memories().deleteForChat(chatId)
        db.chats().deleteById(chatId)
    }

    suspend fun deleteCharacter(characterId: String) {
        db.chats().listForCharacter(characterId).forEach { deleteChat(it.id) }
        db.lore().deleteForCharacter(characterId)
        db.characters().deleteById(characterId)
    }

    // ---- 上下文 ----

    suspend fun buildPrompt(
        chatId: String,
        upToIdxExclusive: Long? = null,
        oneShotDirective: String? = null,
    ): Pair<BuiltPrompt, ApiProfileEntity>? {
        val chat = db.chats().get(chatId) ?: return null
        val char = db.characters().get(chat.characterId) ?: return null
        val profile = db.apiProfiles().getActive() ?: return null
        val app = settings.get()
        val persona = chat.personaId?.let { db.personas().get(it) } ?: activePersona()

        var engine = app.toEngineSettings()
        if (char.systemPromptOverride.isNotBlank()) {
            engine = engine.copy(mainPrompt = char.systemPromptOverride)
        }
        if (char.postHistoryOverride.isNotBlank()) {
            engine = engine.copy(postHistory = char.postHistoryOverride)
        }

        val built = ContextBuilder.build(
            ContextInput(
                character = char,
                persona = persona,
                chat = chat,
                messages = db.messages().listForChat(chatId),
                memories = db.memories().listEnabled(chatId),
                lore = db.lore().listActive(chat.characterId),
                settings = engine,
                profile = profile,
                oneShotDirective = oneShotDirective,
                upToIdxExclusive = upToIdxExclusive,
            )
        )
        return built to profile
    }

    /**
     * 一轮对话结束后的上下文维护。三件事互相独立，任何一件失败都不影响其它两件，
     * 也都不影响对话本身。
     */
    suspend fun runMaintenance(chatId: String) {
        val app = settings.get()
        val chat = db.chats().get(chatId) ?: return
        val char = db.characters().get(chat.characterId) ?: return
        val profile = db.apiProfiles().getActive() ?: return
        if (profile.baseUrl.isBlank() || profile.model.isBlank()) return
        val persona = chat.personaId?.let { db.personas().get(it) } ?: activePersona()
        val userName = persona?.name?.ifBlank { "对方" } ?: "对方"
        val all = db.messages().listForChat(chatId).filter { !it.hidden }

        // 1) 场景状态
        if (app.sceneTrackingEnabled && all.size >= 2) {
            runCatching {
                maintainer.trackScene(
                    profile = profile,
                    charName = char.name,
                    userName = userName,
                    previous = SceneState.parse(chat.sceneStateJson),
                    recent = all,
                )
            }.getOrNull()?.let { updated ->
                db.chats().get(chatId)?.let {
                    db.chats().upsert(it.copy(sceneStateJson = updated.encode()))
                }
            }
        }

        // 2) 长期记忆
        if (app.memoryEnabled) {
            val current = db.chats().get(chatId) ?: return
            val unscanned = all.filter { it.idx > current.memoryScannedUpToIdx }
            if (unscanned.size >= app.memoryEveryNTurns * 2) {
                val existing = db.memories().listAll(chatId).map { it.text }
                runCatching {
                    maintainer.extractMemories(profile, char.name, userName, existing, unscanned)
                }.getOrNull()?.let { extracted ->
                    if (extracted.isNotEmpty()) {
                        db.memories().upsertAll(
                            extracted.map {
                                MemoryEntity(
                                    id = UUID.randomUUID().toString(),
                                    chatId = chatId,
                                    text = it.text,
                                    category = it.category,
                                    importance = it.importance,
                                    sourceIdx = unscanned.last().idx,
                                )
                            }
                        )
                    }
                    db.chats().get(chatId)?.let {
                        db.chats().upsert(it.copy(memoryScannedUpToIdx = unscanned.last().idx))
                    }
                }
            }
        }

        // 3) 归档压缩：只压缩"已经被挤出上下文窗口"的那部分
        if (app.summaryEnabled) {
            val current = db.chats().get(chatId) ?: return
            val built = buildPrompt(chatId)?.first ?: return
            val toArchive = built.overflow.filter { it.idx > current.summarizedUpToIdx }
            if (toArchive.size >= app.summarizeBatchSize) {
                runCatching {
                    maintainer.summarize(
                        profile = profile,
                        charName = char.name,
                        userName = userName,
                        previous = current.rollingSummary,
                        chunk = toArchive,
                        targetChars = 900,
                    )
                }.getOrNull()?.let { summary ->
                    db.chats().get(chatId)?.let {
                        db.chats().upsert(
                            it.copy(
                                rollingSummary = summary,
                                summarizedUpToIdx = toArchive.last().idx,
                            )
                        )
                    }
                }
            }
        }
    }

    // ---- 角色卡导入导出 ----

    suspend fun importCard(card: ImportedCard): String {
        var char = card.character
        card.avatarBytes?.let { bytes ->
            saveAvatar(char.id, bytes)?.let { char = char.copy(avatarUri = it) }
        }
        db.characters().upsert(char)
        if (card.lore.isNotEmpty()) {
            db.lore().upsertAll(card.lore.map { it.copy(characterId = char.id) })
        }
        return char.id
    }

    fun saveAvatar(characterId: String, bytes: ByteArray): String? = runCatching {
        val dir = File(appContext.filesDir, "avatars").apply { mkdirs() }
        val f = File(dir, "$characterId.png")
        f.writeBytes(bytes)
        f.absolutePath
    }.getOrNull()

    suspend fun exportCharacter(characterId: String): String? {
        val char = db.characters().get(characterId) ?: return null
        return CharacterCardIO.exportJson(char, db.lore().listOwnedBy(characterId))
    }

    suspend fun duplicateCharacter(characterId: String): String? {
        val char = db.characters().get(characterId) ?: return null
        val newId = UUID.randomUUID().toString()
        db.characters().upsert(
            char.copy(
                id = newId,
                name = char.name + " 副本",
                builtin = false,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
        )
        db.lore().listOwnedBy(characterId).forEach {
            db.lore().upsert(it.copy(id = UUID.randomUUID().toString(), characterId = newId))
        }
        return newId
    }

    suspend fun upsertLore(entry: LoreEntity) = db.lore().upsert(entry)

    suspend fun upsertCharacter(char: CharacterEntity) =
        db.characters().upsert(char.copy(updatedAt = System.currentTimeMillis()))

    companion object {
        @Volatile private var INSTANCE: Repository? = null
        fun get(context: Context): Repository = INSTANCE ?: synchronized(this) {
            INSTANCE ?: Repository(context.applicationContext).also { INSTANCE = it }
        }
    }
}
