package com.example.novelreader.analyzeRule

import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * URL 解析核心（对齐 Legado `io.legado.app.model.analyzeRule.AnalyzeUrlCore`）。
 *
 * 本地化裁剪说明（相对上游 655 行原文）：
 * - 去掉 KMP 抽象（`KmpHttpClient` / `KmpRequestBuilder` / `expect-actual`），直接用 OkHttp；
 * - 去掉协程（`CoroutineContext` / `runBlockingInScope` / `suspend`），改为同步方法；
 * - 去掉限速器 `ConcurrentRateLimiter`（后续如需再补）；
 * - 去掉 WebView 后台渲染 `BackstageWebViewProviders`，`useWebView` 时降级 OkHttp 并记日志；
 * - Cookie 走本工程 [CookieStore] 内存实现，接口面与上游一致。
 *
 * 流水线（与上游零 diff）：initUrl → analyzeJs → replaceKeyPageJs → analyzeUrl → analyzeParams。
 */
open class AnalyzeUrlCore(
    val rawUrl: String,
    baseUrl: String = "",
    val source: BaseSource? = null,
    val ruleData: RuleDataInterface? = null,
    val chapter: BookChapterLike? = null,
    val readTimeout: Long? = null,
    val callTimeout: Long? = null,
    val headerMapF: (() -> Map<String, String>)? = null,
    val hasLoginHeader: Boolean = false,
    val selectedOptions: Map<String, String>? = null,
    val variables: Map<String, String>? = null,
) {

    /** 解析完成的最终 URL（已绝对化、已替换模板与选项）。 */
    var url: String = ""
        private set

    /** 去掉 query 后的 URL（GET 时 query 走 [encodedParams]）。 */
    var urlNoQuery: String = ""
        private set

    /** 已编码的参数：GET 为 query 串，POST 为表单串。 */
    var encodedParams: String? = null
        private set

    var option: UrlOption? = null
        private set

    var domain: String = ""
        private set

    var baseUrl: String = baseUrl
        private set

    private var tmpUrl: String = rawUrl

    /** @js / <js> 执行后的 URL，供登录检测后复用。 */
    var urlAfterJs: String = rawUrl
        private set

    /** URL 级请求头（书源 header → 此处 → 请求）。 */
    private val headerMap = LinkedHashMap<String, String>()

    init {
        headerMapF?.invoke()?.let { headerMap.putAll(it) }
        source?.getHeaderMap(hasLoginHeader) { evalJS(it) }?.let { headerMap.putAll(it) }

        if (this.baseUrl.isBlank()) this.baseUrl = source?.bookSourceUrl ?: ""
        if (this.baseUrl.contains("{")) {
            val match = paramPattern.find(this.baseUrl)
            if (match != null) this.baseUrl = this.baseUrl.substring(0, match.range.first)
        }
        // 前置执行 initUrl()，这样 source.header 里的 js 才能拿到相关参数
        initUrl()

        domain = NetworkUtils.getSubDomain(
            source?.bookSourceUrl?.takeIf { it.startsWith("http") } ?: url
        )
    }

    /**
     * 处理 url，可由书源 JS 在登录检测后再次调用以重新解析。
     */
    fun initUrl() {
        tmpUrl = rawUrl
        analyzeJs()
        urlAfterJs = tmpUrl
        tmpUrl = replaceKeyPageJs(replaceDynamicOptions(tmpUrl))
        analyzeUrl()
    }

    private fun replaceDynamicOptions(curRuleUrl: String): String =
        replaceExploreOptionsInUrl(curRuleUrl) { name -> selectedOptions?.get(name) }

    /**
     * 执行 @js, <js></js>。
     */
    private fun analyzeJs() {
        if (!tmpUrl.contains("js")) return
        var result = tmpUrl
        var start = 0
        fun useSegment(end: Int) {
            tmpUrl.substring(start, end).trim().takeIf { it.isNotEmpty() }?.let { result = it }
        }
        for (match in JS_PATTERN.findAll(tmpUrl)) {
            useSegment(match.range.first)
            result = evalJS(match.groups[2]?.value ?: match.groups[1]?.value ?: "", result).toString()
            start = match.range.last + 1
        }
        useSegment(tmpUrl.length)
        tmpUrl = result
    }

    /**
     * 替换 `{{...}}` 内嵌规则（关键字/页数/JS）。
     */
    private fun replaceKeyPageJs(curRuleUrl: String): String {
        // 先替换内嵌规则再替换页数规则，避免内嵌规则中存在大于小于号时规则被切错
        if (curRuleUrl.contains("{{") && curRuleUrl.contains("}}")) {
            val res = RuleAnalyzer(curRuleUrl).innerRule("{{", "}}") {
                when (val jsEval = evalJS(it) ?: "") {
                    is String -> jsEval
                    is Double -> if (jsEval % 1.0 == 0.0) formatDoubleNoDecimal(jsEval) else jsEval.toString()
                    else -> jsEval.toString()
                }
            }
            if (res.isNotEmpty()) return res
        }
        return curRuleUrl
    }

    /**
     * 解析 URL：拆出 `,{...}` 选项，绝对化，处理 POST/GET 参数。
     */
    private fun analyzeUrl() {
        var urlNoOption = tmpUrl
        var urlOptionEnd = -1
        if (tmpUrl.contains("{")) {
            val match = paramPattern.find(tmpUrl)
            if (match != null) {
                urlNoOption = tmpUrl.substring(0, match.range.first)
                urlOptionEnd = match.range.last + 1
            }
        }
        url = if (urlNoOption.isDataUrl()) urlNoOption
        else NetworkUtils.getAbsoluteURL(baseUrl, urlNoOption)
        NetworkUtils.getBaseUrl(url)?.let { baseUrl = it }

        if (urlOptionEnd != -1) {
            val urlOptionStr = tmpUrl.substring(urlOptionEnd)
            option = parseUrlOptionOrNull(urlOptionStr) ?: run {
                SourceDebugLoggers.log("链接参数 JSON 格式不规范，请改为规范格式")
                null
            }
            option?.let { opt ->
                opt.headers?.forEach { (k, v) -> headerMap[k] = v.toString() }
                opt.js?.let { js -> evalJS(js, url)?.toString()?.let { url = it } }
            }
        }

        urlNoQuery = url
        if (isPost()) {
            val body = option?.body
            if (body != null && !body.isJson() && !body.isXml() && headerMap["Content-Type"].isNullOrEmpty()) {
                analyzeParams(body, false)
            }
        } else {
            val pos = url.indexOf('?')
            if (pos != -1) {
                analyzeParams(url.substring(pos + 1), true)
                urlNoQuery = url.substring(0, pos)
            }
        }
    }

    /**
     * 解析参数 `<key>=<value>`。
     * isQuery=true 时是 URL query，false 时是 POST form body。
     */
    private fun analyzeParams(text: String, isQuery: Boolean) {
        encodedParams = encodeUrlParams(text, option?.charset, isQuery)
    }

    /**
     * 执行 JS。空串早返回，避免不必要的编译开销。
     */
    fun evalJS(jsStr: String, result: Any? = null): Any? {
        if (jsStr.isBlank()) return null
        val scope = JsEngine.newScope()
        return try {
            JsEngine.putBinding(scope, "java", this)
            JsEngine.putBinding(scope, "baseUrl", url.ifEmpty { baseUrl })
            JsEngine.putBinding(scope, "book", ruleData)
            JsEngine.putBinding(scope, "chapter", chapter)
            JsEngine.putBinding(scope, "source", source)
            JsEngine.putBinding(scope, "result", result)
            JsEngine.putBinding(scope, "cookie", CookieManagerProxy)
            JsEngine.putBinding(scope, "cache", null)
            variables?.forEach { (k, v) -> JsEngine.putBinding(scope, k, v) }
            JsEngine.eval(scope, jsStr)
        } finally {
            // Rhino 的 scope 由 GC 回收，无需显式 close
        }
    }

    fun put(key: String, value: String): String {
        if (key == "bookName" || key == "title") {
            SourceDebugLoggers.log("≡变量 $key 在特定情况下会被覆盖，建议使用其他键名")
        }
        chapter?.putVariable(key, value) ?: ruleData?.putVariable(key, value)
        return value
    }

    fun get(key: String): String = when (key) {
        "bookName" -> (ruleData as? BookLike)?.name ?: ""
        "title" -> chapter?.title ?: ""
        else -> chapter?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ruleData?.getVariable(key)?.takeIf { it.isNotEmpty() }
            ?: ""
    }

    /**
     * 访问网站，返回 [StrResponse]。
     */
    fun getStrResponse(
        jsStr: String? = null,
        sourceRegex: String? = null,
        allowWebView: Boolean = true,
    ): StrResponse {
        getByteArrayIfDataUri()?.let { return StrResponse(url, it.toHexString()) }
        setCookie()
        try {
            return if (option?.useWebView == true) {
                SourceDebugLoggers.log("本工程暂未实现 WebView 渲染，已降级为 OkHttp 请求")
                getOkHttpStrResponse()
            } else {
                getOkHttpStrResponse()
            }
        } finally {
            saveCookie()
        }
    }

    fun getStrResponse(): StrResponse = getStrResponse(null, null, true)
    fun getStrResponse(jsStr: String?): StrResponse = getStrResponse(jsStr, null, true)
    fun getStrResponse(jsStr: String?, sourceRegex: String?): StrResponse =
        getStrResponse(jsStr, sourceRegex, true)

    /**
     * OkHttp 方式获取 [StrResponse]。
     */
    private fun getOkHttpStrResponse(): StrResponse {
        val req = Network.Req(
            url = urlNoQuery,
            method = option?.method ?: "GET",
            headers = LinkedHashMap(headerMap),
            encodedParams = encodedParams,
            body = option?.body,
            charset = charsetFrom(option?.charset),
            retry = option?.retry ?: 0,
            readTimeoutMs = readTimeout,
            callTimeoutMs = callTimeout,
        )
        val res = Network.execute(req)
        val isXml = res.contentType?.matches(AppPattern.xmlContentTypeRegex) == true
        return if (isXml && res.body?.trim()?.startsWith("<?xml", true) == false) {
            StrResponse(res.url, "<?xml version=\"1.0\"?>" + res.body)
        } else res
    }

    private fun getByteArrayIfDataUri(): ByteArray? {
        if (!url.isDataUrl()) return null
        val pos = urlNoQuery.indexOf(";base64,")
        return if (pos != -1) {
            android.util.Base64.decode(urlNoQuery.substring(pos + 8), android.util.Base64.DEFAULT)
        } else {
            ByteArray(0)
        }
    }

    private fun ByteArray.toHexString(): String {
        if (isEmpty()) return ""
        val sb = StringBuilder(size * 2)
        for (b in this) {
            sb.append(HEX[(b.toInt() ushr 4) and 0xF]).append(HEX[b.toInt() and 0xF])
        }
        return sb.toString()
    }

    private fun charsetFrom(name: String?): Charset? {
        if (name.isNullOrBlank() || name.equals("escape", true)) return null
        return try {
            Charset.forName(name)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 设置 cookie 优先级：书源库 cookie 合并进已有 `Cookie` 头。
     * 启用 CookieJar 时打上伪头，由 [Network] 摘除并做自动存取。
     */
    protected fun setCookie() {
        val cookie = CookieStore.get(domain)
        if (cookie.isNotEmpty()) {
            mergeCookies(cookie, headerMap["Cookie"])?.let { headerMap["Cookie"] = it }
        }
        if (source?.enabledCookieJar == true) headerMap[Network.cookieJarHeader] = "1"
        else headerMap.remove(Network.cookieJarHeader)
    }

    /**
     * 保存 CookieJar 中的 cookie。
     * 本工程 Cookie 由 [Network] 在伪头开启时随响应直接写入 [CookieStore]，此处无需二次动作。
     */
    private fun saveCookie() = Unit

    fun getUserAgent(): String = headerMap["User-Agent"] ?: Network.DEFAULT_UA

    fun isPost(): Boolean = option?.method.equals("POST", true)

    // 注意：getSource() 由上面的构造属性 `source` 自动生成；
    // 再手写一个同名方法会触发 JVM Platform declaration clash，故此处不重复声明。
    // Java 调用方仍可直接使用 getSource()。 

    /** URL 解析期间可见的请求头快照（供响应阶段复用）。 */
    fun headerSnapshot(): Map<String, String> = LinkedHashMap(headerMap)

    companion object {
        /** URL 选项前缀（对齐上游 `AppPattern.urlParamPattern`）。 */
        val paramPattern: Regex = AppPattern.urlParamPattern

        private val JS_PATTERN =
            Regex("<js>([\\w\\W]*?)</js>|@js:([\\w\\W]*)", RegexOption.IGNORE_CASE)

        private const val HEX = "0123456789abcdef"
    }

    /**
     * URL 选项（对齐上游 `AnalyzeUrlCore.UrlOption`）。
     *
     * 所有字符串字段在 setter 里做 `ifBlank { null }` 归一，与上游一致。
     */
    class UrlOption {
        var method: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }
        var charset: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** 源 Url */
        var origin: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** 类型 */
        var type: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** webView 中执行的 js */
        var webJs: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** 解析完 url 参数时执行的 js，执行结果会赋值给 url */
        var js: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** 重试次数 */
        var retry: Int? = null

        /** 服务器 id */
        var serverID: Long? = null

        /** webview 等待页面加载完毕的延迟时间（毫秒） */
        var webViewDelayTime: Long? = null

        /** 请求体 */
        var body: String? = null
            set(value) {
                field = value?.ifBlank { null }
            }

        /** 请求头 */
        var headers: Map<String, Any?>? = null

        /** 是否使用 webView */
        var useWebView: Boolean = false
    }
}

/**
 * 宽松解析 URL 选项 JSON（对齐上游 Gson 双栈：严格失败降级宽松，并容错单引号/尾逗号）。
 * 解析失败返回 null，由调用方记录「格式不规范」日志。
 */
internal fun parseUrlOptionOrNull(jsonStr: String): AnalyzeUrlCore.UrlOption? {
    val element = try {
        lenientJson.parseToJsonElement(jsonStr)
    } catch (_: Exception) {
        try {
            lenientJson.parseToJsonElement(fixSingleQuotes(jsonStr))
        } catch (_: Exception) {
            return null
        }
    }
    val obj = element as? JsonObject ?: return null

    fun prim(key: String): JsonPrimitive? =
        obj[key]?.let { if (it is JsonNull) null else it as? JsonPrimitive }

    val opt = AnalyzeUrlCore.UrlOption()
    opt.method = prim("method")?.content
    opt.charset = prim("charset")?.content
    opt.origin = prim("origin")?.content
    opt.type = prim("type")?.content
    opt.webJs = prim("webJs")?.content
    opt.js = prim("js")?.content
    opt.retry = prim("retry")?.content?.toDoubleOrNull()?.toInt()
    opt.serverID = prim("serverID")?.content?.toDoubleOrNull()?.toLong()
    opt.webViewDelayTime = prim("webViewDelayTime")?.content?.toDoubleOrNull()?.toLong()
    opt.body = obj["body"]?.let {
        if (it is JsonNull) null else (it as? JsonPrimitive)?.content ?: it.toString()
    }
    opt.headers = (obj["headers"] as? JsonObject)?.entries?.associate { (k, v) ->
        k to ((v as? JsonPrimitive)?.takeIf { p -> p !is JsonNull }?.content ?: v.toString())
    }
    opt.useWebView = prim("useWebView")?.let { it.booleanOrNull ?: it.content.toBoolean() } ?: false
    return opt
}

/** 把未被转义的单引号替换为双引号，容错 `{method:'POST'}` 这类非标 JSON。 */
private fun fixSingleQuotes(s: String): String {
    val sb = StringBuilder(s.length)
    var inSingle = false
    for (c in s) {
        if (c == '\'') {
            sb.append('"')
            inSingle = !inSingle
        } else {
            sb.append(c)
        }
    }
    return sb.toString()
}

/**
 * 替换探索项动态选项（对齐上游 `replaceExploreOptionsInUrl`）。
 *
 * 语法：`<选项名, 选项1, 选项2>`，取 [selected] 选中的项；未选中取第一项。
 * 注：上游该扩展无独立参考文件，此处按语义复刻，待 1264 条书源实测校准。
 */
internal fun replaceExploreOptionsInUrl(curRuleUrl: String, selected: (String) -> String?): String {
    if (!curRuleUrl.contains('<') || !curRuleUrl.contains(',')) return curRuleUrl
    return EXPLORE_OPTION_PATTERN.replace(curRuleUrl) { m ->
        val inner = m.groupValues[1]
        val comma = inner.indexOf(',')
        if (comma == -1) {
            m.value
        } else {
            val name = inner.substring(0, comma).trim()
            val options = inner.substring(comma + 1)
                .split(',')
                .map { it.trim() }
                .filter { it.isNotEmpty() }
            if (options.isEmpty()) m.value
            else selected(name)?.takeIf { it.isNotEmpty() } ?: options.first()
        }
    }
}

private val EXPLORE_OPTION_PATTERN = Regex("<([^<>]+)>")

/**
 * URL 参数编码（对齐上游 `encodeUrlParams` 的 jvm actual 语义）。
 *
 * - charset 空 → UTF-8
 * - charset == "escape" → 不编码
 * - isQuery=true → 整段按 query 安全字符集编码（已含 `%` 视为已编码，原样返回）
 * - isQuery=false → 按 `&` 分段、`=` 分 key/value，各自 form 编码
 */
internal fun encodeUrlParams(params: String, charset: String?, isQuery: Boolean): String {
    if (params.isEmpty()) return params
    if (charset.equals("escape", true)) return params

    val cs = try {
        if (charset.isNullOrBlank()) Charsets.UTF_8 else Charset.forName(charset)
    } catch (_: Exception) {
        Charsets.UTF_8
    }

    return if (isQuery) {
        if (params.contains('%')) params else percentEncode(params, cs)
    } else {
        params.split('&').joinToString("&") { seg ->
            val eq = seg.indexOf('=')
            if (eq == -1) {
                URLEncoder.encode(seg, cs.name())
            } else {
                URLEncoder.encode(seg.substring(0, eq), cs.name()) +
                    "=" + URLEncoder.encode(seg.substring(eq + 1), cs.name())
            }
        }
    }
}

/** query 段保留字符（对齐上游 `urlQueryEncoder = UNRESERVED.orNew("!$%&()*+,/:;=?@[\\]^`{|}")`）。 */
private const val QUERY_SAFE_CHARS = "!\$%&()*+,/:;=?@[]^`{|}"

private fun percentEncode(s: String, cs: Charset): String {
    val sb = StringBuilder()
    for (b in s.toByteArray(cs)) {
        val c = (b.toInt() and 0xFF).toChar()
        val keep = (c in 'A'..'Z') || (c in 'a'..'z') || (c in '0'..'9') ||
            c == '-' || c == '.' || c == '_' || c == '~' ||
            c.code < 128 && QUERY_SAFE_CHARS.indexOf(c) >= 0
        if (keep) sb.append(c)
        else sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
    }
    return sb.toString()
}