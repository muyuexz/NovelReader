package com.example.novelreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.BackHandler
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelreader.analyzeRule.Book
import com.example.novelreader.analyzeRule.BookChapter
import com.example.novelreader.analyzeRule.BookSourceEngine
import com.example.novelreader.ui.AppHeader
import com.example.novelreader.ui.BookCard
import com.example.novelreader.ui.CountBadge
import com.example.novelreader.ui.Ink
import com.example.novelreader.ui.NovelReaderTheme
import com.example.novelreader.ui.ReaderPalettes
import com.example.novelreader.ui.StateBlock
import com.example.novelreader.ui.TagPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 开机心跳：证明 App 进程真的起来了（写文件，不上屏）
        runCatching { writeToDownloads(this, "nr_boot.txt", "boot=" + System.currentTimeMillis()) }
        enableEdgeToEdge()
        setContent {
            // 关键改造：不再用裸 MaterialTheme{}，接入自定义 ColorScheme + Typography。
            NovelReaderTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
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
    var hasSearched by remember { mutableStateOf(false) }
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
    // 阅读页右上角「目录」：在阅读态上层叠出目录页
    var showReaderToc by remember { mutableStateOf(false) }
    // 记录最近阅读的章节，返回目录时可高亮
    var lastChapterUrl by remember { mutableStateOf<String?>(null) }

    // 正文章节缓存（第 6 条）：点进阅读后台预取后续章节，翻页命中即秒开
    val contentCache = remember {
        java.util.concurrent.ConcurrentHashMap<String, String>()
    }
    val prefetching = remember {
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    }
    // 第9批：contentCache 是 ConcurrentHashMap，写入不触发 Compose 重组，
    // 目录页「已缓存」标签要滑动一次才刷新。这里用一个版本号状态承接变更，
    // 缓存一变就 +1，目录页据此重算 cachedUrls。
    var cacheTick by remember { mutableStateOf(0) }

    // 首页 Tab（0 = 书架，1 = 发现/搜索）/ 详情页
    var homeTab by remember { mutableStateOf(0) }
    var showDetail by remember { mutableStateOf(false) }
    var detailBook by remember { mutableStateOf<Book?>(null) }
    var shelfEntries by remember { mutableStateOf(listOf<ShelfEntry>()) }

    val refreshShelf: () -> Unit = {
        scope.launch {
            shelfEntries = withContext(Dispatchers.IO) {
                runCatching { ShelfRepository.all(context) }.getOrDefault(emptyList())
            }
        }
    }

    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) {
            SourceRepository.ensureLoaded(context)
            SourceRepository.totalCount to SourceRepository.parseFailedCount
        }
        sourceCount = info.first
        sourceFailed = info.second
        sourcesLoaded = true
        shelfEntries = withContext(Dispatchers.IO) {
            runCatching { ShelfRepository.all(context) }.getOrDefault(emptyList())
        }
    }

    // 打开某本书 → 拉取目录
    val openBook: (Book) -> Unit = { book ->
        currentBook = book
        chapters = emptyList()
        tocError = null
        lastChapterUrl = null
        showReaderToc = false
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
            if (list.isNotEmpty()) {
                // 目录抓全后回填真实章节数与最新章节，
                // 修正书源搜索规则里可能过时/错误的 lastChapter（如只给到第 81 章）。
                book.chapterCount = list.size
                list.lastOrNull()?.title?.takeIf { it.isNotBlank() }?.let { book.lastChapter = it }
            }
            if (list.isEmpty()) tocError = "目录解析为空（书源规则不匹配或网络失败）"
            loadingToc = false
            // 详情拿到了就顺手刷进书架（不在书架里则无操作）
            val src = book.source
            if (src != null) {
                withContext(Dispatchers.IO) {
                    runCatching { ShelfRepository.refreshMeta(context, book, src.bookSourceUrl) }
                }
            }
        }
    }

    // 后台预取某章的后续 2 章正文（第 6 条）：串行、去重，命中缓存后翻页零等待。
    val prefetchAfter: (BookChapter) -> Unit = pre@{ cur ->
        val b = currentBook ?: return@pre
        val src = b.source ?: return@pre
        val list = chapters
        val from = list.indexOfFirst { it.url == cur.url }
        if (from < 0) return@pre
        scope.launch(Dispatchers.IO) {
            var fetched = 0
            var i = from + 1
            while (i < list.size && fetched < 2) {
                val c = list[i]
                if (!contentCache.containsKey(c.url) && prefetching.add(c.url)) {
                    val t = runCatching { BookSourceEngine.getContent(src, b, c) }.getOrDefault("")
                    if (t.isNotBlank()) contentCache[c.url] = t else prefetching.remove(c.url)
                    fetched++
                }
                i++
            }
            // 第9批：预取写入后通知 UI 刷新缓存标签
            withContext(Dispatchers.Main) { cacheTick++ }
        }
    }

    // 正文读取统一入口：首次点章 / 上一章 / 下一章共用；带防串台守卫 + 缓存命中（第 6 条）。
    val loadChapter: (BookChapter) -> Unit = load@{ ch ->
        val b = currentBook ?: return@load
        val src = b.source ?: return@load
        currentChapter = ch
        lastChapterUrl = ch.url
        // 命中缓存：直接出正文，翻页零等待；未命中才走网络并显示 loading。
        val cached = contentCache[ch.url]
        if (cached != null) {
            content = cached
            loadingContent = false
        } else {
            content = ""
            loadingContent = true
        }
        scope.launch {
            // 进度写入与正文拉取并发：进度是本地 IO，不该拖慢正文首屏（第 8 条）
            launch(Dispatchers.IO) {
                runCatching { ShelfRepository.updateProgress(context, b.bookUrl, src.bookSourceUrl, ch) }
            }
            if (cached == null) {
                val txt = withContext(Dispatchers.IO) {
                    runCatching { BookSourceEngine.getContent(src, b, ch) }.getOrDefault("")
                }
                if (txt.isNotBlank()) {
                    contentCache[ch.url] = txt
                    withContext(Dispatchers.Main) { cacheTick++ }
                }
                // 快速连点翻章时，只有仍是最新选中的章节才允许回填，避免串台。
                if (currentChapter === ch) {
                    content = txt.ifBlank { "（正文解析为空：书源规则不匹配或网络失败）" }
                    loadingContent = false
                }
            }
            // 正文稳了就把后面几章也拉进缓存，翻页不再等网络。
            prefetchAfter(ch)
        }
    }

    // 第 5 条：离线缓存区间（从第 a 章到第 b 章，1 基、含端点）。
    // 串行拉取、去重，写进 contentCache；之后翻到这些章节即秒开，不用每次在线等。
    val cacheRange: (Int, Int) -> Unit = cr@{ a, bEnd ->
        val b = currentBook ?: return@cr
        val src = b.source ?: return@cr
        val list = chapters
        if (list.isEmpty()) return@cr
        scope.launch(Dispatchers.IO) {
            val lo = a.coerceIn(1, list.size)
            val hi = bEnd.coerceIn(lo, list.size)
            for (i in lo..hi) {
                val c = list[i - 1]
                if (!contentCache.containsKey(c.url) && prefetching.add(c.url)) {
                    val t = runCatching { BookSourceEngine.getContent(src, b, c) }.getOrDefault("")
                    if (t.isNotBlank()) contentCache[c.url] = t else prefetching.remove(c.url)
                }
            }
            // 第9批：整段区间缓存完，通知 UI 刷新目录页缓存标签
            withContext(Dispatchers.Main) { cacheTick++ }
        }
    }
    val openBookNow = currentBook
    val openChapter = currentChapter

    // 系统返回（返回手势 / 返回键）接入：与各页左上角按钮走同一套回退逻辑。
    // 层级：正文章 → 详情 → 目录 → 发现页 → 书架 → 退出 App。
    // 只在"还有上一级"的状态下拦截，根页面交还系统。
    BackHandler(enabled = openBookNow != null || homeTab == 1) {
        when {
            // 阅读页的目录浮层优先关掉
            showReaderToc -> {
                showReaderToc = false
            }
            openChapter != null -> {
                currentChapter = null
                content = ""
                showReaderToc = false
                // 第 7 批第 2 条：从详情页进来的，返回阅读页要回详情页（而不是掉到目录页）
                if (detailBook != null) showDetail = true
            }
            showDetail -> {
                showDetail = false
                detailBook = null
                currentBook = null
                chapters = emptyList()
                tocError = null
            }
            openBookNow != null -> {
                // 目录页：有详情来源就退回详情，否则直接回首页
                if (detailBook != null) {
                    showDetail = true
                } else {
                    currentBook = null
                    chapters = emptyList()
                    tocError = null
                }
            }
            else -> homeTab = 0
        }
    }

    // —— 详情页 / 书架派生状态 ——
    val detailNow = detailBook
    val detailSourceUrl = detailNow?.source?.bookSourceUrl ?: ""
    val shelfEntryNow = detailNow?.let { b ->
        shelfEntries.firstOrNull { it.bookUrl == b.bookUrl && it.sourceUrl == detailSourceUrl }
    }
    val inShelf = shelfEntryNow != null
    val continueChapter = chapters.firstOrNull { it.url == shelfEntryNow?.lastReadChapterUrl }
        ?: chapters.firstOrNull()
    // onContinue 已并入详情页底部「阅读」按钮（第 4 条）
    val onToggleShelf: () -> Unit = {
        val b = detailNow
        if (b != null && detailSourceUrl.isNotBlank()) {
            if (inShelf) ShelfRepository.removeBy(context, b.bookUrl, detailSourceUrl)
            else ShelfRepository.add(context, b, detailSourceUrl)
            refreshShelf()
        }
    }

    // 搜索卡片 → 详情页（目录照旧在后台拉，详情页上能直接看到章节数）
    val openDetail: (Book) -> Unit = { book ->
        detailBook = book
        showDetail = true
        openBook(book)
    }

    // 书架条目 → 还原成 Book 并直接续读到上次那一章
    val openFromShelf: (Book, ShelfEntry) -> Unit = { book, entry ->
        detailBook = book
        showDetail = false
        currentBook = book
        chapters = emptyList()
        tocError = null
        lastChapterUrl = entry.lastReadChapterUrl
        showReaderToc = false
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
            if (list.isNotEmpty()) {
                book.chapterCount = list.size
                list.lastOrNull()?.title?.takeIf { it.isNotBlank() }?.let { book.lastChapter = it }
            }
            loadingToc = false
            if (list.isEmpty()) {
                tocError = "目录解析为空（书源规则不匹配或网络失败）"
            } else {
                loadChapter(list.firstOrNull { it.url == entry.lastReadChapterUrl } ?: list.first())
            }
        }
    }

    when {
        openBookNow != null && openChapter != null && !showReaderToc -> ReaderScreen(
            chapter = openChapter,
            content = content,
            loading = loadingContent,
            chapters = chapters,
            onOpenChapter = loadChapter,
            onOpenToc = { showReaderToc = true },
            onCacheRange = cacheRange,
            cache = contentCache,
        )

        // 阅读页点「目录」：目录叠在阅读之上，选中章节后回到阅读
        openBookNow != null && showReaderToc -> TocScreen(
            book = openBookNow,
            chapters = chapters,
            currentChapterUrl = lastChapterUrl,
            loading = loadingToc,
            error = tocError,
            onBack = { showReaderToc = false },
            onOpen = { ch ->
                showReaderToc = false
                loadChapter(ch)
            },
            cachedUrls = remember(cacheTick) { contentCache.keys.toSet() },
        )

        openBookNow != null && showDetail -> DetailScreen(
            book = openBookNow,
            chapters = chapters,
            loading = loadingToc,
            error = tocError,
            inShelf = inShelf,
            onToggleShelf = onToggleShelf,
                onRead = {
                    // 第 4 条：底部右侧「阅读」→ 直接开读（有进度续读，否则第一章）。
                    // 第 7 批第 2 条：不再清掉 showDetail，阅读页返回时才能回详情页而非目录页。
                    val target = continueChapter
                    if (target != null) {
                        loadChapter(target)
                    }
                },
            onRetry = { openBook(openBookNow) },
        )

        openBookNow != null -> TocScreen(
            book = openBookNow,
            chapters = chapters,
            currentChapterUrl = lastChapterUrl,
            loading = loadingToc,
            error = tocError,
            onBack = {
                if (detailBook != null) {
                    showDetail = true
                } else {
                    currentBook = null
                    chapters = emptyList()
                    tocError = null
                }
            },
            onOpen = { ch -> loadChapter(ch) },
            cachedUrls = remember(cacheTick) { contentCache.keys.toSet() },
        )

        // —— 首页：底部 Tab（书架 / 发现），不再有多余的返回入口 ——
        else -> Column(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
        ) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (homeTab == 0) {
                    ShelfScreen(
                        entries = shelfEntries,
                        onOpen = { entry ->
                            openDetail(entry.toBook(SourceRepository.findByKey(entry.sourceUrl)))
                        },
                        onRead = { entry ->
                            openFromShelf(
                                entry.toBook(SourceRepository.findByKey(entry.sourceUrl)),
                                entry,
                            )
                        },
                        onRemove = { entry ->
                            ShelfRepository.removeBy(context, entry.bookUrl, entry.sourceUrl)
                            refreshShelf()
                        },
                    )
                } else {
                    SearchScreen(
                        keyword = keyword,
                        onKeywordChange = { keyword = it },
                        searching = searching,
                        hasSearched = hasSearched,
                        hits = hits,
                        sourceCount = sourceCount,
                        sourceFailed = sourceFailed,
                        sourcesLoaded = sourcesLoaded,
                        onSearch = {
                            val k = keyword.trim()
                            if (k.isNotEmpty() && !searching) {
                                searching = true
                                hasSearched = true
                                hits = emptyList()
                                scope.launch {
                                    // 回调是全量有序快照（已按匹配度/源权重排序），整体替换即可。
                                    SourceRepository.search(k) { ranked -> hits = ranked }
                                    searching = false
                                }
                            }
                        },
                        onOpen = openDetail,
                    )
                }
            }
            NavigationBar {
                NavigationBarItem(
                    selected = homeTab == 0,
                    onClick = {
                        refreshShelf()
                        homeTab = 0
                    },
                    icon = { Text("📚", fontSize = 18.sp) },
                    label = { Text("书架") },
                )
                NavigationBarItem(
                    selected = homeTab == 1,
                    onClick = { homeTab = 1 },
                    icon = { Text("🔍", fontSize = 18.sp) },
                    label = { Text("发现") },
                )
            }
        }
    }
}

/* ==================================================================== *
 *  搜索页：Hero 渐变头 + 输入框 + 结果卡片流
 * ==================================================================== */
@Composable
private fun SearchScreen(
    keyword: String,
    onKeywordChange: (String) -> Unit,
    searching: Boolean,
    hasSearched: Boolean,
    hits: List<Book>,
    sourceCount: Int,
    sourceFailed: Int,
    sourcesLoaded: Boolean,
    onSearch: () -> Unit,
    onOpen: (Book) -> Unit,
) {
    val listState = rememberLazyListState()
    // 第 1 条：每次结果刷新后回到顶部，不再停留在上次的滚动位置
    LaunchedEffect(hits) {
        if (hits.isNotEmpty()) runCatching { listState.scrollToItem(0) }
    }
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        // —— Hero 头部：渐变延伸到状态栏下 ——
        Column(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp))
                .background(Brush.linearGradient(Ink.BrandGradient))
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 22.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("发现", style = MaterialTheme.typography.headlineMedium, color = Color.White)
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(5.dp))
            Text(
                text = when {
                    !sourcesLoaded -> "正在装载书源…"
                    sourceFailed > 0 -> "已载入 $sourceCount 条书源 · $sourceFailed 条解析失败"
                    else -> "已载入 $sourceCount 条书源 · 全网聚合"
                },
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.85f),
            )
            Spacer(Modifier.height(18.dp))

            Surface(
                shape = RoundedCornerShape(15.dp),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    Modifier.fillMaxWidth().padding(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = keyword,
                        onValueChange = onKeywordChange,
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("书名 / 作者", style = MaterialTheme.typography.bodyLarge) },
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            disabledContainerColor = Color.Transparent,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent,
                            disabledIndicatorColor = Color.Transparent,
                        ),
                    )
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = onSearch,
                        enabled = !searching,
                        shape = RoundedCornerShape(11.dp),
                    ) {
                        Text(if (searching) "搜索中" else "搜索")
                    }
                }
            }
        }

        // —— 结果区 ——
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                hits.isNotEmpty() -> Column(Modifier.fillMaxSize().padding(top = 14.dp)) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CountBadge("命中 ${hits.size} 条")
                        Spacer(Modifier.width(10.dp))
                        if (searching) {
                            CircularProgressIndicator(modifier = Modifier.size(13.dp), strokeWidth = 1.5.dp)
                            Spacer(Modifier.width(6.dp))
                            Text(
                                "搜索中…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(
                            "按匹配度排序",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        items(hits, key = { it.originName + "|" + it.bookUrl + "|" + it.name }) { book ->
                            BookCard(book) { onOpen(book) }
                        }
                    }
                }

                searching -> StateBlock(
                    title = "正在并发搜索",
                    description = "已向 $sourceCount 条书源发出请求…",
                    spinner = true,
                )

                !hasSearched -> StateBlock(
                    title = "开始探索",
                    description = "输入书名或作者，一键搜遍全部书源",
                )

                else -> StateBlock(
                    title = "没有找到结果",
                    description = "换个关键词试试，或稍后重试",
                    actionText = "重新搜索",
                    onAction = onSearch,
                )
            }
        }
    }
}

/* ==================================================================== *
 *  目录页：章节查找 + 当前章高亮 + 自动定位
 * ==================================================================== */
@Composable
private fun TocScreen(
    book: Book,
    chapters: List<BookChapter>,
    currentChapterUrl: String?,
    loading: Boolean,
    error: String?,
    onBack: () -> Unit,
    onOpen: (BookChapter) -> Unit,
    cachedUrls: Set<String> = emptySet(),
) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query, chapters) {
        if (query.isBlank()) chapters else chapters.filter { it.title.contains(query, ignoreCase = true) }
    }
    val listState = rememberLazyListState()

    // 进入目录时自动滚到最近阅读的章节
    LaunchedEffect(currentChapterUrl, chapters) {
        if (query.isBlank()) {
            val i = chapters.indexOfFirst { it.url == currentChapterUrl }
            if (i >= 0) listState.scrollToItem(i)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        AppHeader(
            title = book.name.ifBlank { "目录" },
            subtitle = listOfNotNull(
                book.author.takeIf { it.isNotBlank() },
                if (chapters.isNotEmpty()) "共 ${chapters.size} 章" else null,
            ).joinToString(" · ").ifBlank { null },
        )

        if (chapters.isNotEmpty()) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true,
                placeholder = { Text("查找章节", style = MaterialTheme.typography.bodyMedium) },
                shape = RoundedCornerShape(13.dp),
            )
            Spacer(Modifier.height(10.dp))
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                loading -> StateBlock(
                    title = "正在解析目录",
                    description = "从书源拉取章节列表…",
                    spinner = true,
                )

                error != null -> StateBlock(
                    title = "目录加载失败",
                    description = error,
                    actionText = "返回重试",
                    onAction = onBack,
                )

                chapters.isEmpty() -> StateBlock(
                    title = "目录为空",
                    description = "该书源规则可能不匹配",
                )

                filtered.isEmpty() -> StateBlock(
                    title = "没有匹配的章节",
                    description = "换个关键词试试",
                )

                else -> LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = 24.dp),
                ) {
                    itemsIndexed(filtered, key = { _, ch -> ch.url + "|" + ch.index }) { pos, ch ->
                        val current = currentChapterUrl != null && ch.url == currentChapterUrl
                        ChapterRow(index = pos + 1, chapter = ch, current = current, cached = cachedUrls.contains(ch.url)) { onOpen(ch) }
                    }
                }
            }
        }
    }
}

@Composable
private fun ChapterRow(
    index: Int,
    chapter: BookChapter,
    current: Boolean,
    cached: Boolean,
    onClick: () -> Unit,
) {
    val primary = MaterialTheme.colorScheme.primary
    val bg = if (current) primary.copy(alpha = 0.10f) else Color.Transparent
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "$index",
            style = MaterialTheme.typography.labelSmall,
            color = if (current) primary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(38.dp),
        )
        Text(
            text = chapter.title.ifBlank { "（无标题）" },
            style = MaterialTheme.typography.bodyMedium,
            color = if (current) primary else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (chapter.isVip || chapter.isPay) {
            Spacer(Modifier.width(8.dp))
            TagPill(if (chapter.isPay) "付费" else "VIP")
        }
        if (cached) {
            Spacer(Modifier.width(8.dp))
            TagPill("已缓存")
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