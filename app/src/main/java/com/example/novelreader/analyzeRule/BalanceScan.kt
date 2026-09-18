package com.example.novelreader.analyzeRule

/**
 * 平衡组扫描器。
 *
 * 这是 Legado `io.legado.app.utils.scan.BalanceScan` 的等价复刻版本，
 * 用于从字符串中"拉出"一个括号平衡组（例如 jsoup 选择器里的 `[a=b]`、
 * XPath 里的 `(a|b)`、内嵌 JS 里的 `{...}`）。
 *
 * 语义与 Legado 保持一致：
 * - 从 [start] 处开始（该位置必须是 [open]），扫描到主配对深度归零为止；
 * - 返回 close 字符**之后**的下标（即 `queue[返回值 - 1] == close`）；不平衡返回 -1；
 * - `primaryOpen` / `primaryClose`（通常是 `[` `]`）参与统一计层，用于避免
 *   `{$.data[0].name}` 这类 JSON 表达式中 `]` 把 `{` 的主深度提前吃掉；
 * - 转义规则由 [Escape] 决定：ALWAYS 表示引号内外都吃转义，OUTSIDE_QUOTES
 *   表示只有引号外才吃转义（jsoup / XPath 规则里引号内转义无效）。
 */
object BalanceScan {

    enum class Escape { NONE, ALWAYS, OUTSIDE_QUOTES }

    fun chomp(
        queue: String,
        start: Int,
        open: Char,
        close: Char,
        primaryOpen: Char? = null,
        primaryClose: Char? = null,
        escape: Escape = Escape.OUTSIDE_QUOTES
    ): Int {
        if (start < 0 || start >= queue.length) return -1
        // 起点不是 open 字符时，直接按不平衡处理
        if (queue[start] != open) return -1

        var i = start
        var depth = 0
        var quote = '\u0000'

        while (i < queue.length) {
            val c = queue[i]

            // ---- 引号内 ----
            if (quote != '\u0000') {
                // ALWAYS 表示引号内也处理转义
                if (escape == Escape.ALWAYS && c == '\\') {
                    i += 2
                    continue
                }
                if (c == quote) quote = '\u0000'
                i++
                continue
            }

            // ---- 引号外 ----
            when {
                escape != Escape.NONE && c == '\\' -> {
                    i += 2
                }
                c == '\'' || c == '"' -> {
                    quote = c
                    i++
                }
                c == open -> {
                    depth++
                    i++
                }
                c == close -> {
                    depth--
                    if (depth == 0) return i + 1
                    i++
                }
                primaryOpen != null && c == primaryOpen -> {
                    depth++
                    i++
                }
                primaryClose != null && c == primaryClose -> {
                    depth--
                    if (depth == 0) return i + 1
                    i++
                }
                else -> i++
            }
        }
        return -1
    }
}
