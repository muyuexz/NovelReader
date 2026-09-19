package com.example.novelreader.analyzeRule

/* ====================================================================
 *  第 44 批需求④：换源弹窗「信息完善」所需的共享小工具。
 *
 *  纯函数、无状态、不碰 Compose / Context，方便阅读页与详情页两处弹窗
 *  复用同一套口径，也方便单测。
 * ==================================================================== */

/**
 * 书源「规则完备度」档位 —— 与 SourceRepository 聚合代表选取同一口径。
 *
 * - 0：有目录规则（`ruleToc.chapterList` 非空）+ 有详情规则 —— 名片与目录都能出；
 * - 1：只有目录规则 —— 详情名片可能缺字段，但目录能出，仍算可用；
 * - 2：只有搜索规则 —— 详情/目录都空，当代表会让整组失效。
 */
fun ruleCompletenessOf(src: BookSource?): Int {
    val hasToc = !src?.ruleToc?.chapterList.isNullOrBlank()
    val hasInfo = src?.ruleBookInfo != null
    return when {
        hasToc && hasInfo -> 0
        hasToc -> 1
        else -> 2
    }
}

/** 档位 → 人话标签（弹窗副信息展示用）。 */
fun ruleCompletenessLabel(src: BookSource?): String = when (ruleCompletenessOf(src)) {
    0 -> "目录+详情"
    1 -> "仅目录"
    else -> "仅搜索"
}

/** 从书籍 / 书源地址里抠出可读域名（去掉 www.），失败返回空串。 */
fun hostOf(url: String?): String {
    val u = url?.trim().orEmpty()
    if (u.isEmpty()) return ""
    val host = runCatching { java.net.URI(u).host }.getOrNull()
        ?: u.substringAfter("://").substringBefore('/').substringBefore('?')
    return host.removePrefix("www.").takeIf { it.isNotBlank() } ?: ""
}

/**
 * 组装换源弹窗条目的一条副信息：`域名 · 章节数 · 规则完备度`。
 * 拿不到的字段直接省略，全空时返回「—」，保证 UI 不会出现多余分隔符。
 */
fun sourceMetaLine(book: Book): String {
    val host = hostOf(book.source?.bookSourceUrl ?: book.bookUrl)
    val count = if (book.chapterCount > 0) "${book.chapterCount} 章" else ""
    return listOfNotNull(
        host.takeIf { it.isNotBlank() },
        count.takeIf { it.isNotBlank() },
        ruleCompletenessLabel(book.source),
    ).joinToString(" · ").ifBlank { "—" }
}
