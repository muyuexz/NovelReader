package com.example.novelreader.analyzeRule

import kotlin.jvm.JvmName

/**
 * 通用的规则切分处理。
 *
 * 复刻自 Legado `io.legado.app.model.analyzeRule.RuleAnalyzer`。
 * 核心思想（原文注释）：**不用正则、不到最后不切片、也不用中间变量存储**，
 * 只在序列中标记当前查找字段的开头结尾，到返回时才切片，高效快速准确地切割规则。
 * 这样能解决 JsonPath 自带的 `&&` / `||` 与阅读规则冲突，以及规则正则或字符串中
 * 包含 `&&`、`||`、`%%`、`@` 导致的误切。
 */
class RuleAnalyzer(data: String, code: Boolean = false) {

    private var queue: String = data   // 被处理字符串
    private var pos = 0                // 当前处理到的位置
    private var start = 0              // 当前处理字段的开始
    private var startX = 0             // 当前规则的开始

    private var rule = ArrayList<String>()  // 分割出的规则列表
    private var step: Int = 0               // 分割字符的长度
    var elementsType = ""                   // 当前分割字符串

    /** 修剪当前规则之前的 "@" 或者空白符 */
    fun trim() {
        if (pos < queue.length && (queue[pos] == '@' || queue[pos] < '!')) {
            pos++
            while (pos < queue.length && (queue[pos] == '@' || queue[pos] < '!')) pos++
            start = pos
            startX = pos
        }
    }

    /** 将 pos 重置为 0，方便复用 */
    fun reSetPos() {
        pos = 0
        startX = 0
    }

    /** 从剩余字串中拉出一个字符串，直到但不包括匹配序列。区分大小写。 */
    private fun consumeTo(seq: String): Boolean {
        start = pos
        val offset = queue.indexOf(seq, pos)
        return if (offset != -1) {
            pos = offset
            true
        } else false
    }

    /** 从剩余字串中拉出一个字符串，直到但不包括匹配序列（任一即为匹配），或字串用完。 */
    private fun consumeToAny(vararg seq: String): Boolean {
        var pos = pos
        while (pos != queue.length) {
            for (s in seq) {
                if (queue.regionMatches(pos, s, 0, s.length)) {
                    step = s.length
                    this.pos = pos
                    return true
                }
            }
            pos++
        }
        return false
    }

    /** 返回任一给定字符首次出现的位置，未找到返回 -1。 */
    private fun findToAny(vararg seq: Char): Int {
        var pos = pos
        while (pos != queue.length) {
            for (s in seq) if (queue[pos] == s) return pos
            pos++
        }
        return -1
    }

    /** 拉出一个内嵌代码平衡组（`{$.js}` / JSON 表达式），存在转义文本。 */
    private fun chompCodeBalanced(open: Char, close: Char): Boolean {
        val end = BalanceScan.chomp(
            queue, pos, open, close,
            primaryOpen = '[', primaryClose = ']',
            escape = BalanceScan.Escape.ALWAYS
        )
        return if (end < 0) false else {
            pos = end
            true
        }
    }

    /** 拉出一个规则平衡组（jsoup / XPath 中引号内转义无效）。 */
    private fun chompRuleBalanced(open: Char, close: Char): Boolean {
        val end = BalanceScan.chomp(
            queue, pos, open, close,
            escape = BalanceScan.Escape.OUTSIDE_QUOTES
        )
        return if (end < 0) false else {
            pos = end
            true
        }
    }

    /**
     * 不用正则，不到最后不切片也不用中间变量存储，只在序列中标记当前查找字段的开头结尾，
     * 到返回时才切片，高效快速准确切割规则。
     */
    tailrec fun splitRule(vararg split: String): ArrayList<String> { // 首段匹配，elementsType 为空
        if (split.size == 1) {
            elementsType = split[0]
            return if (!consumeTo(elementsType)) {
                rule += queue.substring(startX)
                rule
            } else {
                step = elementsType.length
                splitRule()
            }
        } else if (!consumeToAny(*split)) { // 未找到分隔符
            rule += queue.substring(startX)
            return rule
        }

        val end = pos // 记录分隔位置
        pos = start   // 重回开始，启动另一种查找

        do {
            val st = findToAny('[', '(') // 查找筛选器位置

            if (st == -1) {
                rule = arrayListOf(queue.substring(startX, end))
                elementsType = queue.substring(end, end + step)
                pos = end + step

                while (consumeTo(elementsType)) {
                    rule += queue.substring(start, pos)
                    pos += step
                }
                rule += queue.substring(pos)
                return rule
            }

            if (st > end) { // 分隔字串不在选择器中
                rule = arrayListOf(queue.substring(startX, end))
                elementsType = queue.substring(end, end + step)
                pos = end + step

                while (consumeTo(elementsType) && pos < st) {
                    rule += queue.substring(start, pos)
                    pos += step
                }

                return if (pos > st) {
                    startX = start
                    splitRule() // 首段已匹配，但当前段匹配未完成，调用二段匹配
                } else {
                    rule += queue.substring(pos)
                    rule
                }
            }

            pos = st
            val next = if (queue[pos] == '[') ']' else ')'
            if (!chompBalanced(queue[pos], next)) throw Error(
                queue.substring(0, start) + "后未平衡"
            )
        } while (end > pos)

        start = pos
        return splitRule(*split)
    }

    @JvmName("splitRuleNext")
    private tailrec fun splitRule(): ArrayList<String> { // 二段匹配：elementsType 非空

        val end = pos
        pos = start

        do {
            val st = findToAny('[', '(')

            if (st == -1) {
                rule += arrayOf(queue.substring(startX, end))
                pos = end + step

                while (consumeTo(elementsType)) {
                    rule += queue.substring(start, pos)
                    pos += step
                }
                rule += queue.substring(pos)
                return rule
            }

            if (st > end) {
                rule += arrayListOf(queue.substring(startX, end))
                pos = end + step

                while (consumeTo(elementsType) && pos < st) {
                    rule += queue.substring(start, pos)
                    pos += step
                }

                return if (pos > st) {
                    startX = start
                    splitRule()
                } else {
                    rule += queue.substring(pos)
                    rule
                }
            }

            pos = st
            val next = if (queue[pos] == '[') ']' else ')'
            if (!chompBalanced(queue[pos], next)) throw Error(
                queue.substring(0, start) + "后未平衡"
            )
        } while (end > pos)

        start = pos

        return if (!consumeTo(elementsType)) {
            rule += queue.substring(startX)
            rule
        } else splitRule()
    }

    /**
     * 替换内嵌规则（带前后置字符长度版），如 `{$.*}`。
     */
    fun innerRule(
        inner: String,
        startStep: Int = 1,
        endStep: Int = 1,
        fr: (String) -> String?
    ): String {
        val st = StringBuilder()

        while (consumeTo(inner)) {
            val posPre = pos
            if (chompCodeBalanced('{', '}')) {
                val frv = fr(queue.substring(posPre + startStep, pos - endStep))
                if (!frv.isNullOrEmpty()) {
                    st.append(queue.substring(startX, posPre) + frv)
                    startX = pos
                    continue
                }
            }
            pos += inner.length
        }

        return if (startX == 0) "" else st.apply {
            append(queue.substring(startX))
        }.toString()
    }

    /**
     * 替换内嵌规则（起止字符串版）。
     */
    fun innerRule(
        startStr: String,
        endStr: String,
        fr: (String) -> String?
    ): String {
        val st = StringBuilder()
        while (consumeTo(startStr)) {
            pos += startStr.length
            val posPre = pos
            if (consumeTo(endStr)) {
                val frv = fr(queue.substring(posPre, pos))
                st.append(
                    queue.substring(
                        startX,
                        posPre - startStr.length
                    ) + frv
                )
                pos += endStr.length
                startX = pos
            }
        }

        return if (startX == 0) queue else st.apply {
            append(queue.substring(startX))
        }.toString()
    }

    // JSON 或 JavaScript 时使用 chompCodeBalanced，否则 chompRuleBalanced
    val chompBalanced = if (code) ::chompCodeBalanced else ::chompRuleBalanced
}
