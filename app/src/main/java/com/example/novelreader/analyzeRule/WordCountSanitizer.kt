package com.example.novelreader.analyzeRule

/**
 * 第48批刀B：总字数「脏值」清洗器。
 *
 * 背景：书源里 wordCount 规则大量是「位置型」写法（如 `dd span.3@text`、
 * `class.book_other.0@tag.span.3@text`、`tag.td.3@text`），页面结构一变就会抓到
 * 日期、更新时间、章节 ID 等与字数无关的数字串。旧逻辑原样喂给 UI，而
 * `formatWordCount` 又只做 `filter { isDigit }` 兜底，于是 "2019-05-01"
 * 会被抠成 20190501，显示为「2019.1万」——这就是「错的总字数」的直接来源。
 *
 * 本清洗器只做「保守拒绝」，不做任何数值推算：
 * - null / 空白 → null（UI 回落「未知」）
 * - 多数字段且无量词（日期 / 区间 / 时间）→ 判脏，返回 null
 * - 带量词（万 / 字 / W / words）→ 归一成「数字 + 单位」
 * - 单个数字段 → 范围校验（1 千 ~ 3 亿）后放行
 *
 * 宁可显示「未知」，也不展示一个看着像字数、其实是日期的错值。
 */
object WordCountSanitizer {

    /** 数量级量词：中英文都认。 */
    private val UNIT = Regex("[万字wW]|words?", RegexOption.IGNORE_CASE)

    /** 数字段（支持小数）。 */
    private val NUM = Regex("\\d+(?:\\.\\d+)?")

    /** 千分位逗号：1,234,567 → 1234567，避免被误拆成多个数字段。 */
    private val GROUP_SEP = Regex("(?<=\\d),(?=\\d)")

    /** 全网文单本合理区间的保守上下限（字）。 */
    private const val MIN_WORDS = 1_000L
    private const val MAX_WORDS = 300_000_000L

    /** 归一化后的字数串；无法判定为字数时返回 null。 */
    fun sanitize(raw: String?): String? {
        val text = raw?.trim()?.replace('\u3000', ' ')?.trim()
        if (text.isNullOrEmpty()) return null

        val probe = text.replace(GROUP_SEP, "")
        val segs = NUM.findAll(probe).map { it.value }.toList()
        if (segs.isEmpty()) return null

        val hasUnit = UNIT.containsMatchIn(text)

        // 多数字段（2019-05-01 / 12:34 / 100-200）且无量词：正常字数不会长这样 → 判脏。
        if (segs.size >= 2 && !hasUnit) return null

        if (hasUnit) {
            // 带量词还夹着 3 段以上数字（如「2019-05-01 更新 12 万字」）→ 判脏。
            if (segs.size >= 3) return null
            val num = segs.first()
            return when {
                text.contains('万') -> "${num}万"
                text.contains('字') -> "${num}字"
                else -> text // W / words：英文站口径，原样保留。
            }
        }

        val n = segs.first().toDoubleOrNull() ?: return null
        return if (n >= MIN_WORDS.toDouble() && n <= MAX_WORDS.toDouble()) text else null
    }
}
