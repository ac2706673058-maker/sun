package com.murmur.app.engine

/**
 * 轻量 token 估算。不依赖具体 tokenizer——真正的分词在服务端，
 * 客户端只需要一个"够准、够快、宁可偏保守"的估算来做预算分配。
 *
 * 经验值（对 cl100k / o200k / Qwen / Llama3 系列都在可接受误差内）：
 *  - 中日韩表意文字：约 0.75 token/字
 *  - 拉丁字母：约 1 token / 3.6 字符
 *  - 数字与标点：约 1 token / 2.5 字符
 * 再给每条消息加上 role 与分隔符的固定开销。
 */
object TokenEstimator {

    const val PER_MESSAGE_OVERHEAD = 4

    fun estimate(text: String): Int {
        if (text.isEmpty()) return 0
        var cjk = 0
        var latin = 0
        var other = 0
        for (ch in text) {
            when {
                isCjk(ch) -> cjk++
                ch.isLetter() -> latin++
                ch.isWhitespace() -> other++
                else -> other++
            }
        }
        val est = cjk * 0.75 + latin / 3.6 + other / 2.5
        return kotlin.math.ceil(est).toInt().coerceAtLeast(1)
    }

    fun estimateMessage(role: String, content: String): Int =
        estimate(content) + estimate(role) + PER_MESSAGE_OVERHEAD

    private fun isCjk(ch: Char): Boolean {
        val c = ch.code
        return (c in 0x4E00..0x9FFF) ||   // 中日韩统一表意文字
            (c in 0x3400..0x4DBF) ||      // 扩展 A
            (c in 0x3040..0x30FF) ||      // 平假名 / 片假名
            (c in 0xAC00..0xD7AF) ||      // 谚文
            (c in 0xF900..0xFAFF) ||      // 兼容表意文字
            (c in 0x3000..0x303F) ||      // 中日韩标点
            (c in 0xFF00..0xFFEF)         // 全角字符
    }
}
