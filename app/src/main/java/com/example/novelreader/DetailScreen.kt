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
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.example.novelreader.ui.AppHeader
import com.example.novelreader.ui.TagPill
import coil.compose.SubcomposeAsyncImage
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
) {
    val scroll = rememberScrollState()
    val lastChapter = book.lastChapter?.takeIf { it.isNotBlank() }
    val intro = remember(book.intro) {
        book.intro
            ?.replace(Regex("<[^>]+>"), " ")
            ?.replace("&nbsp;", " ")
            ?.replace(Regex("\\s+"), " ")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }
    val kinds = remember(book.kind) {
        (book.kind ?: "")
            .split(',', '，', '、', '/', '|')
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(4)
    }
    val chapterText = when {
        chapters.isNotEmpty() -> "${chapters.size}"
        loading -> "…"
        else -> "—"
    }
    val wordText = formatWordCount(book.wordCount)
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
                    StatCell("总字数", wordText, Modifier.weight(1f))
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
                TextButton(onClick = onRetry) { Text("重试") }
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
}

/**
 * 状态兜底（第 2 条）：书源未给 status 时，从分类/简介里嗅「完结 / 连载」字样推断，
 * 总比一律显示「未知」有信息量。
 */
private fun inferStatus(kind: String?, intro: String?): String {
    val hay = (kind ?: "") + " " + (intro ?: "")
    return when {
        hay.contains("完结") || hay.contains("完本") -> "已完结"
        hay.contains("连载") -> "连载中"
        else -> "未知"
    }
}

@Composable
private fun StatCell(label: String, value: String, modifier: Modifier = Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
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
    listOf(Color(0xFF7C4DFF), Color(0xFF5E35B1)),
    listOf(Color(0xFF3F51B5), Color(0xFF283593)),
    listOf(Color(0xFF009688), Color(0xFF00695C)),
    listOf(Color(0xFFFF8A65), Color(0xFFD84315)),
)

@Composable
private fun BigCover(coverUrl: String?, name: String) {
    val initial = name.trim().firstOrNull()?.toString() ?: "书"
    val shade = remember(name) { BigCoverShades[abs(name.hashCode()) % BigCoverShades.size] }
    val frame = Modifier
        .size(width = 92.dp, height = 124.dp)
        .clip(RoundedCornerShape(14.dp))
    if (!coverUrl.isNullOrBlank()) {
        SubcomposeAsyncImage(
            model = coverUrl,
            contentDescription = name,
            modifier = frame,
            contentScale = ContentScale.Crop,
            loading = { CoverFallback(initial, shade) },
            error = { CoverFallback(initial, shade) },
        )
    } else {
        CoverFallback(initial, shade, frame)
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

/**
 * 总字数格式化：「5485000」这类纯数字折算成「548.5万」，
 * 已带单位（万/字/W）的文本原样保留，拿不到就回落「未知」。
 */
private fun formatWordCount(raw: String?): String {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return "未知"
    if (text.any { it == '万' || it == '字' || it == 'W' || it == 'w' }) return text
    val n = text.filter { it.isDigit() }.toLongOrNull() ?: return text
    return if (n >= 10_000L) {
        val w = n / 10_000.0
        if (w >= 100.0) String.format(java.util.Locale.CHINA, "%.0f万", w)
        else String.format(java.util.Locale.CHINA, "%.1f万", w)
    } else {
        "${n}字"
    }
}
