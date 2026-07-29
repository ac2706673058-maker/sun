package com.murmur.app.engine

/**
 * 不依赖分词器、不依赖 embedding 的相关性打分。
 * 中文用字符二元组（bigram），西文用小写单词，取重叠系数。
 * 目的只是把"和当前话题有关的记忆"排到前面，不需要语义级精度，但必须够快（主线程外每轮调用一次）。
 */
object TextRelevance {

    fun shingles(text: String): Set<String> {
        if (text.isBlank()) return emptySet()
        val out = HashSet<String>()
        val latin = StringBuilder()
        var prevCjk: Char? = null
        for (ch in text) {
            if (isCjk(ch)) {
                flushLatin(latin, out)
                prevCjk?.let { out.add("$it$ch") }
                prevCjk = ch
            } else if (ch.isLetterOrDigit()) {
                prevCjk = null
                latin.append(ch.lowercaseChar())
            } else {
                prevCjk = null
                flushLatin(latin, out)
            }
        }
        flushLatin(latin, out)
        return out
    }

    private fun flushLatin(sb: StringBuilder, out: MutableSet<String>) {
        if (sb.length >= 2) out.add(sb.toString())
        sb.setLength(0)
    }

    /** 重叠系数：交集 / 较小集合的大小。短记忆不会因为长度吃亏。 */
    fun overlap(a: Set<String>, b: Set<String>): Float {
        if (a.isEmpty() || b.isEmpty()) return 0f
        val small = if (a.size <= b.size) a else b
        val large = if (a.size <= b.size) b else a
        var hits = 0
        for (s in small) if (s in large) hits++
        return hits.toFloat() / small.size
    }

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) || (c in 0x3400..0x4DBF) ||
            (c in 0x3040..0x30FF) || (c in 0xAC00..0xD7AF)
    }
}

/**
 * 复读检测。模型在长对话里最明显的衰减信号就是开始复用同一批句子和动作，
 * 把命中的片段回灌进锚点让它换个说法，比单纯提高 frequency_penalty 精准得多
 * （惩罚参数是全局的，会连带伤害角色的口头禅）。
 */
object RepetitionScanner {

    private const val SHINGLE_LEN = 8

    fun findRepeatedPhrases(recentReplies: List<String>, max: Int = 4): List<String> {
        if (recentReplies.size < 2) return emptyList()
        val counts = HashMap<String, Int>()
        for (reply in recentReplies) {
            val clean = reply.replace(Regex("[\\s*_~`]+"), "")
            if (clean.length < SHINGLE_LEN) continue
            val seen = HashSet<String>()
            for (i in 0..clean.length - SHINGLE_LEN) {
                val sh = clean.substring(i, i + SHINGLE_LEN)
                if (seen.add(sh)) counts[sh] = (counts[sh] ?: 0) + 1
            }
        }
        val repeated = counts.filter { it.value >= 2 }.keys
        if (repeated.isEmpty()) return emptyList()

        // 合并互相重叠的 shingle，避免输出一堆只差一个字的片段
        val sorted = repeated.sortedByDescending { counts[it] ?: 0 }
        val picked = ArrayList<String>()
        for (s in sorted) {
            if (picked.none { it.contains(s) || s.contains(it) || sharesHalf(it, s) }) {
                picked.add(s)
                if (picked.size >= max) break
            }
        }
        return picked
    }

    private fun sharesHalf(a: String, b: String): Boolean {
        val half = SHINGLE_LEN / 2
        for (i in 0..a.length - half) {
            if (b.contains(a.substring(i, i + half))) return true
        }
        return false
    }
}
