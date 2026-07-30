package com.murmur.app.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 候选回复的解析。
 * 模型大概率给合法 JSON，但"大概率"不够 —— 解析失败等于这个功能当场消失，
 * 所以编号列表、代码块、前后废话都得接得住。
 */
class SuggestionParseTest {

    @Test
    fun `parses a clean json array`() {
        val out = SuggestionEngine.parse("""["还不困。","你今天声音有点哑。","那我送你回去。"]""")
        assertEquals(listOf("还不困。", "你今天声音有点哑。", "那我送你回去。"), out)
    }

    @Test
    fun `parses json wrapped in a fenced block with commentary`() {
        val raw = """
            好的，这是三个选项：
            ```json
            ["嗯，我在。","你怎么还没走？","*我把外套递过去* 穿上。"]
            ```
        """.trimIndent()
        val out = SuggestionEngine.parse(raw)
        assertEquals(3, out.size)
        assertTrue(out[2].startsWith("*我把外套递过去*"))
    }

    @Test
    fun `falls back to a numbered list`() {
        val raw = """
            1. 还不困。
            2. 你今天声音有点哑。
            3. 那我送你回去。
        """.trimIndent()
        assertEquals(listOf("还不困。", "你今天声音有点哑。", "那我送你回去。"), SuggestionEngine.parse(raw))
    }

    @Test
    fun `falls back to a bulleted list`() {
        val raw = "- 嗯。\n- 你先说。\n· 我不太想聊这个。"
        assertEquals(listOf("嗯。", "你先说。", "我不太想聊这个。"), SuggestionEngine.parse(raw))
    }

    @Test
    fun `strips chinese numbering and quotes`() {
        val raw = "1、\"还不困。\"\n2、\"你今天声音有点哑。\"\n3、\"那我送你回去。\""
        val out = SuggestionEngine.parse(raw)
        assertEquals(3, out.size)
        assertEquals("还不困。", out[0])
    }

    @Test
    fun `drops duplicates`() {
        val out = SuggestionEngine.parse("""["嗯。","嗯。","你先说。"]""")
        assertEquals(listOf("嗯。", "你先说。"), out)
    }

    @Test
    fun `caps at the requested count`() {
        val out = SuggestionEngine.parse("""["一","二","三","四","五"]""", want = 3)
        assertEquals(listOf("一", "二", "三"), out)
    }

    @Test
    fun `discards absurdly long lines`() {
        val long = "啊".repeat(200)
        val out = SuggestionEngine.parse("""["短的一句","$long"]""")
        assertEquals(listOf("短的一句"), out)
    }

    @Test
    fun `returns empty on garbage rather than throwing`() {
        assertTrue(SuggestionEngine.parse("").isEmpty())
        assertTrue(SuggestionEngine.parse("   \n  ").isEmpty())
    }

    @Test
    fun `handles a single unparseable blob without crashing`() {
        val out = SuggestionEngine.parse("我没法给出建议。")
        assertEquals(listOf("我没法给出建议。"), out)
    }
}
