package com.example.novelreader.analyzeRule

import java.util.Locale
import kotlin.math.abs

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

    /** 单章字数超过该值即视为「疑似把字节数当字数」（偏大方向）。 */
    const val BYTE_RATIO_HINT: Long = 8_000L

    /**
     * 单章字数低于该值即视为「疑似抓错字段」（偏小方向：日期 / ID / 章节数 / 被截断的字段被当成字数）。
     *
     * 第 53 批修正：原值 200 过松。中文网文正常量纲约 2000~4000 字/章
     * （《斗破苍穹》官方 533.23 万字 ÷ 1647 章 ≈ 3238 字/章），
     * 而实测「爱笔趣阁#4」给 1618 章的书报 54.1 万字（≈334 字/章）却落在 200 之上，
     * 被误判成「量级正常」，绕过全部校正逻辑裸奔。改取 1000 字/章。
     */
    const val MIN_PER_CHAPTER: Long = 1_000L

    /** 章节数少于该值时不做「偏小」判定：短篇 / 诗集 / 随笔的章节天然短，避免误伤。 */
    const val MIN_CHAPTERS_FOR_SMALL: Int = 20

    /** 本地估算值与源值的相对偏差超过该比例时，改用估算值。 */
    private const val ESTIMATE_DEVIATION = 0.35

    /** 展示结果：[text] 是最终展示文本，[badge] 为角标（null 表示不显示）。 */
    data class Display(val text: String, val badge: String? = null)

    /**
     * 第 52 批 L1 + L2（第 53 批收紧判据）：
     * - L1（双向量纲）：单章字数落在 [MIN_PER_CHAPTER, BYTE_RATIO_HINT] 之外即判异常，
     *   同时覆盖「偏大＝字节数」与「偏小＝抓错字段」两个方向；判据统一走 [isAbnormalCount]，
     *   与 [needsEstimate] 共用同一道闸门，杜绝两处阈值分歧导致脏值裸奔。
     * - L2（本地估算）：[estimate] 由 WordCountEstimator 抽样正文现算，优先级最高。
     * 每一档都有退路，正常源零额外成本。
     */
    fun resolve(
        raw: String?,
        chapterCount: Int,
        altSources: List<Book> = emptyList(),
        estimate: Long? = null,
    ): Display {
        val est = estimate?.takeIf { it > 0L }
        val text = raw?.trim().orEmpty()
        val n = parseCount(text)

        // 源没给字数（或给的根本不是数字）：有本地估算就信估算。
        if (n == null || n <= 0L) {
            if (est != null) return Display("约" + format(est), "本地估算")
            return if (text.isEmpty()) Display("未知") else Display(text, "源站自报")
        }

        val hasUnit = text.any { it == '万' || it == '字' || it == 'W' || it == 'w' }

        // 章节数未知 → 无从判定量纲；有估算则信估算。
        if (chapterCount <= 0) {
            if (est != null) return Display("约" + format(est), "本地估算")
            return Display(format(n), if (hasUnit) "源站自报" else null)
        }

        val per = n / chapterCount
        val abnormal = isAbnormalCount(n, chapterCount)

        // —— 量级正常 —— 唯一例外：本地估算与源值严重不符时信估算。
        if (!abnormal) {
            if (est != null && deviates(n, est)) return Display("约" + format(est), "本地估算")
            return Display(format(n), if (hasUnit) "源站自报" else null)
        }

        // —— 量级异常 —— 本地估算优先级最高。
        if (est != null) return Display("约" + format(est), "本地估算")

        // 其次：兄弟源里的量级正常值。
        val ref = altSources.asSequence()
            .mapNotNull { it.wordCount }
            .mapNotNull { parseCount(it) }
            .filter { it > 0L }
            .firstOrNull { (it / chapterCount) in MIN_PER_CHAPTER until BYTE_RATIO_HINT }
        if (ref != null) return Display("约" + format(ref), "多源参考")

        // 偏大（字节数嫌疑）→ 按 UTF-8 中文 3 字节/字折算。
        if (per > BYTE_RATIO_HINT) return Display("约" + format(n / 3), "量纲校正")

        // 偏小且无任何参照 → 原样显示但标注「存疑」，不伪造数字。
        return Display(format(n), "存疑")
    }

    /** 这本书是否需要跑一次本地估算（L2）：源值缺失、或量级落在异常区。 */
    fun needsEstimate(raw: String?, chapterCount: Int): Boolean {
        val n = parseCount(raw?.trim().orEmpty()) ?: return true
        if (n <= 0L) return true
        return isAbnormalCount(n, chapterCount)
    }

    /**
     * 唯一的「量级异常」闸门 —— 显示层（[resolve]）与估算触发层（[needsEstimate]）必须共用它。
     *
     * 第 53 批修正：此前两处各写一遍判据，阈值一旦分歧就会出现
     * 「显示层判正常 → 不挂角标；估算层也判正常 → 不跑估算」的裸奔漏洞。
     *
     * @param n 已解析出的字数量。
     * @param chapterCount 章节数；<= 0 表示未知，无从判定量纲，返回 false。
     */
    fun isAbnormalCount(n: Long, chapterCount: Int): Boolean {
        if (chapterCount <= 0) return false
        val per = n / chapterCount
        if (per > BYTE_RATIO_HINT) return true
        if (chapterCount < MIN_CHAPTERS_FOR_SMALL) return false
        return per < MIN_PER_CHAPTER
    }

    /** 源值与估算值是否「严重不符」（相对偏差超阈值）。 */
    private fun deviates(source: Long, estimate: Long): Boolean {
        val hi = maxOf(source, estimate)
        if (hi <= 0L) return false
        return abs(source - estimate).toDouble() / hi.toDouble() > ESTIMATE_DEVIATION
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
