package com.murmur.app.engine

import com.murmur.app.data.db.ApiProfileEntity
import com.murmur.app.data.db.AppJson
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.data.db.MessageEntity
import com.murmur.app.data.db.PersonaEntity
import com.murmur.app.data.db.SceneState
import com.murmur.app.net.OpenAiClient
import com.murmur.app.net.WireMessage
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * 实时生成三个候选回复，站在使用者这一侧。
 *
 * 刻意不复用主对话的完整上下文：那会让每一轮的 token 成本翻倍，
 * 而"我接下来可能说什么"其实只需要最近几轮 + 我是谁 + 现在在哪。
 * 走 utilityModel、限死输出长度，一轮的开销大概是主回复的十分之一。
 */
class SuggestionEngine(private val client: OpenAiClient) {

    companion object {
        const val WINDOW = 8

        /** 模型十有八九会给合法 JSON，但也得接住编号列表、代码块、多写一句废话这些情况。 */
        fun parse(raw: String, want: Int = 3): List<String> {
            val fromJson = ContextMaintainer.extractJsonArray(raw)?.let { json ->
                runCatching {
                    AppJson.decodeFromString(ListSerializer(String.serializer()), json)
                }.getOrNull()
            }
            val candidates = fromJson ?: raw.lineSequence()
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { line ->
                    line.removePrefix("```").removeSuffix("```")
                        // 去掉 "1. " "1、" "- " "· " 这类前缀
                        .replace(Regex("^\\s*[\\-*·•]\\s*"), "")
                        .replace(Regex("^\\s*\\d+\\s*[.、)．]\\s*"), "")
                        .trim()
                        .removeSurrounding("\"")
                        .removeSurrounding("“", "”")
                }
                .filter { it.isNotEmpty() && !it.startsWith("[") && !it.startsWith("{") }
                .toList()

            return candidates
                .map { it.trim().trim(',').trim() }
                .filter { it.isNotBlank() && it.length <= 120 }
                .distinct()
                .take(want)
        }
    }

    suspend fun generate(
        profile: ApiProfileEntity,
        character: CharacterEntity,
        persona: PersonaEntity?,
        scene: SceneState,
        recent: List<MessageEntity>,
        count: Int = 3,
    ): Result<List<String>> {
        if (recent.isEmpty()) return Result.success(emptyList())
        val userName = persona?.name?.takeIf { it.isNotBlank() } ?: "我"
        val vars = mapOf("char" to character.name, "user" to userName)

        val prompt = buildString {
            appendLine(PromptTemplates.fill(PromptTemplates.SUGGESTIONS, vars))
            appendLine()
            appendLine("【$userName 是谁】")
            appendLine(persona?.description?.takeIf { it.isNotBlank() } ?: "（没有特别设定）")
            appendLine()
            appendLine("【${character.name} 是谁】")
            appendLine(
                listOf(character.personality, character.description)
                    .firstOrNull { it.isNotBlank() }?.take(200) ?: "（没有特别设定）"
            )
            if (!scene.isEmpty) {
                appendLine()
                appendLine("【此刻】")
                listOfNotNull(
                    scene.location.takeIf { it.isNotBlank() }?.let { "地点：$it" },
                    scene.pose.takeIf { it.isNotBlank() }?.let { "姿态：$it" },
                    scene.mood.takeIf { it.isNotBlank() }?.let { "${character.name}的情绪：$it" },
                    scene.threads.takeIf { it.isNotEmpty() }?.let { "悬念：${it.joinToString("；")}" },
                ).forEach { appendLine(it) }
            }
            appendLine()
            appendLine("【最近的对话】")
            recent.takeLast(WINDOW).forEach { m ->
                val who = if (m.role == "assistant") character.name else userName
                appendLine("$who：${m.content.trim()}")
            }
            appendLine()
            append("现在给出 $count 条 $userName 可能会说的话。")
        }

        return client.complete(
            profile = profile,
            messages = listOf(WireMessage("user", prompt)),
            model = profile.utilityModel.ifBlank { profile.model },
            maxTokens = 320,
            temperature = 1.0f,
        ).map { parse(it, count) }
    }
}
