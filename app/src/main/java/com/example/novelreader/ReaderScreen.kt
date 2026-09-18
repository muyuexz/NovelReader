package com.example.novelreader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.novelreader.analyzeRule.Book
import com.example.novelreader.analyzeRule.BookChapter
import com.example.novelreader.analyzeRule.BookSourceEngine
import com.example.novelreader.ui.ReaderPalette
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import com.example.novelreader.ui.ReaderPalettes
import com.example.novelreader.ui.Stepper

/* ==================================================================== *
 *  阅读页（横向分页 + 仿真翻页）
 *
 *  与旧版最大的区别：正文不再是「一根竖着滚的长条」，而是先按阅读区
 *  尺寸切成一页一页，再由 HorizontalPager 承载。翻页动画由
 *  graphicsLayer 做 3D 旋转（以左侧书脊为轴心），配合书脊阴影，
 *  模拟纸张被掀起 → 绕轴翻过去的过程。
 * ==================================================================== */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ReaderScreen(
    chapter: BookChapter,
    content: String,
    loading: Boolean,
    chapters: List<BookChapter>,
    onOpenChapter: (BookChapter) -> Unit,
    onOpenToc: () -> Unit,
    onCacheRange: (Int, Int) -> Unit = { _, _ -> },
    cache: Map<String, String> = emptyMap(),
    // 第16批：缓存写入本身不触发重组，靠这个自增计数驱动「全文搜索」结果刷新。
    cacheTick: Int = 0,
    initialPage: Int = 0,
    onPageChanged: (Int) -> Unit = {},
    // 第17批：全文搜索整页化后，搜索页要用书源规则补拉未缓存章节的正文。
    book: Book? = null,
    // 第17批：搜索时补拉到的新正文回灌上层缓存，之后阅读页打开即秒开。
    onCacheLoaded: (String, String) -> Unit = { _, _ -> },
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
    // 第 7 批：顶栏两个新入口的状态
    var showCacheDialog by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    var cacheFrom by remember { mutableStateOf("") }
    var cacheTo by remember { mutableStateOf("") }
    // 第9批：打开离线缓存弹窗时，默认把范围填成「第一章 ~ 最后一章」。
    LaunchedEffect(showCacheDialog, chapters.size) {
        if (showCacheDialog && chapters.isNotEmpty()) {
            if (cacheFrom.isBlank()) cacheFrom = "1"
            if (cacheTo.isBlank()) cacheTo = chapters.size.toString()
        }
    }
    var searchKeyword by remember { mutableStateOf("") }
    // 第16批：正文搜索的「跳转 + 高亮」意图。
    // seekChapter = 待跳转的章 url（跳完即清空）；highlight = 当前要在正文里标黄的关键字。
    var seekChapter by remember { mutableStateOf("") }
    var highlight by remember { mutableStateOf("") }
    val palette = ReaderPalettes.firstOrNull { it.id == paletteId } ?: ReaderPalettes.first()

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val textStyle = remember(fontSize, lineHeight) {
        TextStyle(fontSize = fontSize.sp, lineHeight = lineHeight.sp)
    }

    // 当前章在目录中的位置 → 上一章 / 下一章
    val idx = remember(chapter.url, chapters) { chapters.indexOfFirst { it.url == chapter.url } }
    val prev = if (idx > 0) chapters[idx - 1] else null
    val next = if (idx in 0 until chapters.size - 1) chapters[idx + 1] else null

    // 分页结果：首尾各挂一张「上一章 / 下一章」占位页，翻到头就等于换章。
    var pageItems by remember { mutableStateOf(listOf<String>()) }
    var pagesChapter by remember { mutableStateOf("") }
    // 第8批：navLock 如果随 chapter.url 重建，换章瞬间会被重置为 false，
    // 导致边界效应在「旧分页 + 新章」的组合下重复触发，一次连跳多章。
    // 第12批：初始置锁。进入非第一章时 pagerState 的初始 currentPage 恒为 0（首占位页），
    // 会被下方边界检测误判成「往右划翻到了上一章」，于是续读直接跳到上一章最后一页。
    // 首次归位完成后，由「归位」LaunchedEffect 解锁。
    var navLock by remember { mutableStateOf(true) }
    // 第9批：记录「往右划回到上一章」这次换章的目标章 url。归位时若命中，
    // 就落到该章最后一页而不是第一页，这才是正常的「往前翻」体验。
    var enterEndChapter by remember { mutableStateOf<String?>(null) }

    val pagerState = rememberPagerState(pageCount = { pageItems.size.coerceAtLeast(1) })

    // 换章后归位：普通入口回到正文第 1 页（第 0 页是「上一章」占位）；
    // 若是「往右划回到上一章」触发的换章，则落到该章最后一页（末页是「下一章」占位）。
    LaunchedEffect(chapter.url, pagesChapter) {
        if (pagesChapter == chapter.url && pageItems.size > 1) {
            val endPage = (pageItems.size - 2).coerceAtLeast(1)
            // 第16批：正文搜索点进来的章，优先落到关键字所在那一页。
            // pageItems[0] 是「上一章」占位页，所以命中的下标天然就是 1 基页号。
            val seekIdx = if (seekChapter == chapter.url && highlight.isNotBlank()) {
                pageItems.indexOfFirst { it.contains(highlight, ignoreCase = true) }
            } else {
                -1
            }
            // 第13批：续读时回到上次退出那一页（initialPage 为 1 基页号，0 视作第 1 页）。
            val target = when {
                seekIdx in 1..endPage -> seekIdx
                enterEndChapter == chapter.url -> endPage
                else -> initialPage.coerceIn(1, endPage)
            }
            runCatching { pagerState.scrollToPage(target) }
            // 第9批：末尾进入的意图只消费一次，不影响后续换章。
            enterEndChapter = null
            // 第16批：跳转意图同样一次性消费，避免后续换章又被拽回旧页。
            if (seekIdx in 1..endPage) seekChapter = ""
            // 第8批：确认已回到本正文页，才解除换章锁
            navLock = false
        }
    }

    // 第16批：点的是「当前章」里的搜索结果时不会换章，上面那个 effect 不会被触发，
    // 这里单独兜一次——直接跳到本页命中的页号。
    LaunchedEffect(seekChapter, pagesChapter, pageItems.size) {
        if (seekChapter.isNotBlank() && seekChapter == chapter.url &&
            pagesChapter == chapter.url && pageItems.size > 2
        ) {
            val endPage = (pageItems.size - 2).coerceAtLeast(1)
            val p = pageItems.indexOfFirst { it.contains(highlight, ignoreCase = true) }
            if (p in 1..endPage) runCatching { pagerState.scrollToPage(p) }
            seekChapter = ""
        }
    }

    val totalPages = (pageItems.size - 2).coerceAtLeast(1)
    val curPage = pagerState.currentPage.coerceIn(0, totalPages)
    val percent = (((curPage - 1).coerceAtLeast(0)) * 100 / totalPages).coerceIn(0, 100)

    // 第13批：把「当前页」上报给上层落盘，续读才能精确回到退出时那一页。
    // 只在正文页（跳过首尾占位页）且本页归属当前章时上报，避免过渡页污染进度。
    LaunchedEffect(chapter.url, pagesChapter, pagerState.currentPage) {
        if (pagesChapter == chapter.url && pageItems.size > 2 && pagerState.currentPage >= 1) {
            onPageChanged(curPage.coerceIn(1, totalPages))
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(palette.bg)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        // —— 顶部栏（浮层：不再挤压正文高度，点按切换工具条时不重分页、字不跳）——
        AnimatedVisibility(
            visible = barsVisible,
            // 第8批：顶栏必须在正文层之上，否则被每页不透明底色整个盖住（顶栏“消失”）
            modifier = Modifier.align(Alignment.TopCenter).zIndex(1f),
        ) {
            Surface(color = palette.panel) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = chapter.title.ifBlank { "正文" },
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        color = palette.fg,
                        fontSize = 14.sp,
                    )
                    Text(
                        text = if (loading) "--" else "$percent%",
                        color = palette.sub,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(end = 2.dp),
                    )
                    // 第 7 批第 5/7 条：顶栏补两个入口——离线缓存、全文搜索
                    TextButton(onClick = { showCacheDialog = true }) {
                        Text("缓存", color = palette.fg, fontSize = 13.sp)
                    }
                    TextButton(onClick = { showSearch = true }) {
                        Text("搜索", color = palette.fg, fontSize = 13.sp)
                    }
                }
            }
        }

        // —— 正文：横向分页 + 翻页动画（铺满整个阅读区，高度恒定）——
        Box(Modifier.fillMaxSize()) {
            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.fg)
                }
            } else {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val availW = with(density) { (maxWidth - 44.dp).toPx() }
                    val availH = with(density) { (maxHeight - 32.dp).toPx() }
                    val body = indentParagraphs(content.ifBlank { "（正文为空）" })
                    val contentPages = remember(body, availW, availH, textStyle) {
                        paginate(measurer, body, textStyle, availW, availH)
                    }
                    LaunchedEffect(contentPages) {
                        pageItems = listOf("") + contentPages + listOf("")
                        pagesChapter = chapter.url
                    }

                    // 翻到首/末占位页并停稳 → 换章
                    LaunchedEffect(
                        pagerState.currentPage,
                        pageItems.size,
                        chapter.url,
                    ) {
                        // 第 7 批第 6 条：不再等滚动停稳，翻到首/末过渡页立即换章，
                        // 过渡页本身不渲染任何「上一章/下一章」文字，视觉上直接进入下一章。
                        // 第8批：pageItems 必须已属于当前章，否则 currentPage 还是
                        // 上一章的边界值（0 / lastIndex），换章瞬间会重复触发上一/下一章。
                        if (pagesChapter != chapter.url) return@LaunchedEffect
                        if (navLock) return@LaunchedEffect
                        if (pageItems.size < 3) return@LaunchedEffect
                        when (pagerState.currentPage) {
                            0 -> prev?.let { navLock = true; enterEndChapter = it.url; onOpenChapter(it) }
                            pageItems.lastIndex -> next?.let { navLock = true; onOpenChapter(it) }
                        }
                    }

                    if (pageItems.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = palette.fg)
                        }
                    } else {
                        HorizontalPager(
                            state = pagerState,
                            modifier = Modifier.fillMaxSize(),
                        ) { page ->
                            val item = pageItems.getOrNull(page) ?: ""
                            // offset：0 = 正落在屏上，1 = 已翻到左边，-1 = 还在右边等你翻
                            val offset =
                                (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                            val turn = offset.coerceIn(0f, 1f)
                            val turning = offset > 0.01f
                            val spineShadow = with(density) { 26.dp.toPx() }

                            Box(
                                Modifier
                                    .fillMaxSize()
                                    .zIndex(if (turning) 1f else 0f)
                                    .background(palette.bg)
                                    .graphicsLayer {
                                        // 以左侧书脊为轴掀起纸张；二次曲线让文字多留一会儿
                                        transformOrigin = TransformOrigin(0f, 0.5f)
                                        cameraDistance = 14f * density.density
                                        if (turning) rotationY = -88f * turn * turn
                                    },
                            ) {
                                // 书脊阴影（左侧渐隐）
                                Box(
                                    Modifier
                                        .fillMaxSize()
                                        .background(
                                            Brush.horizontalGradient(
                                                colors = listOf(
                                                    palette.sub.copy(alpha = 0.20f),
                                                    Color.Transparent,
                                                ),
                                                startX = 0f,
                                                endX = spineShadow,
                                            ),
                                        ),
                                )
                                when {
                                    // 第 7 批第 6 条：首尾过渡页不再渲染「上一章/下一章」占位内容与章节标题，
                                    // 只留可点的空白层；换章由上面的 LaunchedEffect 立即触发。
                                    page == 0 -> Box(
                                        Modifier
                                            .fillMaxSize()
                                            .clickable(enabled = prev != null) { prev?.let(onOpenChapter) },
                                    )
                                    page == pageItems.lastIndex -> Box(
                                        Modifier
                                            .fillMaxSize()
                                            .clickable(enabled = next != null) { next?.let(onOpenChapter) },
                                    )

                                    else -> Box(
                                        Modifier
                                            .fillMaxSize()
                                            .pointerInput(Unit) {
                                                detectTapGestures {
                                                    if (barsVisible) showPanel = false
                                                    barsVisible = !barsVisible
                                                }
                                            }
                                            .padding(horizontal = 22.dp, vertical = 16.dp),
                                    ) {
                                        Text(
                                            // 第16批：正文里把搜索结果的关键字标黄，跳过来一眼就能看到。
                                            text = remember(item, highlight) { highlightText(item, highlight) },
                                            color = palette.fg,
                                            fontSize = fontSize.sp,
                                            lineHeight = lineHeight.sp,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // —— 设置面板 + 底部翻章栏（浮层）——
        AnimatedVisibility(
            visible = barsVisible,
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(1f),
        ) {
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
                    Column(Modifier.fillMaxWidth()) {
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
                            text = if (idx >= 0) {
                                "${idx + 1}/${chapters.size} · ${curPage.coerceAtLeast(1)}/$totalPages 页"
                            } else {
                                "${curPage.coerceAtLeast(1)}/$totalPages 页"
                            },
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
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        TextButton(
                            onClick = { showPanel = !showPanel },
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("Aa", color = palette.fg, fontSize = 15.sp)
                        }
                        TextButton(
                            onClick = onOpenToc,
                            modifier = Modifier.weight(1f),
                        ) {
                            Text("目录", color = palette.fg, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }
        // ============================================================
        // 第 7 批第 5 条：离线缓存弹窗（输入起止章，一次性缓存区间）
        // ============================================================
        if (showCacheDialog) {
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { showCacheDialog = false },
            ) {
                Surface(
                    color = palette.panel,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp)
                        .fillMaxWidth(),
                ) {
                    Column(Modifier.padding(18.dp)) {
                        Text("离线缓存章节", color = palette.fg, fontSize = 16.sp)
                        Spacer(Modifier.height(12.dp))
                        Row(Modifier.fillMaxWidth()) {
                            OutlinedTextField(
                                value = cacheFrom,
                                onValueChange = { s -> cacheFrom = s.filter { it.isDigit() } },
                                label = { Text("起始章", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                            Spacer(Modifier.width(10.dp))
                            OutlinedTextField(
                                value = cacheTo,
                                onValueChange = { s -> cacheTo = s.filter { it.isDigit() } },
                                label = { Text("结束章", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "共 " + chapters.size + " 章，缓存后可离线阅读",
                            color = palette.sub,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { showCacheDialog = false }) {
                                Text("取消", color = palette.sub)
                            }
                            TextButton(onClick = {
                                val lo = cacheFrom.toIntOrNull() ?: 1
                                val hi = cacheTo.toIntOrNull() ?: chapters.size
                                onCacheRange(lo, hi)
                                showCacheDialog = false
                            }) {
                                Text("开始缓存", color = palette.fg)
                            }
                        }
                    }
                }
            }
        }

        // ============================================================
        // 第17批：正文搜索整页化——弹窗下架，改成整页覆盖式搜索页。
        // 只扫缓存的时代结束：未缓存章节由搜索页用书源规则联网补拉，
        // 命中范围对齐 Legado 的「全书检索」。
        // ============================================================
        if (showSearch) {
            FullTextSearchPage(
                palette = palette,
                book = book,
                chapters = chapters,
                currentChapterUrl = chapter.url,
                currentContent = content,
                cache = cache,
                onCacheLoaded = onCacheLoaded,
                onClose = { showSearch = false },
                onJump = { c, kw ->
                    // 第16批的「跳哪一章 + 高亮什么词」意图照旧交给阅读页消费。
                    highlight = kw
                    seekChapter = c.url
                    showSearch = false
                    onOpenChapter(c)
                },
            )
        }
}
}

/* ------------------------------------------------------------------ *
 *  第16批：正文搜索命中项
 *  count   = 关键字在本章已缓存全文里的出现总次数
 *  firstAt = 首次出现的下标（用来截取结果里的上下文片段；-1 表示没找到）
 * ------------------------------------------------------------------ */
private data class SearchHit(
    val chapter: BookChapter,
    val count: Int,
    val firstAt: Int,
    /** 第17批：命中处上下文片段，整页搜索结果直接展示，不必回头再查缓存。 */
    val snippet: String = "",
)

/* ------------------------------------------------------------------ *
 *  第16批：把一页正文里所有出现的关键字标上底色，
 *  让「搜索结果跳转」之后能立刻看到词在哪。
 * ------------------------------------------------------------------ */
private fun highlightText(text: String, keyword: String): AnnotatedString {
    val kw = keyword.trim()
    if (kw.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        var start = text.indexOf(kw, 0, ignoreCase = true)
        while (start >= 0) {
            append(text.substring(i, start))
            withStyle(SpanStyle(color = Color(0xFFE53935), background = Color(0x40E53935))) {
                append(text.substring(start, start + kw.length))
            }
            i = start + kw.length
            start = text.indexOf(kw, i, ignoreCase = true)
        }
        append(text.substring(i))
    }
}


/* ------------------------------------------------------------------ *
 *  第18批：搜索结果里的关键字标红（仅红色前景，便于一眼扫到）。
 * ------------------------------------------------------------------ */
private fun keywordRed(text: String, keyword: String): AnnotatedString {
    val kw = keyword.trim()
    if (kw.isEmpty() || text.isEmpty()) return AnnotatedString(text)
    return buildAnnotatedString {
        var i = 0
        var start = text.indexOf(kw, 0, ignoreCase = true)
        while (start >= 0) {
            append(text.substring(i, start))
            withStyle(SpanStyle(color = Color(0xFFE53935))) {
                append(text.substring(start, start + kw.length))
            }
            i = start + kw.length
            start = text.indexOf(kw, i, ignoreCase = true)
        }
        append(text.substring(i))
    }
}

/* ------------------------------------------------------------------ *
 *  分页核心：把一整章正文按「阅读区实际宽高 + 当前字号行距」切成页
 *
 *  先让 TextMeasurer 按可用宽度做一次完整排版，拿到每行的真实高度，
 *  再按页高把行累积成页；页边界取该页最后一行的行尾偏移量，
 *  保证换行、段落空行都不会被切坏。
 * ------------------------------------------------------------------ */
/**
 * 段落首行缩进：给每个非空段落行首补两个全角空格。
 *
 * 刻意放在"分页之前"而不是渲染时才缩进——这样 [paginate] 测量到的
 * 换行与行高就是屏幕最终显示的样子，页码、切分点和实际排版天然一致。
 */
private fun indentParagraphs(body: String): String {
    if (body.isBlank()) return body
    return body.split('\n').joinToString("\n") { line ->
        if (line.isBlank()) line else "\u3000\u3000" + line.trimStart()
    }
}

private fun paginate(
    measurer: TextMeasurer,
    body: String,
    style: TextStyle,
    widthPx: Float,
    heightPx: Float,
): List<String> {
    if (body.isBlank() || widthPx <= 1f || heightPx <= 1f) return listOf(body)

    val layout: TextLayoutResult = runCatching {
        measurer.measure(
            text = body,
            style = style,
            constraints = Constraints(maxWidth = widthPx.toInt().coerceAtLeast(1)),
        )
    }.getOrNull() ?: return listOf(body)

    val pages = ArrayList<String>()
    val lineCount = layout.lineCount
    var line = 0
    var start = 0
    while (line < lineCount) {
        var used = 0f
        var j = line
        while (j < lineCount) {
            val lh = layout.getLineBottom(j) - layout.getLineTop(j)
            if (used > 0f && used + lh > heightPx) break
            used += lh
            j++
        }
        val end = if (j >= lineCount) body.length else layout.getLineEnd(j - 1, visibleEnd = false)
        val safeEnd = end.coerceIn(start, body.length)
        pages.add(body.substring(start, safeEnd))
        start = safeEnd
        line = j
    }
    if (pages.isEmpty()) pages.add(body)
    return pages
}

/* ------------------------------------------------------------------ *
 *  第17批：单章关键字计数（全书搜索的判定核心）
 *  count   = 关键字在本章正文里的出现总次数
 *  firstAt = 首次出现下标（-1 表示没命中，调用方会过滤）
 *  snippet = 命中处上下文片段，整页搜索结果直接展示
 * ------------------------------------------------------------------ */
private fun countHits(key: String, c: BookChapter, body: String): SearchHit? {
    if (key.isEmpty() || body.isEmpty()) return null
    var n = 0
    var i = body.indexOf(key, 0, ignoreCase = true)
    val first = i
    while (i >= 0) {
        n++
        i = body.indexOf(key, i + key.length, ignoreCase = true)
    }
    if (n == 0) return null
    val at = first.coerceAtLeast(0)
    val snippet = body.substring(
        (at - 14).coerceAtLeast(0),
        (at + 46).coerceAtMost(body.length),
    )
    return SearchHit(c, n, at, snippet)
}

/* ------------------------------------------------------------------ *
 *  第17批：全文搜索整页
 *
 *  - 形态：整页覆盖层（第16批的弹窗下架），阅读页状态不销毁，返回无缝。
 *  - 范围：全书。已缓存章节直读缓存；未缓存章节用书源规则联网补拉，
 *          拉到的正文回灌上层缓存，之后阅读页打开即秒开。
 *  - 性能：搜索跑在后台协程 + 并发限流（4 路），输入 350ms 防抖，
 *          章节进度与结果节流回写，绝不堵主线程。
 * ------------------------------------------------------------------ */
@Composable
private fun FullTextSearchPage(
    palette: ReaderPalette,
    book: Book?,
    chapters: List<BookChapter>,
    currentChapterUrl: String,
    currentContent: String,
    cache: Map<String, String>,
    onCacheLoaded: (String, String) -> Unit,
    onClose: () -> Unit,
    onJump: (BookChapter, String) -> Unit,
) {
    var kw by remember { mutableStateOf("") }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(0) }
    var totalCount by remember { mutableStateOf(0) }
    var hits by remember { mutableStateOf(emptyList<SearchHit>()) }
    // 第18批：结果列表滚动状态 + 一键跳首/尾。
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val atBottom = !listState.canScrollForward && hits.isNotEmpty()

    val source = book?.source

    // 关键字一变，上一个搜索协程随 LaunchedEffect 自动取消；
    // 停手 350ms 才真正开搜，避免边打字边全书扫描。
    LaunchedEffect(kw, chapters.size, source) {
        val key = kw.trim()
        if (key.isEmpty()) {
            scanning = false
            scanned = 0
            totalCount = 0
            hits = emptyList()
            return@LaunchedEffect
        }
        delay(350)
        scanning = true
        scanned = 0
        totalCount = 0
        hits = emptyList()

        val list = chapters
        val total = list.size
        if (total == 0) {
            scanning = false
            return@LaunchedEffect
        }
        val found = ArrayList<SearchHit>()
        // 第18批：结果按章节正序排序用的索引表（章节 url 到书内序号）。
        val order = list.withIndex().associate { it.value.url to it.index }
        val sem = Semaphore(4)
        var localDone = 0
        var lastShown = 0

        coroutineScope {
            list.map { c ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val cached = cache[c.url]
                        val body = when {
                            c.url == currentChapterUrl && currentContent.isNotBlank() -> currentContent
                            !cached.isNullOrEmpty() -> cached
                            source != null -> runCatching {
                                BookSourceEngine.getContent(source, book!!, c)
                            }.getOrDefault("")
                            else -> ""
                        }
                        // 补拉到的正文回灌缓存（不覆盖已有内容）。
                        if (body.isNotEmpty() && c.url != currentChapterUrl && cached.isNullOrEmpty()) {
                            onCacheLoaded(c.url, body)
                        }
                        val hit = countHits(key, c, body)
                        if (hit != null) {
                            synchronized(found) { found.add(hit) }
                        }
                        localDone++
                        // 节流：每 16 章才回写一次进度/结果，避免上千次重组。
                        if (localDone - lastShown >= 16 || localDone == total) {
                            lastShown = localDone
                            val snap = synchronized(found) { found.sortedBy { order[it.chapter.url] ?: Int.MAX_VALUE } }
                            val d = localDone
                            withContext(Dispatchers.Main) {
                                scanned = d
                                hits = snap
                                totalCount = snap.sumOf { it.count }
                            }
                        }
                    }
                }
            }.awaitAll()
        }
        scanning = false
        val snap = synchronized(found) { found.sortedBy { order[it.chapter.url] ?: Int.MAX_VALUE } }
        hits = snap
        totalCount = snap.sumOf { it.count }
        scanned = total
    }

    Box(
        Modifier
            .fillMaxSize()
            .zIndex(3f)
            .background(palette.bg),
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.systemBars),
        ) {
            // —— 顶栏 ——
            Surface(color = palette.panel) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = onClose) {
                        Text("‹ 返回", color = palette.fg, fontSize = 14.sp)
                    }
                    Text(
                        text = "全文搜索",
                        color = palette.fg,
                        fontSize = 15.sp,
                        modifier = Modifier.weight(1f),
                    )
                    if (scanning) {
                        Text(
                            text = scanned.toString() + "/" + chapters.size,
                            color = palette.sub,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(end = 10.dp),
                        )
                    }
                    TextButton(onClick = {
                        scope.launch {
                            if (atBottom) listState.scrollToItem(0)
                            else listState.scrollToItem(hits.lastIndex.coerceAtLeast(0))
                        }
                    }) {
                        Text(
                            text = if (atBottom) "⇧ 开头" else "⇩ 末尾",
                            color = palette.fg,
                            fontSize = 13.sp,
                        )
                    }
                }
            }

            Column(Modifier.padding(horizontal = 14.dp)) {
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = kw,
                    onValueChange = { kw = it },
                    label = { Text("关键字", fontSize = 12.sp) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = when {
                        kw.isBlank() -> "输入关键字，全书范围检索（已缓存直读，其余联网补拉）"
                        scanning -> "正在扫描 " + scanned + "/" + chapters.size +
                            " 章，已命中 " + totalCount + " 次"
                        else -> "全书命中 " + totalCount + " 次，分布在 " + hits.size + " 章"
                    },
                    color = palette.sub,
                    fontSize = 12.sp,
                )
                Spacer(Modifier.height(4.dp))
            }

            if (hits.isEmpty()) {
                Box(
                    Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = when {
                            scanning -> "扫描中…"
                            kw.isBlank() -> "全书搜索"
                            else -> "没有命中"
                        },
                        color = palette.sub,
                        fontSize = 13.sp,
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxWidth().weight(1f).padding(horizontal = 14.dp),
                ) {
                    items(hits, key = { it.chapter.url }) { h ->
                        val c = h.chapter
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onJump(c, kw.trim()) }
                                .padding(vertical = 10.dp),
                        ) {
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = keywordRed("第 " + (chapters.indexOf(c) + 1) + " 章 " + c.title, kw.trim()),
                                    color = palette.fg,
                                    fontSize = 14.sp,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = h.count.toString() + " 次",
                                    color = palette.sub,
                                    fontSize = 12.sp,
                                )
                            }
                            if (h.snippet.isNotBlank()) {
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = keywordRed(h.snippet, kw.trim()),
                                    color = palette.sub,
                                    fontSize = 12.sp,
                                    maxLines = 2,
                                )
                            }
                            Spacer(Modifier.height(8.dp))
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(palette.divider),
                            )
                        }
                    }
                }
            }
        }
    }
}
