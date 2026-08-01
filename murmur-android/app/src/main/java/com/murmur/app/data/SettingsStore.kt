package com.murmur.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.murmur.app.data.db.AppJson
import com.murmur.app.engine.EngineSettings
import com.murmur.app.engine.PromptTemplates
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

private val Context.dataStore by preferencesDataStore("murmur_settings")

@Serializable
data class AppSettings(
    val onboarded: Boolean = false,
    val ageConfirmed: Boolean = false,
    val activePersonaId: String? = null,
    /** 内置角色只在首次安装时播种一次，删掉之后不该又自己长回来 */
    val charactersSeeded: Boolean = false,

    // 上下文引擎
    val mainPrompt: String = PromptTemplates.DEFAULT_MAIN,
    val postHistory: String = PromptTemplates.DEFAULT_POST_HISTORY,
    val customDirective: String = "",
    val anchorEnabled: Boolean = true,
    val anchorDepth: Int = 1,
    val lengthHint: String = "medium",
    val exampleDialogueEnabled: Boolean = true,
    val antiRepeatEnabled: Boolean = true,
    val summaryEnabled: Boolean = true,
    val memoryEnabled: Boolean = true,
    val sceneTrackingEnabled: Boolean = true,
    val historyFloorRatio: Float = 0.5f,
    val maxMemoriesInContext: Int = 12,

    // 后台维护节奏
    /** 历史被挤出上下文后，累计到这么多条就压进梗概 */
    val summarizeBatchSize: Int = 6,
    /** 每这么多轮对话抽一次长期记忆 */
    val memoryEveryNTurns: Int = 4,

    /** 亲密度跟踪：每轮后台评估关系进展，结果进锚点影响分寸 */
    val affinityEnabled: Boolean = true,

    // 候选回复
    val suggestionsEnabled: Boolean = true,
    /** 每轮角色回复结束后自动生成，关掉则改为手动点一下才生成 */
    val suggestionsAuto: Boolean = true,
    val suggestionCount: Int = 3,

    // 外观
    /** 全局聊天背景，角色/会话没单独设置时用这张 */
    val globalBackgroundUri: String? = null,
    /** 背景压暗程度，太亮会看不清字 */
    val backgroundDim: Float = 0.58f,
    /** 气泡不透明度，调低能透出背景 */
    val bubbleAlpha: Float = 0.90f,

    // 界面
    val fontScale: Float = 1f,
    val hapticsEnabled: Boolean = true,
    val ttsAutoRead: Boolean = false,
    val showContextBar: Boolean = true,
    val streamingSmoothing: Boolean = true,
    val enterToSend: Boolean = false,
) {
    fun toEngineSettings() = EngineSettings(
        mainPrompt = mainPrompt,
        postHistory = postHistory,
        customDirective = customDirective,
        anchorEnabled = anchorEnabled,
        anchorDepth = anchorDepth,
        lengthHint = lengthHint,
        exampleDialogueEnabled = exampleDialogueEnabled,
        antiRepeatEnabled = antiRepeatEnabled,
        summaryEnabled = summaryEnabled,
        memoryEnabled = memoryEnabled,
        sceneTrackingEnabled = sceneTrackingEnabled,
        historyFloorRatio = historyFloorRatio,
        maxMemoriesInContext = maxMemoriesInContext,
    )
}

class SettingsStore(private val context: Context) {

    private val key = stringPreferencesKey("app_settings_json")

    val flow: Flow<AppSettings> = context.dataStore.data.map { prefs ->
        prefs[key]?.let { runCatching { AppJson.decodeFromString<AppSettings>(it) }.getOrNull() }
            ?: AppSettings()
    }

    suspend fun get(): AppSettings = flow.first()

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        context.dataStore.edit { prefs ->
            val current = prefs[key]
                ?.let { runCatching { AppJson.decodeFromString<AppSettings>(it) }.getOrNull() }
                ?: AppSettings()
            prefs[key] = AppJson.encodeToString(AppSettings.serializer(), transform(current))
        }
    }
}
