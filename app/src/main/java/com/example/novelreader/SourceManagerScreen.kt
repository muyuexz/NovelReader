package com.example.novelreader

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.novelreader.analyzeRule.BookSource
import com.example.novelreader.ui.AppHeader
import com.example.novelreader.ui.CountBadge
import com.example.novelreader.ui.PillChip
import com.example.novelreader.ui.StateBlock

/**
 * 第21批：书源管理页。
 *
 * 布局对齐 Legado 书源管理：顶部返回 + 计数、右上角三点菜单（本地/网络导入）、
 * 搜索框、全选/反选/删除工具条、书源列表（多选 + 启停）。
 * 数据与副作用全部走回调上抛，本页只负责呈现与本地交互态。
 */
@Composable
fun SourceManagerScreen(
    sources: List<BookSource>,
    notice: String?,
    onBack: () -> Unit,
    onImportText: (String) -> Unit,
    onImportNetwork: (String) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onClearNotice: () -> Unit,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(setOf<String>()) }

    // 本地导入：拉起系统文件选择器，读成文本交给上层解析入库。
    val pickFile = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) {
            val text = runCatching {
                context.contentResolver.openInputStream(uri)
                    ?.bufferedReader()?.use { it.readText() }
            }.getOrNull()
            onImportText(text ?: "")
        }
    }

    val filtered = remember(sources, query) {
        val q = query.trim()
        if (q.isEmpty()) {
            sources
        } else {
            sources.filter {
                it.bookSourceName.contains(q, ignoreCase = true) ||
                    it.bookSourceUrl.contains(q, ignoreCase = true) ||
                    (it.bookSourceGroup ?: "").contains(q, ignoreCase = true)
            }
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .windowInsetsPadding(WindowInsets.systemBars),
    ) {
        AppHeader(
            title = "书源管理",
            subtitle = "共 ${sources.size} 条 · 启用 ${sources.count { it.enabled }} 条",
            // 第25批：按需求去掉左上角返回按钮（改由系统返回键 / 底部导航离开）。
            trailing = {
                Box {
                    Text(
                        text = "\u22ee",
                        fontSize = 22.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clip(CircleShape)
                            .clickable { menuOpen = true }
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                    )
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("本地导入") },
                            onClick = {
                                menuOpen = false
                                pickFile.launch(arrayOf("application/json", "text/plain", "*/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("网络导入") },
                            onClick = {
                                menuOpen = false
                                showUrlDialog = true
                            },
                        )
                    }
                }
            },
        )

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true,
            placeholder = { Text("搜索书源") },
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val allKeys = filtered.map { it.getKey() }.toSet()
            PillChip(
                text = "全选",
                selected = allKeys.isNotEmpty() && allKeys.all { it in selected },
                onClick = { selected = selected + allKeys },
            )
            PillChip(
                text = "反选",
                selected = false,
                onClick = { selected = (selected - allKeys) + (allKeys - selected) },
            )
            Box(Modifier.weight(1f))
            CountBadge("已选 ${selected.size}")
            TextButton(
                onClick = {
                    onDelete(selected)
                    selected = emptySet()
                },
                enabled = selected.isNotEmpty(),
            ) {
                Text("删除")
            }
        }

        if (!notice.isNullOrBlank()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = notice,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                TextButton(onClick = onClearNotice) { Text("知道了") }
            }
            Spacer(Modifier.height(4.dp))
        }

        if (filtered.isEmpty()) {
            StateBlock(
                title = if (sources.isEmpty()) "还没有书源" else "没有匹配的书源",
                description = if (sources.isEmpty()) {
                    "点右上角 \u22ee 用「本地导入」或「网络导入」添加书源"
                } else {
                    "换个关键词试试"
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                items(filtered) { src ->
                    val key = src.getKey()
                    SourceRow(
                        name = src.bookSourceName.ifBlank { "(未命名书源)" },
                        url = src.bookSourceUrl,
                        group = src.bookSourceGroup,
                        enabled = src.enabled,
                        checked = key in selected,
                        onCheckedChange = { ck ->
                            selected = if (ck) selected + key else selected - key
                        },
                        onToggleEnabled = { onToggle(key, it) },
                    )
                }
            }
        }
    }

    if (showUrlDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("网络导入") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text("书源 JSON 的直链 URL") },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val u = url.trim()
                        if (u.isNotEmpty()) {
                            showUrlDialog = false
                            onImportNetwork(u)
                        }
                    },
                    enabled = url.isNotBlank(),
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) { Text("取消") }
            },
        )
    }
}

/** 单条书源行：多选框 + 名称/地址 + 启用开关。 */
@Composable
private fun SourceRow(
    name: String,
    url: String,
    group: String?,
    enabled: Boolean,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onCheckedChange(!checked) }
            .padding(horizontal = 4.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = { ck -> onCheckedChange(ck == true) },
        )
        Column(Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = listOfNotNull(group?.takeIf { it.isNotBlank() }, url).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(start = 2.dp),
        ) {
            Switch(
                checked = enabled,
                onCheckedChange = { onToggleEnabled(it) },
            )
            Text(
                text = if (enabled) "已启用" else "已停用",
                fontSize = 11.sp,
                color = if (enabled) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
    }
}
