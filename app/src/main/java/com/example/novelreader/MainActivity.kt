package com.example.novelreader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.foundation.layout.navigationBars
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
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
import com.example.novelreader.ui.Stepper
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
    // 记录最近阅读的章节，返回目录时可高亮
    var lastChapterUrl by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        val info = withContext(Dispatchers.IO) {
            SourceRepository.ensureLoaded(context)
            SourceRepository.totalCount to SourceRepository.parseFailedCount
        }
        sourceCount = info.first
        sourceFailed = info.second
        sourcesLoaded = true
    }

    // 打开某本书 → 拉取目录
    val openBook: (Book) -> Unit = { book ->
        currentBook = book
        chapters = emptyList()
        tocError = null
        lastChapterUrl = null
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
    }

    // 正文读取统一入口：首次点章 / 上一章 / 下一章共用；带防串台守卫。
    val loadChapter: (BookChapter) -> Unit = load@{ ch ->
        val b = currentBook ?: return@load
        val src = b.source ?: return@load
        currentChapter = ch
        lastChapterUrl = ch.url
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

    val openBookNow = currentBook
    val openChapter = currentChapter

    when {
        openBookNow != null && openChapter != null -> ReaderScreen(
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

        openBookNow != null -> TocScreen(
            book = openBookNow,
            chapters = chapters,
            currentChapterUrl = lastChapterUrl,
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
            onOpen = openBook,
        )
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
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.navigationBars),
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
            Text("轻阅读", style = MaterialTheme.typography.headlineMedium, color = Color.White)
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
            onBack = onBack,
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
                        ChapterRow(index = pos + 1, chapter = ch, current = current) { onOpen(ch) }
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
    }
}

/* ==================================================================== *
 *  阅读页：5 套底色 + 沉浸式点击隐藏栏 + 字号/行距/底色设置
 * ==================================================================== */
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
    var paletteId by remember {
        mutableStateOf(
            prefs.getString("palette", null)
                ?: if (prefs.getBoolean("nightMode", false)) "black" else "paper",
        )
    }
    var showPanel by remember { mutableStateOf(false) }
    var barsVisible by remember { mutableStateOf(true) }
    val palette = ReaderPalettes.firstOrNull { it.id == paletteId } ?: ReaderPalettes.first()

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

    Column(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // —— 顶部栏 ——
        AnimatedVisibility(visible = barsVisible) {
            Surface(color = palette.panel) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onBack) {
                        Text("‹ 目录", color = palette.sub, fontSize = 14.sp)
                    }
                    Text(
                        text = chapter.title.ifBlank { "正文" },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.fg,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = if (loading) "--" else "${(progress * 100).toInt()}%",
                        color = palette.sub,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 6.dp),
                    )
                    TextButton(onClick = { showPanel = !showPanel }) {
                        Text("Aa", color = if (showPanel) palette.fg else palette.sub, fontSize = 15.sp)
                    }
                }
            }
        }

        // —— 正文（点击切换上下栏显隐）——
        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.fg)
                }
            } else {
                Text(
                    text = content.ifBlank { "（正文为空）" },
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                        .pointerInput(Unit) {
                            detectTapGestures {
                                if (barsVisible) showPanel = false
                                barsVisible = !barsVisible
                            }
                        }
                        .padding(horizontal = 22.dp, vertical = 16.dp),
                    color = palette.fg,
                    fontSize = fontSize.sp,
                    lineHeight = lineHeight.sp,
                )
            }
        }

        // —— 设置面板 + 底部翻章栏 ——
        AnimatedVisibility(visible = barsVisible) {
            Column {
                if (showPanel) {
                    Surface(color = palette.panel) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 10.dp)) {
                            Stepper(
                                label = "字号",
                                valueText = "$fontSize",
                                canDec = fontSize > 13,
                                canInc = fontSize < 30,
                                onDec = {
                                    fontSize -= 1
                                    prefs.edit().putInt("fontSize", fontSize).apply()
                                },
                                onInc = {
                                    fontSize += 1
                                    prefs.edit().putInt("fontSize", fontSize).apply()
                                },
                                fg = palette.fg,
                                sub = palette.sub,
                            )
                            Spacer(Modifier.height(4.dp))
                            Stepper(
                                label = "行距",
                                valueText = "$lineHeight",
                                canDec = lineHeight > 20,
                                canInc = lineHeight < 48,
                                onDec = {
                                    lineHeight -= 1
                                    prefs.edit().putInt("lineHeight", lineHeight).apply()
                                },
                                onInc = {
                                    lineHeight += 1
                                    prefs.edit().putInt("lineHeight", lineHeight).apply()
                                },
                                fg = palette.fg,
                                sub = palette.sub,
                            )
                            Spacer(Modifier.height(12.dp))

                            // 底色选择：5 套预设
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "底色",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = palette.fg,
                                    modifier = Modifier.width(48.dp),
                                )
                                ReaderPalettes.forEach { p ->
                                    val selected = p.id == palette.id
                                    Box(
                                        modifier = Modifier
                                            .padding(end = 12.dp)
                                            .size(30.dp)
                                            .clip(CircleShape)
                                            .background(p.bg)
                                            .border(
                                                width = if (selected) 2.dp else 1.dp,
                                                color = if (selected) palette.fg else palette.divider,
                                                shape = CircleShape,
                                            )
                                            .clickable {
                                                paletteId = p.id
                                                prefs.edit()
                                                    .putString("palette", p.id)
                                                    .putBoolean("nightMode", p.id == "black")
                                                    .apply()
                                            },
                                    )
                                }
                            }
                            Spacer(Modifier.height(6.dp))
                        }
                    }
                }

                Surface(color = palette.panel) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { prev?.let(onOpenChapter) },
                            enabled = prev != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "‹ 上一章",
                                color = if (prev != null) palette.fg else palette.sub,
                                fontSize = 14.sp,
                            )
                        }
                        Text(
                            text = if (idx >= 0) "${idx + 1}/${chapters.size}" else "",
                            color = palette.sub,
                            fontSize = 12.sp,
                        )
                        TextButton(
                            onClick = { next?.let(onOpenChapter) },
                            enabled = next != null,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text(
                                "下一章 ›",
                                color = if (next != null) palette.fg else palette.sub,
                                fontSize = 14.sp,
                            )
                        }
                    }
                }
            }
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