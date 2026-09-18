package com.example.novelreader.analyzeRule

/**
 * 书源执行引擎：把 [BookSource] 里的规则按 Legado 的四阶段跑起来。
 *
 * 对齐 Legado 的模型划分：
 * - 搜索 → `SearchModel`（searchUrl + ruleSearch）
 * - 详情 → `BookInfoModel`（ruleBookInfo）
 * - 目录 → `TocModel`（ruleToc）
 * - 正文 → `ContentModel`（ruleContent，含 `nextContentUrl` 分页）
 *
 * 每个阶段都是「AnalyzeUrlCore 发请求 → AnalyzeRuleCore 解规则」那套流水线，
 * 只是喂给引擎的书源规则段和 ruleData / chapter 不同。
 *
 * 所有方法对失败都取「空结果」语义（网络异常、规则缺字段、页面改版），
 * 单条书源炸掉不会影响批量搜索里的其它书源。
 */
object BookSourceEngine {

    /** 正文分页上限，防 `nextContentUrl` 自环或书源规则写错导致死循环。 */
    private const val MAX_CONTENT_PAGES = 20

    // ── 分阶段请求超时（毫秒）──────────────────────────────────────────────
    // 网络层全局默认是 30s，对「点进去看目录」这种交互来说是灾难级体验：
    // 一条死源就能让用户白等半分钟。这里按阶段收紧，坏源快速失败、快速跳到下一源。

    /** 搜索：批量场景，外层还有 withTimeout 兜底，单源给 10s。 */
    private const val TIMEOUT_SEARCH = 10_000L

    /** 详情页：只补书名/作者/简介，8s 足够。 */
    private const val TIMEOUT_DETAIL = 8_000L

    /** 目录页：交互主路径，最敏感，8s 拿不到就放弃。 */
    private const val TIMEOUT_TOC = 8_000L

    /** 正文页：内容大，且可能带分页，放宽到 15s。 */
    private const val TIMEOUT_CONTENT = 15_000L

    // ── 搜索 ──────────────────────────────────────────────────────────────

    /**
     * 搜索。`{{key}}` 由 [AnalyzeUrlCore.variables] 绑进 JS 作用域求值，
     * 与 Legado 中 `key` 作为 JS 变量的行为一致。
     */
    fun search(source: BookSource, key: String): List<Book> {
        // 对齐 Legado：searchUrl 为空表示该源不支持搜索；为 `-` 表示搜索页即书源首页。
        val rawSearch = source.searchUrl?.trim()
        val searchUrl = when {
            rawSearch.isNullOrEmpty() -> return emptyList()
            rawSearch == "-" -> source.bookSourceUrl.takeIf { it.isNotBlank() } ?: return emptyList()
            else -> rawSearch
        }
        val ruleSearch = source.ruleSearch ?: return emptyList()
        val bookListRule = ruleSearch.bookList?.takeIf { it.isNotBlank() } ?: return emptyList()

        return runCatching {
            val analyzeUrl = AnalyzeUrlCore(
                rawUrl = searchUrl,
                source = source,
                variables = mapOf("key" to key),
                readTimeout = TIMEOUT_SEARCH,
                callTimeout = TIMEOUT_SEARCH + 2_000,
            )
            val resp = analyzeUrl.getStrResponse()
            val body = resp.body ?: return emptyList()

            val rule = AnalyzeRuleCore(source = source)
            rule.variables = mapOf("key" to key)
            rule.setContent(body, resp.url)

            val elements = rule.getElements(bookListRule)
            val books = ArrayList<Book>(elements.size)
            for (element in elements) {
                rule.setContent(element)
                val book = Book(origin = source.bookSourceUrl, originName = source.bookSourceName)
                book.source = source
                book.name = rule.getString(ruleSearch.name)
                book.author = rule.getString(ruleSearch.author)
                book.kind = rule.getString(ruleSearch.kind)
                book.intro = rule.getString(ruleSearch.intro)
                book.coverUrl = rule.getString(ruleSearch.coverUrl, null, true)
                book.wordCount = rule.getString(ruleSearch.wordCount)
                book.lastChapter = rule.getString(ruleSearch.lastChapter)
                book.bookUrl = rule.getString(ruleSearch.bookUrl, null, true)
                if (book.name.isNotBlank() && book.bookUrl.isNotBlank()) books += book
            }
            // 单源内先按匹配度排序；跨源统一排序在 SourceRepository 侧完成。
            books.sortWith(
                compareBy(
                    { relevanceRank(it.name, key) },
                    { it.name.length },
                    { it.name },
                )
            )
            books
        }.getOrDefault(emptyList())
    }

    // ── 详情 ──────────────────────────────────────────────────────────────

    /** 详情页：补齐书名/作者/简介/封面，并解析出目录地址（tocUrl）。 */
    fun getBookInfo(source: BookSource, book: Book) {
        val rbi = source.ruleBookInfo ?: return
        runCatching {
            val analyzeUrl = AnalyzeUrlCore(
                rawUrl = book.bookUrl,
                baseUrl = source.bookSourceUrl,
                source = source,
                ruleData = book,
                readTimeout = TIMEOUT_DETAIL,
                callTimeout = TIMEOUT_DETAIL + 2_000,
            )
            val resp = analyzeUrl.getStrResponse()
            val body = resp.body ?: return

            val rule = AnalyzeRuleCore(ruleData = book, source = source).setContent(body, resp.url)
            rbi.init?.takeIf { it.isNotBlank() }?.let { rule.getString(it) }

            rule.getString(rbi.name).takeIf { it.isNotBlank() }?.let { book.name = it }
            rule.getString(rbi.author).takeIf { it.isNotBlank() }?.let { book.author = it }
            rule.getString(rbi.kind).takeIf { it.isNotBlank() }?.let { book.kind = it }
            rule.getString(rbi.intro).takeIf { it.isNotBlank() }?.let { book.intro = it }
            rule.getString(rbi.wordCount).takeIf { it.isNotBlank() }?.let { book.wordCount = it }
            rule.getString(rbi.lastChapter).takeIf { it.isNotBlank() }?.let { book.lastChapter = it }
            rule.getString(rbi.status).takeIf { it.isNotBlank() }?.let { book.status = it }
            rule.getString(rbi.coverUrl, null, true).takeIf { it.isNotBlank() }?.let { book.coverUrl = it }

            val tocUrl = rule.getString(rbi.tocUrl, null, true)
            book.tocUrl = tocUrl.ifBlank { book.bookUrl }
        }
    }

    // ── 目录 ──────────────────────────────────────────────────────────────

    /** 目录页：解析章节列表，章节地址绝对化。 */
    fun getChapterList(source: BookSource, book: Book): List<BookChapter> {
        val rt = source.ruleToc ?: return emptyList()
        val chapterListRule = rt.chapterList?.takeIf { it.isNotBlank() } ?: return emptyList()
        val chapterNameRule = rt.chapterName?.takeIf { it.isNotBlank() } ?: return emptyList()

        return runCatching {
            val tocUrl = book.tocUrl.ifBlank { book.bookUrl }
            val analyzeUrl = AnalyzeUrlCore(
                rawUrl = tocUrl,
                baseUrl = book.bookUrl,
                source = source,
                ruleData = book,
                readTimeout = TIMEOUT_TOC,
                callTimeout = TIMEOUT_TOC + 2_000,
            )
            val resp = analyzeUrl.getStrResponse()
            val body = resp.body ?: return emptyList()

            val rule = AnalyzeRuleCore(ruleData = book, source = source).setContent(body, resp.url)
            rt.preUpdateJs?.takeIf { it.isNotBlank() }?.let { rule.evalJS(it) }

            val elements = rule.getElements(chapterListRule)
            val chapters = ArrayList<BookChapter>(elements.size)
            for ((i, element) in elements.withIndex()) {
                rule.setContent(element)
                val chapter = BookChapter(bookUrl = book.bookUrl, index = i)
                chapter.title = rule.getString(chapterNameRule).trim()
                chapter.url = rule.getString(rt.chapterUrl, null, true)
                if (chapter.title.isBlank() || chapter.url.isBlank()) continue
                rule.getString(rt.isVip).takeIf { it.isNotBlank() }?.let {
                    chapter.isVip = it != "0" && it != "false"
                }
                chapter.updateTime = parseUpdateTime(rule.getString(rt.updateTime))
                chapters += chapter
            }
            chapters
        }.getOrDefault(emptyList())
    }

    // ── 正文 ──────────────────────────────────────────────────────────────

    /**
     * 正文页：解析正文，并按 `nextContentUrl` 向后拼接分页内容。
     * 分页链最大 [MAX_CONTENT_PAGES] 页，遇到自环或空地址即停。
     */
    fun getContent(source: BookSource, book: Book, chapter: BookChapter): String {
        val rc = source.ruleContent ?: return ""
        val contentRule = rc.content?.takeIf { it.isNotBlank() } ?: return ""

        return runCatching {
            val sb = StringBuilder()
            var currentUrl = chapter.url
            var page = 0
            while (currentUrl.isNotBlank() && page++ < MAX_CONTENT_PAGES) {
                val analyzeUrl = AnalyzeUrlCore(
                    rawUrl = currentUrl,
                    baseUrl = book.bookUrl,
                    source = source,
                    ruleData = book,
                    chapter = chapter,
                    readTimeout = TIMEOUT_CONTENT,
                    callTimeout = TIMEOUT_CONTENT + 3_000,
                )
                val resp = analyzeUrl.getStrResponse(rc.webJs)
                val body = resp.body ?: break

                val rule = AnalyzeRuleCore(ruleData = book, source = source)
                rule.chapter = chapter
                rule.setContent(body, resp.url)

                val text = rule.getString(contentRule)
                if (text.isNotBlank()) {
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }

                val next = rule.getString(rc.nextContentUrl, null, true)
                if (next.isBlank() || next == currentUrl) break
                currentUrl = next
            }
            cleanContent(sb.toString())
        }.getOrDefault("")
    }

    // ── 文本净化 ──────────────────────────────────────────────────────────

    private val RE_BR = Regex("(?i)<br\\s*/?>")
    private val RE_BLOCK_END = Regex("(?i)</(p|div|h[1-6]|li|tr|section|article)>")
    private val RE_TAG = Regex("<[^>]+>")
    private val RE_NUM_ENTITY = Regex("&#(\\d+);")
    private val RE_INLINE_WS = Regex("[ \\t\\u3000]{2,}")
    private val RE_BLANK_LINES = Regex("\\n{3,}")

    /**
     * 常见 HTML 实体表。key 用 `"&" + "xxx;"` 拼接，
     * 避免源码里的实体串被工具链/编辑器二次转义。
     */
    private val ENTITIES = mapOf(
        ("&" + "nbsp;") to "\u3000",
        ("&" + "amp;") to "&",
        ("&" + "lt;") to "<",
        ("&" + "gt;") to ">",
        ("&" + "quot;") to ("" + '"'),
        ("&" + "apos;") to "'",
        ("&" + "ldquo;") to "\u201c",
        ("&" + "rdquo;") to "\u201d",
        ("&" + "lsquo;") to "\u2018",
        ("&" + "rsquo;") to "\u2019",
        ("&" + "hellip;") to "\u2026",
        ("&" + "mdash;") to "\u2014",
        ("&" + "ndash;") to "\u2013",
        ("&" + "middot;") to "\u00b7",
    )

    /**
     * 正文净化：把 [AnalyzeRuleCore.getString] 可能残留的 HTML 标记、实体、
     * 多余空白压回可读纯文本。
     *
     * 只做结构级清洗（标签 / 实体 / 空白），**不做广告词过滤**——
     * 误删正文一句比留一条广告更糟，广告交给书源规则和用户自定义替换处理。
     */
    fun cleanContent(raw: String): String {
        if (raw.isBlank()) return ""
        var s = raw
        s = RE_BR.replace(s, "\n")
        s = RE_BLOCK_END.replace(s, "\n")
        s = RE_TAG.replace(s, "")
        for ((k, v) in ENTITIES) s = s.replace(k, v)
        s = RE_NUM_ENTITY.replace(s) { m ->
            m.groupValues[1].toIntOrNull()?.let { String(Character.toChars(it)) } ?: ""
        }
        s = s.replace('\u00A0', '\u3000')
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        s = RE_INLINE_WS.replace(s, "\u3000")
        s = RE_BLANK_LINES.replace(s, "\n\n")
        s = s.lines().joinToString("\n") { it.trimEnd() }
        return s.trim()
    }

    /**
     * 目录项「最近更新」时间解析：把书源规则抓到的字符串转成毫秒时间戳。
     *
     * 真实书源的更新时间格式五花八门（`2024-05-01`、`2024-05-01 12:30`、
     * `05-01 12:30`、纯秒/毫秒时间戳），这里按「数字时间戳 → 常见日期格式」顺序试，
     * 全部失败返回 null（UI 侧回落「未知」），绝不臆测一个假时间。
     */
    private val UPDATE_TIME_PATTERNS = listOf(
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd",
        "yyyy/MM/dd HH:mm:ss",
        "yyyy/MM/dd HH:mm",
        "yyyy/MM/dd",
        "MM-dd HH:mm",
        "MM-dd",
    )

    private fun parseUpdateTime(raw: String?): Long? {
        val text = raw?.trim().orEmpty()
        if (text.isEmpty()) return null

        // 1) 纯数字：>= 1e12 视为毫秒，否则视为秒。
        text.toLongOrNull()?.let { n ->
            return when {
                n >= 1_000_000_000_000L -> n
                n > 0L -> n * 1000L
                else -> null
            }
        }

        // 2) 抠掉「更新：」之类前缀后按常见格式试。
        val stripped = text.replace(Regex("^[^0-9]*"), "")
        for (p in UPDATE_TIME_PATTERNS) {
            val fmt = java.text.SimpleDateFormat(p, java.util.Locale.CHINA).apply { isLenient = true }
            runCatching { fmt.parse(stripped) }.getOrNull()?.let { return it.time }
        }
        return null
    }

    /** 书名与关键词的匹配档次：0=完全相等，1=前缀，2=包含，3=无关。 */
    private fun relevanceRank(name: String, key: String): Int {
        val n = name.trim()
        val k = key.trim()
        return when {
            n.equals(k, ignoreCase = true) -> 0
            n.startsWith(k, ignoreCase = true) -> 1
            n.contains(k, ignoreCase = true) -> 2
            else -> 3
        }
    }
}
