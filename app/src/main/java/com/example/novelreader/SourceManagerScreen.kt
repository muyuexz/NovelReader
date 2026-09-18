package com.example.novelreader

import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
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
import com.example.novelreader.analyzeRule.RuleBookInfo
import com.example.novelreader.analyzeRule.RuleContent
import com.example.novelreader.analyzeRule.RuleSearch
import com.example.novelreader.analyzeRule.RuleToc
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
    onAddSource: (BookSource) -> Unit,
    onDelete: (Set<String>) -> Unit,
    onToggle: (String, Boolean) -> Unit,
    onClearNotice: () -> Unit,
    importStage: String? = null,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }
    var menuOpen by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showNewDialog by remember { mutableStateOf(false) }
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
                        DropdownMenuItem(
                            text = { Text("新建源") },
                            onClick = {
                                menuOpen = false
                                showNewDialog = true
                            },
                        )
                    }
                }
            },
        )

        if (importStage != null) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(6.dp))
                Text(
                    text = importStage ?: "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

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
            //第38批 E刀：组合期集合运算 remember 化——筛选结果没变就不重算整张 key 集合
            //（多选几百条书源时，点一下复选框过去都要重算一遍）。
            val allKeys = remember(filtered) { filtered.map { it.getKey() }.toSet() }
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
                    "点右上角 \u22ee 用「本地导入」「网络导入」或「新建源」添加书源"
                } else {
                    "换个关键词试试"
                },
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) {
                //第38批 E刀：补齐全工程最后一个缺失的列表 key，条目复用与滚动位置更稳。
                items(filtered, key = { it.getKey() }) { src ->
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

    if (showNewDialog) {
        var newName by remember { mutableStateOf("") }
        var newUrl by remember { mutableStateOf("") }
        var newGroup by remember { mutableStateOf("") }
        var showRules by remember { mutableStateOf(false) }
        var sSearchUrl by remember { mutableStateOf("") }
        var rBookList by remember { mutableStateOf("") }
        var rName by remember { mutableStateOf("") }
        var rAuthor by remember { mutableStateOf("") }
        var rCover by remember { mutableStateOf("") }
        var rBookUrl by remember { mutableStateOf("") }
        var iInit by remember { mutableStateOf("") }
        var iIntro by remember { mutableStateOf("") }
        var iTocUrl by remember { mutableStateOf("") }
        var tList by remember { mutableStateOf("") }
        var tName by remember { mutableStateOf("") }
        var tUrl by remember { mutableStateOf("") }
        var cContent by remember { mutableStateOf("") }
        var cReplace by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showNewDialog = false },
            title = { Text("新建书源") },
            text = {
                Column(
                    modifier = Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState()),
                ) {
                    RuleField("书源名称（必填）", newName) { newName = it }
                    RuleField("书源地址 URL（必填）", newUrl) { newUrl = it }
                    RuleField("分组（可选）", newGroup) { newGroup = it }
                    TextButton(onClick = { showRules = !showRules }) {
                        Text(if (showRules) "收起解析规则 ▲" else "展开解析规则（进阶） ▼")
                    }
                    AnimatedVisibility(visible = showRules) {
                        Column {
                            RuleSection("搜索规则（搜索结果页怎么解析）")
                            RuleField("搜索地址 searchUrl（关键词用 {{key}}）", sSearchUrl) { sSearchUrl = it }
                            RuleField("列表规则 bookList（如 .result li）", rBookList) { rBookList = it }
                            RuleField("书名规则 name（如 h3 a@text）", rName) { rName = it }
                            RuleField("作者规则 author", rAuthor) { rAuthor = it }
                            RuleField("封面规则 coverUrl（如 img@src）", rCover) { rCover = it }
                            RuleField("详情链接规则 bookUrl（如 h3 a@href）", rBookUrl) { rBookUrl = it }
                            RuleSection("详情规则（书籍信息页）")
                            RuleField("预处理 init（可空，JS 或规则）", iInit) { iInit = it }
                            RuleField("简介规则 intro（如 #intro@text）", iIntro) { iIntro = it }
                            RuleField("目录链接规则 tocUrl（可空，与详情页不同时填）", iTocUrl) { iTocUrl = it }
                            RuleSection("目录规则（章节列表页）")
                            RuleField("章节列表 chapterList（如 #list dd）", tList) { tList = it }
                            RuleField("章节名 chapterName（如 a@text）", tName) { tName = it }
                            RuleField("章节链接 chapterUrl（如 a@href）", tUrl) { tUrl = it }
                            RuleSection("正文规则（章节内容页）")
                            RuleField("正文规则 content（如 #content@text）", cContent) { cContent = it }
                            RuleField("正文替换 replaceRegex（可空，如去空白与换行）", cReplace) { cReplace = it }
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "提示：规则语法与 Legado 书源一致，搜索地址里的关键词用 {{key}} 占位；只有填了对应规则，才能解析出对应内容。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (newName.isNotBlank() && newUrl.isNotBlank()) {
                            showNewDialog = false
                            onAddSource(
                                BookSource(
                                    bookSourceUrl = newUrl.trim(),
                                    bookSourceName = newName.trim(),
                                    bookSourceGroup = newGroup.trim().ifBlank { null },
                                    searchUrl = sSearchUrl.trim().ifBlank { null },
                                    ruleSearch = RuleSearch(
                                        bookList = rBookList.trim().ifBlank { null },
                                        name = rName.trim().ifBlank { null },
                                        author = rAuthor.trim().ifBlank { null },
                                        coverUrl = rCover.trim().ifBlank { null },
                                        bookUrl = rBookUrl.trim().ifBlank { null },
                                    ),
                                    ruleBookInfo = RuleBookInfo(
                                        init = iInit.trim().ifBlank { null },
                                        intro = iIntro.trim().ifBlank { null },
                                        tocUrl = iTocUrl.trim().ifBlank { null },
                                    ),
                                    ruleToc = RuleToc(
                                        chapterList = tList.trim().ifBlank { null },
                                        chapterName = tName.trim().ifBlank { null },
                                        chapterUrl = tUrl.trim().ifBlank { null },
                                    ),
                                    ruleContent = RuleContent(
                                        content = cContent.trim().ifBlank { null },
                                        replaceRegex = cReplace.trim().ifBlank { null },
                                    ),
                                )
                            )
                        }
                    },
                    enabled = newName.isNotBlank() && newUrl.isNotBlank(),
                ) { Text("新建") }
            },
            dismissButton = {
                TextButton(onClick = { showNewDialog = false }) { Text("取消") }
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


/** 第36批：规则输入行（用 placeholder 当字段名，省 label 占高）。 */
@Composable
private fun RuleField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = Modifier.fillMaxWidth(),
        singleLine = true,
        placeholder = { Text(label, fontSize = 13.sp) },
    )
    Spacer(Modifier.height(6.dp))
}

/** 第36批：规则分区小标题。 */
@Composable
private fun RuleSection(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
    )
    Spacer(Modifier.height(4.dp))
}
