package com.example.novelreader

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.novelreader.ui.AppHeader
import com.example.novelreader.ui.CoverBadge
import com.example.novelreader.ui.StateBlock

/* ==================================================================== *
 *  书架页：全部收藏 + 阅读进度 + 续读入口
 * ==================================================================== */
@Composable
fun ShelfScreen(
    entries: List<ShelfEntry>,
    onBack: (() -> Unit)? = null,
    onOpen: (ShelfEntry) -> Unit,
    onRead: (ShelfEntry) -> Unit,
    onRemove: (ShelfEntry) -> Unit,
) {
    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.statusBars),
    ) {
        AppHeader(
            title = "我的书架",
            subtitle = if (entries.isEmpty()) "还没有收藏" else "共 ${entries.size} 本",
            onBack = onBack,
        )

        if (entries.isEmpty()) {
            StateBlock(
                title = "书架还是空的",
                description = "搜一本书，进详情页点「加入书架」",
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(entries, key = { it.key }) { entry ->
                    ShelfCard(
                        entry = entry,
                        onOpen = onOpen,
                        onRead = onRead,
                        onRemove = onRemove,
                    )
                }
            }
        }
    }
}

@Composable
private fun ShelfCard(
    entry: ShelfEntry,
    onOpen: (ShelfEntry) -> Unit,
    onRead: (ShelfEntry) -> Unit,
    onRemove: (ShelfEntry) -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .clickable { onOpen(entry) }
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            CoverBadge(entry.name.ifBlank { "书" })
            Spacer(Modifier.width(13.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = entry.name.ifBlank { "（无书名）" },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                val meta = listOfNotNull(
                    entry.author.takeIf { it.isNotBlank() },
                    entry.originName.takeIf { it.isNotBlank() },
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
                Spacer(Modifier.height(5.dp))
                Text(
                    text = if (entry.lastReadChapterTitle.isNullOrBlank()) {
                        "尚未开始阅读"
                    } else {
                        "读到　${entry.lastReadChapterTitle}"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Button(
                        onClick = { onRead(entry) },
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 4.dp),
                    ) {
                        Text(if (entry.lastReadChapterUrl.isNullOrBlank()) "开始阅读" else "继续阅读")
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { onRemove(entry) }) { Text("移出") }
                }
            }
        }
    }
}
