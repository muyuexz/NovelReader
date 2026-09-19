package com.example.novelreader.analyzeRule

import java.util.Locale

/**
 * 第 51 批 C-lite：总字数「可信度」解析。
 *
 * 背景：部分书源把「UTF-8 字节数」当「字数」上报。
 * 实例：爱下书网给《斗破苍穹》报 16870136（站点原文「字数：16870136」），
 *       而官方真值仅 533.23 万字；16870136 ÷ 5332300 ≈ 3.16 ≈ UTF-8 中文 3 字节/字，
 *       实锤该源把字节数当字数。
 *
 * 策略（每一档都有退路，正常源零额外成本）：
 *   1. 正常值（字数 ÷ 章节数 < [BYTE_RATIO_HINT]）→ 原样显示，不加任何角标。
 *   2. 疑似字节数（字数 ÷ 章节数 ≥ [BYTE_RATIO_HINT]）→
 *      ① 兄弟源（altSources）里挑一个量级正常的字数 → 「约 X万」+ 角标「多源参考」；
 *      ② 兄弟源也没有 → ÷3（UTF-8 中文 3 字节/字）折算 → 「约 X万」+ 角标「量纲校正」。
 *   3. 带单位（万/字/W）本就无法判定量纲 → 原样 + 角标「源站自报」。
 *
 * 注意：判据是启发式而非神谕，极端单章字数偏高的正常书也可能落在嫌疑区，
 *       此时退路是「约」字打头（明示估算），不会伪造精确值。
 */
object WordCountResolver {

    /** 单章字数超过该值即视为「疑似把字节数当字数」。 */
    const val BYTE_RATIO_HINT: Long = 8_000L

    /** 展示结果：[text] 是最终展示文本，[badge] 为角标（null 表示不显示）。 */
    data class Display(val text: String, val badge: String? = null)

    fun resolve(
        raw: String?,
        chapterCount: Int,
        altSources: List<Book> = emptyList(),
    ): Display {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return Display("未知")

        // 已带单位：人为量级，无法也不必换算，原样保留并标注「源站自报」。
        if (text.any { it == '万' || it == '字' || it == 'W' || it == 'w' }) {
            return Display(text, "源站自报")
        }

        val n = parseCount(text) ?: return Display(text)
        if (n <= 0L) return Display(text)

        // 章节数未知 → 无从判定量纲，原样显示。
        if (chapterCount <= 0) return Display(format(n))

        // 量级正常 → 原样显示（绝大多数源走这条，零额外成本）。
        if (n / chapterCount < BYTE_RATIO_HINT) return Display(format(n))

        // —— 疑似字节数 —— 先看兄弟源有没有正常值。
        val ref = altSources.asSequence()
            .mapNotNull { it.wordCount }
            .mapNotNull { parseCount(it) }
            .filter { it > 0L }
            .firstOrNull { (it / chapterCount) in 1L until BYTE_RATIO_HINT }
        if (ref != null) return Display("约" + format(ref), "多源参考")

        // 退路：按 UTF-8 中文 3 字节/字折算。
        return Display("约" + format(n / 3), "量纲校正")
    }

    /** 解析「16870136」「623万」「533.2W」这类文本为字数量（Long）。 */
    private fun parseCount(s: String?): Long? {
        val t = s?.trim().orEmpty()
        if (t.isEmpty()) return null
        val digits = t.filter { it.isDigit() }.toLongOrNull() ?: return null
        return when {
            t.contains('万') -> digits * 10_000L
            t.contains('W') || t.contains('w') -> digits * 10_000L
            else -> digits
        }
    }

    /** 「5485000」→「548.5万」；不足 1 万 →「N字」。 */
    private fun format(n: Long): String {
        if (n < 10_000L) return "${n}字"
        val w = n / 10_000.0
        return if (w >= 100.0) String.format(Locale.CHINA, "%.0f万", w)
        else String.format(Locale.CHINA, "%.1f万", w)
    }
}
