package com.murmur.app.engine

import com.murmur.app.data.db.ApiProfileEntity
import com.murmur.app.data.db.CharacterEntity
import com.murmur.app.data.db.ChatEntity
import com.murmur.app.data.db.LoreEntity
import com.murmur.app.data.db.MemoryEntity
import com.murmur.app.data.db.MessageEntity
import com.murmur.app.data.db.PersonaEntity
import com.murmur.app.data.db.SceneState
import com.murmur.app.net.WireMessage

data class EngineSettings(
    val mainPrompt: String = PromptTemplates.DEFAULT_MAIN,
    val postHistory: String = PromptTemplates.DEFAULT_POST_HISTORY,
    val customDirective: String = "",
    val anchorEnabled: Boolean = true,
    /** 锚点插在倒数第几条消息之前。1 = 紧贴最后一条用户消息之前 */
    val anchorDepth: Int = 1,
    val lengthHint: String = "medium",
    val exampleDialogueEnabled: Boolean = true,
    val antiRepeatEnabled: Boolean = true,
    val summaryEnabled: Boolean = true,
    val memoryEnabled: Boolean = true,
    val sceneTrackingEnabled: Boolean = true,
    /** 历史对话至少要拿到的预算比例，防止设定块把上下文吃光 */
    val historyFloorRatio: Float = 0.5f,
    val maxMemoriesInContext: Int = 12,
)

data class BudgetReport(
    val limit: Int = 0,
    val used: Int = 0,
    val systemCore: Int = 0,
    val characterBlock: Int = 0,
    val personaBlock: Int = 0,
    val exampleBlock: Int = 0,
    val loreBlock: Int = 0,
    val memoryBlock: Int = 0,
    val summaryBlock: Int = 0,
    val anchorBlock: Int = 0,
    val historyBlock: Int = 0,
    val reservedForReply: Int = 0,
    val historyIncluded: Int = 0,
    val historyDropped: Int = 0,
    val loreActivated: List<String> = emptyList(),
    val memoriesUsed: Int = 0,
)

enum class Perspective { CHARACTER, USER }

data class BuiltPrompt(
    val messages: List<WireMessage>,
    val report: BudgetReport,
    /** 因预算被挤出上下文、应当交给摘要器归档的消息 */
    val overflow: List<MessageEntity>,
)

data class ContextInput(
    val character: CharacterEntity,
    val persona: PersonaEntity?,
    val chat: ChatEntity,
    val messages: List<MessageEntity>,
    val memories: List<MemoryEntity>,
    val lore: List<LoreEntity>,
    val settings: EngineSettings,
    val profile: ApiProfileEntity,
    /** 追加到最后的一次性指令，如"继续写完"、"以我的身份写" */
    val oneShotDirective: String? = null,
    /**
     * 这一轮要以谁的身份输出。
     * 代写时必须切成 [Perspective.USER]，否则末尾的锚点和尾部指令仍然在喊
     * "只写 {{char}}"，权重又最高，代写指令会被整个盖掉 —— 表现就是"帮我想的话还是对方在说"。
     */
    val perspective: Perspective = Perspective.CHARACTER,
    /** 生成到第几条为止（重roll 时排除掉正在重写的那条） */
    val upToIdxExclusive: Long? = null,
)

/**
 * 上下文装配。
 *
 * 顺序（从模型视角，越靠后注意力权重越高）：
 *   system  : 底线 + 主提示词 + 文体指令
 *   system  : 角色卡（身份/性格/说话方式/情境）
 *   system  : 用户人设
 *   system  : 世界书（命中关键词的条目）
 *   system  : 长期记忆（按相关性挑选）
 *   system  : 前情梗概（归档区压缩）
 *   system  : 示例对白（语气锚）
 *   ...历史消息...
 *   system  : ★ 深度锚点（身份 + 此刻场景 + 文体 + 去重提示）
 *   ...最后 anchorDepth 条消息...
 *   system  : 尾部指令
 *
 * 预算分配是"先保历史、再塞设定"：历史至少拿到 historyFloorRatio，
 * 设定块超支时按 示例对白 → 世界书 → 记忆 → 梗概 的顺序退让。
 */
object ContextBuilder {

    private const val SAFETY_MARGIN = 96

    fun build(input: ContextInput): BuiltPrompt {
        val s = input.settings
        val p = input.profile
        val char = input.character
        val vars = buildVars(char, input.persona)

        val limit = (p.contextTokens - p.maxTokens - SAFETY_MARGIN).coerceAtLeast(512)

        // ---- 参与上下文的历史 ----
        val usableHistory = input.messages
            .filter { !it.hidden && it.content.isNotBlank() }
            .filter { input.upToIdxExclusive == null || it.idx < input.upToIdxExclusive }

        // ---- 固定块 ----
        val systemCore = buildString {
            append(PromptTemplates.SAFETY_FLOOR)
            append("\n\n")
            append(PromptTemplates.fill(s.mainPrompt, vars).trim())
            val extra = PromptTemplates.fill(s.customDirective, vars).trim()
            if (extra.isNotEmpty()) {
                append("\n\n【补充要求】\n")
                append(extra)
            }
        }

        val characterBlock = buildCharacterBlock(char, vars)
        val personaBlock = buildPersonaBlock(input.persona, vars)

        // ---- 世界书：只在最近若干条里扫关键词 ----
        val activated = activateLore(input.lore, usableHistory)
        val loreBlock = if (activated.isEmpty()) "" else buildString {
            appendLine("【世界设定】")
            activated.forEach { appendLine("· ${it.content.trim()}") }
        }.trim()

        // ---- 长期记忆：按与近期话题的相关性 + 重要度挑选 ----
        val recentText = usableHistory.takeLast(6).joinToString("\n") { it.content }
        val pickedMemories = if (s.memoryEnabled) {
            rankMemories(input.memories, recentText, s.maxMemoriesInContext)
        } else emptyList()
        val memoryBlock = if (pickedMemories.isEmpty()) "" else buildString {
            appendLine("【关于你们之间，你记得的事】")
            pickedMemories.forEach { appendLine("· ${it.text.trim()}") }
        }.trim()

        val summaryBlock = if (s.summaryEnabled && input.chat.rollingSummary.isNotBlank()) {
            "【前情】\n" + input.chat.rollingSummary.trim()
        } else ""

        val exampleBlock = if (s.exampleDialogueEnabled && char.exampleDialogue.isNotBlank()) {
            "【说话风格示例 · 只学语气和节奏，不要照抄内容，也不要当成发生过的事】\n" +
                PromptTemplates.fill(char.exampleDialogue, vars).trim()
        } else ""

        // ---- 深度锚点 ----
        val scene = SceneState.parse(input.chat.sceneStateJson)
        val avoid = if (s.antiRepeatEnabled) {
            RepetitionScanner.findRepeatedPhrases(
                usableHistory.filter { it.role == "assistant" }.takeLast(6).map { it.content }
            )
        } else emptyList()

        val sceneLines = PromptTemplates.SceneLines(
            location = scene.location.nullIfBlank(),
            time = scene.time.nullIfBlank(),
            pose = scene.pose.nullIfBlank(),
            outfit = scene.outfit.nullIfBlank(),
            mood = scene.mood.nullIfBlank(),
            threads = scene.threads.filter { it.isNotBlank() },
        )

        val anchorText = if (input.perspective == Perspective.USER) {
            PromptTemplates.userAnchor(
                charName = char.name,
                userName = vars["user"] ?: "我",
                personaLine = input.persona?.description?.lineSequence()?.firstOrNull()?.take(80).orEmpty(),
                scene = sceneLines,
            )
        } else if (s.anchorEnabled) {
            PromptTemplates.anchor(
                charName = char.name,
                userName = vars["user"] ?: "对方",
                identity = identityLine(char),
                speechStyle = char.speechStyle.trim(),
                scene = sceneLines,
                avoidPhrases = avoid,
                lengthHint = PromptTemplates.LENGTH_HINTS[s.lengthHint] ?: "写 2–5 句",
                relationship = input.chat.relationshipStage.takeIf { it.isNotBlank() },
                affinity = input.chat.affinity,
            )
        } else ""

        val postHistory = PromptTemplates.fill(
            if (input.perspective == Perspective.USER) PromptTemplates.USER_POST_HISTORY else s.postHistory,
            vars,
        ).trim()

        // ---- 预算 ----
        var cSystem = tk(systemCore)
        var cChar = tk(characterBlock)
        var cPersona = tk(personaBlock)
        var cAnchor = tk(anchorText)
        var cPost = tk(postHistory)
        var cExample = tk(exampleBlock)
        var cLore = tk(loreBlock)
        var cMemory = tk(memoryBlock)
        var cSummary = tk(summaryBlock)

        val immovable = cSystem + cChar + cPersona + cAnchor + cPost
        val historyFloor = (limit * s.historyFloorRatio).toInt()
        var optionalBudget = (limit - immovable - historyFloor).coerceAtLeast(0)

        // 可选块按优先级退让：示例对白最先砍（它只是语气锚，锚点里已经有 speechStyle 兜底）
        var exampleUsed = exampleBlock
        var loreUsed = activated
        var memoryUsed = pickedMemories
        var summaryUsed = summaryBlock

        var optionalCost = cExample + cLore + cMemory + cSummary
        if (optionalCost > optionalBudget) {
            // 1. 砍示例对白
            if (optionalCost - cExample <= optionalBudget) {
                val room = optionalBudget - (cLore + cMemory + cSummary)
                exampleUsed = truncateToTokens(exampleBlock, room)
                cExample = tk(exampleUsed)
            } else {
                exampleUsed = ""
                cExample = 0
                optionalCost = cLore + cMemory + cSummary
                // 2. 砍世界书（低优先级先走）
                if (optionalCost > optionalBudget) {
                    val sortedLore = loreUsed.sortedByDescending { it.priority }
                    val kept = ArrayList<LoreEntity>()
                    var acc = 0
                    val loreRoom = (optionalBudget - cMemory - cSummary).coerceAtLeast(0)
                    for (e in sortedLore) {
                        val c = tk(e.content)
                        if (acc + c <= loreRoom) { kept.add(e); acc += c } else break
                    }
                    loreUsed = kept
                    cLore = if (kept.isEmpty()) 0 else tk(renderLore(kept))
                    optionalCost = cLore + cMemory + cSummary
                }
                // 3. 砍记忆（低重要度先走）
                if (optionalCost > optionalBudget) {
                    val memRoom = (optionalBudget - cLore - cSummary).coerceAtLeast(0)
                    val kept = ArrayList<MemoryEntity>()
                    var acc = 0
                    for (m in memoryUsed) {
                        val c = tk(m.text) + 3
                        if (acc + c <= memRoom) { kept.add(m); acc += c } else break
                    }
                    memoryUsed = kept
                    cMemory = if (kept.isEmpty()) 0 else tk(renderMemories(kept))
                    optionalCost = cLore + cMemory + cSummary
                }
                // 4. 截断梗概（保留尾部，尾部是最近发生的）
                if (optionalCost > optionalBudget) {
                    val sumRoom = (optionalBudget - cLore - cMemory).coerceAtLeast(0)
                    summaryUsed = truncateToTokensFromEnd(summaryBlock, sumRoom)
                    cSummary = tk(summaryUsed)
                }
            }
        }

        val loreRendered = if (loreUsed.isEmpty()) "" else renderLore(loreUsed)
        val memoryRendered = if (memoryUsed.isEmpty()) "" else renderMemories(memoryUsed)
        cLore = tk(loreRendered)
        cMemory = tk(memoryRendered)

        val fixedCost = cSystem + cChar + cPersona + cAnchor + cPost +
            cExample + cLore + cMemory + cSummary
        var historyBudget = (limit - fixedCost).coerceAtLeast(256)

        // ---- 历史：从新往旧塞，置顶消息优先保留 ----
        val chosen = LinkedHashSet<String>()
        var historyCost = 0

        val pinned = usableHistory.filter { it.pinned }
        for (m in pinned) {
            val c = TokenEstimator.estimateMessage(m.role, m.content)
            if (historyCost + c <= historyBudget) { chosen.add(m.id); historyCost += c }
        }
        for (m in usableHistory.asReversed()) {
            if (m.id in chosen) continue
            val c = TokenEstimator.estimateMessage(m.role, m.content)
            if (historyCost + c > historyBudget) break
            chosen.add(m.id)
            historyCost += c
        }

        val included = usableHistory.filter { it.id in chosen }
        val overflow = usableHistory.filter { it.id !in chosen }

        // ---- 组装 ----
        val out = ArrayList<WireMessage>()
        out.add(WireMessage("system", systemCore))
        if (characterBlock.isNotBlank()) out.add(WireMessage("system", characterBlock))
        if (personaBlock.isNotBlank()) out.add(WireMessage("system", personaBlock))
        if (loreRendered.isNotBlank()) out.add(WireMessage("system", loreRendered))
        if (memoryRendered.isNotBlank()) out.add(WireMessage("system", memoryRendered))
        if (summaryUsed.isNotBlank()) out.add(WireMessage("system", summaryUsed))
        if (exampleUsed.isNotBlank()) out.add(WireMessage("system", exampleUsed))

        val depth = s.anchorDepth.coerceIn(0, 6)
        val insertAt = (included.size - depth).coerceIn(0, included.size)
        included.forEachIndexed { i, m ->
            if (i == insertAt && anchorText.isNotBlank()) out.add(WireMessage("system", anchorText))
            out.add(
                WireMessage(
                    role = wireRole(m.role),
                    content = m.content,
                    images = listOfNotNull(m.imageUri),
                )
            )
        }
        if (insertAt >= included.size && anchorText.isNotBlank()) {
            out.add(WireMessage("system", anchorText))
        }

        input.oneShotDirective?.takeIf { it.isNotBlank() }?.let {
            out.add(WireMessage("system", PromptTemplates.fill(it, vars)))
        }
        if (postHistory.isNotBlank()) out.add(WireMessage("system", postHistory))

        val finalMessages = if (p.mergeSystemMessages) mergeSystems(out) else out
        val used = finalMessages.sumOf { TokenEstimator.estimateMessage(it.role, it.content) }

        return BuiltPrompt(
            messages = finalMessages,
            report = BudgetReport(
                limit = limit,
                used = used,
                systemCore = cSystem,
                characterBlock = cChar,
                personaBlock = cPersona,
                exampleBlock = cExample,
                loreBlock = cLore,
                memoryBlock = cMemory,
                summaryBlock = cSummary,
                anchorBlock = cAnchor,
                historyBlock = historyCost,
                reservedForReply = p.maxTokens,
                historyIncluded = included.size,
                historyDropped = overflow.size,
                loreActivated = loreUsed.map { it.name.ifBlank { it.keys.firstOrNull() ?: "条目" } },
                memoriesUsed = memoryUsed.size,
            ),
            overflow = overflow,
        )
    }

    // ---- 组件 ----

    fun buildVars(char: CharacterEntity, persona: PersonaEntity?): Map<String, String> = mapOf(
        "char" to char.name,
        "user" to (persona?.name?.takeIf { it.isNotBlank() } ?: "我"),
        "persona" to (persona?.description ?: ""),
        "description" to char.description,
        "personality" to char.personality,
        "scenario" to char.scenario,
    )

    private fun identityLine(char: CharacterEntity): String {
        val bits = listOf(char.personality, char.description)
            .firstOrNull { it.isNotBlank() } ?: ""
        return bits.lineSequence().firstOrNull()?.take(80)?.trim().orEmpty()
    }

    private fun buildCharacterBlock(char: CharacterEntity, vars: Map<String, String>): String =
        buildString {
            appendLine("【你扮演的角色】")
            appendLine("名字：${char.name}")
            if (char.description.isNotBlank()) {
                appendLine("身份与外貌：")
                appendLine(PromptTemplates.fill(char.description, vars).trim())
            }
            if (char.personality.isNotBlank()) {
                appendLine("性格：")
                appendLine(PromptTemplates.fill(char.personality, vars).trim())
            }
            if (char.speechStyle.isNotBlank()) {
                appendLine("说话方式：")
                appendLine(PromptTemplates.fill(char.speechStyle, vars).trim())
            }
            if (char.scenario.isNotBlank()) {
                appendLine("当前情境：")
                appendLine(PromptTemplates.fill(char.scenario, vars).trim())
            }
        }.trim()

    private fun buildPersonaBlock(persona: PersonaEntity?, vars: Map<String, String>): String {
        if (persona == null) return ""
        if (persona.name.isBlank() && persona.description.isBlank()) return ""
        return buildString {
            appendLine("【对方是谁】")
            if (persona.name.isNotBlank()) appendLine("名字：${persona.name}")
            if (persona.description.isNotBlank()) {
                appendLine(PromptTemplates.fill(persona.description, vars).trim())
            }
        }.trim()
    }

    fun activateLore(lore: List<LoreEntity>, history: List<MessageEntity>): List<LoreEntity> {
        if (lore.isEmpty()) return emptyList()
        val out = ArrayList<LoreEntity>()
        for (e in lore) {
            if (!e.enabled) continue
            if (e.constant) { out.add(e); continue }
            if (e.keys.isEmpty()) continue
            val depth = e.scanDepth.coerceIn(1, 40)
            val window = history.takeLast(depth).joinToString("\n") { it.content }
            val haystack = if (e.caseSensitive) window else window.lowercase()
            val hit = e.keys.any { k ->
                val key = if (e.caseSensitive) k else k.lowercase()
                key.isNotBlank() && haystack.contains(key)
            }
            if (hit) out.add(e)
        }
        return out.sortedByDescending { it.priority }
    }

    fun rankMemories(all: List<MemoryEntity>, recentText: String, max: Int): List<MemoryEntity> {
        val enabled = all.filter { it.enabled && it.text.isNotBlank() }
        if (enabled.isEmpty()) return emptyList()
        val q = TextRelevance.shingles(recentText)
        val scored = enabled.map { m ->
            val rel = TextRelevance.overlap(q, TextRelevance.shingles(m.text))
            val score = (if (m.pinned) 1000f else 0f) + m.importance * 2f + rel * 8f
            m to score
        }
        return scored.sortedByDescending { it.second }.take(max).map { it.first }
            .sortedByDescending { it.importance }
    }

    private fun renderLore(items: List<LoreEntity>): String = buildString {
        appendLine("【世界设定】")
        items.forEach { appendLine("· ${it.content.trim()}") }
    }.trim()

    private fun renderMemories(items: List<MemoryEntity>): String = buildString {
        appendLine("【关于你们之间，你记得的事】")
        items.groupBy { it.category }.forEach { (cat, list) ->
            appendLine("$cat：")
            list.forEach { appendLine("· ${it.text.trim()}") }
        }
    }.trim()

    private fun mergeSystems(messages: List<WireMessage>): List<WireMessage> {
        val out = ArrayList<WireMessage>()
        val head = StringBuilder()
        var i = 0
        while (i < messages.size && messages[i].role == "system") {
            if (head.isNotEmpty()) head.append("\n\n")
            head.append(messages[i].content)
            i++
        }
        if (head.isNotEmpty()) out.add(WireMessage("system", head.toString()))
        // 中后段的 system（锚点、尾部指令）改写成带方括号的 user 旁注，
        // 兼容只接受单条 system 的端点。
        while (i < messages.size) {
            val m = messages[i]
            if (m.role == "system") {
                out.add(WireMessage("user", "[ ${m.content} ]"))
            } else {
                out.add(m)
            }
            i++
        }
        return coalesceSameRole(out)
    }

    private fun coalesceSameRole(messages: List<WireMessage>): List<WireMessage> {
        val out = ArrayList<WireMessage>()
        for (m in messages) {
            val last = out.lastOrNull()
            if (last != null && last.role == m.role && last.role != "system") {
                out[out.size - 1] = WireMessage(last.role, last.content + "\n\n" + m.content)
            } else out.add(m)
        }
        return out
    }

    private fun wireRole(role: String) = when (role) {
        "assistant" -> "assistant"
        "system" -> "system"
        else -> "user"
    }

    private fun tk(s: String) = if (s.isBlank()) 0 else TokenEstimator.estimate(s) + TokenEstimator.PER_MESSAGE_OVERHEAD

    private fun truncateToTokens(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        if (tk(text) <= maxTokens) return text
        val ratio = maxTokens.toFloat() / tk(text)
        val cut = (text.length * ratio * 0.95f).toInt().coerceAtLeast(0)
        return text.take(cut).substringBeforeLast('\n', text.take(cut))
    }

    private fun truncateToTokensFromEnd(text: String, maxTokens: Int): String {
        if (maxTokens <= 0) return ""
        if (tk(text) <= maxTokens) return text
        val ratio = maxTokens.toFloat() / tk(text)
        val keep = (text.length * ratio * 0.95f).toInt().coerceAtLeast(0)
        return "…" + text.takeLast(keep)
    }

    private fun String.nullIfBlank(): String? = takeIf { it.isNotBlank() }
}
