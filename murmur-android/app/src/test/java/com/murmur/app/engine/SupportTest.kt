package com.murmur.app.engine

import com.murmur.app.data.CharacterCardIO
import com.murmur.app.net.OpenAiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class TokenEstimatorTest {

    @Test
    fun `chinese costs roughly three quarters of a token per character`() {
        val n = TokenEstimator.estimate("今天天气很好我们出去走走吧")
        assertTrue("估算值 $n 偏离经验区间", n in 8..14)
    }

    @Test
    fun `english costs roughly one token per four characters`() {
        val n = TokenEstimator.estimate("the quick brown fox jumps over the lazy dog")
        assertTrue("估算值 $n 偏离经验区间", n in 8..16)
    }

    @Test
    fun `estimates grow monotonically with length`() {
        val short = TokenEstimator.estimate("你好")
        val long = TokenEstimator.estimate("你好".repeat(50))
        assertTrue(long > short * 20)
    }

    @Test
    fun `empty text costs nothing`() {
        assertEquals(0, TokenEstimator.estimate(""))
    }
}

class RepetitionScannerTest {

    @Test
    fun `detects a phrase reused across replies`() {
        val found = RepetitionScanner.findRepeatedPhrases(
            listOf(
                "*她把玩着手里的耳机线，绕了一圈。* 嗯。",
                "她沉默了一会儿。*她把玩着手里的耳机线，绕了一圈。*",
                "*她把玩着手里的耳机线，绕了一圈。* 你说吧。",
            )
        )
        assertTrue("应当检出复读片段", found.isNotEmpty())
        assertTrue(found.any { "耳机线" in it || "把玩" in it })
    }

    @Test
    fun `does not flag varied replies`() {
        val found = RepetitionScanner.findRepeatedPhrases(
            listOf(
                "*她站起身，把外套搭在臂弯里。*",
                "窗外开始下雨了。*她伸手关掉调音台的电源。*",
                "你今天话很少。*她偏过头看你。*",
            )
        )
        assertTrue("不同内容不应被判为复读：$found", found.isEmpty())
    }

    @Test
    fun `single reply cannot repeat`() {
        assertTrue(RepetitionScanner.findRepeatedPhrases(listOf("只有一条回复")).isEmpty())
    }
}

class JsonExtractionTest {

    @Test
    fun `pulls an object out of a fenced code block`() {
        val raw = """
            好的，这是更新后的状态：
            ```json
            {"location": "直播间", "mood": "疲惫"}
            ```
            希望有帮助。
        """.trimIndent()
        val json = ContextMaintainer.extractJsonObject(raw)
        assertNotNull(json)
        assertEquals("""{"location": "直播间", "mood": "疲惫"}""", json)
    }

    @Test
    fun `handles nested braces`() {
        val raw = """前言 {"a": {"b": 1}, "c": 2} 后语"""
        assertEquals("""{"a": {"b": 1}, "c": 2}""", ContextMaintainer.extractJsonObject(raw))
    }

    @Test
    fun `ignores braces inside strings`() {
        val raw = """{"text": "这里有个 } 符号", "ok": true}"""
        assertEquals(raw, ContextMaintainer.extractJsonObject(raw))
    }

    @Test
    fun `pulls an array out of prose`() {
        val raw = """抽取结果如下：[{"text":"阿舟养猫","importance":3}] 完毕"""
        assertEquals("""[{"text":"阿舟养猫","importance":3}]""", ContextMaintainer.extractJsonArray(raw))
    }

    @Test
    fun `returns null when there is nothing to extract`() {
        assertNull(ContextMaintainer.extractJsonObject("这里没有 JSON"))
    }

    @Test
    fun `returns null on an unbalanced block`() {
        assertNull(ContextMaintainer.extractJsonObject("""{"a": 1"""))
    }
}

class EndpointUrlTest {

    @Test
    fun `appends the full path to a bare host`() {
        assertEquals(
            "https://api.example.com/v1/chat/completions",
            OpenAiClient.resolveChatUrl("https://api.example.com"),
        )
    }

    @Test
    fun `appends chat completions to a v1 root`() {
        assertEquals(
            "https://api.deepseek.com/v1/chat/completions",
            OpenAiClient.resolveChatUrl("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun `leaves a complete url alone`() {
        val full = "https://api.openai.com/v1/chat/completions"
        assertEquals(full, OpenAiClient.resolveChatUrl(full))
    }

    @Test
    fun `tolerates a trailing slash`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiClient.resolveChatUrl("https://api.openai.com/v1/"),
        )
    }

    @Test
    fun `assumes https when the scheme is missing`() {
        assertEquals(
            "https://api.openai.com/v1/chat/completions",
            OpenAiClient.resolveChatUrl("api.openai.com/v1"),
        )
    }

    @Test
    fun `keeps http for local endpoints`() {
        assertEquals(
            "http://192.168.1.100:5000/v1/chat/completions",
            OpenAiClient.resolveChatUrl("http://192.168.1.100:5000/v1"),
        )
    }

    @Test
    fun `derives the models url`() {
        assertEquals(
            "https://api.deepseek.com/v1/models",
            OpenAiClient.resolveModelsUrl("https://api.deepseek.com/v1"),
        )
    }

    @Test
    fun `rejects an empty address`() {
        assertNull(OpenAiClient.resolveChatUrl("   "))
    }
}

class CharacterCardTest {

    /** 造一张最小可用的 PNG：签名 + IHDR + tEXt(chara) + IEND。解析器不校验 CRC，占位即可。 */
    private fun fakeCardPng(json: String): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))

        fun chunk(type: String, data: ByteArray) {
            val len = data.size
            out.write(byteArrayOf(
                (len ushr 24).toByte(), (len ushr 16).toByte(),
                (len ushr 8).toByte(), len.toByte(),
            ))
            out.write(type.toByteArray(Charsets.US_ASCII))
            out.write(data)
            out.write(byteArrayOf(0, 0, 0, 0)) // CRC 占位
        }

        chunk("IHDR", ByteArray(13))
        val b64 = Base64.getEncoder().encodeToString(json.toByteArray(Charsets.UTF_8))
        chunk("tEXt", "chara".toByteArray(Charsets.US_ASCII) + byteArrayOf(0) + b64.toByteArray(Charsets.US_ASCII))
        chunk("IEND", ByteArray(0))
        return out.toByteArray()
    }

    private val v2Card = """
        {"spec":"chara_card_v2","spec_version":"2.0","data":{
          "name":"测试角色",
          "description":"一个用来测试的角色",
          "personality":"沉默寡言",
          "scenario":"雨夜的便利店",
          "first_mes":"*她抬起头。* 你也来买伞？",
          "mes_example":"<START>\n{{user}}: 你好\n{{char}}: 嗯。",
          "alternate_greetings":["另一个开场"],
          "tags":["都市","冷淡"],
          "character_book":{"entries":[
            {"name":"便利店","keys":["便利店","伞"],"content":"街角那家 24 小时营业。","enabled":true,"constant":false,"insertion_order":120}
          ]}
        }}
    """.trimIndent()

    @Test
    fun `parses a v2 card embedded in png`() {
        val card = CharacterCardIO.parsePngCard(fakeCardPng(v2Card))
        assertNotNull(card)
        assertEquals("测试角色", card!!.character.name)
        assertEquals("沉默寡言", card.character.personality)
        assertEquals("雨夜的便利店", card.character.scenario)
        assertTrue(card.character.firstMessage.contains("你也来买伞"))
        assertEquals(listOf("另一个开场"), card.character.alternateGreetings)
        assertEquals(listOf("都市", "冷淡"), card.character.tags)
        assertNotNull("PNG 本身应当被留作头像", card.avatarBytes)
    }

    @Test
    fun `parses the embedded character book into lore entries`() {
        val card = CharacterCardIO.parsePngCard(fakeCardPng(v2Card))!!
        assertEquals(1, card.lore.size)
        val entry = card.lore.first()
        assertEquals(listOf("便利店", "伞"), entry.keys)
        assertEquals(120, entry.priority)
        assertTrue(entry.content.contains("24 小时"))
    }

    @Test
    fun `parses a flat v1 card`() {
        val v1 = """{"name":"老卡","description":"V1 格式","first_mes":"你好。"}"""
        val card = CharacterCardIO.parseJsonCard(v1)
        assertNotNull(card)
        assertEquals("老卡", card!!.character.name)
        assertEquals("你好。", card.character.firstMessage)
    }

    @Test
    fun `parses tavern legacy field names`() {
        val legacy = """{"char_name":"旧卡","char_persona":"人设","char_greeting":"开场","world_scenario":"情境"}"""
        val card = CharacterCardIO.parseJsonCard(legacy)!!
        assertEquals("旧卡", card.character.name)
        assertEquals("人设", card.character.description)
        assertEquals("开场", card.character.firstMessage)
        assertEquals("情境", card.character.scenario)
    }

    @Test
    fun `rejects a png without card data`() {
        val plain = fakeCardPng("").let {
            // 去掉 tEXt：直接给一张只有 IHDR/IEND 的图
            val out = java.io.ByteArrayOutputStream()
            out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
            out.write(byteArrayOf(0, 0, 0, 13))
            out.write("IHDR".toByteArray())
            out.write(ByteArray(13))
            out.write(byteArrayOf(0, 0, 0, 0))
            out.write(byteArrayOf(0, 0, 0, 0))
            out.write("IEND".toByteArray())
            out.write(byteArrayOf(0, 0, 0, 0))
            out.toByteArray()
        }
        assertNull(CharacterCardIO.parsePngCard(plain))
    }

    @Test
    fun `rejects malformed json`() {
        assertNull(CharacterCardIO.parseJsonCard("{ not json"))
    }

    @Test
    fun `export round trips back through the importer`() {
        val original = CharacterCardIO.parsePngCard(fakeCardPng(v2Card))!!
        val json = CharacterCardIO.exportJson(original.character, original.lore)
        val reimported = CharacterCardIO.parseJsonCard(json)
        assertNotNull(reimported)
        assertEquals(original.character.name, reimported!!.character.name)
        assertEquals(original.character.personality, reimported.character.personality)
        assertEquals(original.character.alternateGreetings, reimported.character.alternateGreetings)
        assertEquals(original.lore.size, reimported.lore.size)
        assertEquals(original.lore.first().keys, reimported.lore.first().keys)
    }
}

class TextRelevanceTest {

    @Test
    fun `related chinese sentences overlap`() {
        val a = TextRelevance.shingles("馒头是一只橘猫")
        val b = TextRelevance.shingles("馒头今天又把键盘踩了")
        assertTrue(TextRelevance.overlap(a, b) > 0f)
    }

    @Test
    fun `unrelated sentences do not overlap`() {
        val a = TextRelevance.shingles("林晚在电台工作")
        val b = TextRelevance.shingles("顾青梧守着剑冢")
        assertEquals(0f, TextRelevance.overlap(a, b), 0.001f)
    }

    @Test
    fun `empty input scores zero`() {
        assertEquals(0f, TextRelevance.overlap(emptySet(), TextRelevance.shingles("任意")), 0.001f)
    }
}
