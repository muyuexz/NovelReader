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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.example.novelreader.analyzeRule.BookChapter
import com.example.novelreader.ui.ReaderPalette
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
    var navLock by remember(chapter.url) { mutableStateOf(false) }

    val pagerState = rememberPagerState(pageCount = { pageItems.size.coerceAtLeast(1) })

    // 换章后回到正文第 1 页（第 0 页是「上一章」占位）
    LaunchedEffect(chapter.url, pagesChapter) {
        if (pagesChapter == chapter.url && pageItems.size > 1) {
            runCatching { pagerState.scrollToPage(1) }
        }
    }

    val totalPages = (pageItems.size - 2).coerceAtLeast(1)
    val curPage = pagerState.currentPage.coerceIn(0, totalPages)
    val percent = (((curPage - 1).coerceAtLeast(0)) * 100 / totalPages).coerceIn(0, 100)

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
                        text = if (loading) "--" else "$percent%",
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

        // —— 正文：横向分页 + 翻页动画 ——
        Box(Modifier.weight(1f).fillMaxWidth()) {
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
                        pagerState.isScrollInProgress,
                        pageItems.size,
                        chapter.url,
                    ) {
                        if (navLock || pagerState.isScrollInProgress) return@LaunchedEffect
                        if (pageItems.size < 3) return@LaunchedEffect
                        when (pagerState.currentPage) {
                            0 -> prev?.let { navLock = true; onOpenChapter(it) }
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
                                    page == 0 -> JumpPage(
                                        label = "上一章",
                                        target = prev,
                                        palette = palette,
                                        onGo = { t -> onOpenChapter(t) },
                                    )

                                    page == pageItems.lastIndex -> JumpPage(
                                        label = "下一章",
                                        target = next,
                                        palette = palette,
                                        onGo = { t -> onOpenChapter(t) },
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
                                            text = item,
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
                }
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 *  换章占位页：翻到最左/最右会停在这里，停稳即自动换章，点一下也行
 * ------------------------------------------------------------------ */
@Composable
private fun JumpPage(
    label: String,
    target: BookChapter?,
    palette: ReaderPalette,
    onGo: (BookChapter) -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .clickable(enabled = target != null) { target?.let(onGo) },
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = if (target == null) "（没有" + label + "了）" else label + " ›",
                color = palette.sub,
                fontSize = 15.sp,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = target?.title?.ifBlank { "（无标题）" } ?: "",
                color = palette.fg,
                fontSize = 17.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 40.dp),
            )
        }
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
