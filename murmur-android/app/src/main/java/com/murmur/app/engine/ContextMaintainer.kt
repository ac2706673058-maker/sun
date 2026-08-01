package com.murmur.app.engine

import com.murmur.app.data.db.ApiProfileEntity
import com.murmur.app.data.db.AppJson
import com.murmur.app.data.db.MessageEntity
import com.murmur.app.data.db.SceneState
import com.murmur.app.net.OpenAiClient
import com.murmur.app.net.WireMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

@Serializable
data class AffinityDelta(
    val delta: Int = 0,
    val stage: String = "",
)

@Serializable
data class ExtractedMemory(
    val text: String = "",
    val category: String = "事实",
    val importance: Int = 3,
)

/**
 * 上下文的后台维护：场景跟踪、记忆抽取、归档压缩。
 * 三件事都走 utilityModel、都不阻塞聊天、都允许失败——失败了就沿用旧状态，
 * 因为它们只是让上下文更好，而不是让对话能不能继续的前提。
 */
class ContextMaintainer(private val client: OpenAiClient) {

    suspend fun trackScene(
        profile: ApiProfileEntity,
        charName: String,
        userName: String,
        previous: SceneState,
        recent: List<MessageEntity>,
    ): SceneState? {
        if (recent.isEmpty()) return null
        val prompt = buildString {
            appendLine(PromptTemplates.SCENE_TRACKER)
            appendLine()
            appendLine("旧状态：")
            appendLine(previous.encode())
            appendLine()
            appendLine("最近的对话（$charName 是角色，$userName 是对方）：")
            appendLine(transcript(recent.takeLast(8), charName, userName))
        }
        val raw = client.complete(
            profile = profile,
            messages = listOf(WireMessage("user", prompt)),
            model = profile.utilityModel.ifBlank { profile.model },
            maxTokens = 300,
            temperature = 0.3f,
        ).getOrNull() ?: return null

        val json = extractJsonObject(raw) ?: return null
        val parsed = runCatching { AppJson.decodeFromString<SceneState>(json) }.getOrNull() ?: return null
        // 空字段沿用旧值，避免跟踪器偶尔漏字段就把状态抹掉
        return SceneState(
            location = parsed.location.ifBlank { previous.location },
            time = parsed.time.ifBlank { previous.time },
            pose = parsed.pose.ifBlank { previous.pose },
            outfit = parsed.outfit.ifBlank { previous.outfit },
            mood = parsed.mood.ifBlank { previous.mood },
            threads = parsed.threads.filter { it.isNotBlank() }.ifEmpty { previous.threads }.take(3),
        )
    }

    /**
     * 亲密度增量。
     * 返回 null 表示这一轮不动 —— 判不出来就别动，比乱加乱减好。
     */
    suspend fun trackAffinity(
        profile: ApiProfileEntity,
        charName: String,
        userName: String,
        current: Int,
        currentStage: String,
        recent: List<MessageEntity>,
    ): Pair<Int, String>? {
        if (recent.size < 2) return null
        val prompt = buildString {
            appendLine(PromptTemplates.AFFINITY)
            appendLine()
            appendLine("角色：$charName　对方：$userName")
            appendLine("当前亲近度：$current/100" + if (currentStage.isNotBlank()) "（$currentStage）" else "")
            appendLine()
            appendLine("最近的对话：")
            appendLine(transcript(recent.takeLast(6), charName, userName))
        }
        val raw = client.complete(
            profile = profile,
            messages = listOf(WireMessage("user", prompt)),
            model = profile.utilityModel.ifBlank { profile.model },
            maxTokens = 160,
            temperature = 0.25f,
        ).getOrNull() ?: return null

        val json = extractJsonObject(raw) ?: return null
        val parsed = runCatching { AppJson.decodeFromString<AffinityDelta>(json) }.getOrNull() ?: return null
        // 单轮最多动 10 点，防止模型一次给个 +80 把进度条冲顶
        val delta = parsed.delta.coerceIn(-10, 10)
        val next = (current + delta).coerceIn(0, 100)
        val stage = parsed.stage.trim().take(16).ifBlank { currentStage }
        return next to stage
    }

    /**
     * 即兴生成一个契合当下场景的转折事件（导演模式的"随机来点什么"）。
     * 要求它作用于环境或第三方，而不是替角色决定情绪 —— 否则就成了越俎代庖。
     */
    suspend fun improviseEvent(
        profile: ApiProfileEntity,
        charName: String,
        userName: String,
        scene: SceneState,
        recent: List<MessageEntity>,
    ): String? {
        val prompt = buildString {
            appendLine("给下面这段虚构对话投放一个突发事件，用来打破当前节奏。")
            appendLine()
            appendLine("要求：")
            appendLine("- 只写发生了什么，一到两句，第三人称，客观陈述。")
            appendLine("- 作用在环境、外部或第三方身上（天气、来电、断电、有人闯入、东西摔了、消息传来）。")
            appendLine("- 不要替 $charName 或 $userName 决定反应、情绪或台词。")
            appendLine("- 要贴合此刻的地点和气氛，不要凭空跳到别的场景。")
            appendLine("- 直接输出那句话，不要引号，不要解释，不要编号。")
            appendLine()
            if (!scene.isEmpty) {
                appendLine("此刻：${scene.location}｜${scene.time}｜${scene.pose}")
            }
            appendLine("最近的对话：")
            appendLine(transcript(recent.takeLast(6), charName, userName))
        }
        return client.complete(
            profile = profile,
            messages = listOf(WireMessage("user", prompt)),
            model = profile.utilityModel.ifBlank { profile.model },
            maxTokens = 150,
            temperature = 1.05f,
        ).getOrNull()?.trim()?.trim('"', '「', '」')?.takeIf { it.isNotBlank() }
    }

    suspend fun extractMemories(
        profile: ApiProfileEntity,
        charName: String,
        userName: String,
        existing: List<String>,
        chunk: List<MessageEntity>,
    ): List<ExtractedMemory> {
        if (chunk.isEmpty()) return emptyList()
        val prompt = buildString {
            appendLine(PromptTemplates.MEMORY_EXTRACT)
            appendLine()
            if (existing.isNotEmpty()) {
                appendLine("已知记忆（不要重复抽取）：")
                existing.take(40).forEach { appendLine("· $it") }
                appendLine()
            }
            appendLine("对话片段（$charName 是角色，$userName 是对方）：")
            appendLine(transcript(chunk, charName, userName))
        }
        val raw = client.complete(
            profile = profile,
            messages = listOf(WireMessage("user", prompt)),
            model = profile.utilityModel.ifBlank { profile.model },
            maxTokens = 500,
            temperature = 0.3f,
        ).getOrNull() ?: return emptyList()

        val json = extractJsonArray(raw) ?: return emptyList()
        return runCatching {
            AppJson.decodeFromString(ListSerializer(ExtractedMemory.serializer()), json)
        }.getOrDefault(emptyList())
            .filter { it.text.isNotBlank() }
            .map { it.copy(importance = it.importance.coerceIn(1, 5), text = it.text.trim()) }
            .take(5)
    }

    suspend fun summarize(
        profile: ApiProfileEntity,
        charName: String,
        userName: String,
        previous: String,
        chunk: List<MessageEntity>,
        targetChars: Int,
    ): String? {
        if (chunk.isEmpty()) return null
        val prompt = buildString {
            appendLine(PromptTemplates.SUMMARIZE)
            appendLine()
            if (previous.isNotBlank()) {
                appendLine("已有的梗概（在此基础上整合续写，总长控制在 $targetChars 字以内）：")
                appendLine(previous)
                appendLine()
            } else {
                appendLine("总长控制在 $targetChars 字以内。")
                appendLine()
            }
            appendLine("需要压缩的对话（$charName 是角色，$userName 是对方）：")
            appendLine(transcript(chunk, charName, userName))
        }
        val raw = client.complete(
            profile = profile,
            messages = listOf(WireMessage("user", prompt)),
            model = profile.utilityModel.ifBlank { profile.model },
            maxTokens = (targetChars * 1.2f).toInt().coerceIn(300, 1600),
            temperature = 0.4f,
        ).getOrNull()?.trim() ?: return null

        return raw.removeSurrounding("```").removePrefix("markdown").trim().takeIf { it.isNotBlank() }
    }

    private fun transcript(messages: List<MessageEntity>, charName: String, userName: String) =
        messages.joinToString("\n") { m ->
            val who = if (m.role == "assistant") charName else userName
            "$who：${m.content.trim()}"
        }

    companion object {
        /** 模型经常把 JSON 包在 ``` 里或者前后加废话，这里抠出第一个平衡的括号块。 */
        fun extractJsonObject(raw: String): String? = extractBalanced(raw, '{', '}')

        fun extractJsonArray(raw: String): String? = extractBalanced(raw, '[', ']')

        private fun extractBalanced(raw: String, open: Char, close: Char): String? {
            val start = raw.indexOf(open)
            if (start < 0) return null
            var depth = 0
            var inString = false
            var escaped = false
            for (i in start until raw.length) {
                val c = raw[i]
                when {
                    escaped -> escaped = false
                    c == '\\' && inString -> escaped = true
                    c == '"' -> inString = !inString
                    inString -> Unit
                    c == open -> depth++
                    c == close -> {
                        depth--
                        if (depth == 0) return raw.substring(start, i + 1)
                    }
                }
            }
            return null
        }
    }
}
