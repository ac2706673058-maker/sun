package com.murmur.app.engine

import com.murmur.app.data.db.ApiProfileEntity
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.data.db.ChatEntity
import com.murmur.app.data.db.LoreEntity
import com.murmur.app.data.db.MemoryEntity
import com.murmur.app.data.db.MessageEntity
import com.murmur.app.data.db.PersonaEntity
import com.murmur.app.data.db.SceneState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContextBuilderTest {

    private fun char(
        name: String = "林晚",
        example: String = "",
        speech: String = "语速慢，句子短",
    ) = CharacterEntity(
        id = "c1",
        name = name,
        description = "28岁，深夜电台主播",
        personality = "洞察力强，回避型",
        speechStyle = speech,
        scenario = "凌晨的直播间",
        exampleDialogue = example,
    )

    private fun persona() = PersonaEntity(id = "p1", name = "阿舟", description = "夜班程序员")

    private fun chat(summary: String = "", scene: SceneState = SceneState()) = ChatEntity(
        id = "chat1",
        characterId = "c1",
        personaId = "p1",
        rollingSummary = summary,
        sceneStateJson = if (scene.isEmpty) "" else scene.encode(),
    )

    private fun profile(context: Int = 16384, maxTokens: Int = 700) = ApiProfileEntity(
        id = "a1", name = "test", baseUrl = "https://x/v1", model = "m",
        contextTokens = context, maxTokens = maxTokens,
    )

    private fun msgs(n: Int, body: String = "这是一条测试消息，用来占位。"): List<MessageEntity> =
        (0 until n).map { i ->
            MessageEntity(
                id = "m$i",
                chatId = "chat1",
                idx = i.toLong(),
                role = if (i % 2 == 0) "assistant" else "user",
                swipes = listOf("$body#$i"),
            )
        }

    private fun input(
        messages: List<MessageEntity>,
        memories: List<MemoryEntity> = emptyList(),
        lore: List<LoreEntity> = emptyList(),
        settings: EngineSettings = EngineSettings(),
        profile: ApiProfileEntity = profile(),
        chat: ChatEntity = chat(),
        character: CharacterEntity = char(),
        upTo: Long? = null,
        directive: String? = null,
    ) = ContextInput(
        character = character,
        persona = persona(),
        chat = chat,
        messages = messages,
        memories = memories,
        lore = lore,
        settings = settings,
        profile = profile,
        oneShotDirective = directive,
        upToIdxExclusive = upTo,
    )

    @Test
    fun `system core comes first and contains the adults-only floor`() {
        val built = ContextBuilder.build(input(msgs(4)))
        assertEquals("system", built.messages.first().role)
        assertTrue(built.messages.first().content.startsWith(PromptTemplates.SAFETY_FLOOR))
    }

    @Test
    fun `character name and persona name are substituted into the prompt`() {
        val built = ContextBuilder.build(input(msgs(2)))
        val joined = built.messages.joinToString("\n") { it.content }
        assertTrue(joined.contains("林晚"))
        assertTrue(joined.contains("阿舟"))
        assertFalse("模板变量必须被替换掉", joined.contains("{{char}}"))
        assertFalse(joined.contains("{{user}}"))
    }

    @Test
    fun `anchor is injected near the end, not at the top`() {
        val built = ContextBuilder.build(
            input(msgs(10), settings = EngineSettings(anchorEnabled = true, anchorDepth = 1))
        )
        val anchorIdx = built.messages.indexOfFirst { it.content.startsWith("[ 场景锚") }
        assertTrue("锚点必须存在", anchorIdx >= 0)
        // 锚点之后应当只剩下 1 条历史消息 + 尾部指令
        val after = built.messages.drop(anchorIdx + 1)
        assertEquals(1, after.count { it.role == "user" || it.role == "assistant" })
        assertTrue("锚点必须在靠后的位置", anchorIdx > built.messages.size / 2)
    }

    @Test
    fun `anchor depth controls how many messages follow it`() {
        val built = ContextBuilder.build(
            input(msgs(12), settings = EngineSettings(anchorDepth = 3))
        )
        val anchorIdx = built.messages.indexOfFirst { it.content.startsWith("[ 场景锚") }
        val after = built.messages.drop(anchorIdx + 1)
        assertEquals(3, after.count { it.role == "user" || it.role == "assistant" })
    }

    @Test
    fun `scene state lands in the anchor`() {
        val scene = SceneState(
            location = "直播间",
            time = "凌晨两点四十",
            pose = "她瘫在椅背上，隔着桌子",
            outfit = "宽大的针织衫",
            mood = "疲惫但没打算走",
            threads = listOf("答应过要请她吃夜宵"),
        )
        val built = ContextBuilder.build(input(msgs(6), chat = chat(scene = scene)))
        val anchor = built.messages.first { it.content.startsWith("[ 场景锚") }.content
        assertTrue(anchor.contains("直播间"))
        assertTrue(anchor.contains("凌晨两点四十"))
        assertTrue(anchor.contains("针织衫"))
        assertTrue(anchor.contains("夜宵"))
    }

    @Test
    fun `history is trimmed to fit the budget and overflow is reported`() {
        val long = "床前明月光疑是地上霜举头望明月低头思故乡".repeat(30)
        val built = ContextBuilder.build(
            input(msgs(80, long), profile = profile(context = 4096, maxTokens = 500))
        )
        assertTrue("必须有消息被挤出去", built.overflow.isNotEmpty())
        assertTrue(
            "装配结果不能超过预算：used=${built.report.used} limit=${built.report.limit}",
            built.report.used <= built.report.limit,
        )
        // 保留的必须是最近的那些
        val keptIdx = built.report.historyIncluded
        assertTrue(keptIdx > 0)
        val lastWireContent = built.messages.last { it.role == "user" || it.role == "assistant" }.content
        assertTrue("最后一条必须是最新的消息", lastWireContent.endsWith("#79"))
    }

    @Test
    fun `pinned messages survive trimming even when very old`() {
        val long = "这是一段很长的内容用来把预算撑爆".repeat(40)
        val all = msgs(60, long).toMutableList()
        all[0] = all[0].copy(pinned = true, swipes = listOf("我叫阿舟，在做夜班运维。"))
        val built = ContextBuilder.build(
            input(all, profile = profile(context = 4096, maxTokens = 400))
        )
        val joined = built.messages.joinToString("\n") { it.content }
        assertTrue("置顶消息不能被裁掉", joined.contains("我叫阿舟"))
    }

    @Test
    fun `hidden messages never reach the wire`() {
        val all = msgs(6).toMutableList()
        all[2] = all[2].copy(hidden = true, swipes = listOf("场外备注：不要发给模型"))
        val built = ContextBuilder.build(input(all))
        val joined = built.messages.joinToString("\n") { it.content }
        assertFalse(joined.contains("场外备注"))
    }

    @Test
    fun `lore activates only when a keyword appears in the recent window`() {
        val lore = listOf(
            LoreEntity(
                id = "l1", characterId = "c1", name = "电台",
                keys = listOf("电台"), content = "《晚安频率》是凌晨一点的节目。",
                scanDepth = 3,
            ),
            LoreEntity(
                id = "l2", characterId = "c1", name = "不该出现",
                keys = listOf("完全无关的词"), content = "这条不应该被注入。",
            ),
        )
        val history = msgs(4).toMutableList()
        history[3] = history[3].copy(swipes = listOf("你今天的电台节目我听了。"))

        val built = ContextBuilder.build(input(history, lore = lore))
        val joined = built.messages.joinToString("\n") { it.content }
        assertTrue(joined.contains("晚安频率"))
        assertFalse(joined.contains("这条不应该被注入"))
    }

    @Test
    fun `constant lore is injected without any keyword`() {
        val lore = listOf(
            LoreEntity(
                id = "l1", characterId = "c1", keys = emptyList(),
                content = "这个世界没有手机。", constant = true,
            )
        )
        val built = ContextBuilder.build(input(msgs(4), lore = lore))
        assertTrue(built.messages.joinToString("\n") { it.content }.contains("这个世界没有手机"))
    }

    @Test
    fun `lore scan depth limits how far back keywords are searched`() {
        val lore = listOf(
            LoreEntity(
                id = "l1", characterId = "c1", keys = listOf("电台"),
                content = "《晚安频率》。", scanDepth = 2,
            )
        )
        val history = msgs(10).toMutableList()
        history[0] = history[0].copy(swipes = listOf("你今天的电台节目我听了。"))
        val built = ContextBuilder.build(input(history, lore = lore))
        assertFalse(
            "关键词只出现在很早的消息里，超出扫描深度就不该命中",
            built.messages.joinToString("\n") { it.content }.contains("晚安频率"),
        )
    }

    @Test
    fun `memories relevant to the recent topic outrank irrelevant ones`() {
        val memories = listOf(
            MemoryEntity(id = "1", chatId = "chat1", text = "阿舟养了一只叫馒头的橘猫。", importance = 3),
            MemoryEntity(id = "2", chatId = "chat1", text = "林晚戒烟戒了四次。", importance = 3),
            MemoryEntity(id = "3", chatId = "chat1", text = "阿舟对海鲜过敏。", importance = 3),
        )
        val history = msgs(4).toMutableList()
        history[3] = history[3].copy(swipes = listOf("馒头今天又把我的键盘踩了一遍。"))

        val ranked = ContextBuilder.rankMemories(memories, history.last().content, max = 1)
        assertEquals("和当前话题最相关的记忆应当排第一", "1", ranked.first().id)
    }

    @Test
    fun `pinned memories always win regardless of relevance`() {
        val memories = listOf(
            MemoryEntity(id = "1", chatId = "chat1", text = "完全不相关的事情。", pinned = true, importance = 1),
            MemoryEntity(id = "2", chatId = "chat1", text = "馒头是一只橘猫。", importance = 5),
        )
        val ranked = ContextBuilder.rankMemories(memories, "馒头今天很闹", max = 1)
        assertEquals("1", ranked.first().id)
    }

    @Test
    fun `regenerating excludes the target message and everything after it`() {
        val all = msgs(8)
        val built = ContextBuilder.build(input(all, upTo = 5L))
        val joined = built.messages.joinToString("\n") { it.content }
        assertTrue(joined.contains("#4"))
        assertFalse(joined.contains("#5"))
        assertFalse(joined.contains("#7"))
    }

    @Test
    fun `merge mode collapses to a single leading system message`() {
        val built = ContextBuilder.build(
            input(
                msgs(6),
                profile = profile().copy(mergeSystemMessages = true),
                chat = chat(summary = "他们在电台认识。"),
            )
        )
        assertEquals(1, built.messages.count { it.role == "system" })
        assertEquals("system", built.messages.first().role)
        // 锚点降级成方括号旁注，但内容还在
        assertTrue(built.messages.any { it.content.contains("场景锚") })
    }

    @Test
    fun `example dialogue is dropped before history when the budget is tight`() {
        val example = "<START>\n{{user}}: 测试\n{{char}}: 回应".repeat(40)
        val built = ContextBuilder.build(
            input(
                msgs(30, "一段中等长度的对话内容用来占预算。"),
                character = char(example = example),
                profile = profile(context = 3000, maxTokens = 400),
            )
        )
        assertTrue(built.report.used <= built.report.limit)
        assertTrue(
            "预算吃紧时应当先牺牲示例对白，保住历史",
            built.report.historyIncluded > 0,
        )
    }

    @Test
    fun `history floor is respected when settings blocks are huge`() {
        val huge = CharacterEntity(
            id = "c1", name = "林晚",
            description = "很长的设定".repeat(500),
            personality = "很长的性格".repeat(500),
        )
        val built = ContextBuilder.build(
            input(
                msgs(20, "对话内容"),
                character = huge,
                profile = profile(context = 8000, maxTokens = 500),
            )
        )
        assertTrue("设定再长也不能把历史挤没", built.report.historyIncluded > 0)
    }

    @Test
    fun `post history instruction is the very last message`() {
        val built = ContextBuilder.build(input(msgs(6)))
        val last = built.messages.last()
        assertEquals("system", last.role)
        assertTrue(last.content.contains("继续"))
    }

    @Test
    fun `one shot directive is included`() {
        val built = ContextBuilder.build(input(msgs(4), directive = "接着往下写，不要重开。"))
        assertTrue(built.messages.any { it.content.contains("接着往下写") })
    }

    @Test
    fun `repeated phrases are surfaced into the anchor`() {
        val replies = List(4) { "*她把玩着手里的耳机线，绕了一圈又松开。* 嗯。#$it" }
        val history = replies.mapIndexed { i, text ->
            MessageEntity(
                id = "r$i", chatId = "chat1", idx = i.toLong(),
                role = "assistant", swipes = listOf(text),
            )
        }
        val built = ContextBuilder.build(
            input(history, settings = EngineSettings(antiRepeatEnabled = true))
        )
        val anchor = built.messages.first { it.content.startsWith("[ 场景锚") }.content
        assertTrue("复读片段应当被点名", anchor.contains("最近已经用过"))
    }

    @Test
    fun `empty chat still produces a valid prompt`() {
        val built = ContextBuilder.build(input(emptyList()))
        assertTrue(built.messages.isNotEmpty())
        assertEquals("system", built.messages.first().role)
        assertTrue(built.report.used > 0)
    }
}
