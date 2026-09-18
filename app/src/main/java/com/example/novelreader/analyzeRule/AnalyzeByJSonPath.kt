package com.example.novelreader.analyzeRule

import com.jayway.jsonpath.Configuration
import com.jayway.jsonpath.JsonPath
import com.jayway.jsonpath.Option
import com.jayway.jsonpath.PathNotFoundException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

private val jsonParser = Json { ignoreUnknownKeys = true }

/**
 * RJPath 兼容层。
 *
 * Legado 用 `com.github.jershell.rjpath.RJPath`（Jayway JsonPath 的 KMP 移植）,
 * 本工程直接以 Jayway JsonPath 复刻同一语义，保留两个入口：
 * - `selector(path).getAll(json)`：一律返回列表，无匹配返回空列表；
 * - `selector(path).read(json)` ：definite 路径返回单值，无匹配返回 null。
 *
 * 两处语义（单值直返 / 多值 joinToString）正是 Legado AnalyzeByJSonPath 的行为基线，
 * 不能改成 "总是数组"，否则单值规则会多出包装层。
 */
private object RJPath {

    private val listConf: Configuration = Configuration.defaultConfiguration()
        .addOptions(Option.ALWAYS_RETURN_LIST, Option.SUPPRESS_EXCEPTIONS)

    private val singleConf: Configuration = Configuration.defaultConfiguration()
        .addOptions(Option.SUPPRESS_EXCEPTIONS)

    fun selector(path: String): Selector = Selector(path)

    class Selector(path: String) {

        private val compiled: JsonPath? = try {
            JsonPath.compile(path)
        } catch (_: Exception) {
            null
        }

        /** 对齐 jayway `getAll`：路径无匹配 → 空列表（不抛异常）。 */
        fun getAll(json: Any?): List<Any?> {
            val compiled = compiled ?: return emptyList()
            val native = toNative(json)
            val r = try {
                compiled.read<Any?>(native, listConf)
            } catch (_: Exception) {
                null
            }
            return when (r) {
                null -> emptyList()
                is List<*> -> r
                else -> listOf(r)
            }
        }

        /** 对齐 jayway `read`：结果保持原生类型，类型转换延迟到 JS 胶水层。 */
        fun read(json: Any?): Any? {
            val compiled = compiled ?: return null
            val native = toNative(json)
            return try {
                compiled.read<Any?>(native, singleConf)
            } catch (_: PathNotFoundException) {
                null
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun toNative(value: Any?): Any? = when (value) {
        null -> null
        is JsonElement -> elementToNative(value)
        else -> value
    }

    private fun elementToNative(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.entries.associate { (k, v) -> k to elementToNative(v) }
        is JsonArray -> e.map { elementToNative(it) }
        is JsonPrimitive -> when {
            e.isString -> e.content
            else -> e.booleanOrNull
                ?: e.content.toDoubleOrNull()?.let { d -> if (d % 1.0 == 0.0) d.toLong() else d }
                ?: e.content
        }
    }
}

/**
 * JSONPath 后端。同构复刻自 Legado `io.legado.app.model.analyzeRule.AnalyzeByJSonPath`。
 *
 * 关键点（原注释意图，逐条保留）：
 * - 解决 "&&"、"||" 与 JsonPath 自带逻辑运算符的冲突 → 靠 RuleAnalyzer 平衡组切分；
 * - 解决 `{$.rule}` 内嵌替换时用正则误匹配含 `}` 的 JSON 文本 → 改用平衡嵌套。
 */
@Suppress("RegExpRedundantEscape")
class AnalyzeByJSonPath(json: Any) {

    companion object {

        fun parse(json: Any): JsonElement {
            return when (json) {
                is JsonElement -> json
                is String -> jsonParser.parseToJsonElement(json)
                // JS 引擎返回的普通 Map/List 回流(@js→$. 链)时重建。
                // JsonPath 自己的结果保持 JsonElement，不在此处提前解包。
                is Map<*, *>, is List<*> -> anyToElement(json)
                else -> jsonParser.parseToJsonElement(json.toString())
            }
        }

        /**
         * JSONPath 规则有时会作用于登录页/防爬页等 HTML 响应。
         * 这类输入不是 JSON，应按"无匹配"处理，不能在构造解析器时把整条请求打崩。
         */
        private fun parseOrNull(json: Any): JsonElement? {
            return try {
                parse(json)
            } catch (_: Exception) {
                null
            }
        }

        /** Map/List/基本类型 递归重建为 JsonElement，供解包结果回流再查询。 */
        private fun anyToElement(value: Any?): JsonElement {
            return when (value) {
                null -> JsonNull
                is JsonElement -> value
                is Map<*, *> -> JsonObject(value.entries.associate { (k, v) ->
                    k.toString() to anyToElement(v)
                })

                is List<*> -> JsonArray(value.map { anyToElement(it) })
                is String -> JsonPrimitive(value)
                is Number -> JsonPrimitive(value)
                is Boolean -> JsonPrimitive(value)
                else -> jsonParser.parseToJsonElement(value.toString())
            }
        }
    }

    // HTML 错误页/登录页误套 JSONPath 时，按空结果处理；合法 JSON 的行为不变。
    private val parsedElement: JsonElement? = parseOrNull(json)

    fun getString(rule: String): String? {
        if (rule.isEmpty()) return null
        val element = parsedElement ?: return null
        var result: String
        val ruleAnalyzes = RuleAnalyzer(rule, true) // 设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||")

        if (rules.size == 1) {
            ruleAnalyzes.reSetPos() // 将 pos 重置为 0，复用解析器
            result = ruleAnalyzes.innerRule("{$.") { getString(it) } // 替换所有 {$.rule...}
            if (result.isEmpty()) { // st 为空，表明无成功替换的内嵌规则
                try {
                    // 保持 jayway read 语义：单值直接返回，数组才 joinToString
                    val results = RJPath.selector(rule).getAll(element)
                    result = when {
                        results.isEmpty() -> ""
                        // 单值：不强制套数组，直接取该元素
                        results.size == 1 -> elementToString(results[0])
                        // 多值：各自转换后 joinToString
                        else -> results.joinToString("\n") { elementToString(it) }
                    }
                } catch (_: Exception) {
                    // 对齐 Legado printStackTraceOnDebug：仅 debug 打印
                }
            }
            return result
        } else {
            val textList = arrayListOf<String>()
            for (rl in rules) {
                val temp = getString(rl)
                if (!temp.isNullOrEmpty()) {
                    textList.add(temp)
                    if (ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            return textList.joinToString("\n")
        }
    }

    /**
     * 将 JsonElement 转为字符串，保持 jayway read 行为：
     * 基本类型取字面值，数组展开元素后 joinToString，对象取 JSON 字符串。
     */
    private fun elementToString(element: Any?): String {
        return when (element) {
            null, is JsonNull -> ""
            is JsonPrimitive -> element.content
            is JsonArray -> element.joinToString("\n") { item ->
                (item as? JsonPrimitive)?.content ?: item.toString()
            }

            is Map<*, *> -> JsonObject(
                element.entries.associate { (k, v) ->
                    k.toString() to anyToElementStatic(v)
                }
            ).toString()

            is List<*> -> JsonArray(element.map { anyToElementStatic(it) }).toString()
            is String -> element
            is Number -> formatDoubleNoDecimal(element.toDouble())
            is Boolean -> element.toString()
            else -> element.toString()
        }
    }

    fun getStringList(rule: String): List<String> {
        val result = ArrayList<String>()
        if (rule.isEmpty()) return result
        val element = parsedElement ?: return result
        val ruleAnalyzes = RuleAnalyzer(rule, true) // 设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")

        if (rules.size == 1) {
            ruleAnalyzes.reSetPos() // 将 pos 重置为 0，复用解析器
            val st = ruleAnalyzes.innerRule("{$.") { getString(it) } // 替换所有 {$.rule...}
            if (st.isEmpty()) { // st 为空，表明无成功替换的内嵌规则
                try {
                    // 保持 jayway read 语义：单值作为整体加入，数组才展开逐个加入
                    val results = RJPath.selector(rule).getAll(element)
                    for (r in results) {
                        when (r) {
                            is JsonPrimitive -> result.add(r.content)
                            is JsonArray -> {
                                for (item in r) {
                                    result.add((item as? JsonPrimitive)?.content ?: item.toString())
                                }
                            }

                            else -> result.add(if (r == null) "" else r.toString())
                        }
                    }
                } catch (_: Exception) {
                    // 仅 debug 打印
                }
            } else {
                result.add(st)
            }
            return result
        } else {
            val results = ArrayList<List<String>>()
            for (rl in rules) {
                val temp = getStringList(rl)
                if (temp.isNotEmpty()) {
                    results.add(temp)
                    if (ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.isNotEmpty()) {
                RuleCombiner.combineResults(results, ruleAnalyzes.elementsType, result)
            }
            return result
        }
    }

    fun getObject(rule: String): Any? {
        val element = parsedElement ?: return null
        return try {
            // 对齐 jayway：ctx.read(rule) 直接返回，null 就是 null。
            // 结果保持 JsonElement，类型转换延迟到 JS 胶水层。
            RJPath.selector(rule).read(element)
        } catch (_: Exception) {
            null
        }
    }

    fun getList(rule: String): ArrayList<Any> {
        val result = ArrayList<Any>()
        if (rule.isEmpty()) return result
        val element = parsedElement ?: return result
        val ruleAnalyzes = RuleAnalyzer(rule, true) // 设置平衡组为代码平衡
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")
        if (rules.size == 1) {
            try {
                // 使用 rules[0] 而非 rule，避免规则含分隔符残留导致解析失败
                val elements = RJPath.selector(rules[0]).getAll(element)
                val resultList = ArrayList<Any?>()
                for (item in elements) {
                    when (item) {
                        is JsonArray -> {
                            // 路径直接指向数组时展开元素，与 jayway read 行为一致
                            resultList.addAll(item)
                        }
                        // JsonNull/JsonPrimitive/JsonObject 均保持原生 JsonElement。
                        // 进入 JS 时再由胶水层转成 JS 原生值。
                        else -> resultList.add(item)
                    }
                }
                resultList.forEach { if (it != null) result.add(it) }
                return result
            } catch (_: Exception) {
                // 仅 debug 打印
            }
        } else {
            val results = ArrayList<List<Any>>()
            for (rl in rules) {
                val temp = getList(rl)
                if (temp.isNotEmpty()) {
                    results.add(temp)
                    if (ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.isNotEmpty()) {
                RuleCombiner.combineResults(results, ruleAnalyzes.elementsType, result)
            }
        }
        return result
    }
}

/** 文件级辅助：把任意值重建为 JsonElement（供 elementToString 输出对象/数组）。 */
private fun anyToElementStatic(value: Any?): JsonElement = when (value) {
    null -> JsonNull
    is JsonElement -> value
    is Map<*, *> -> JsonObject(value.entries.associate { (k, v) ->
        k.toString() to anyToElementStatic(v)
    })

    is List<*> -> JsonArray(value.map { anyToElementStatic(it) })
    is String -> JsonPrimitive(value)
    is Number -> JsonPrimitive(value)
    is Boolean -> JsonPrimitive(value)
    else -> JsonPrimitive(value.toString())
}
