package com.murmur.app.data

import com.murmur.app.data.db.AppJson
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.data.db.LoreEntity
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.add
import java.util.Base64
import java.util.UUID

data class ImportedCard(
    val character: CharacterEntity,
    val lore: List<LoreEntity>,
    /** 角色卡 PNG 本身就是头像 */
    val avatarBytes: ByteArray? = null,
)

/**
 * SillyTavern / TavernAI 角色卡的读写。
 *
 * 卡片是一张 PNG，JSON 藏在 tEXt 辅助块里（关键字 `chara`，值是 base64 的 JSON）。
 * 支持这个格式意味着网上现成的几万张卡可以直接拖进来用，这是最划算的一个兼容性投入。
 */
object CharacterCardIO {

    private val PNG_SIG = byteArrayOf(
        0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    )

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size > 8 && PNG_SIG.indices.all { bytes[it] == PNG_SIG[it] }

    /** 读出 PNG 里所有 tEXt / iTXt 文本块。 */
    fun readPngTextChunks(bytes: ByteArray): Map<String, String> {
        if (!isPng(bytes)) return emptyMap()
        val out = LinkedHashMap<String, String>()
        var pos = 8
        while (pos + 8 <= bytes.size) {
            val len = readInt(bytes, pos)
            if (len < 0 || pos + 12 + len > bytes.size) break
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            if (type == "tEXt" || type == "iTXt") {
                val sep = (dataStart until dataStart + len).firstOrNull { bytes[it] == 0.toByte() }
                if (sep != null) {
                    val key = String(bytes, dataStart, sep - dataStart, Charsets.US_ASCII)
                    var valueStart = sep + 1
                    if (type == "iTXt") {
                        // iTXt: keyword \0 compressionFlag compressionMethod langTag \0 translatedKeyword \0 text
                        valueStart += 2
                        var zeros = 0
                        var i = valueStart
                        while (i < dataStart + len && zeros < 2) {
                            if (bytes[i] == 0.toByte()) zeros++
                            i++
                        }
                        valueStart = i
                    }
                    if (valueStart <= dataStart + len) {
                        val value = String(bytes, valueStart, dataStart + len - valueStart, Charsets.UTF_8)
                        out[key] = value
                    }
                }
            }
            if (type == "IEND") break
            pos = dataStart + len + 4
        }
        return out
    }

    fun parsePngCard(bytes: ByteArray): ImportedCard? {
        val chunks = readPngTextChunks(bytes)
        val raw = chunks["ccv3"] ?: chunks["chara"] ?: return null
        val json = runCatching { String(Base64.getDecoder().decode(raw.trim()), Charsets.UTF_8) }
            .getOrElse { raw }
        return parseJsonCard(json)?.copy(avatarBytes = bytes)
    }

    fun parseJsonCard(json: String): ImportedCard? {
        val root = runCatching { AppJson.parseToJsonElement(json).jsonObject }.getOrNull() ?: return null
        val data = root["data"]?.jsonObject ?: root
        val name = data.str("name") ?: data.str("char_name") ?: return null

        val id = UUID.randomUUID().toString()
        val character = CharacterEntity(
            id = id,
            name = name,
            description = data.str("description") ?: data.str("char_persona") ?: "",
            personality = data.str("personality") ?: "",
            scenario = data.str("scenario") ?: data.str("world_scenario") ?: "",
            firstMessage = data.str("first_mes") ?: data.str("char_greeting") ?: "",
            alternateGreetings = data["alternate_greetings"]?.let { el ->
                runCatching { el.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
            } ?: emptyList(),
            exampleDialogue = data.str("mes_example") ?: data.str("example_dialogue") ?: "",
            systemPromptOverride = data.str("system_prompt") ?: "",
            postHistoryOverride = data.str("post_history_instructions") ?: "",
            creatorNotes = data.str("creator_notes") ?: "",
            tags = data["tags"]?.let { el ->
                runCatching { el.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
            } ?: emptyList(),
        )

        val book = data["character_book"]?.jsonObject
        val lore = book?.get("entries")?.let { entries ->
            runCatching {
                entries.jsonArray.mapNotNull { e ->
                    val o = e.jsonObject
                    val content = o.str("content") ?: return@mapNotNull null
                    if (content.isBlank()) return@mapNotNull null
                    LoreEntity(
                        id = UUID.randomUUID().toString(),
                        characterId = id,
                        name = o.str("name") ?: o.str("comment") ?: "",
                        keys = o["keys"]?.let { k ->
                            runCatching { k.jsonArray.mapNotNull { it.jsonPrimitive.contentOrNull } }.getOrNull()
                        } ?: emptyList(),
                        content = content,
                        enabled = o["enabled"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: true,
                        constant = o["constant"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() ?: false,
                        priority = runCatching { o["insertion_order"]!!.jsonPrimitive.int }.getOrDefault(100),
                        caseSensitive = o["case_sensitive"]?.jsonPrimitive?.contentOrNull
                            ?.toBooleanStrictOrNull() ?: false,
                    )
                }
            }.getOrNull()
        } ?: emptyList()

        return ImportedCard(character, lore)
    }

    /** 导出成 V2 规格的 JSON，能被 SillyTavern 直接读。 */
    fun exportJson(char: CharacterEntity, lore: List<LoreEntity>): String {
        val obj = buildJsonObject {
            put("spec", "chara_card_v2")
            put("spec_version", "2.0")
            put("data", buildJsonObject {
                put("name", char.name)
                put("description", char.description)
                put("personality", char.personality)
                put("scenario", char.scenario)
                put("first_mes", char.firstMessage)
                put("mes_example", char.exampleDialogue)
                put("creator_notes", char.creatorNotes)
                put("system_prompt", char.systemPromptOverride)
                put("post_history_instructions", char.postHistoryOverride)
                put("alternate_greetings", buildJsonArray { char.alternateGreetings.forEach { add(it) } })
                put("tags", buildJsonArray { char.tags.forEach { add(it) } })
                put("creator", "Murmur")
                put("character_version", "1.0")
                put("extensions", buildJsonObject {
                    put("murmur_speech_style", char.speechStyle)
                })
                if (lore.isNotEmpty()) {
                    put("character_book", buildJsonObject {
                        put("name", "${char.name} 的世界书")
                        put("entries", buildJsonArray {
                            lore.forEach { e ->
                                add(buildJsonObject {
                                    put("name", e.name)
                                    put("keys", buildJsonArray { e.keys.forEach { add(it) } })
                                    put("content", e.content)
                                    put("enabled", e.enabled)
                                    put("constant", e.constant)
                                    put("insertion_order", e.priority)
                                    put("case_sensitive", e.caseSensitive)
                                })
                            }
                        })
                    })
                }
            })
        }
        return AppJson.encodeToString(JsonObject.serializer(), obj)
    }

    private fun JsonObject.str(key: String): String? =
        this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() && it != "null" }

    private fun readInt(b: ByteArray, off: Int): Int =
        ((b[off].toInt() and 0xFF) shl 24) or
            ((b[off + 1].toInt() and 0xFF) shl 16) or
            ((b[off + 2].toInt() and 0xFF) shl 8) or
            (b[off + 3].toInt() and 0xFF)
}
