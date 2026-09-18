package com.example.novelreader.analyzeRule

/**
 * 正则后端（对应 Legado `AnalyzeByRegex`）。
 *
 * 逐级串联：每条 rule 对上一级结果集合做 `findAll`，命中捕获组 1 时取组 1，否则取整体匹配。
 * 规则以 `&&` 分隔的链式写法（`rule1 && rule2`）即多级串行。
 */
object AnalyzeByRegex {

    fun getElement(content: String, rules: List<String>): String? =
        getElements(content, rules).firstOrNull()

    fun getElements(content: String, rules: List<String>): List<String> {
        var result: List<String> = listOf(content)
        for (rule in rules) {
            if (rule.isBlank()) continue
            val regex = try {
                rule.toRegex()
            } catch (_: Exception) {
                continue
            }
            val newResult = ArrayList<String>()
            for (item in result) {
                for (match in regex.findAll(item)) {
                    val group1 = if (match.groups.size > 1) match.groups[1]?.value else null
                    newResult.add(group1 ?: match.value)
                }
            }
            if (newResult.isEmpty()) return emptyList()
            result = newResult
        }
        return result
    }

    fun getString(content: String, rule: String): String =
        getElements(content, listOf(rule)).joinToString("\n")
}