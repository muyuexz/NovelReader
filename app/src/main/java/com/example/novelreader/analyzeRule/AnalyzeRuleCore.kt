package com.example.novelreader.analyzeRule

import kotlinx.serialization.decodeFromString
import org.jsoup.nodes.Node
import org.mozilla.javascript.Scriptable
import java.io.Closeable
import java.net.URL

/**
 * 规则引擎主体（同构复刻自 Legado `io.legado.app.model.analyzeRule.AnalyzeRuleCore`）。
 *
 * 与 Legado 的对应关系：
 * - `com.fleeksoft.ksoup.nodes.Node` → `org.jsoup.nodes.Node`（API 同构）
 * - `AppPattern.JS_PATTERN` → 本类伴生对象里的 [JS_PATTERN]（同一正则）
 * - `KS_JSON_STRICT` → [strictJson]；`decodeStringMapOrNull` 等同名工具见 Utils.kt
 * - `EscapeUtils.unescapeHtml` → [unescapeHtml]；`NetworkUtils.getAbsoluteURL` → [resolveUrl]
 * - `JsEngines` / `JsCompiledScript` → [JsEngine]（Rhino）
 * - `SourceDebugLoggers` → [SourceDebugLoggers]（Logcat 出口）
 * - `SourceNetworkProviders` → [Network]（OkHttp 出口，`java.ajax` 用）
 * - `BookLike` / `BookChapterLike` → 本包同名最小契约
 *
 * 行为对齐点（不可改）：
 * 1. `getStringList` / `getString` 的分派顺序：先判 JS 对象 → 再逐条规则按 Mode 分派；
 * 2. `SourceRule` 的 mode 判定链与 `@put/@get/{{}}/$n/##` 切分顺序；
 * 3. `replaceRegex` 的「##match##replace###」三/四段语义；
 * 4. `getString` 默认 `unescape = true`，`isUrl` 时结果走绝对化并去重。
 */
@Suppress("unused", "RegExpRedundantEscape")
open class AnalyzeRuleCore(
    var ruleData: RuleDataInterface? = null,
    private val source: BaseSource? = null,
    @Suppress("unused") private val preUpdateJs: Boolean = false
) : Closeable {

    var chapter: BookChapterLike? = null
    var nextChapterUrl: String? = null
    private var content: Any? = null
    private var baseUrl: String? = null
    private var redirectUrl: URL? = null
    private var isJSON: Boolean = false
    private var isRegex: Boolean = false

    /** JS 变量绑定：`{{key}}` / 规则里 `java.put` 的初始变量。 */
    var variables: Map<String, Any>? = null

    private var analyzeByXPath: AnalyzeByXPath? = null
    private var analyzeByJSoup: AnalyzeByJSoup? = null
    private var analyzeByJSonPath: AnalyzeByJSonPath? = null

    private val stringRuleCache = hashMapOf<String, List<SourceRule>>()
    private val regexCache = hashMapOf<String, Regex?>()

    /** JS 共享顶层作用域（jsLib 之类跨规则函数挂这里，本工程暂未启用 jsLib）。 */
    private var topScopeRef: Scriptable? = null

    private var loggedNonStandardJSON = false

    // ── 内容 / 地址 ────────────────────────────────────────────────────────

    fun setContent(content: Any?): AnalyzeRuleCore = setContent(content, null)

    fun setContent(content: Any?, baseUrl: String? = null): AnalyzeRuleCore {
        if (content == null) throw AssertionError("内容不可空（Content cannot be null）")
        this.content = content
        isJSON = when (content) {
            is Node -> false
            else -> content.toString().isJson()
        }
        setBaseUrl(baseUrl)
        analyzeByXPath = null
        analyzeByJSoup = null
        analyzeByJSonPath = null
        return this
    }

    fun setBaseUrl(baseUrl: String?): AnalyzeRuleCore {
        baseUrl?.let {
            this.baseUrl = baseUrl
            // 对齐 Legado：baseUrl 同时作为相对 URL 绝对化的基准，
            // 否则 `getString(..., isUrl = true)` 取到的相对 href 无法补全域名。
            setRedirectUrl(baseUrl)
        }
        return this
    }

    fun setRedirectUrl(url: String): URL? {
        if (url.isDataUrl()) {
            return redirectUrl
        }
        try {
            redirectUrl = URL(url)
        } catch (e: Exception) {
            SourceDebugLoggers.log("URL($url) error\n${e.message}")
        }
        return redirectUrl
    }

    /** redirectUrl 转绝对地址（对齐 Legado `NetworkUtils.getAbsoluteURL`）。 */
    protected open fun getAbsoluteURL(redirectUrl: URL?, relativePath: String): String {
        return resolveUrl(redirectUrl?.toString(), relativePath)
    }

    // ── 三个后端解析器缓存 ────────────────────────────────────────────────

    private fun getAnalyzeByXPath(o: Any): AnalyzeByXPath {
        return if (o != content) {
            AnalyzeByXPath(o)
        } else {
            if (analyzeByXPath == null) {
                analyzeByXPath = AnalyzeByXPath(content!!)
            }
            analyzeByXPath!!
        }
    }

    private fun getAnalyzeByJSoup(o: Any): AnalyzeByJSoup {
        return if (o != content) {
            AnalyzeByJSoup(o)
        } else {
            if (analyzeByJSoup == null) {
                analyzeByJSoup = AnalyzeByJSoup(content!!)
            }
            analyzeByJSoup!!
        }
    }

    private fun getAnalyzeByJSonPath(o: Any): AnalyzeByJSonPath {
        return if (o != content) {
            AnalyzeByJSonPath(o)
        } else {
            if (analyzeByJSonPath == null) {
                analyzeByJSonPath = AnalyzeByJSonPath(content!!)
            }
            analyzeByJSonPath!!
        }
    }

    // ── getStringList ─────────────────────────────────────────────────────

    fun getStringList(rule: String?): List<String>? = getStringList(rule, null, false)

    fun getStringList(rule: String?, mContent: Any?): List<String>? =
        getStringList(rule, mContent, false)

    fun getStringList(rule: String?, mContent: Any? = null, isUrl: Boolean = false): List<String>? {
        if (rule.isNullOrEmpty()) return null
        val ruleList = splitSourceRuleCacheString(rule)
        return getStringList(ruleList, mContent, isUrl)
    }

    fun getStringList(ruleList: List<SourceRule>): List<String>? =
        getStringList(ruleList, null, false)

    fun getStringList(ruleList: List<SourceRule>, mContent: Any?): List<String>? =
        getStringList(ruleList, mContent, false)

    fun getStringList(
        ruleList: List<SourceRule>,
        mContent: Any? = null,
        isUrl: Boolean = false
    ): List<String>? {
        var result: Any? = null
        val content = mContent ?: this.content
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            // JS 返回的对象在 Kotlin 侧是 NativeObject（Rhino），走键值直取分支；
            // JsonPath 返回的普通 Map 走 else 分支，继续执行后续 JS/JsonPath 等。
            val jsObj = JsEngine.asJsObject(result)
            if (jsObj != null) {
                val sourceRule = ruleList.first()
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(jsObj)
                result = if (sourceRule.getParamSize() > 1) {
                    // get {{}}
                    sourceRule.rule
                } else {
                    // 键值直接访问
                    JsEngine.getProperty(jsObj, sourceRule.rule)
                }
                // 注意：不能让闭包捕获并修改 result，否则后续所有 smart cast 都会失效。
                val jsResult = result
                if (jsResult != null) {
                    if (sourceRule.replaceRegex.isNotEmpty() && jsResult is List<*>) {
                        result = jsResult.map { o ->
                            replaceRegex(o.toString(), sourceRule)
                        }
                    } else if (sourceRule.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(jsResult.toString(), sourceRule)
                    }
                }
            } else {
                for (sourceRule in ruleList) {
                    putRule(sourceRule.putMap)
                    sourceRule.makeUpRule(result)
                    result ?: continue
                    val rule = sourceRule.rule
                    if (rule.isNotEmpty()) {
                        result = when (sourceRule.mode) {
                            Mode.Js -> evalJS(rule, result)
                            Mode.Json -> getAnalyzeByJSonPath(result).getStringList(rule)
                            Mode.XPath -> getAnalyzeByXPath(result).getStringList(rule)
                            Mode.Default -> getAnalyzeByJSoup(result).getStringList(rule)
                            else -> rule
                        }
                    }
                    if (sourceRule.replaceRegex.isNotEmpty() && result is List<*>) {
                        val newList = ArrayList<String>()
                        for (item in result) {
                            newList.add(replaceRegex(item.toString(), sourceRule))
                        }
                        result = newList
                    } else if (sourceRule.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(result.toString(), sourceRule)
                    }
                }
            }
        }
        if (result == null) return null
        if (result is String) {
            result = result.split("\n")
        }
        if (isUrl) {
            val urlList = ArrayList<String>()
            if (result is List<*>) {
                for (url in result) {
                    val absoluteURL = getAbsoluteURL(redirectUrl, url.toString())
                    if (absoluteURL.isNotEmpty() && !urlList.contains(absoluteURL)) {
                        urlList.add(absoluteURL)
                    }
                }
            }
            return urlList
        }
        @Suppress("UNCHECKED_CAST")
        return result as? List<String>
    }

    // ── getString ─────────────────────────────────────────────────────────

    fun getString(ruleStr: String?): String = getString(ruleStr, null, false)

    fun getString(ruleStr: String?, mContent: Any?): String = getString(ruleStr, mContent, false)

    fun getString(ruleStr: String?, mContent: Any? = null, isUrl: Boolean = false): String {
        if (ruleStr.isNullOrEmpty()) return ""
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getString(ruleList, mContent, isUrl)
    }

    fun getString(ruleStr: String?, unescape: Boolean): String {
        if (ruleStr.isNullOrEmpty()) return ""
        val ruleList = splitSourceRuleCacheString(ruleStr)
        return getString(ruleList, unescape = unescape)
    }

    fun getString(ruleList: List<SourceRule>): String = getString(
        ruleList, null, isUrl = false,
        unescape = true
    )

    fun getString(ruleList: List<SourceRule>, mContent: Any?): String =
        getString(ruleList, mContent, isUrl = false, unescape = true)

    fun getString(ruleList: List<SourceRule>, mContent: Any?, isUrl: Boolean): String =
        getString(ruleList, mContent, isUrl, true)

    fun getString(
        ruleList: List<SourceRule>,
        mContent: Any? = null,
        isUrl: Boolean = false,
        unescape: Boolean = true
    ): String {
        var result: Any? = null
        val content = mContent ?: this.content
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            // 否则 `$.postTime<js>格式化</js>` 这类规则只取到原始时间戳，JS 被跳过。
            val jsObj = JsEngine.asJsObject(result)
            if (jsObj != null) {
                val sourceRule = ruleList.first()
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(jsObj)
                result = if (sourceRule.getParamSize() > 1) {
                    // get {{}}
                    sourceRule.rule
                } else {
                    // 键值直接访问
                    JsEngine.getProperty(jsObj, sourceRule.rule)?.toString()
                }?.let {
                    replaceRegex(it, sourceRule)
                }
            } else {
                for (sourceRule in ruleList) {
                    putRule(sourceRule.putMap)
                    sourceRule.makeUpRule(result)
                    result ?: continue
                    val rule = sourceRule.rule
                    if (rule.isNotBlank() || sourceRule.replaceRegex.isEmpty()) {
                        result = when (sourceRule.mode) {
                            Mode.Js -> evalJS(rule, result)
                            Mode.Json -> getAnalyzeByJSonPath(result).getString(rule)
                            Mode.XPath -> getAnalyzeByXPath(result).getString(rule)
                            Mode.Default -> if (isUrl) {
                                getAnalyzeByJSoup(result).getString0(rule)
                            } else {
                                getAnalyzeByJSoup(result).getString(rule)
                            }

                            else -> rule
                        }
                    }
                    if (result != null && sourceRule.replaceRegex.isNotEmpty()) {
                        result = replaceRegex(result.toString(), sourceRule)
                    }
                }
            }
        }
        if (result == null) result = ""
        val resultStr = result.toString()
        val str = if (unescape && resultStr.indexOf('&') > -1) {
            unescapeHtml(resultStr)
        } else {
            resultStr
        }
        if (str.indexOf("::") > -1) {
            return str
        }
        if (isUrl) {
            return if (str.isBlank()) {
                baseUrl ?: ""
            } else {
                if (str.isDataUrl()) str
                else getAbsoluteURL(redirectUrl, str)
            }
        }
        return str
    }

    // ── getElement / getElements ──────────────────────────────────────────

    /**
     * 获取 Element
     */
    fun getElement(ruleStr: String): Any? {
        if (ruleStr.isEmpty()) return null
        var result: Any? = null
        val content = this.content
        val ruleList = splitSourceRule(ruleStr, true)
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                sourceRule.makeUpRule(result)
                result ?: continue
                val rule = sourceRule.rule
                result = when (sourceRule.mode) {
                    Mode.Regex -> AnalyzeByRegex.getElement(
                        result.toString(),
                        rule.splitNotBlank("&&")
                    )

                    Mode.Js -> evalJS(rule, result)
                    Mode.Json -> getAnalyzeByJSonPath(result).getObject(rule)
                    Mode.XPath -> getAnalyzeByXPath(result).getElements(rule)
                    else -> getAnalyzeByJSoup(result).getElements(rule)
                }
                if (sourceRule.replaceRegex.isNotEmpty()) {
                    result = replaceRegex(result.toString(), sourceRule)
                }
            }
        }
        return result
    }

    /**
     * 获取列表
     */
    @Suppress("UNCHECKED_CAST")
    fun getElements(ruleStr: String): List<Any> {
        var result: Any? = null
        val content = this.content
        val ruleList = splitSourceRule(ruleStr, true)
        if (content != null && ruleList.isNotEmpty()) {
            result = content
            for (sourceRule in ruleList) {
                putRule(sourceRule.putMap)
                result ?: continue
                val rule = sourceRule.rule
                result = when (sourceRule.mode) {
                    Mode.Regex -> AnalyzeByRegex.getElements(
                        result.toString(),
                        rule.splitNotBlank("&&")
                    )

                    Mode.Js -> evalJS(rule, result)
                    Mode.Json -> getAnalyzeByJSonPath(result).getList(rule)
                    Mode.XPath -> getAnalyzeByXPath(result).getElements(rule)
                    else -> getAnalyzeByJSoup(result).getElements(rule)
                }
            }
        }
        result?.let {
            return it as List<Any>
        }
        return ArrayList()
    }

    // ── putRule / splitPutRule / replaceRegex ─────────────────────────────

    /**
     * 保存变量
     */
    private fun putRule(map: Map<String, String>) {
        for ((key, value) in map) {
            put(key, getString(value))
        }
    }

    /**
     * 分离 put 规则
     */
    private fun splitPutRule(ruleStr: String, putMap: HashMap<String, String>): String {
        var vRuleStr = ruleStr
        // Pattern.matcher → Regex.findAll: match.value 对应 matcher.group(), groupValues[1] 对应 matcher.group(1)
        for (match in putPattern.findAll(vRuleStr)) {
            vRuleStr = vRuleStr.replace(match.value, "")
            val putJsonStr = match.groupValues[1]
            // 复刻原 GSONStrict 严格解析失败时降级到宽松解析（并触发一次 log 提示 JSON 格式不规范）
            val strictMap: Map<String, String>? = try {
                strictJson.decodeFromString(putJsonStr)
            } catch (_: Exception) {
                null
            }
            if (strictMap != null) {
                putMap.putAll(strictMap)
                continue
            }
            val lenientMap = decodeStringMapOrNull(putJsonStr)
            if (lenientMap != null) {
                if (!loggedNonStandardJSON) {
                    SourceDebugLoggers.log("≡@put 规则 JSON 格式不规范，请改为规范格式")
                    loggedNonStandardJSON = true
                }
                putMap.putAll(lenientMap)
            }
        }
        return vRuleStr
    }

    /**
     * 正则替换
     */
    private fun replaceRegex(result: String, rule: SourceRule): String {
        if (rule.replaceRegex.isEmpty()) return result
        val replaceRegex = rule.replaceRegex
        val replacement = rule.replacement
        val regex = compileRegexCache(replaceRegex)
        if (rule.replaceFirst) {
            /* ##match##replace### 获取第一个匹配到的结果并进行替换 */
            if (regex != null) runCatching {
                // regex.find: match.value 对应 matcher.group(0)（整个匹配）
                val match = regex.find(result)
                return match?.value?.replaceFirst(regex, replacement) ?: ""
            }
            return replacement
        } else {
            /* ##match##replace 替换 */
            if (regex != null) runCatching {
                return result.replace(regex, replacement)
            }
            return result.replace(replaceRegex, replacement)
        }
    }

    private fun compileRegexCache(regex: String): Regex? {
        return regexCache.getOrPutLimit(regex, 16) {
            try {
                regex.toRegex()
            } catch (e: Exception) {
                null
            }
        }
    }

    // ── 规则分解 ──────────────────────────────────────────────────────────

    /**
     * getString 类规则缓存
     */
    private fun splitSourceRuleCacheString(ruleStr: String?): List<SourceRule> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        return stringRuleCache.getOrPut(ruleStr) {
            splitSourceRule(ruleStr)
        }
    }

    /**
     * 分解规则生成规则列表
     */
    fun splitSourceRule(ruleStr: String?, allInOne: Boolean = false): List<SourceRule> {
        if (ruleStr.isNullOrEmpty()) return emptyList()
        val ruleList = ArrayList<SourceRule>()
        var mMode: Mode = Mode.Default
        var start = 0
        // 仅首字符为:时为 AllInOne，其实 : 与伪类选择器冲突，建议改成 ? 更合理
        if (allInOne && ruleStr.startsWith(":")) {
            mMode = Mode.Regex
            isRegex = true
            start = 1
        } else if (isRegex) {
            mMode = Mode.Regex
        }
        var tmp: String
        // groups[n]?.value 对应 matcher.group(n)（null 若组未参与匹配）
        for (match in JS_PATTERN.findAll(ruleStr)) {
            if (match.range.first > start) {
                tmp = ruleStr.substring(start, match.range.first).trim()
                if (tmp.isNotEmpty()) {
                    ruleList.add(SourceRule(tmp, mMode))
                }
            }
            ruleList.add(SourceRule(match.groups[2]?.value ?: match.groups[1]?.value ?: "", Mode.Js))
            start = match.range.last + 1
        }

        if (ruleStr.length > start) {
            tmp = ruleStr.substring(start).trim()
            if (tmp.isNotEmpty()) {
                ruleList.add(SourceRule(tmp, mMode))
            }
        }

        return ruleList
    }

    private fun getOrCreateSingleSourceRule(rule: String): List<SourceRule> {
        return stringRuleCache.getOrPutLimit(rule, 16) {
            listOf(SourceRule(rule))
        }
    }

    /**
     * 规则类
     */
    inner class SourceRule internal constructor(
        ruleStr: String,
        internal var mode: Mode = Mode.Default
    ) {
        internal var rule: String
        internal var replaceRegex = ""
        internal var replacement = ""
        internal var replaceFirst = false
        internal val putMap = HashMap<String, String>()
        private val ruleParam = ArrayList<String>()
        private val ruleType = ArrayList<Int>()
        private val getRuleType = -2
        private val jsRuleType = -1
        private val defaultRuleType = 0

        init {
            rule = when {
                mode == Mode.Js || mode == Mode.Regex -> ruleStr
                ruleStr.startsWith("@CSS:", true) -> {
                    mode = Mode.Default
                    ruleStr
                }

                ruleStr.startsWith("@@") -> {
                    mode = Mode.Default
                    ruleStr.substring(2)
                }

                ruleStr.startsWith("@XPath:", true) -> {
                    mode = Mode.XPath
                    ruleStr.substring(7)
                }

                ruleStr.startsWith("@Json:", true) -> {
                    mode = Mode.Json
                    ruleStr.substring(6)
                }

                isJSON || ruleStr.startsWith("$.") || ruleStr.startsWith("$[") -> {
                    mode = Mode.Json
                    ruleStr
                }

                ruleStr.startsWith("/") -> { // XPath 特征很明显，无需配置单独的识别标头
                    mode = Mode.XPath
                    ruleStr
                }

                else -> ruleStr
            }
            // 分离 put
            rule = splitPutRule(rule, putMap)
            // @get, {{ }}, 拆分
            var start = 0
            var tmp: String
            // match.range.first 对应 matcher.start(), match.range.last + 1 对应 matcher.end()
            val evalMatches = evalPattern.findAll(rule).toList()
            if (evalMatches.isNotEmpty()) {
                val firstMatch = evalMatches.first()
                tmp = rule.substring(start, firstMatch.range.first)
                if (mode != Mode.Js && mode != Mode.Regex &&
                    (firstMatch.range.first == 0 || !tmp.contains("##"))
                ) {
                    mode = Mode.Regex
                }
                for (match in evalMatches) {
                    if (match.range.first > start) {
                        tmp = rule.substring(start, match.range.first)
                        splitRegex(tmp)
                    }
                    tmp = match.value
                    when {
                        tmp.startsWith("@get:", true) -> {
                            ruleType.add(getRuleType)
                            ruleParam.add(tmp.substring(6, tmp.lastIndex))
                        }

                        tmp.startsWith("{{") -> {
                            ruleType.add(jsRuleType)
                            ruleParam.add(tmp.substring(2, tmp.length - 2))
                        }

                        else -> {
                            splitRegex(tmp)
                        }
                    }
                    start = match.range.last + 1
                }
            }
            if (rule.length > start) {
                tmp = rule.substring(start)
                splitRegex(tmp)
            }
        }

        /**
         * 拆分 \$\d{1,2}
         */
        private fun splitRegex(ruleStr: String) {
            var start = 0
            var tmp: String
            val ruleStrArray = ruleStr.split("##")
            // 先收集所有匹配，替代 while(find) + do-while(find) 语义
            val regexMatches = regexPattern.findAll(ruleStrArray[0]).toList()
            if (regexMatches.isNotEmpty()) {
                if (mode != Mode.Js && mode != Mode.Regex) {
                    mode = Mode.Regex
                }
                for (match in regexMatches) {
                    if (match.range.first > start) {
                        tmp = ruleStr.substring(start, match.range.first)
                        ruleType.add(defaultRuleType)
                        ruleParam.add(tmp)
                    }
                    tmp = match.value
                    ruleType.add(tmp.substring(1).toInt())
                    ruleParam.add(tmp)
                    start = match.range.last + 1
                }
            }
            if (ruleStr.length > start) {
                tmp = ruleStr.substring(start)
                ruleType.add(defaultRuleType)
                ruleParam.add(tmp)
            }
        }

        /**
         * 替换 @get,{{ }}
         */
        fun makeUpRule(result: Any?) {
            val infoVal = StringBuilder()
            if (ruleParam.isNotEmpty()) {
                var index = ruleParam.size
                while (index-- > 0) {
                    val regType = ruleType[index]
                    when {
                        regType > defaultRuleType -> {
                            @Suppress("UNCHECKED_CAST")
                            (result as? List<String?>)?.run {
                                if (this.size > regType) {
                                    this[regType]?.let {
                                        infoVal.insert(0, it)
                                    }
                                }
                            } ?: infoVal.insert(0, ruleParam[index])
                        }

                        regType == jsRuleType -> {
                            if (isRule(ruleParam[index])) {
                                val ruleList = getOrCreateSingleSourceRule(ruleParam[index])
                                getString(ruleList).let {
                                    infoVal.insert(0, it)
                                }
                            } else {
                                when (val jsEval: Any? = evalJS(ruleParam[index], result)) {
                                    null -> Unit
                                    is String -> infoVal.insert(0, jsEval)
                                    is Double -> if (jsEval % 1.0 == 0.0) {
                                        infoVal.insert(0, formatDoubleNoDecimal(jsEval))
                                    } else {
                                        infoVal.insert(0, jsEval.toString())
                                    }

                                    else -> infoVal.insert(0, jsEval.toString())
                                }
                            }
                        }

                        regType == getRuleType -> {
                            infoVal.insert(0, get(ruleParam[index]))
                        }

                        else -> infoVal.insert(0, ruleParam[index])
                    }
                }
                rule = infoVal.toString()
            }
            // 分离正则表达式
            val ruleStrS = rule.split("##")
            rule = ruleStrS[0].trim()
            if (ruleStrS.size > 1) {
                replaceRegex = ruleStrS[1]
            }
            if (ruleStrS.size > 2) {
                replacement = ruleStrS[2]
            }
            if (ruleStrS.size > 3) {
                replaceFirst = true
            }
        }

        private fun isRule(ruleStr: String): Boolean {
            return ruleStr.startsWith('@') // js 首个字符不可能是 @，除非是装饰器，所以 @ 开头规定为规则
                || ruleStr.startsWith("$.")
                || ruleStr.startsWith("$[")
                || ruleStr.startsWith("//")
        }

        fun getParamSize(): Int {
            return ruleParam.size
        }
    }

    enum class Mode {
        XPath, Json, Default, Js, Regex
    }

    // ── 变量读写 ──────────────────────────────────────────────────────────

    /**
     * 保存数据
     */
    fun put(key: String, value: String): String {
        if (key == "bookName" || key == "title") {
            SourceDebugLoggers.log("≡变量 $key 在特定情况下会被覆盖，建议使用其他键名")
        }
        chapter?.putVariable(key, value)
            ?: ruleData?.putVariable(key, value)
            ?: source?.put(key, value)
        return value
    }

    /**
     * 获取保存的数据
     */
    fun get(key: String): String {
        when (key) {
            "bookName" -> (ruleData as? BookLike)?.let {
                return it.name
            }

            "title" -> chapter?.let {
                return it.title
            }
        }
        return chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: source?.get(key)?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    // ── JS 执行 ───────────────────────────────────────────────────────────

    /**
     * 执行 JS（Rhino）。
     *
     * 与 Legado（QuickJS + IIFE/eval 包装）的等价点：
     * - 每次 evalJS 新建子作用域（原型指向 [topScopeRef]），顶层 `let/const` 不污染共享作用域，
     *   避免重复执行报 `redeclaration of 'xxx'`；
     * - Rhino `evaluateString` 本身返回最后一条表达式的完成值，无需 IIFE 包装；
     * - binding 全部注入当前子作用域，书源 JS 里 `java`/`src`/`baseUrl`/`result` 等可直接访问。
     */
    fun evalJS(jsStr: String, result: Any? = null): Any? {
        // 空字符串早返回，避免不必要的编译执行开销
        if (jsStr.isBlank()) return null
        val scope = JsEngine.newChildScope(topScopeRef)
        buildBindings(result).forEach { (k, v) -> JsEngine.putBinding(scope, k, v) }
        return try {
            JsEngine.eval(scope, jsStr)
        } catch (e: Exception) {
            throw RuntimeException("JS执行出错\n${e.message}", e)
        }
    }

    private fun buildBindings(result: Any?): Map<String, Any?> {
        val m = LinkedHashMap<String, Any?>()
        variables?.forEach { (k, v) -> m[k] = v }
        m["java"] = this
        m["source"] = source
        m["book"] = ruleData as? BookLike
        m["result"] = result
        m["baseUrl"] = baseUrl
        m["chapter"] = chapter
        m["title"] = chapter?.title
        m["src"] = content
        m["nextChapterUrl"] = nextChapterUrl
        return m
    }

    // ── 网络 / 生命周期 ───────────────────────────────────────────────────

    open fun getSource(): BaseSource? {
        return source
    }

    /**
     * JS 实现跨域访问（书源 JS 里 `java.ajax(url)`），不能删。
     */
    open fun ajax(url: Any): String? {
        val urlStr = if (url is List<*>) {
            url.firstOrNull().toString()
        } else {
            url.toString()
        }
        return runCatching {
            Network.fetch(urlStr)
        }.onFailure {
            SourceDebugLoggers.log("ajax(${urlStr}) error\n${it.stackTraceToString()}")
        }.getOrElse {
            it.stackTraceToString()
        }
    }

    /**
     * 释放持有的 JS 作用域引用（本工程未启用共享 jsLib，通常为空操作）。
     */
    override fun close() {
        topScopeRef = null
    }

    companion object {
        /** 拆分 `<js>...</js>` / `@js:`（对应 Legado `AppPattern.JS_PATTERN`）。 */
        val JS_PATTERN = Regex(
            "<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)",
            setOf(RegexOption.IGNORE_CASE)
        )

        // setOf(RegexOption.IGNORE_CASE) 对应 Pattern.CASE_INSENSITIVE
        private val putPattern = Regex("@put:(\\{[^}]+?\\})", setOf(RegexOption.IGNORE_CASE))
        private val evalPattern =
            Regex("@get:\\{[^}]+?\\}|\\{\\{[\\w\\W]*?\\}\\}", setOf(RegexOption.IGNORE_CASE))
        private val regexPattern = Regex("\\$\\d{1,2}")
    }
}