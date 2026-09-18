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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelreader.analyzeRule.Book
import com.example.novelreader.analyzeRule.BookChapter
import com.example.novelreader.ui.AppHeader
import com.example.novelreader.ui.TagPill
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
    onBack: () -> Unit,
    onToggleShelf: () -> Unit,
    onRead: () -> Unit,
    onContinue: (() -> Unit)?,
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
    val wordText = book.wordCount?.takeIf { it.isNotBlank() } ?: "未知"
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
            title = "书籍详情",
            subtitle = book.originName.takeIf { it.isNotBlank() },
            onBack = onBack,
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
                BigCover(book.name)
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

            // —— 操作区 ——
            Spacer(Modifier.height(16.dp))
            Row {
                OutlinedButton(onClick = onToggleShelf, modifier = Modifier.weight(1f)) {
                    Text(if (inShelf) "移出书架" else "加入书架")
                }
                if (onContinue != null) {
                    Spacer(Modifier.width(10.dp))
                    OutlinedButton(onClick = onContinue, modifier = Modifier.weight(1f)) {
                        Text("继续阅读")
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onRead,
                modifier = Modifier.fillMaxWidth().height(46.dp),
                enabled = chapters.isNotEmpty(),
            ) {
                Text(if (chapters.isEmpty()) "目录尚未就绪" else "查看目录 · 共 ${chapters.size} 章")
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

            if (lastChapter != null) {
                Spacer(Modifier.height(20.dp))
                Text("最新章节", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(6.dp))
                Text(
                    text = lastChapter,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(Modifier.height(30.dp))
        }
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

private val BigCoverShades = listOf(
    listOf(Color(0xFF7C4DFF), Color(0xFF5E35B1)),
    listOf(Color(0xFF3F51B5), Color(0xFF283593)),
    listOf(Color(0xFF009688), Color(0xFF00695C)),
    listOf(Color(0xFFFF8A65), Color(0xFFD84315)),
)

@Composable
private fun BigCover(name: String) {
    val initial = name.trim().firstOrNull()?.toString() ?: "书"
    val shade = remember(name) { BigCoverShades[abs(name.hashCode()) % BigCoverShades.size] }
    Box(
        modifier = Modifier
            .size(width = 92.dp, height = 124.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Brush.linearGradient(shade)),
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
