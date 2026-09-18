package com.example.novelreader.analyzeRule

/**
 * 目录条目「正文 / 非正文」判定 + 章节统计（第 22 批）。
 *
 * 背景：书源给出的目录里常混进两类东西——
 * 1. 纯导航项：`返回目录`、`上一章`、`下一章`、`加入书架`……这些根本不是章节；
 * 2. 作者发的非正文内容：`上架感言`、`请假条`、`公告`……它们会被当成章节一起计数。
 *
 * 详情页原先直接拿 `chapters.size` 当「章节数」，又把目录**最后一条**的标题当「最新章节」，
 * 目录尾条一旦是杂项，两个数字天然对不上。
 *
 * 本批按用户指定的「口径 A」：**章节数只统计正文章节**——
 * 标题里带章节号（`第 X 章` / `第 X 节` / `123、`）的算正文；
 * `序章 / 楔子 / 引子 / 番外 / 大结局` 这类官方特殊名也算正文；
 * 其余（导航项、无编号杂项、卷标题）不计入。
 * 极端情况下若一条正文都认不出来，回落到目录总条数，避免把标题不带编号的正常书源判死。
 */
object ChapterStats {

    /** 纯导航项：整条标题完全相等才判非正文（不做包含，避免误伤正文标题）。 */
    private val NAV_EXACT = setOf(
        "目录", "返回目录", "返回书页", "返回", "上一章", "下一章", "上一页", "下一页",
        "首页", "尾页", "末页", "加入书架", "加入书签", "投推荐票", "推荐本书", "手机阅读",
        "查看更多", "展开全部", "章节列表", "全部章节", "开始阅读", "继续阅读",
    )

    /** 导航性短语：出现在标题里即判非正文（都是不会写进正文标题的强特征）。 */
    private val NAV_PHRASES = listOf(
        "返回目录", "返回书页", "加入书架", "加入书签", "投推荐票", "推荐本书",
        "手机阅读", "查看更多", "展开全部", "章节列表", "全部章节",
    )

    /** 官方特殊章节名（无编号，但确实是正文/内容）。 */
    private val SPECIAL_NAMES = listOf(
        "序章", "序言", "楔子", "引子", "前言", "尾声", "终章", "大结局", "完结章",
        "番外", "后记", "外传", "终篇", "开篇",
    )

    private val RE_CHAPTER =
        Regex("第\\s*([0-9０-９零〇一二三四五六七八九十百千万两]{1,10})\\s*[章节回话卷集篇幕]")
    private val RE_LEAD = Regex("^\\s*([0-9０-９]{1,5})\\s*[、.．,，:：章节回话]")

    /** 是否纯导航项（不该出现在目录里，也不该计入章节数）。 */
    fun isNavTitle(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty()) return false
        if (t in NAV_EXACT) return true
        return NAV_PHRASES.any { t.contains(it) }
    }

    /** 从标题里抠章节号：`第123章`、`第 123 章`、`123、`、`第一千五百九十三章` 都能认。 */
    fun chapterNumberOf(title: String): Int? {
        RE_CHAPTER.find(title)?.let { m -> parseNumber(m.groupValues[1])?.let { return it } }
        RE_LEAD.find(title)?.let { m -> parseNumber(m.groupValues[1])?.let { return it } }
        return null
    }

    /** 该条是否算「正文章节」（口径 A）。 */
    fun isRealChapter(title: String): Boolean {
        val t = title.trim()
        if (t.isEmpty() || isNavTitle(t)) return false
        if (chapterNumberOf(t) != null) return true
        return SPECIAL_NAMES.any { t.contains(it) }
    }

    /**
     * 章节数（口径 A：只数正文章节）。
     * 一条都认不出来时回落到目录总条数——宁可给个偏大的数，也不要把正常目录显示成 0。
     */
    fun realChapterCount(chapters: List<BookChapter>): Int {
        if (chapters.isEmpty()) return 0
        val n = chapters.count { isRealChapter(it.title) }
        return if (n > 0) n else chapters.size
    }

    /**
     * 最新章节：取**章节号最大**的那一条；都没有编号则取最后一条真实章节；
     * 再兜底目录尾条。这样目录尾部混了杂项，也不会把「最新章节」显示成杂项。
     */
    fun lastRealChapter(chapters: List<BookChapter>): BookChapter? {
        if (chapters.isEmpty()) return null
        var best: BookChapter? = null
        var bestNo = Int.MIN_VALUE
        for (ch in chapters) {
            if (isNavTitle(ch.title)) continue
            val n = chapterNumberOf(ch.title) ?: continue
            if (n > bestNo) {
                bestNo = n
                best = ch
            }
        }
        if (best != null) return best
        return chapters.lastOrNull { !isNavTitle(it.title) } ?: chapters.lastOrNull()
    }

    // ── 数字解析 ──────────────────────────────────────────────────────────

    private val CN_DIGIT = mapOf(
        '零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4,
        '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9,
    )

    /** 阿拉伯数字（含全角）或中文数字 → Int。 */
    private fun parseNumber(raw: String): Int? {
        if (raw.isEmpty()) return null
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            var c = ch
            if (c.code in 0xFF10..0xFF19) c = (c.code - 0xFEE0).toChar()
            sb.append(c)
        }
        val t = sb.toString()
        if (t.isNotEmpty() && t.all { it.isDigit() }) return t.toIntOrNull()
        return cnToInt(t)
    }

    /**
     * 中文数字解析（万位以内够用）。
     * 兼容「一千五百九十三」这种规范写法，也兼容「一五九三」这种作者随手写。
     */
    private fun cnToInt(s: String): Int? {
        var total = 0
        var section = 0
        var num = 0
        var seen = false
        for (ch in s) {
            when (ch) {
                '十' -> { section += (if (num == 0) 1 else num) * 10; num = 0; seen = true }
                '百' -> { section += (if (num == 0) 1 else num) * 100; num = 0; seen = true }
                '千' -> { section += (if (num == 0) 1 else num) * 1000; num = 0; seen = true }
                '万' -> { total += (section + num) * 10000; section = 0; num = 0; seen = true }
                else -> {
                    val d = CN_DIGIT[ch] ?: return null
                    num = num * 10 + d
                    seen = true
                }
            }
        }
        if (!seen) return null
        return total + section + num
    }
}
