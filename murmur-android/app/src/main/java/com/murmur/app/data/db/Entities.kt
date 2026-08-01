package com.murmur.app.data.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
    explicitNulls = false
}

class Converters {
    @TypeConverter
    fun stringListToJson(value: List<String>): String =
        AppJson.encodeToString(ListSerializer(String.serializer()), value)

    @TypeConverter
    fun jsonToStringList(value: String): List<String> = runCatching {
        AppJson.decodeFromString(ListSerializer(String.serializer()), value)
    }.getOrDefault(emptyList())
}

/** 角色卡。字段与 SillyTavern V2 角色卡对齐，方便双向导入导出。 */
@Entity(tableName = "characters")
data class CharacterEntity(
    @PrimaryKey val id: String,
    val name: String,
    val avatarUri: String? = null,
    /** 角色是谁：身份、背景、外貌 */
    val description: String = "",
    /** 性格与行为倾向 */
    val personality: String = "",
    /** 开场情境 */
    val scenario: String = "",
    /** 开场白 */
    val firstMessage: String = "",
    /** 备选开场白，开新对话时可换 */
    val alternateGreetings: List<String> = emptyList(),
    /** 示例对白，作为语气锚点常驻上下文 */
    val exampleDialogue: String = "",
    /** 说话方式的显式描述：口头禅、称呼、语速、用词习惯 */
    val speechStyle: String = "",
    /** 覆盖全局主提示词（留空则用全局） */
    val systemPromptOverride: String = "",
    /** 覆盖全局尾部指令（留空则用全局） */
    val postHistoryOverride: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val accentColor: Int = 0,
    /** 大图立绘：首页头卡和聊天页顶部用 */
    val portraitUri: String? = null,
    /** 聊天页背景，留空则用全局背景 */
    val backgroundUri: String? = null,
    val ttsVoice: String? = null,
    val favorite: Boolean = false,
    val builtin: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "personas")
data class PersonaEntity(
    @PrimaryKey val id: String,
    val name: String,
    val description: String = "",
    val avatarUri: String? = null,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "chats",
    indices = [Index("characterId"), Index("updatedAt")],
)
data class ChatEntity(
    @PrimaryKey val id: String,
    val characterId: String,
    val personaId: String? = null,
    val title: String = "",
    val pinned: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val lastPreview: String = "",
    /** 归档区的滚动梗概 */
    val rollingSummary: String = "",
    /** 梗概已覆盖到第几条消息（idx，含） */
    val summarizedUpToIdx: Long = -1,
    /** 场景状态 JSON（SceneState） */
    val sceneStateJson: String = "",
    /** 上次做记忆抽取时覆盖到的 idx */
    val memoryScannedUpToIdx: Long = -1,
    /** 这段对话专属的背景，优先级高于角色背景 */
    val backgroundUri: String? = null,
    /** 亲密度 0..100，由后台按对话进展增减 */
    val affinity: Int = 12,
    /** 一句话描述此刻的关系，进锚点 */
    val relationshipStage: String = "",
)

@Entity(
    tableName = "messages",
    indices = [Index("chatId"), Index(value = ["chatId", "idx"], unique = true)],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val idx: Long,
    /** user | assistant | system */
    val role: String,
    /** 同一条消息的多个候选生成，左右滑动切换 */
    val swipes: List<String> = emptyList(),
    val activeSwipe: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
    /** 置顶：永不被上下文预算裁掉 */
    val pinned: Boolean = false,
    /** 隐藏：不参与上下文（如场外备注） */
    val hidden: Boolean = false,
    val model: String? = null,
    /** 生成耗时（毫秒），用于显示 */
    val elapsedMs: Long = 0,
    /** 随消息发出的图片（应用私有目录里的绝对路径） */
    val imageUri: String? = null,
) {
    val content: String get() = swipes.getOrElse(activeSwipe) { "" }
}

@Entity(tableName = "memories", indices = [Index("chatId")])
data class MemoryEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val text: String,
    /** 事实 / 关系 / 事件 / 喜好 / 约定 / 外貌 */
    val category: String = "事实",
    /** 1..5，越高越优先进入上下文 */
    val importance: Int = 3,
    val sourceIdx: Long = -1,
    val createdAt: Long = System.currentTimeMillis(),
    val pinned: Boolean = false,
    val enabled: Boolean = true,
)

/** 世界书条目：命中关键词才注入，省 token 又能装很多设定。 */
@Entity(tableName = "lore", indices = [Index("characterId")])
data class LoreEntity(
    @PrimaryKey val id: String,
    /** null = 全局条目，对所有角色生效 */
    val characterId: String? = null,
    val name: String = "",
    val keys: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    /** 常驻：不需要命中关键词 */
    val constant: Boolean = false,
    val priority: Int = 100,
    /** 在最近多少条消息里扫描关键词 */
    val scanDepth: Int = 6,
    val caseSensitive: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "api_profiles")
data class ApiProfileEntity(
    @PrimaryKey val id: String,
    val name: String,
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    /** 辅助任务（摘要/记忆/场景）用的模型，留空则复用主模型 */
    val utilityModel: String = "",
    val extraHeadersJson: String = "{}",
    val extraBodyJson: String = "{}",
    val isActive: Boolean = false,

    val temperature: Float = 0.92f,
    val topP: Float = 0.95f,
    val topK: Int = 0,
    val minP: Float = 0.05f,
    val frequencyPenalty: Float = 0.35f,
    val presencePenalty: Float = 0.35f,
    val repetitionPenalty: Float = 1.0f,
    val maxTokens: Int = 700,
    val contextTokens: Int = 16384,
    val stopSequences: List<String> = emptyList(),
    val stream: Boolean = true,
    /** 部分端点不接受多条 system 消息，打开后合并成一条 */
    val mergeSystemMessages: Boolean = false,
    /** 模型支持读图时打开，图片会以 base64 data URI 随消息发出 */
    val visionEnabled: Boolean = false,
    /** 发送 temperature 之外的采样参数（有些端点会因未知字段 400） */
    val sendAdvancedSampling: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)

@Serializable
data class SceneState(
    val location: String = "",
    val time: String = "",
    val pose: String = "",
    val outfit: String = "",
    val mood: String = "",
    val threads: List<String> = emptyList(),
) {
    val isEmpty: Boolean
        get() = location.isBlank() && time.isBlank() && pose.isBlank() &&
            outfit.isBlank() && mood.isBlank() && threads.isEmpty()

    companion object {
        fun parse(json: String): SceneState = runCatching {
            if (json.isBlank()) SceneState() else AppJson.decodeFromString<SceneState>(json)
        }.getOrDefault(SceneState())
    }

    fun encode(): String = AppJson.encodeToString(serializer(), this)
}
