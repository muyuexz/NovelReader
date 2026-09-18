package com.example.novelreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelreader.analyzeRule.Book
import com.example.novelreader.analyzeRule.BookChapter
import com.example.novelreader.analyzeRule.BookSourceEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 端到端自检开关：验证期置 true，会自动跑「搜索→详情→目录→正文」并把结果写日志。 */
private const val PROBE_ENABLED = true
private const val PROBE_KEYWORD = "斗破苍穹"

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 开机心跳：证明 App 进程真的起来了，用于区分「没启动/启动即崩」与「探针卡在中途」
        runCatching { writeToDownloads(this, "nr_boot.txt", "boot=" + System.currentTimeMillis()) }
        enableEdgeToEdge()
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    NovelApp()
                }
            }
        }
    }
}

/**
 * 顶层状态机：搜索 → 目录 → 阅读。
 *
 * 三个页面共用一个 Compose 树，靠 [currentBook] / [currentChapter] 两个状态切换，
 * 不引入导航库（依赖已主动瘦身，少一个白背包袱）。
 */
@Composable
private fun NovelApp() {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    // 书源加载
    var sourcesLoaded by remember { mutableStateOf(false) }
    var sourceCount by remember { mutableStateOf(0) }
    var sourceFailed by remember { mutableStateOf(0) }

    // 搜索
    var keyword by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var hits by remember { mutableStateOf(listOf<Book>()) }

    // 目录
    var currentBook by remember { mutableStateOf<Book?>(null) }
    var chapters by remember { mutableStateOf(listOf<BookChapter>()) }
    var loadingToc by remember { mutableStateOf(false) }
    var tocError by remember { mutableStateOf<String?>(null) }

    // 正文
    var currentChapter by remember { mutableStateOf<BookChapter?>(null) }
    var content by remember { mutableStateOf("") }
    var loadingContent by remember { mutableStateOf(false) }

    // 自检日志（探针输出，验证期用）
    var probeLog by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) {
            SourceRepository.ensureLoaded(context)
            SourceRepository.totalCount to SourceRepository.parseFailedCount
        }
        sourceCount = info.first
        sourceFailed = info.second
        sourcesLoaded = true
    }

    // —— 自检探针：书源加载完成后自动跑一遍全链路，结果写文件 + 上屏 ——
    LaunchedEffect(sourcesLoaded) {
        if (!PROBE_ENABLED || !sourcesLoaded) return@LaunchedEffect
        val log = withContext(Dispatchers.IO) {
            val sb = StringBuilder()
            // 增量落盘（黑匣子）：每走一步就把当前内容刷到公共 Download，
            // 避免中途卡死/崩溃导致一个字节都拿不到。全程 runCatching 兜底。
            val flush: () -> Unit = {
                runCatching { writeToDownloads(context, "nr_probe.txt", sb.toString()) }
                Unit
            }
            val nowStr: () -> String = {
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date())
            }
            try {
                val key = PROBE_KEYWORD
                sb.appendLine("== NovelReader 自检 ==")
                sb.appendLine("阶段=进入探针 " + nowStr())
                sb.appendLine("书源总数=${SourceRepository.totalCount} 解析失败=${SourceRepository.parseFailedCount}")
                sb.appendLine("关键词=$key")
                flush()

                // 1) App 内网络自检
                runCatching {
                    val conn = (java.net.URL("https://www.baidu.com").openConnection() as java.net.HttpURLConnection)
                    conn.connectTimeout = 8000
                    conn.readTimeout = 8000
                    conn.requestMethod = "GET"
                    sb.appendLine("App内网络=HTTP " + conn.responseCode)
                    conn.disconnect()
                }.onFailure { sb.appendLine("App内网络=FAIL:" + it.message) }
                sb.appendLine("阶段=网络自检完 " + nowStr())
                flush()

                // 2) 引擎离线自测：固定 HTML + 规则，排除网络/书源干扰
                runCatching {
                    val html = "<html><body><div class='book-list'>" +
                        "<div class='item'><a href='/b/1'>斗破苍穹</a></div>" +
                        "<div class='item'><a href='/b/2'>武动乾坤</a></div></div></body></html>"
                    val rr = com.example.novelreader.analyzeRule.AnalyzeRuleCore()
                    rr.variables = mapOf("key" to key)
                    rr.setContent(html, "http://test.local/")
                    val els = rr.getElements("class.book-list@tag.div")
                    sb.appendLine("引擎自测 elements=${els.size}")
                    val firstEl = els.firstOrNull()
                    if (firstEl != null) {
                        rr.setContent(firstEl)
                        sb.appendLine("引擎自测 首项名=" + rr.getString("tag.a@text"))
                    }
                }.onFailure { sb.appendLine("引擎自测 FAIL:" + it.message) }
                sb.appendLine("阶段=引擎自测完 " + nowStr())
                flush()

                // 3) 书源池取样：打印真实请求 URL / body 长度 / elements 数
                val enabledAll = SourceRepository.enabled()
                sb.appendLine("enabled书源数=${enabledAll.size}")
                // 取样改为「活源优先」：先对书源域名做 DNS 探活，只收集可达的源，最多 5 条，避免又撞一堆死域名
                val candidates = enabledAll.filter { !it.searchUrl.isNullOrBlank() }
                val probeTargets = ArrayList<com.example.novelreader.analyzeRule.BookSource>()
                run {
                    var scanned = 0
                    for (s in candidates) {
                        if (probeTargets.size >= 5 || scanned >= 150) break
                        scanned++
                        val host = runCatching { java.net.URI(s.bookSourceUrl).host }.getOrNull()
                        if (host.isNullOrBlank()) continue
                        val alive = runCatching { java.net.InetAddress.getByName(host); true }.getOrDefault(false)
                        if (alive) {
                            probeTargets.add(s)
                            sb.appendLine("探活命中 $host")
                        } else if (probeTargets.isEmpty()) {
                            sb.appendLine("死域(跳过) $host")
                        }
                        if (scanned % 5 == 0) {
                            sb.appendLine("阶段=探活中 scanned=$scanned")
                            flush()
                        }
                    }
                }
                sb.appendLine("探活取样(活源)=${probeTargets.size}")
                sb.appendLine("阶段=探活完 " + nowStr())
                flush()
                for (i in probeTargets.indices) {
                    val s = probeTargets[i]
                    runCatching {
                        val au = com.example.novelreader.analyzeRule.AnalyzeUrlCore(
                            rawUrl = s.searchUrl!!,
                            source = s,
                            variables = mapOf("key" to key),
                        )
                        sb.appendLine("源[$i] ${s.bookSourceName} 规则=${(s.ruleSearch?.bookList ?: "").take(60)}")
                        sb.appendLine("源[$i] 请求URL=${au.url}")
                        val resp = au.getStrResponse()
                        val body = resp.body ?: ""
                        sb.appendLine("源[$i] body长度=${body.length}")
                        val rr2 = com.example.novelreader.analyzeRule.AnalyzeRuleCore(source = s)
                        rr2.variables = mapOf("key" to key)
                        rr2.setContent(body, resp.url)
                        val els2 = rr2.getElements(s.ruleSearch?.bookList ?: "")
                        sb.appendLine("源[$i] elements=${els2.size}")
                    }.onFailure { sb.appendLine("源[$i] ERR:" + (it.message ?: it.javaClass.simpleName)) }
                    flush()
                }

                val picked = java.util.Collections.synchronizedList(mutableListOf<Book>())
                val n = SourceRepository.search(key) { batch -> picked.addAll(batch) }
                sb.appendLine("搜索命中=$n")
                sb.appendLine("阶段=搜索完 " + nowStr())
                flush()

                val first = picked.firstOrNull()
                if (first == null) {
                    sb.appendLine("首条=null（无任何书源命中）")
                } else {
                    val src = first.source
                    sb.appendLine("首条书名=${first.name} 作者=${first.author} 书源=${src?.bookSourceName ?: "null"}")
                    if (src != null) {
                        val info = runCatching { BookSourceEngine.getBookInfo(src, first) }
                        sb.appendLine("getBookInfo=" + (if (info.isSuccess) "OK" else "FAIL:" + info.exceptionOrNull()?.message))
                        val chs = runCatching { BookSourceEngine.getChapterList(src, first) }.getOrDefault(emptyList())
                        sb.appendLine("目录章节数=${chs.size}")
                        // —— 目录深潜诊断：逐层打印中间态，定位 0 章节真因 ——
                        runCatching {
                            sb.appendLine("诊断 bookUrl=${first.bookUrl}")
                            sb.appendLine("诊断 tocUrl=${first.tocUrl}")
                            val rt = src.ruleToc
                            sb.appendLine("诊断 ruleToc.list=${rt?.chapterList} | name=${rt?.chapterName} | url=${rt?.chapterUrl}")
                            if (rt != null) {
                                val tUrl = first.tocUrl.ifBlank { first.bookUrl }
                                val au2 = com.example.novelreader.analyzeRule.AnalyzeUrlCore(
                                    rawUrl = tUrl,
                                    baseUrl = first.bookUrl,
                                    source = src,
                                    ruleData = first,
                                )
                                sb.appendLine("诊断 目录请求URL=${au2.url}")
                                val r2 = au2.getStrResponse()
                                val b2 = r2.body ?: ""
                                sb.appendLine("诊断 目录body长度=${b2.length}")
                                sb.appendLine("诊断 目录body预览=" + b2.take(160).replace("\n", " "))
                                val rr3 = com.example.novelreader.analyzeRule.AnalyzeRuleCore(ruleData = first, source = src)
                                rr3.setContent(b2, r2.url)
                                val els3 = rr3.getElements(rt.chapterList ?: "")
                                sb.appendLine("诊断 目录elements=${els3.size}")
                                els3.firstOrNull()?.let { e ->
                                    rr3.setContent(e)
                                    sb.appendLine("诊断 首元素text=" + rr3.getString("text").take(50))
                                    sb.appendLine("诊断 首元素href=" + rr3.getString("href").take(80))
                                }
                            }
                        }.onFailure { sb.appendLine("诊断 ERR=" + (it.message ?: it.javaClass.simpleName)) }
                        flush()
                        val ch0 = chs.firstOrNull()
                        if (ch0 == null) {
                            sb.appendLine("首章=null（目录为空）")
                        } else {
                            sb.appendLine("首章标题=${ch0.title}")
                            val txt = runCatching { BookSourceEngine.getContent(src, first, ch0) }.getOrDefault("")
                            sb.appendLine("正文长度=${txt.length}")
                            sb.appendLine("正文预览=" + txt.take(200).replace("\n", " "))
                        }
                    }
                }
            } catch (t: Throwable) {
                sb.appendLine("探针异常=" + t.message)
                sb.appendLine(
                    runCatching {
                        java.io.StringWriter().also { w -> t.printStackTrace(java.io.PrintWriter(w)) }.toString()
                    }.getOrDefault("").take(3000)
                )
            }
            sb.appendLine("阶段=探针结束 " + nowStr())
            flush()
            val out = sb.toString()
            runCatching { writeToDownloads(context, "nr_probe.txt", out) }
            out
        }
        probeLog = log
    }

    val openBook = currentBook
    val openChapter = currentChapter

    // 正文读取统一入口：首次点章 / 上一章 / 下一章共用；带防串台守卫。
    val loadChapter: (BookChapter) -> Unit = load@{ ch ->
        val b = currentBook ?: return@load
        val src = b.source ?: return@load
        currentChapter = ch
        content = ""
        loadingContent = true
        scope.launch {
            val txt = withContext(Dispatchers.IO) {
                runCatching { BookSourceEngine.getContent(src, b, ch) }.getOrDefault("")
            }
            // 快速连点翻章时，只有仍是最新选中的章节才允许回填，避免串台。
            if (currentChapter === ch) {
                content = txt.ifBlank { "（正文解析为空：书源规则不匹配或网络失败）" }
                loadingContent = false
            }
        }
    }

    when {
        openBook != null && openChapter != null -> ReaderScreen(
            chapter = openChapter,
            content = content,
            loading = loadingContent,
            chapters = chapters,
            onOpenChapter = loadChapter,
            onBack = {
                currentChapter = null
                content = ""
            },
        )

        openBook != null -> TocScreen(
            book = openBook,
            chapters = chapters,
            loading = loadingToc,
            error = tocError,
            onBack = {
                currentBook = null
                chapters = emptyList()
                tocError = null
            },
            onOpen = { ch -> loadChapter(ch) },
        )

        else -> SearchScreen(
            keyword = keyword,
            onKeywordChange = { keyword = it },
            searching = searching,
            hits = hits,
            sourceCount = sourceCount,
            sourceFailed = sourceFailed,
            sourcesLoaded = sourcesLoaded,
            probeLog = probeLog,
            onSearch = {
                val k = keyword.trim()
                if (k.isNotEmpty() && !searching) {
                    searching = true
                    hits = emptyList()
                    scope.launch {
                        // 回调是全量有序快照（已按匹配度/源权重排序），整体替换即可。
                        SourceRepository.search(k) { ranked -> hits = ranked }
                        searching = false
                    }
                }
            },
            onOpen = { book ->
                currentBook = book
                chapters = emptyList()
                tocError = null
                loadingToc = true
                scope.launch {
                    val list = withContext(Dispatchers.IO) {
                        val src = book.source
                        if (src == null) emptyList<BookChapter>() else runCatching {
                            BookSourceEngine.getBookInfo(src, book)
                            BookSourceEngine.getChapterList(src, book)
                        }.getOrDefault(emptyList())
                    }
                    chapters = list
                    if (list.isEmpty()) tocError = "目录解析为空（书源规则不匹配或网络失败）"
                    loadingToc = false
                }
            },
        )
    }
}

@Composable
private fun SearchScreen(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    searching: Boolean,
    hits: List<Book>,
    sourceCount: Int,
    sourceFailed: Int,
    sourcesLoaded: Boolean,
    probeLog: String,
    onSearch: () -> Unit,
    onOpen: (Book) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        if (probeLog.isNotBlank()) {
            Text(
                text = probeLog,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 16,
            )
            HorizontalDivider()
            Spacer(Modifier.height(8.dp))
        }
        Text("轻阅读 · Novel Reader", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(4.dp))
        Text(
            text = when {
                !sourcesLoaded -> "正在加载书源…"
                sourceFailed > 0 -> "已载入 $sourceCount 条书源（$sourceFailed 条解析失败）"
                else -> "已载入 $sourceCount 条书源"
            },
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(12.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                label = { Text("书名 / 作者") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            )
            Spacer(Modifier.width(8.dp))
            Button(onClick = onSearch, enabled = !searching) {
                Text(if (searching) "搜索中" else "搜索")
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            searching && hits.isEmpty() -> Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Spacer(Modifier.width(8.dp))
                Text("正在并发搜索书源…", style = MaterialTheme.typography.bodySmall)
            }

            hits.isEmpty() -> Text("输入关键词开始搜索", style = MaterialTheme.typography.bodySmall)

            else -> {
                Text("命中 ${hits.size} 条", style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(4.dp))
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(hits) { book ->
                        BookRow(book) { onOpen(book) }
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun BookRow(book: Book, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(book.name.ifBlank { "（无书名）" }, style = MaterialTheme.typography.titleMedium)
        val sub = listOfNotNull(
            book.author.takeIf { it.isNotBlank() },
            book.originName.takeIf { it.isNotBlank() },
        ).joinToString(" · ")
        if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodySmall)
        book.lastChapter?.takeIf { it.isNotBlank() }?.let {
            Text("最新：$it", style = MaterialTheme.typography.bodySmall, maxLines = 1)
        }
    }
}

@Composable
private fun TocScreen(
    book: Book,
    chapters: List<BookChapter>,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onOpen: (BookChapter) -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        TitleBar(title = book.name.ifBlank { "目录" }, onBack = onBack)
        HorizontalDivider()
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> CenterMessage("正在解析目录…", spinner = true)
                error != null -> CenterMessage(error)
                chapters.isEmpty() -> CenterMessage("目录为空")
                else -> LazyColumn {
                    items(chapters) { ch ->
                        Text(
                            text = ch.title,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpen(ch) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ReaderScreen(
    chapter: BookChapter,
    content: String,
    loading: Boolean,
    chapters: List<BookChapter>,
    onOpenChapter: (BookChapter) -> Unit,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val prefs = remember {
        context.getSharedPreferences("reader_prefs", android.content.Context.MODE_PRIVATE)
    }
    var fontSize by remember { mutableStateOf(prefs.getInt("fontSize", 17)) }
    var lineHeight by remember { mutableStateOf(prefs.getInt("lineHeight", 28)) }
    var nightMode by remember { mutableStateOf(prefs.getBoolean("nightMode", false)) }
    var showPanel by remember { mutableStateOf(false) }

    val scrollState = rememberScrollState()
    // 切章后回到顶部
    LaunchedEffect(chapter.url) { scrollState.scrollTo(0) }

    // 当前章在目录中的位置 → 上一章 / 下一章
    val idx = remember(chapter.url, chapters) {
        chapters.indexOfFirst { it.url == chapter.url }
    }
    val prev = if (idx > 0) chapters[idx - 1] else null
    val next = if (idx in 0 until chapters.size - 1) chapters[idx + 1] else null
    val progress by remember {
        derivedStateOf {
            val max = scrollState.maxValue
            if (max <= 0) 0f else scrollState.value.toFloat() / max.toFloat()
        }
    }

    val bg = if (nightMode) Color(0xFF121212) else Color(0xFFFAF9F6)
    val fg = if (nightMode) Color(0xFFB8B8B8) else Color(0xFF1A1A1A)
    val sub = if (nightMode) Color(0xFF7C7C7C) else Color(0xFF6B6B6B)
    val divider = if (nightMode) Color(0xFF2A2A2A) else Color(0xFFE4E2DC)

    Box(Modifier.fillMaxSize().background(bg)) {
        Column(Modifier.fillMaxSize()) {
            // 顶部栏：返回目录 / 章节名 / 阅读进度 / 设置
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ 目录", color = sub, fontSize = 14.sp) }
                Text(
                    text = chapter.title.ifBlank { "正文" },
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    color = fg,
                    fontSize = 14.sp,
                )
                Text(
                    text = if (loading) "--" else "${(progress * 100).toInt()}%",
                    color = sub,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(end = 4.dp),
                )
                TextButton(onClick = { showPanel = !showPanel }) {
                    Text("Aa", color = sub, fontSize = 14.sp)
                }
            }
            HorizontalDivider(color = divider)

            // 阅读设置面板（字号 / 行距 / 夜间）
            if (showPanel) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ReaderStepper("字号", fontSize, 13, 30, fg, sub) {
                        fontSize = it
                        prefs.edit().putInt("fontSize", it).apply()
                    }
                    ReaderStepper("行距", lineHeight, 20, 48, fg, sub) {
                        lineHeight = it
                        prefs.edit().putInt("lineHeight", it).apply()
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("夜间", color = fg, fontSize = 13.sp, modifier = Modifier.width(56.dp))
                        TextButton(onClick = {
                            nightMode = !nightMode
                            prefs.edit().putBoolean("nightMode", nightMode).apply()
                        }) {
                            Text(if (nightMode) "已开启" else "已关闭", color = fg, fontSize = 13.sp)
                        }
                    }
                }
                HorizontalDivider(color = divider)
            }

            // 正文
            if (loading) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                Text(
                    text = content.ifBlank { "（正文为空）" },
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(scrollState)
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    color = fg,
                    fontSize = fontSize.sp,
                    lineHeight = lineHeight.sp,
                )
            }

            // 底部翻章
            HorizontalDivider(color = divider)
            Row(
                Modifier.fillMaxWidth().padding(bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(
                    onClick = { prev?.let(onOpenChapter) },
                    enabled = prev != null,
                    modifier = Modifier.weight(1f),
                ) { Text("‹ 上一章", color = if (prev != null) fg else sub, fontSize = 14.sp) }
                Text(
                    text = if (idx >= 0) "${idx + 1}/${chapters.size}" else "",
                    color = sub,
                    fontSize = 12.sp,
                )
                TextButton(
                    onClick = { next?.let(onOpenChapter) },
                    enabled = next != null,
                    modifier = Modifier.weight(1f),
                ) { Text("下一章 ›", color = if (next != null) fg else sub, fontSize = 14.sp) }
            }
        }
    }
}

/** 阅读设置里的加减档控件（字号 / 行距共用）。 */
@Composable
private fun ReaderStepper(
    label: String,
    value: Int,
    min: Int,
    max: Int,
    fg: Color,
    sub: Color,
    onChange: (Int) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, color = fg, fontSize = 13.sp, modifier = Modifier.width(56.dp))
        TextButton(onClick = { if (value > min) onChange(value - 1) }) {
            Text("－", color = if (value > min) fg else sub, fontSize = 16.sp)
        }
        Text("$value", color = fg, fontSize = 13.sp, modifier = Modifier.width(28.dp))
        TextButton(onClick = { if (value < max) onChange(value + 1) }) {
            Text("＋", color = if (value < max) fg else sub, fontSize = 16.sp)
        }
    }
}

@Composable
private fun TitleBar(title: String, onBack: (() -> Unit)? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack) { Text("‹ 返回") }
        }
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CenterMessage(text: String, spinner: Boolean = false) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (spinner) {
                CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                Spacer(Modifier.height(8.dp))
            }
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/**
 * 把探针文本写到公共 Download 目录，便于外部终端直接读取全量内容。
 * API 29+ 走 MediaStore.Downloads；低版本回退到公共 Download 目录 + 文件权限。
 * 全程 runCatching 包裹，失败也不影响 App 运行。
 */
private fun writeToDownloads(context: android.content.Context, name: String, text: String): Boolean {
    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
        val resolver = context.contentResolver
        // 先删同名，避免重复文件
        runCatching {
            resolver.delete(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "${android.provider.MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(name),
            )
        }
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                android.os.Environment.DIRECTORY_DOWNLOADS)
        }
        val uri = resolver.insert(
            android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
        ) ?: return false
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) } ?: return false
        return true
    } else {
        @Suppress("DEPRECATION")
        val dir = android.os.Environment.getExternalStoragePublicDirectory(
            android.os.Environment.DIRECTORY_DOWNLOADS,
        )
        if (!dir.exists()) dir.mkdirs()
        java.io.File(dir, name).writeText(text, Charsets.UTF_8)
        return true
    }
}