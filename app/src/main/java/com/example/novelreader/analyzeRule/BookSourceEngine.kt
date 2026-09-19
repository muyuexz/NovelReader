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

    /**
     * 第31批：分页链「串章」识别。
     * 中文章节站里 `text.下一@href` 之类规则会同时命中「下一页」与「下一章」，
     * 导致把后续章节正文一段段拼进当前章（页数暴涨）。第 2 页起，
     * 若新拉到的正文以「第X章」开头即判定串章，停止拼接。
     */
    private val RE_CHAPTER_HEAD =
        Regex("^第[0-9０-９零一二三四五六七八九十百千万两]{1,10}[章節节]")

    // ── 分阶段请求超时（毫秒）──────────────────────────────────────────────
    // 网络层全局默认是 30s，对「点进去看目录」这种交互来说是灾难级体验：
    // 一条死源就能让用户白等半分钟。这里按阶段收紧，坏源快速失败、快速跳到下一源。

    /** 搜索：批量场景，外层还有 withTimeout 兜底，单源给 10s。 */
    private const val TIMEOUT_SEARCH = 10_000L

    /** 详情页：只补书名/作者/简介，8s 足够。 */
    private const val TIMEOUT_DETAIL = 8_000L

    /** 目录页：交互主路径，最敏感，8s 拿不到就放弃。 */
    private const val TIMEOUT_TOC = 8_000L

    /** 第22批：目录分页上限，防 nextTocUrl 自环或书源规则写错导致死循环。 */
    private const val MAX_TOC_PAGES = 60

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
                // 第34批：getString(isUrl=true) 在规则取空时会回落成 baseUrl（=搜索页 URL），
                // 于是「没有封面规则」被写成「封面 = 搜索页地址」，Coil 加载必然失败，列表全是失败态。
                // 这里把空串与「等于本页 URL」的伪封面一律清掉，交给详情兜底回填。
                book.coverUrl = rule.getString(ruleSearch.coverUrl, null, true)
                    .takeIf { it.isNotBlank() && it != resp.url && it != searchUrl }
                // 第48批刀B：搜索页字数多为位置型规则，抓歪即成日期/ID；脏值一律清成 null。
                book.wordCount = WordCountSanitizer.sanitize(rule.getString(ruleSearch.wordCount))
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
            // 第48批刀B（详情路径）：同样清洗。位置型规则（dd span.3@text / tag.td.3@text 一类）
            // 页面结构一变就抓到日期/ID 数字串，旧逻辑直接落库 → 详情页把 "2019-05-01"
            // 抠成 20190501 显示成「2019.1万」。清洗后为 null 时保留搜索阶段已校验过的值。
            WordCountSanitizer.sanitize(rule.getString(rbi.wordCount))?.let { book.wordCount = it }
            rule.getString(rbi.lastChapter).takeIf { it.isNotBlank() }?.let { book.lastChapter = it }
            rule.getString(rbi.status).takeIf { it.isNotBlank() }?.let { book.status = it }
            // 第34批：同样防「空规则回落成详情页 URL」的伪封面。
            rule.getString(rbi.coverUrl, null, true)
                .takeIf { it.isNotBlank() && it != resp.url && it != book.bookUrl }
                ?.let { book.coverUrl = it }

            val tocUrl = rule.getString(rbi.tocUrl, null, true)
            book.tocUrl = tocUrl.ifBlank { book.bookUrl }
        }
    }

    // ── 目录 ──────────────────────────────────────────────────────────────

    /** 目录页：解析章节列表，章节地址绝对化。 */
    /**
     * 目录页：解析章节列表，章节地址绝对化。
     *
     * 第 22 批增强：
     * 1. `nextTocUrl` 分页——不少书源目录分页，原先只取第一页，章节数天然偏少；
     *    现在按 `nextTocUrl` 逐页翻，最多 [MAX_TOC_PAGES] 页，访问过的页面去重防自环。
     * 2. 章条归一化——按 chapterUrl 跨页 / 页内去重，剔除「返回目录 / 上一章 / 下一章」这类纯导航项。
     * 3. index 按入列位置重排，保证目录序号连续（供进度序号与阅读页显示）。
     */
    fun getChapterList(source: BookSource, book: Book): List<BookChapter> {
        val rt = source.ruleToc ?: return emptyList()
        val chapterListRule = rt.chapterList?.takeIf { it.isNotBlank() } ?: return emptyList()
        val chapterNameRule = rt.chapterName?.takeIf { it.isNotBlank() } ?: return emptyList()

        return runCatching {
            val chapters = ArrayList<BookChapter>()
            val seenUrl = HashSet<String>()
            val visitedPages = HashSet<String>()
            var pageUrl = book.tocUrl.ifBlank { book.bookUrl }
            var page = 0

            while (pageUrl.isNotBlank() && page < MAX_TOC_PAGES && visitedPages.add(pageUrl)) {
                val analyzeUrl = AnalyzeUrlCore(
                    rawUrl = pageUrl,
                    baseUrl = book.bookUrl,
                    source = source,
                    ruleData = book,
                    readTimeout = TIMEOUT_TOC,
                    callTimeout = TIMEOUT_TOC + 2_000,
                )
                val resp = analyzeUrl.getStrResponse()
                val body = resp.body ?: break

                val rule = AnalyzeRuleCore(ruleData = book, source = source).setContent(body, resp.url)
                if (page == 0) rt.preUpdateJs?.takeIf { it.isNotBlank() }?.let { rule.evalJS(it) }

                val elements = rule.getElements(chapterListRule)
                for (element in elements) {
                    rule.setContent(element)
                    val title = rule.getString(chapterNameRule).trim()
                    val url = rule.getString(rt.chapterUrl, null, true)
                    if (title.isBlank() || url.isBlank()) continue
                    // 第22批：纯导航项不算章节。
                    if (ChapterStats.isNavTitle(title)) continue
                    // 第22批：同一 URL 只收一次（跨页重复 / 页内重复 / 预读块）。
                    if (!seenUrl.add(url)) continue

                    // index 取真实入列位置（第12批语义）：空条目 / 导航项 / 重复项都不占号。
                    val chapter = BookChapter(bookUrl = book.bookUrl, index = chapters.size)
                    chapter.title = title
                    chapter.url = url
                    rule.getString(rt.isVip).takeIf { it.isNotBlank() }?.let {
                        chapter.isVip = it != "0" && it != "false"
                    }
                    chapter.updateTime = parseUpdateTime(rule.getString(rt.updateTime))
                    chapters += chapter
                }

                // 读完本章条目后把规则上下文切回「整页」，否则 nextTocUrl 会在最后一个元素上求值。
                rule.setContent(body, resp.url)
                val nextRaw = rule.getString(rt.nextTocUrl, null, true)
                pageUrl = nextRaw
                    .split('\n', '\r', ',', '，', ' ', '\t')
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && it !in visitedPages && it != resp.url }
                    ?: ""
                page++
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
            // 第31批：记录分页链已访问地址，防止「下一页/下一章」规则互跳（A→B→A）重复拼接。
            val visited = HashSet<String>()
            var page = 0
            while (currentUrl.isNotBlank() && page++ < MAX_CONTENT_PAGES) {
                if (!visited.add(currentUrl)) break
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
                    // 第31批：第2页起若正文以章节标题开头，说明分页链串到了下一章，直接停。
                    if (page > 1 && RE_CHAPTER_HEAD.containsMatchIn(text.trimStart().take(30))) break
                    if (sb.isNotEmpty()) sb.append('\n')
                    sb.append(text)
                }

                // 第31批：nextContentUrl 可能一次解析出多个地址，取第一个非空且未访问的。
                val next = rule.getString(rc.nextContentUrl, null, true)
                    .split('\n', '\r', ',', '，', ' ', '\t')
                    .map { it.trim() }
                    .firstOrNull { it.isNotBlank() && it !in visited && it != currentUrl }
                    ?: ""
                if (next.isBlank()) break
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
