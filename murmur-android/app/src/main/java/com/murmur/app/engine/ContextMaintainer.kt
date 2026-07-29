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
