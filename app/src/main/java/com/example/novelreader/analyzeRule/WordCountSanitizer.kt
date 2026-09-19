package com.example.novelreader.analyzeRule

/**
 * 总字数清洗器 + 整页兜底提取器。
 *
 * 第48批刀B：建立「保守拒绝」的清洗口径 —— 位置型规则（`dd span.3@text` /
 * `tag.td.3@text` 一类）页面结构一变就抓到日期、更新时间、章节 ID 等与字数无关的数字串，
 * 旧逻辑原样喂给 UI，而 `formatWordCount` 又只做 `filter { isDigit }` 兜底，于是
 * "2019-05-01" 会被抠成 20190501，显示为「2019.1万」。
 *
 * 第49批刀A（本次）修两处 + 新增一处：
 * 1. **修 UNIT 漏洞**：旧版把 `w/W` 直接当量词，于是「含一个字母 w 的任意文本」
 *    （如被位置型规则误抓的 `www.cxzz958.com`）会走 hasUnit 分支、并从 `else -> text`
 *    原样返回，最终在 UI 上显示出一坨域名 —— 这就是「统计的字数太离谱」的真凶之一。
 *    现在 `w` 只在**紧贴数字**时才算量词（`1.2W` / `1.2 w`），单词 `words/word` 仍认。
 *    且 hasUnit 分支不再「原样返回含杂质文本」，一律归一成「数字 + 量词」。
 * 2. **修多段误放行**：`字数：2019-04-02` 这类文本段数 ≥2 且含「字」，旧逻辑会返回
 *    「2019字」。现在带量词分支一律要求「只有一个数字段」。
 * 3. **新增 [extractFromPage]**：书源里 74.4% 的源根本没有 `wordCount` 规则（结构性
 *    「未知」），而页面正文里往往明明白白写着「字数：6108292」。此方法只认
 *    **锚词附近的数字**（字数 / 万字 / wordCount / words），从整页文本或 JSON 里兜底捞取，
 *    捞到的结果仍必须通过 [sanitize] 复检才放行，杜绝把章节数、时间戳误认成字数。
 */
object WordCountSanitizer {

    /** 英文量词：`1.2W`（W 必须紧贴数字）或 `words/word`。 */
    private val UNIT_W = Regex("\\d\\s*[wW]\\b")
    private val UNIT_WORDS = Regex("\\bwords?\\b", RegexOption.IGNORE_CASE)

    /** 数字段（支持小数）。 */
    private val NUM = Regex("\\d+(?:\\.\\d+)?")

    /** 千分位逗号 / 全角逗号：1,234,567 → 1234567，避免被误拆成多个数字段。 */
    private val GROUP_SEP = Regex("(?<=\\d)[,，](?=\\d)")

    /** 全网文单本合理区间的保守上下限（字）。 */
    private const val MIN_WORDS = 1_000L
    private const val MAX_WORDS = 300_000_000L

    /** URL / 域名痕迹：被位置型规则误抓时常带这些，含 `w` 会骗过旧 UNIT。 */
    private val URL_HINT = Regex("(https?://|www\\.|\\.(com|net|cn|org|cc|xyz|top|vip|la)\\b)", RegexOption.IGNORE_CASE)

    private fun hasUnit(text: String): Boolean =
        text.contains('万') || text.contains('字') ||
            UNIT_W.containsMatchIn(text) || UNIT_WORDS.containsMatchIn(text)

    /** 归一化后的字数串；无法判定为字数时返回 null。 */
    fun sanitize(raw: String?): String? {
        val text = raw?.trim()?.replace('\u3000', ' ')?.trim()
        if (text.isNullOrEmpty()) return null
        // 第49批：URL / 域名痕迹一律判脏，不给「原样返回」留缝。
        if (URL_HINT.containsMatchIn(text)) return null

        val probe = text.replace(GROUP_SEP, "")
        val segs = NUM.findAll(probe).map { it.value }.toList()
        if (segs.isEmpty()) return null

        val unit = hasUnit(text)

        // 多数字段（2019-05-01 / 12:34 / 100-200）且无量词：正常字数不会长这样 → 判脏。
        if (segs.size >= 2 && !unit) return null

        if (unit) {
            // 带量词却夹着 2 段以上数字（如「2019-04-02 字数」）→ 判脏，不再返回「2019字」。
            if (segs.size >= 2) return null
            val num = segs.first()
            return when {
                text.contains('万') -> "${num}万"
                text.contains('字') -> "${num}字"
                UNIT_W.containsMatchIn(text) -> "${num}W"
                else -> "${num}words"
            }
        }

        val n = segs.first().toDoubleOrNull() ?: return null
        return if (n >= MIN_WORDS.toDouble() && n <= MAX_WORDS.toDouble()) text else null
    }

    // ── 第49批刀A：整页兜底提取 ────────────────────────────────────────────

    /** 锚定模式：只在「字数 / 万字 / wordCount / words」这类锚词附近取数字。 */
    private val ANCHOR_PATTERNS = listOf(
        // 字数：6108292 / 字数: 610.8万 / 总字数 6108292
        Regex("""字\s*数\s*[:：]?\s*([0-9][0-9,，]*(?:\.[0-9]+)?\s*万?)"""),
        // 610.8万字 / 610万字
        Regex("""([0-9][0-9,，]*(?:\.[0-9]+)?)\s*万字"""),
        // 轻量 JSON 兜底：不含 JSONPath 依赖，避免脆解析
        Regex(""""(?:wordCount|wordNum|charCount|totalWords|totalCharCount|words?)"\s*:\s*"?([0-9](?:[0-9,，]*[0-9])?(?:\.[0-9]+)?)"?""", RegexOption.IGNORE_CASE),
        // 英文页：word count: 6108292
        Regex("""word\s*count\s*[:：]?\s*([0-9][0-9,，]*(?:\.[0-9]+)?)""", RegexOption.IGNORE_CASE),
    )

    private val HTML_TAG = Regex("<[^>]+>")

    /**
     * 从整页响应体里兜底捞字数。
     *
     * @param body 原始响应体（HTML / JSON / 纯文本均可）。
     * @param isJson body 是否为 JSON（决定是否先按 JSON 文本原样扫）。
     * @return 已通过 [sanitize] 复检的字数串；捞不到返回 null。
     */
    fun extractFromPage(body: String?, isJson: Boolean): String? {
        val text = body?.takeIf { it.isNotBlank() } ?: return null
        // HTML 先剥标签，避免锚词与数字被标签切开；JSON 原样扫。
        val hay = if (isJson) text else text.replace(HTML_TAG, " ")
        for (re in ANCHOR_PATTERNS) {
            val m = re.find(hay) ?: continue
            val raw = m.groupValues.getOrNull(1)?.trim().orEmpty()
            if (raw.isEmpty()) continue
            sanitize(raw)?.let { return it }
        }
        return null
    }
}
