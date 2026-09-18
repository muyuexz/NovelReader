package com.example.novelreader.analyzeRule

/**
 * 规则结果组合器。
 *
 * 复刻 Legado `io.legado.app.utils.RuleCombiner` 的语义（书源官方帮助定义）：
 *
 * - `&&`：多个规则的结果**依次拼接**。例如规则1得到 [a, b]，规则2得到 [1, 2]，
 *   结果为 [a, b, 1, 2]。
 * - `||`：**优先使用第一个有结果的规则**（短路）。若规则1为空则用规则2，依此类推。
 *   调用方在切分时已对 `||` 做 break，因此这里一般只会收到一个非空列表。
 * - `%%`：多个规则的结果**交替合并**。例如规则1得到 [a, b, c]，规则2得到 [1, 2, 3]，
 *   结果为 [a, 1, b, 2, c, 3]。
 * - 未指定（null / 其他）：与 `&&` 一致，直接拼接。
 */
object RuleCombiner {

    fun <T> combineResults(
        results: List<List<T>>,
        type: String?,
        target: MutableList<T>
    ) {
        if (results.isEmpty()) return
        when (type) {
            "%%" -> {
                // 交替合并：按最长列表的步长逐层取
                val maxSize = results.maxOf { it.size }
                for (i in 0 until maxSize) {
                    for (list in results) {
                        if (i < list.size) target.add(list[i])
                    }
                }
            }
            // "&&" 与 null/其他：顺序拼接
            // "||"：调用方已短路，results 至多一个非空项
            else -> results.forEach { target.addAll(it) }
        }
    }
}
