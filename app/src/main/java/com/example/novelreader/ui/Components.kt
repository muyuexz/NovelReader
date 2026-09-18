package com.example.novelreader.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelreader.analyzeRule.Book
import coil.compose.AsyncImage
import kotlin.math.abs

/* ------------------------------------------------------------------ *
 *  通用头部：返回 + 主标题 + 副标题 + 右侧插槽
 * ------------------------------------------------------------------ */
@Composable
fun AppHeader(
    title: String,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            TextButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 8.dp)) {
                Text(
                    "‹ 返回",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(start = if (onBack == null) 12.dp else 0.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!subtitle.isNullOrBlank()) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        trailing?.invoke()
    }
}

/* ------------------------------------------------------------------ *
 *  小圆标：章节数 / 命中数 / 书源数
 * ------------------------------------------------------------------ */
@Composable
fun CountBadge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(MaterialTheme.colorScheme.primaryContainer)
            .padding(horizontal = 9.dp, vertical = 3.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
        )
    }
}

/** 描边小标签（书源名、分类等）。 */
@Composable
fun TagPill(
    text: String,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    border: Color = MaterialTheme.colorScheme.outlineVariant,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(BorderStroke(1.dp, border), RoundedCornerShape(50))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1)
    }
}

/** 可点选胶囊（筛选用）。 */
@Composable
fun PillChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.labelMedium, color = fg)
    }
}

/* ------------------------------------------------------------------ *
 *  书籍封面占位：首字 + 渐变底，不同书不同色（按标题 hash 分桶）
 * ------------------------------------------------------------------ */
// 第 29 批：占位封面色统一收敛到品牌同族色系。
// 旧版 6 组里混了 3 组靛蓝(0xFF3F51B5 / 0xFF5C6BC0 / 0xFF3949AB)和 1 组土棕(0xFF8D6E63)，
// 封面墙和紫调主色是打架的；现在只保留紫 / 品紫 / 玫红 / 青瓷 / 暖橘 / 深紫六个方向。
private val CoverShades = listOf(
    listOf(Color(0xFF6C4DF6), Color(0xFF4B32C4)),
    listOf(Color(0xFF9B2FD6), Color(0xFF6A1BA8)),
    listOf(Color(0xFFE2557B), Color(0xFFA82F55)),
    listOf(Color(0xFF00A9A0), Color(0xFF00786F)),
    listOf(Color(0xFFFF8A65), Color(0xFFD8552A)),
    listOf(Color(0xFF5B48B8), Color(0xFF3A2A86)),
)

@Composable
fun CoverBadge(name: String, modifier: Modifier = Modifier) {
    CoverBadgeInner(
        name = name,
        modifier = modifier
            .size(width = 46.dp, height = 60.dp)
            .clip(RoundedCornerShape(10.dp)),
    )
}

/** 封面渐变底本体（占位 / 加载中 / 加载失败时复用）。 */
@Composable
private fun CoverBadgeInner(name: String, modifier: Modifier = Modifier.fillMaxSize()) {
    val initial = name.trim().firstOrNull()?.toString() ?: "书"
    val shade = remember(name) { CoverShades[abs(name.hashCode()) % CoverShades.size] }
    Box(
        modifier = modifier.background(Brush.linearGradient(shade)),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = initial, color = Color.White, fontSize = 21.sp, fontWeight = FontWeight.Bold)
    }
}

/**
 * 封面缩略图：有 [coverUrl] 走 Coil 网络图，加载中 / 失败回落首字渐变占位。
 * 搜索结果的封面（第 7 条）走这里。
 */
@Composable
fun CoverThumb(coverUrl: String?, name: String, modifier: Modifier = Modifier) {
    val label = name.ifBlank { "书" }
    val frame = modifier
        .size(width = 46.dp, height = 60.dp)
        .clip(RoundedCornerShape(10.dp))
    if (coverUrl.isNullOrBlank()) {
        CoverBadgeInner(label, frame)
    } else {
        // 第37批：SubcomposeAsyncImage -> AsyncImage，去掉每图一个 subcomposition slot 的开销。
        // 加载中 / 失败时仍回落首字渐变占位。
        val ok = remember(coverUrl) { mutableStateOf(true) }
        Box(frame) {
            if (ok.value) CoverBadgeInner(label)
            AsyncImage(
                model = coverUrl,
                contentDescription = label,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                onSuccess = { ok.value = false },
            )
        }
    }
}

/* ------------------------------------------------------------------ *
 *  搜索结果卡片
 * ------------------------------------------------------------------ */
@Composable
fun BookCard(book: Book, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        // 第 29 批：圆角收到 16dp 与全站圆角语言统一，阴影 1→2dp 让卡片有轻微悬浮感。
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shadowElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .clickable(onClick = onClick)
                .padding(start = 12.dp, end = 14.dp, top = 12.dp, bottom = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CoverThumb(book.coverUrl, book.name.ifBlank { "书" })
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = book.name.ifBlank { "（无书名）" },
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    book.author.takeIf { it.isNotBlank() },
                    book.kind?.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (meta.isNotBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (book.chapterCount > 0 || !book.lastChapter.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    val tail = listOfNotNull(
                        book.chapterCount.takeIf { it > 0 }?.let { "共 ${it}章" },
                        book.lastChapter?.takeIf { it.isNotBlank() }?.let { "最新 $it" },
                    ).joinToString(" · ")
                    Text(
                        text = tail,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!book.originName.isNullOrBlank() || book.altSources.isNotEmpty()) {
                    Spacer(Modifier.height(7.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!book.originName.isNullOrBlank()) {
                            TagPill(book.originName)
                        }
                        if (book.altSources.isNotEmpty()) {
                            if (!book.originName.isNullOrBlank()) Spacer(Modifier.width(6.dp))
                            // 第 26 批：聚合后告诉用户「这条背后还有几个源」，进阅读页可换。
                            TagPill("${book.altSources.size + 1} 个书源 · 可换源")
                        }
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                text = "\u203a",
                fontSize = 22.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/* ------------------------------------------------------------------ *
 *  空态 / 加载态 / 错误态
 * ------------------------------------------------------------------ */
@Composable
fun StateBlock(
    title: String,
    description: String? = null,
    spinner: Boolean = false,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            if (spinner) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 2.5.dp)
                Spacer(Modifier.height(16.dp))
            }
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                textAlign = TextAlign.Center,
            )
            if (!description.isNullOrBlank()) {
                Spacer(Modifier.height(7.dp))
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
            if (actionText != null && onAction != null) {
                Spacer(Modifier.height(20.dp))
                Button(onClick = onAction) { Text(actionText) }
            }
        }
    }
}

/* ------------------------------------------------------------------ *
 *  加减档控件（阅读设置用）
 * ------------------------------------------------------------------ */
@Composable
fun Stepper(
    label: String,
    valueText: String,
    canDec: Boolean,
    canInc: Boolean,
    onDec: () -> Unit,
    onInc: () -> Unit,
    fg: Color = MaterialTheme.colorScheme.onSurface,
    sub: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = fg, modifier = Modifier.width(48.dp))
        StepButton("－", enabled = canDec, fg = fg, sub = sub, onClick = onDec)
        Text(
            text = valueText,
            style = MaterialTheme.typography.labelLarge,
            color = fg,
            textAlign = TextAlign.Center,
            modifier = Modifier.width(48.dp),
        )
        StepButton("＋", enabled = canInc, fg = fg, sub = sub, onClick = onInc)
    }
}

@Composable
private fun StepButton(
    glyph: String,
    enabled: Boolean,
    fg: Color,
    sub: Color,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .background(if (enabled) sub.copy(alpha = 0.12f) else Color.Transparent)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(glyph, fontSize = 17.sp, color = if (enabled) fg else sub.copy(alpha = 0.4f))
    }
}
