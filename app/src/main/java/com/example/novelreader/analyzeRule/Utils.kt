package com.example.novelreader.analyzeRule

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.Charset

/** 宽松解析：书源 JSON 里常有尾逗号、单引号等不规范写法。 */
internal val lenientJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/** 严格解析：@put 规则要求规范 JSON，失败才降级到宽松解析并提示。 */
internal val strictJson = Json {
    ignoreUnknownKeys = false
    isLenient = false
}

/** 内容是否为 JSON（对齐 Legado `String.isJson()`）。 */
internal fun String.isJson(): Boolean {
    val s = trim()
    if (s.isEmpty()) return false
    if (s[0] != '{' && s[0] != '[') return false
    return try {
        lenientJson.parseToJsonElement(s)
        true
    } catch (_: Exception) {
        false
    }
}

/** data: URL 不做绝对化处理。 */
internal fun String.isDataUrl(): Boolean = startsWith("data:", ignoreCase = true)

/**
 * 内容是否为 XML（对齐 Legado `String.isXml()` 的判定语义）。
 * 上游实现无独立参考文件，此处按语义复刻：去空白后以 `<` 开头、`>` 结尾。
 */
internal fun String.isXml(): Boolean {
    val s = trim()
    return s.startsWith("<") && s.endsWith(">")
}

/** HTML 实体反转义（对齐 Legado `EscapeUtils.unescapeHtml`）。 */
internal fun unescapeHtml(source: String): String =
    org.jsoup.parser.Parser.unescapeEntities(source, false)

/** URL 绝对化：相对路径基于 base 解析；base 为空时原样返回。 */
internal fun resolveUrl(base: String?, relative: String): String {
    if (relative.isEmpty()) return relative
    if (base.isNullOrEmpty()) return relative
    return try {
        val b = URI(base)
        val r = URI(relative)
        if (r.isAbsolute) relative else b.resolve(r).toString()
    } catch (_: Exception) {
        relative
    }
}

/** 整数值的 Double 去掉 `.0`（对齐 Legado `formatDoubleNoDecimal`）。 */
internal fun formatDoubleNoDecimal(value: Double): String {
    return if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong().toString()
    else value.toString()
}

/** 按分隔符切分并去掉空白项（对齐 Legado `splitNotBlank`）。 */
internal fun String.splitNotBlank(vararg delimiters: String): List<String> {
    var parts: List<String> = listOf(this)
    for (d in delimiters) {
        parts = parts.flatMap { it.split(d) }
    }
    return parts.map { it.trim() }.filter { it.isNotEmpty() }
}

/**
 * 带容量上限的 getOrPut：超过 limit 时淘汰最早插入的一项（LRU 的廉价近似）。
 *
 * 对齐 Legado `getOrPutLimit`——规则缓存、正则缓存、JS 编译缓存都用它，
 * 防止书源刷屏时缓存无限膨胀。
 */
internal fun <K, V> MutableMap<K, V>.getOrPutLimit(key: K, limit: Int, factory: () -> V): V {
    this[key]?.let { return it }
    val value = factory()
    if (size >= limit) {
        val it = iterator()
        if (it.hasNext()) {
            it.next()
            it.remove()
        }
    }
    this[key] = value
    return value
}

/** 把 JSON 对象宽松解析成 `Map<String, String>`；非对象返回 null。 */
internal fun decodeStringMapOrNull(source: String): Map<String, String>? {
    return try {
        val element = lenientJson.parseToJsonElement(source)
        val obj = element as? JsonObject ?: return null
        obj.entries.associate { (k, v) ->
            k to ((v as? JsonPrimitive)?.content ?: v.toString())
        }
    } catch (_: Exception) {
        null
    }
}
