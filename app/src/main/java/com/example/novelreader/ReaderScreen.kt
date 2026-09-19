package com.example.novelreader

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
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
import androidx.compose.ui.text.font.FontWeight
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
import com.example.novelreader.analyzeRule.ruleCompletenessOf
import com.example.novelreader.analyzeRule.sourceMetaLine
import com.example.novelreader.ui.ReaderPalette
import com.example.novelreader.ui.TagPill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.atomic.AtomicInteger
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
// 第 32 批：中文章节站正文常自带一行「第X章 …」标题，与目录标题重复，这里识别并剥离。
private val RE_CHAPTER_HEAD = Regex("^第\\s*[0-9０-９零一二三四五六七八九十百千万两]{1,10}\\s*[章節节]")

/**
 * 第 32 批：若正文第一行本身就是章标题（短行、以「第X章」开头），
 * 就剥掉这一行——首行标题统一由目录 label 提供，避免同一个标题出现两次。
 */
private fun stripLeadingChapterHead(text: String): String {
    val t = text.trimStart()
    val nl = t.indexOf('\n')
    if (nl <= 0) return t
    val first = t.substring(0, nl).trim()
    if (first.isEmpty() || first.length > 40) return t
    if (!RE_CHAPTER_HEAD.containsMatchIn(first)) return t
    val rest = t.substring(nl + 1).trimStart()
    return if (rest.isBlank()) t else rest
}

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
    // 第 26 批：换源——把当前书切到另一个书源继续读（上层重拉目录并定位同章）。
    onSwitchSource: (Book) -> Unit = {},
    // 第 55 批：换源弹窗元信息「预取完成」信号；兄弟源元信息异步回填，靠它驱动弹窗重组。
    metaTick: Int = 0,
    // 第 55 批：弹窗打开时把「当前源 + 兄弟源」交给上层批量预取，与搜索结果同源同量。
    onPrefetchMeta: (List<Book>) -> Unit = {},
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
    // 第 26 批：换源弹窗开关（列同书同作者的各个书源记录）
    var showSwitchSource by remember { mutableStateOf(false) }
    // 第 55 批需求④⑤：弹窗一打开就批量预取兄弟源元信息。书架续读进来的兄弟源是
    // 「裸壳重建」（status/lastChapter 全空），不预取就只能显示 "—"，与搜索结果对不上。
    LaunchedEffect(showSwitchSource) {
        if (showSwitchSource) {
            val b = book
            if (b != null) onPrefetchMeta(listOf(b) + b.altSources)
        }
    }
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
    // 第20批：搜索结果导航模式的状态。
    // navHits/navKw 记住「这一轮搜索的全部结果 + 关键字」，
    // navIndex = 当前定位到第几条（-1 表示未进入导航模式）。
    var navHits by remember { mutableStateOf(emptyList<SearchHit>()) }
    var navKw by remember { mutableStateOf("") }
    var navIndex by remember { mutableStateOf(-1) }
    // 导航模式：已点过某条结果、且没停在搜索结果页时才生效。
    val navActive = navIndex in navHits.indices && !showSearch
    // 跳到第 i 条结果：记住下标、标红关键字、翻到目标章。
    val navJumpTo: (Int) -> Unit = { i ->
        if (i in navHits.indices) {
            navIndex = i
            highlight = navKw
            seekChapter = navHits[i].chapter.url
            barsVisible = true
            onOpenChapter(navHits[i].chapter)
        }
    }
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

    // 第 28 批：首章的「上一章」占位页 / 末章的「下一章」占位页本身没有内容可翻，
    // 但 HorizontalPager 依然能滑进去 → 屏幕一片空白（用户诉求：干脆划不动）。
    // 这里只在「确实没有上一章 / 下一章」时对边界做钳制，把越界的拖拽立刻拽回正文页，
    // 不动 pageItems 的首尾占位结构——totalPages / seekIdx / 页码上报全都依赖它的 1 基页号。
    LaunchedEffect(pagerState, prev, next, pageItems.size) {
        if (pageItems.size < 3) return@LaunchedEffect
        if (prev != null && next != null) return@LaunchedEffect
        snapshotFlow { pagerState.currentPage to pagerState.currentPageOffsetFraction }
            .collect { (page, frac) ->
                val last = pageItems.lastIndex
                // frac > 0：正朝「下一章」方向拖；frac < 0：正朝「上一章」方向拖。
                val blockedHead = prev == null && (page == 0 || (page == 1 && frac < -0.02f))
                val blockedTail = next == null && (page == last || (page == last - 1 && frac > 0.02f))
                when {
                    blockedHead -> runCatching { pagerState.scrollToPage(1) }
                    blockedTail -> runCatching { pagerState.scrollToPage(last - 1) }
                }
            }
    }

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
    // 第 30 批：顶栏「当前章已阅读百分比」已下线，改到页脚右下角显示
    // 「当前页数 + 全书阅读百分比」。全书进度 = (当前章下标 + 章内页占比) / 总章数。
    val chapterProgress = curPage.coerceIn(0, totalPages).toFloat() / totalPages.toFloat()
    val bookPercent = if (idx >= 0 && chapters.isNotEmpty()) {
        (((idx + chapterProgress) / chapters.size) * 100f).coerceIn(0f, 100f).toInt()
    } else {
        0
    }

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
                    // 第 7 批第 5/7 条：顶栏补两个入口——离线缓存、全文搜索
                    TextButton(onClick = { showCacheDialog = true }) {
                        Text("缓存", color = palette.fg, fontSize = 13.sp)
                    }
                    TextButton(onClick = { showSearch = true }) {
                        Text("搜索", color = palette.fg, fontSize = 13.sp)
                    }
                    // 第 26 批：换源入口——弹窗列出同书同作者的各书源记录，选中即切源续读
                    TextButton(onClick = { showSwitchSource = true }) {
                        Text("换源", color = palette.fg, fontSize = 13.sp)
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
                    // 第 33 批：底部新增「正文 / 长横线 / 页码」留白带，正文可用高再扣 24dp。
                    val availH = with(density) { (maxHeight - 56.dp).toPx() }
                    // 第 30 批：每章第一页开头标出当前目录章节（顶格、与正文空一行）。
                    // 第 32 批：目录标题若已自带「第X章」，不再叠加序号，避免「第3章第3章…」。
                    val rawTitle = chapter.title.trim()
                    // 第 37 批 C刀：章标题拼装是纯函数，键只有 (标题, 目录序号)。
                    val chapterLabel = remember(rawTitle, idx) {
                    if (rawTitle.isNotBlank()) {
                    if (RE_CHAPTER_HEAD.containsMatchIn(rawTitle.take(12))) rawTitle
                    else if (idx >= 0) "第 " + (idx + 1) + " 章 " + rawTitle else rawTitle
                    } else {
                    ""
                    }
                    }
                    // 第 32 批：正文首行常自带章标题，先剥离再拼 label，否则标题被渲染两次。
                    // 第 37 批 C刀：整块预处理（剥章头 → 段落缩进 → 拼 label）产物是整章大小的
                    // 新字符串，原来写在组合体里 —— 翻页动画期间 pagerState 每帧变化都会重组这里，
                    // 等于每秒把整章正文切分/拼接几十遍。改用 remember 钉死，只在换章/换字号时重算。
                    val body = remember(content, chapterLabel) {
                    val bodyText = if (chapterLabel.isBlank()) content.ifBlank { "（正文为空）" }
                    else stripLeadingChapterHead(content.ifBlank { "（正文为空）" })
                    indentParagraphs(bodyText).let { t ->
                    if (chapterLabel.isBlank()) t else chapterLabel + "\n\n" + t
                    }
                    }
                    // 第 37 批 C刀：分页（整章 TextMeasurer 排版 → 按页高切行成页）从组合期挪进协程。
                    // 组合期只读 state，不再同步测量堵帧；后台测量若被系统限制而悄悄退化成单页，
                    // 则在主线程重算一次兜底（注意这里仍在协程里，不是组合期）。
                    // 旧页签保留到新页签算完 —— 下方的换章/翻页 effect 全部以 pagesChapter 为闸，
                    // 计算期间它们不会误触发，不会出现「连跳多章」。
                    val pagesState = remember { mutableStateOf<List<String>?>(null) }
                    LaunchedEffect(body, availW, availH, textStyle) {
                    val bg = withContext(Dispatchers.Default) {
                    runCatching { paginate(measurer, body, textStyle, availW, availH, skipCache = true) }.getOrNull()
                    }
                    pagesState.value =
                    if (bg == null || (bg.size == 1 && body.length > 1200)) {
                    paginate(measurer, body, textStyle, availW, availH)
                    } else {
                    bg
                    }
                    }
                    val contentPages = pagesState.value
                    LaunchedEffect(contentPages) {
                    val pages = contentPages ?: return@LaunchedEffect
                    pageItems = listOf("") + pages + listOf("")
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

                    // 第 32 批：正文区右下角常显（对齐 Legado）——本章页码/总页数 + 全书已阅读百分比。
                    // 顶栏/底栏是浮层，此浮标固定吊在正文区右下角；底栏弹出时被底栏盖住，不重复显示。
                    // 第 33 批：正文与页码之间用一条长横线分隔，线上下各留距离（上约 13dp / 下 8dp）。
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .fillMaxWidth()
                            .padding(start = 22.dp, end = 22.dp, bottom = 4.dp)
                            .zIndex(2f),
                        horizontalAlignment = Alignment.End,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .height(1.dp)
                                .background(palette.sub.copy(alpha = 0.35f)),
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = curPage.coerceAtLeast(1).toString() + "/" + totalPages + " " + bookPercent + "%",
                            color = palette.sub,
                            fontSize = 11.sp,
                        )
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
                                            .padding(start = 22.dp, end = 22.dp, top = 16.dp, bottom = 40.dp),
                                    ) {
                                        Text(
                                            // 第16批：正文里把搜索结果的关键字标黄，跳过来一眼就能看到。
                                            // 第 30 批：每章第一页的章首标题加粗，与正文区分（只加粗、不改字号，
                                            // 行高仍由 TextStyle.lineHeight 决定，分页结果不变）。
                                            text = remember(item, highlight, page, chapterLabel) {
                                                if (page == 1 && chapterLabel.isNotBlank() && item.startsWith(chapterLabel)) {
                                                    buildAnnotatedString {
                                                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                                                            append(chapterLabel)
                                                        }
                                                        append(highlightText(item.removePrefix(chapterLabel), highlight))
                                                    }
                                                } else {
                                                    highlightText(item, highlight)
                                                }
                                            },
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
            visible = barsVisible && !navActive,
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
                        // 第 32 批：页码 + 百分比已移到正文区右下角常显，此处不再重复。
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
        // 第 26 批：换源弹窗
        // 与离线缓存弹窗同构（同款遮罩 + 圆角面板）。列表首条是当前源，
        // 其余来自搜索聚合时挂上的 book.altSources，每条给出「源名 + 状态/最新章节」。
        // ============================================================
        if (showSwitchSource) {
            val base = book
            // 第 55 批：metaTick 参与——预取完成即重建列表实例，驱动弹窗重组刷新元信息。
            val options: List<Book> = remember(metaTick, base) {
                if (base == null) emptyList() else listOf(base) + base.altSources
            }
            Box(
                Modifier
                    .fillMaxSize()
                    .zIndex(2f)
                    .background(Color.Black.copy(alpha = 0.45f))
                    .clickable { showSwitchSource = false },
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
                        Text("切换书源", color = palette.fg, fontSize = 16.sp)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = (base?.name ?: "").ifBlank { "当前书籍" } +
                                (base?.author?.takeIf { it.isNotBlank() }?.let { " · " + it } ?: "") +
                                // 第44批需求④：标题副行补上「可选源数量」，心里有数。
                                (if (options.size > 1) " · ${options.size} 个源可选" else ""),
                            color = palette.sub,
                            fontSize = 12.sp,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(12.dp))
                        if (options.size <= 1) {
                            Text(
                                text = "这本书暂时只有 1 个书源记录。可到「发现」页搜索同名书，" +
                                    "命中多个书源后再进来切换。",
                                color = palette.sub,
                                fontSize = 12.sp,
                            )
                        } else {
                            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                                itemsIndexed(
                                    items = options,
                                    key = { i, b -> "ss-" + i + "-" + b.bookUrl + "@" + b.originName },
                                ) { i, opt ->
                                    // 第45批刀C：当前源改按「对象身份」判定 —— 胜出源以对象替换
                                    // 成为当前书，不再有字段搬回造成的身份重合，也不会让字段相等的
                                    // 兄弟源被误标成「当前」。base 自身是 options 首项，恒判当前。
                                    val cur = base != null && opt === base
                                    Column(
                                        Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                showSwitchSource = false
                                                if (!cur) onSwitchSource(opt)
                                            }
                                            .padding(vertical = 10.dp),
                                    ) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(
                                                text = opt.originName.ifBlank {
                                                    opt.origin.ifBlank { "未知书源" }
                                                },
                                                color = if (cur) palette.sub else palette.fg,
                                                fontSize = 14.sp,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f),
                                            )
                                            if (cur) TagPill("当前", color = palette.sub)
                                            // 第44批需求④：非当前、且规则完备度最高（有目录+详情）的源给「推荐」标记。
                                            else if (ruleCompletenessOf(opt.source) == 0) {
                                                TagPill("推荐", color = palette.fg)
                                            }
                                        }
                                        Spacer(Modifier.height(3.dp))
                                        Text(
                                            text = listOfNotNull(
                                                opt.status?.takeIf { it.isNotBlank() },
                                                opt.lastChapter?.takeIf { it.isNotBlank() }
                                                    ?.let { "最新 " + it },
                                            ).joinToString(" · ").ifBlank { "—" },
                                            color = palette.sub,
                                            fontSize = 12.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        // 第44批需求④：补一行「域名 · 章节数 · 规则完备度」，选源前有据可依。
                                        Spacer(Modifier.height(2.dp))
                                        Text(
                                            text = sourceMetaLine(opt),
                                            color = palette.sub.copy(alpha = 0.75f),
                                            fontSize = 10.sp,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(Modifier.fillMaxWidth()) {
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = { showSwitchSource = false }) {
                                Text("取消", color = palette.sub)
                            }
                        }
                    }
                }
            }
        }
        // ============================================================
        // 第20批：搜索结果导航模式。
        // 跳转定位后：正文中间两侧是「上一条 / 下一条结果」，
        // 底部导航条给出「结果」（回到搜索页，结果照旧保留）与「退出」
        // （清除正文标红 + 两侧按钮）。
        // ============================================================
        if (navActive) {
            Box(
                Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 8.dp)
                    .zIndex(2f)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(palette.panel.copy(alpha = 0.92f))
                    .clickable { navJumpTo(navIndex - 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "‹",
                    color = if (navIndex > 0) palette.fg else palette.fg.copy(alpha = 0.3f),
                    fontSize = 22.sp,
                )
            }
            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 8.dp)
                    .zIndex(2f)
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(palette.panel.copy(alpha = 0.92f))
                    .clickable { navJumpTo(navIndex + 1) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "›",
                    color = if (navIndex < navHits.size - 1) palette.fg else palette.fg.copy(alpha = 0.3f),
                    fontSize = 22.sp,
                )
            }
        }
        AnimatedVisibility(
            visible = navActive && barsVisible,
            modifier = Modifier.align(Alignment.BottomCenter).zIndex(2f),
        ) {
            Surface(color = palette.panel) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(onClick = { showSearch = true }) {
                        Text("结果", color = palette.fg, fontSize = 14.sp)
                    }
                    Text(
                        text = "搜索结果: " + (navIndex + 1) + "/" + navHits.size +
                            "  当前章节: 第" + (chapters.indexOfFirst { it.url == chapter.url } + 1) +
                            "章 " + chapter.title,
                        color = palette.sub,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = {
                        navIndex = -1
                        navHits = emptyList()
                        navKw = ""
                        highlight = ""
                    }) {
                        Text("退出", color = palette.fg, fontSize = 14.sp)
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
                initialKw = navKw,
                initialHits = navHits,
                onClose = { showSearch = false },
                onJump = { list, index, kw ->
                    // 第20批：把整份结果与当前下标交回阅读页，进入导航模式。
                    navHits = list
                    navKw = kw
                    navIndex = index
                    highlight = kw
                    seekChapter = list.getOrNull(index)?.chapter?.url ?: ""
                    showSearch = false
                    list.getOrNull(index)?.let { onOpenChapter(it.chapter) }
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
    // 第 37 批 C刀：从后台线程调用时必须置 true —— TextMeasurer 内部 TextLayoutCache
    // 是非线程安全的 LruCache，后台测量与主线程兜底重算交错会踩缓存竞态。
    skipCache: Boolean = false,
): List<String> {
    if (body.isBlank() || widthPx <= 1f || heightPx <= 1f) return listOf(body)

    val layout: TextLayoutResult = runCatching {
        measurer.measure(
            text = body,
            style = style,
            constraints = Constraints(maxWidth = widthPx.toInt().coerceAtLeast(1)),
            skipCache = skipCache,
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
    initialKw: String,
    initialHits: List<SearchHit>,
    onClose: () -> Unit,
    onJump: (List<SearchHit>, Int, String) -> Unit,
) {
    var kw by remember { mutableStateOf(initialKw) }
    var scanning by remember { mutableStateOf(false) }
    var scanned by remember { mutableStateOf(if (initialHits.isNotEmpty()) chapters.size else 0) }
    var totalCount by remember { mutableStateOf(initialHits.sumOf { it.count }) }
    var hits by remember { mutableStateOf(initialHits) }
    // 第19批：失败章节数（覆盖度反馈用）。
    var failed by remember { mutableStateOf(0) }
    // 第20批：本轮已扫完的关键字。带着旧结果从搜索结果页返回时跳过重扫。
    var scannedKw by remember { mutableStateOf(if (initialHits.isNotEmpty()) initialKw else "") }
    val ctx = LocalContext.current
    // 第19批：内层返回拦截。全文搜索是阅读页内部的覆盖层，
    // 系统返回手势在这里先被本回调吃掉，只关闭搜索页、留在阅读页；
    // 否则会落到 MainActivity 的回退链上，把阅读页一起弹回详情页。
    BackHandler(enabled = true) { onClose() }
    // 第18批：结果列表滚动状态 + 一键跳首/尾。
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    //第38批 E刀：滚动状态派生值。canScrollForward 是 State，直接在函数体里读，
    //每滚一帧本面板都要重组一次；派生化之后只在「到底 / 没到底」翻转时才重组。
    val atBottom by remember { derivedStateOf { !listState.canScrollForward && hits.isNotEmpty() } }

    val source = book?.source

    // 关键字一变，上一个搜索协程随 LaunchedEffect 自动取消；
    // 停手 350ms 才真正开搜，避免边打字边全书扫描。
    LaunchedEffect(kw, chapters.size, source) {
        val key = kw.trim()
        // 第20批：带旧结果返回、关键字未变时直接跳过，结果照旧保留。
        if (key.isNotEmpty() && key == scannedKw) return@LaunchedEffect
        if (key.isEmpty()) {
            scanning = false
            scanned = 0
            totalCount = 0
            hits = emptyList()
            scannedKw = ""
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
        // 第19批：本轮补拉失败计数（IO 线程写入，用原子量）。
        val failCount = AtomicInteger(0)
        failed = 0
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
                        // 第19批：三级回退 —— 会话缓存 → 磁盘缓存 → 联网补拉（带重试）。
                        // 磁盘缓存让覆盖度跨会话累积：每轮搜索都在上一轮成果上补齐缺失章节，
                        // 命中数因此能持续向全书真实值收敛，而不是每轮原样复现同一批失败。
                        val fromDisk = if (cached.isNullOrEmpty()) ChapterDiskCache.get(ctx, c.url) else null
                        val body = when {
                            c.url == currentChapterUrl && currentContent.isNotBlank() -> currentContent
                            !cached.isNullOrEmpty() -> cached
                            fromDisk != null -> {
                                onCacheLoaded(c.url, fromDisk)
                                fromDisk
                            }
                            source != null -> {
                                // 最多 3 次尝试，失败退避 300ms / 800ms；
                                // 仍拿不到就计入失败数，由顶栏覆盖度如实报出来。
                                var got = ""
                                var attempt = 0
                                while (attempt < 3 && got.isEmpty()) {
                                    if (attempt > 0) delay(if (attempt == 1) 300L else 800L)
                                    got = runCatching {
                                        BookSourceEngine.getContent(source, book!!, c)
                                    }.getOrDefault("")
                                    attempt++
                                }
                                if (got.isEmpty()) failCount.incrementAndGet()
                                got
                            }
                            else -> {
                                failCount.incrementAndGet()
                                ""
                            }
                        }
                        // 补拉到的正文回灌缓存（不覆盖已有内容）。
                        if (body.isNotEmpty() && c.url != currentChapterUrl && cached.isNullOrEmpty()) {
                            onCacheLoaded(c.url, body)
                        // 第19批：同步落盘，下一轮搜索直接复用，不再重下。
                        ChapterDiskCache.put(ctx, c.url, body)
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
                                failed = failCount.get()
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
        failed = failCount.get()
        scanned = total
        scannedKw = key
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
                        else -> "全书命中 " + totalCount + " 次，分布在 " + hits.size + " 章（已扫 " +
                            scanned + "/" + chapters.size + " 章，失败 " + failed + " 章）"
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
                    // 第 24 批：同一章 URL 可能被多源重复命中，key 必须唯一。
                    itemsIndexed(
                        items = hits,
                        key = { index, h -> "hit-" + index + "-" + h.chapter.url },
                    ) { _, h ->
                        val c = h.chapter
                        Column(
                            Modifier
                                .fillMaxWidth()
                                .clickable { onJump(hits, hits.indexOfFirst { it.chapter.url == h.chapter.url }, kw.trim()) }
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
