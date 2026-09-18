package com.example.novelreader.analyzeRule

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * 书源实体（同构复刻 Legado `io.legado.app.data.entities.BookSource` 的规则相关字段）。
 *
 * 字段名与 Legado 导出的 JSON 键完全一致，便于直接吃下 `src1264.json` 这类真实书源包。
 * 解析采用宽松策略（`ignoreUnknownKeys` + `coerceInputValues` + 逐条容错），
 * 以适配真实世界里参差不齐的书源数据。
 */
@Serializable
class BookSource(
    override var bookSourceUrl: String = "",
    var bookSourceName: String = "",
    var bookSourceGroup: String? = null,
    var bookSourceType: Int = 0,
    var bookSourceComment: String? = null,
    var enabled: Boolean = true,
    var enabledExplore: Boolean = true,
    var customOrder: Int = 0,
    var lastUpdateTime: Long = 0L,
    var respondTime: Long = 180000L,
    var weight: Int = 0,
    var exploreUrl: String? = null,
    var header: String? = null,
    var loginUrl: String? = null,
    var loginUi: String? = null,
    var loginCheckJs: String? = null,
    var variable: String? = null,
    var jsLib: String? = null,
    override var enabledCookieJar: Boolean? = null,
    var bookUrlPattern: String? = null,
    var searchUrl: String? = null,
    var ruleSearch: RuleSearch? = null,
    var ruleExplore: RuleExplore? = null,
    var ruleBookInfo: RuleBookInfo? = null,
    var ruleToc: RuleToc? = null,
    var ruleContent: RuleContent? = null,
) : BaseSource {

    /**
     * 书源唯一键（对齐 Legado `BookSource.getKey()`）。
     *
     * 书源 JS 里 `source.getKey()` 会用到（如作为 cookie / 缓存键）。
     * Kotlin 的 `fun getKey()` 编译为同名 JVM 方法，Rhino 反射可直接调用。
     */
    fun getKey(): String = bookSourceUrl + "_" + bookSourceName

    override val variableMap = HashMap<String, String>()

    private val bigVariableMap = HashMap<String, String>()

    override fun putBigVariable(key: String, value: String?) {
        if (value == null) bigVariableMap.remove(key) else bigVariableMap[key] = value
    }

    override fun getBigVariable(key: String): String? = bigVariableMap[key]
}

/** Legado `RuleSearch` —— 搜索页解析规则。 */
@Serializable
class RuleSearch(
    var checkKeyWord: String? = null,
    var bookList: String? = null,
    var name: String? = null,
    var author: String? = null,
    var kind: String? = null,
    var wordCount: String? = null,
    var lastChapter: String? = null,
    var intro: String? = null,
    var coverUrl: String? = null,
    var bookUrl: String? = null,
)

/** Legado `RuleExplore` —— 发现页解析规则。 */
@Serializable
class RuleExplore(
    var bookList: String? = null,
    var name: String? = null,
    var author: String? = null,
    var kind: String? = null,
    var wordCount: String? = null,
    var lastChapter: String? = null,
    var intro: String? = null,
    var coverUrl: String? = null,
    var bookUrl: String? = null,
)

/** Legado `RuleBookInfo` —— 详情页解析规则。 */
@Serializable
class RuleBookInfo(
    var init: String? = null,
    var name: String? = null,
    var author: String? = null,
    var kind: String? = null,
    var wordCount: String? = null,
    var lastChapter: String? = null,
    var intro: String? = null,
    var coverUrl: String? = null,
    var tocUrl: String? = null,
    var canReName: String? = null,
)

/** Legado `RuleToc` —— 目录页解析规则。 */
@Serializable
class RuleToc(
    var preUpdateJs: String? = null,
    var chapterList: String? = null,
    var chapterName: String? = null,
    var chapterUrl: String? = null,
    var isVip: String? = null,
    var updateTime: String? = null,
    var nextTocUrl: String? = null,
)

/** Legado `RuleContent` —— 正文页解析规则。 */
@Serializable
class RuleContent(
    var content: String? = null,
    var nextContentUrl: String? = null,
    var webJs: String? = null,
    var sourceRegex: String? = null,
    var replaceRegex: String? = null,
    var imageStyle: String? = null,
)

/**
 * 书源 JSON 解析入口（Legado 对标 `BookSource.fromJson` / `fromJsonArray`）。
 *
 * `parseStrict` 走一次性批量解析；`parseLenient` 逐条解析，返回 (成功列表, 失败条数)，
 * 专门用于 1264 条真实书源的回归验证。
 */
object BookSourceParser {

    val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        allowStructuredMapKeys = true
        explicitNulls = false
    }

    fun parseStrict(text: String): List<BookSource> =
        json.decodeFromString(ListSerializerWrapper, text)

    /** 返回 Pair(成功解析的书源, 失败条数)。 */
    fun parseLenient(text: String): Pair<List<BookSource>, Int> {
        val roots: List<JsonElement> = json.decodeFromString(ListSerializerWrapperJson, text)
        val ok = ArrayList<BookSource>(roots.size)
        var failed = 0
        for (el in roots) {
            try {
                ok += json.decodeFromJsonElement(BookSource.serializer(), el)
            } catch (t: Throwable) {
                failed++
            }
        }
        return ok to failed
    }

    private val ListSerializerWrapper =
        kotlinx.serialization.builtins.ListSerializer(BookSource.serializer())
    private val ListSerializerWrapperJson =
        kotlinx.serialization.builtins.ListSerializer(JsonElement.serializer())
}
