package com.example.novelreader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelreader.analyzeRule.Book
import com.example.novelreader.analyzeRule.BookChapter
import com.example.novelreader.analyzeRule.ChapterStats
import com.example.novelreader.analyzeRule.WordCountResolver
import com.example.novelreader.analyzeRule.ruleCompletenessOf
import com.example.novelreader.analyzeRule.sourceMetaLine
import com.example.novelreader.ui.AppHeader
import com.example.novelreader.ui.TagPill
import coil.compose.AsyncImage
import kotlin.math.abs

/* ==================================================================== *
 *  书籍详情页：封面 / 书名 / 作者 / 分类 / 章节数 / 总字数 / 更新时间 / 简介
 *  搜索结果点进来先看这里，再由用户决定「加入书架」还是「查看目录」。
 * ==================================================================== */
@Composable
fun DetailScreen(
    book: Book,
    chapters: List<BookChapter>,
    loading: Boolean,
    error: String?,
    inShelf: Boolean,
    onToggleShelf: () -> Unit,
    onRead: () -> Unit,
    onRetry: () -> Unit,
    onSwitchSource: (Book) -> Unit,
) {
    val scroll = rememberScrollState()
    // 第43批刀C：详情页失败态「换个源试试」的源列表弹窗开关。
    val showSourcePicker = remember { mutableStateOf(false) }
    val lastChapter = book.lastChapter?.takeIf { it.isNotBlank() }
    val intro = remember(book.intro) {
        book.intro
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace("&nbsp;", " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    // 第 1 条：书源「类型」常常缺失或给错（甚至塞进时间串），
    // 先做合法性清洗，再退回书名 / 简介 / 最新章节里嗅探真实分类。
    val kinds = remember(book.kind, intro, book.name, book.lastChapter) {
        sanitizeKind(book.kind).ifEmpty { inferKind(book.name, intro, book.lastChapter) }
    }
    val chapterText = when {
        chapters.isNotEmpty() -> "${ChapterStats.realChapterCount(chapters)}"
        loading -> "…"
        else -> "—"
    }
    // 第 51 批 C-lite：字数展示走「可信度」解析。
    // 正常源原样显示；仅当「字数 ÷ 章节数」高到像字节数时，才用兄弟源参考值或 ÷3 折算，
    // 并挂角标说明来源，绝不伪造精确值。原始文本始终保留在 book.wordCount 里。
    val wcChapterCount = if (chapters.isNotEmpty()) {
        ChapterStats.realChapterCount(chapters)
    } else {
        book.chapterCount
    }
    val wordDisplay = remember(book.wordCount, wcChapterCount, book.altSources) {
        WordCountResolver.resolve(book.wordCount, wcChapterCount, book.altSources)
    }
    val wordText = wordDisplay.text
    val statusText = book.status?.takeIf { it.isNotBlank() } ?: inferStatus(book.kind, intro)
    val updateText = remember(chapters) {
        formatTime(chapters.mapNotNull { it.updateTime }.maxOrNull())
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        AppHeader(
            title = "书籍信息",
            trailing = { if (inShelf) TagPill("已在书架") },
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(scroll)
                .padding(horizontal = 18.dp),
        ) {
            // —— 封面 + 主信息 ——
            Row(verticalAlignment = Alignment.Top) {
                BigCover(book.coverUrl, book.name)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = book.name.ifBlank { "（无书名）" },
                        style = MaterialTheme.typography.titleLarge,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = book.author.takeIf { it.isNotBlank() } ?: "佚名",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (kinds.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Row {
                            kinds.forEach { k ->
                                TagPill(k, modifier = Modifier.padding(end = 6.dp))
                            }
                        }
                    }
                }
            }

            // —— 数据卡 ——
            Spacer(Modifier.height(18.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Row(Modifier.fillMaxWidth().padding(vertical = 14.dp)) {
                    StatCell("章节数", chapterText, Modifier.weight(1f))
                    StatCell("总字数", wordText, Modifier.weight(1f), badge = wordDisplay.badge)
                    StatCell("最近更新", updateText, Modifier.weight(1f))
                }
            }

            // —— 信息区（对齐 Legado 详情页）：来源 / 分类 / 状态 / 最新章节 ——
            Spacer(Modifier.height(14.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 4.dp)) {
                    InfoRow("来源", book.originName.takeIf { it.isNotBlank() } ?: "未知")
                    InfoRow("类型", kinds.joinToString("·").takeIf { it.isNotBlank() } ?: "未知")
                    InfoRow("状态", statusText)
                    InfoRow("最新章节", lastChapter ?: "未知")
                    chapters.firstOrNull()?.title?.takeIf { it.isNotBlank() }?.let {
                        InfoRow("首章", it)
                    }
                }
            }

            // —— 操作区（第 4/5 条）：底部左「加入书架」，右「阅读」；不再单列「查看目录」——
            Spacer(Modifier.height(16.dp))
            Row {
                OutlinedButton(
                    onClick = onToggleShelf,
                    modifier = Modifier.weight(1f).height(46.dp),
                ) {
                    Text(if (inShelf) "移出书架" else "加入书架")
                }
                Spacer(Modifier.width(10.dp))
                Button(
                    onClick = onRead,
                    modifier = Modifier.weight(1f).height(46.dp),
                    enabled = chapters.isNotEmpty(),
                ) {
                    Text(if (chapters.isEmpty()) "目录加载中" else "阅读")
                }
            }

            if (loading) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "正在解析目录…",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (error != null && chapters.isEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "目录解析失败：$error",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
                Row {
                    TextButton(onClick = onRetry) { Text("重试") }
                    // 第43批刀C：代表源失败时给一条人工换源入口，展开命中该书的全部兄弟源。
                    if (book.altSources.isNotEmpty()) {
                        TextButton(onClick = { showSourcePicker.value = true }) { Text("换个源试试") }
                    }
                }
            }

            // —— 简介 ——
            Spacer(Modifier.height(22.dp))
            Text("简介", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Text(
                text = intro ?: "暂无简介",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(Modifier.height(30.dp))
        }
    }
    // 第43批刀C：源选择弹窗 —— 列出「代表源 + 兄弟源」，选中即交给上层走 switchSource 换源链路。
    if (showSourcePicker.value) {
        val pickList = listOf(book) + book.altSources
        AlertDialog(
            onDismissRequest = { showSourcePicker.value = false },
            title = { Text("换个源试试") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    pickList.forEach { s ->
                        TextButton(
                            onClick = {
                                showSourcePicker.value = false
                                onSwitchSource(s)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            // 第44批需求④：详情页弹窗不再只甩一个源名 —— 补「当前/推荐」标记、
                            // 状态与最新章节、以及「域名 · 章节数 · 规则完备度」元信息。
                            val cur = s === book ||
                                (s.bookUrl == book.bookUrl &&
                                    s.source?.bookSourceUrl == book.source?.bookSourceUrl)
                            Column(Modifier.fillMaxWidth()) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = s.originName.takeIf { it.isNotBlank() } ?: "未知来源",
                                        modifier = Modifier.weight(1f),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                    when {
                                        cur -> TagPill("当前")
                                        ruleCompletenessOf(s.source) == 0 -> TagPill("推荐")
                                    }
                                }
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    text = listOfNotNull(
                                        s.status?.takeIf { it.isNotBlank() },
                                        s.lastChapter?.takeIf { it.isNotBlank() }
                                            ?.let { "最新 " + it },
                                    ).joinToString(" · ").ifBlank { "—" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = sourceMetaLine(s),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showSourcePicker.value = false }) { Text("取消") }
            },
        )
    }
}
/**
 * 状态兜底（第 2 条）：书源未给 status 时，从分类/简介里嗅「完结 / 连载」字样推断，
 * 总比一律显示「未知」有信息量。
 */
/** 常见小说分类词典（第 1 条：类型嗅探用）。 */
private val KIND_WORDS = listOf(
    "武侠", "仙侠", "修仙", "玄幻", "奇幻", "灵异", "惊悚", "悬疑", "推理", "科幻",
    "都市", "历史", "军事", "游戏", "体育", "现实", "言情", "古言", "现言", "青春",
    "校园", "穿越", "重生", "系统", "种田", "无限", "末世", "二次元", "同人", "轻小说",
)

/**
 * 清洗书源返回的「类型」字段。
 * 日期串（2018-04-03 20:52:33）、纯数字、超长串一律判废；
 * 剩余片段还要命中已知分类词才算数，避免把垃圾文案留在详情页。
 */
private fun sanitizeKind(raw: String?): List<String> {
    val r = raw?.trim().orEmpty()
    if (r.isBlank() || r.length > 20) return emptyList()
    if (Regex("^[0-9:./\\-年月日\\s]+$").matches(r)) return emptyList()
    val parts = r.split(',', '，', '、', '/', '|', ' ', ';', '；')
        .map { it.trim() }
        .filter { it.isNotBlank() && it.length <= 8 }
        .filter { !Regex("^[0-9]+$").matches(it) }
        .filter { Regex("[\\u4e00-\\u9fa5a-zA-Z]").containsMatchIn(it) }
        .distinct()
    val hit = parts.filter { p -> KIND_WORDS.any { w -> p.contains(w) || w.contains(p) } }
    return if (hit.isNotEmpty()) hit else parts
}

/** 从书名 / 简介 / 最新章节里嗅探分类（第 1 条兜底）。 */
private fun inferKind(name: String?, intro: String?, lastChapter: String?): List<String> {
    val hay = (name ?: "") + " " + (intro ?: "") + " " + (lastChapter ?: "")
    return KIND_WORDS.filter { hay.contains(it) }.distinct().take(4)
}

private fun inferStatus(kind: String?, intro: String?): String {
    val hay = (kind ?: "") + " " + (intro ?: "")
    return when {
        hay.contains("完结") || hay.contains("完本") -> "已完结"
        hay.contains("连载") -> "连载中"
        else -> "未知"
    }
}

@Composable
private fun StatCell(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    badge: String? = null,
) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        // 第 51 批 C-lite：角标行恒定占位（无角标时是空白），
        // 保证「章节数 / 总字数 / 最近更新」三个格子高度一致、基线对齐。
        Text(
            text = badge ?: " ",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(3.dp))
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(64.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private val BigCoverShades = listOf(
    // 第 29 批：与 Components.kt 的 CoverShades 对齐，去掉靛蓝 / 土棕。
    listOf(Color(0xFF6C4DF6), Color(0xFF4B32C4)),
    listOf(Color(0xFF9B2FD6), Color(0xFF6A1BA8)),
    listOf(Color(0xFF00A9A0), Color(0xFF00786F)),
    listOf(Color(0xFFFF8A65), Color(0xFFD8552A)),
)

@Composable
private fun BigCover(coverUrl: String?, name: String) {
    val initial = name.trim().firstOrNull()?.toString() ?: "书"
    val shade = remember(name) { BigCoverShades[abs(name.hashCode()) % BigCoverShades.size] }
    val frame = Modifier
        .size(width = 92.dp, height = 124.dp)
        .clip(RoundedCornerShape(14.dp))
    if (coverUrl.isNullOrBlank()) {
        CoverFallback(initial, shade, frame)
    } else {
        // 第37批：同 Components.kt，改扁平 AsyncImage，避免 subcomposition。
        val ok = remember(coverUrl) { mutableStateOf(true) }
        Box(frame) {
            if (ok.value) CoverFallback(initial, shade)
            AsyncImage(
                model = coverUrl,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { ok.value = false },
            )
        }
    }
}

@Composable
private fun CoverFallback(
    initial: String,
    shade: List<Color>,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    Box(
        modifier = modifier.background(Brush.linearGradient(shade)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = initial, color = Color.White, fontSize = 40.sp, fontWeight = FontWeight.Bold)
    }
}

/** 时间戳兼容「秒 / 毫秒」两种口径，格式化失败就回落成「未知」。 */
private fun formatTime(ts: Long?): String {
    if (ts == null || ts <= 0L) return "未知"
    val ms = if (ts < 1_000_000_000_000L) ts * 1000L else ts
    return runCatching {
        java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.CHINA).format(java.util.Date(ms))
    }.getOrDefault("未知")
}

// 第 51 批 C-lite：原 formatWordCount 已迁入 analyzeRule/WordCountResolver.kt，
// 那里同时负责「字节数嫌疑」判定与降级（多源参考 / ÷3 折算 / 源站自报）。
