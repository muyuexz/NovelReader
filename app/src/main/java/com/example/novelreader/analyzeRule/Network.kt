package com.example.novelreader.analyzeRule

import android.content.Context
import okhttp3.Cache
import okhttp3.Headers
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.File
import java.io.InterruptedIOException
import java.nio.charset.Charset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * HTTP 响应包装（对齐 Legado `io.legado.app.help.http.StrResponse` 的最小面）。
 *
 * 上游两种构造：
 * - `StrResponse(raw, body)`：来自 OkHttp，url/code/headers 取自 raw
 * - `StrResponse(url, body)`：来自 data: URI 短路
 */
class StrResponse private constructor(
    val url: String,
    val body: String?,
    val raw: Response?,
) {
    constructor(raw: Response, body: String?) : this(raw.request.url.toString(), body, raw)
    constructor(url: String, body: String?) : this(url, body, null)

    val code: Int get() = raw?.code ?: 0
    val headers: Headers? get() = raw?.headers
    val contentType: String? get() = raw?.body?.contentType()?.toString()
    val isSuccessful: Boolean get() = raw?.isSuccessful ?: true

    fun close() {
        raw?.close()
    }
}

/**
 * 书源级 Cookie 存储（对齐 Legado `SourceNetworkProviders.impl` 的 cookie 读写面）。
 *
 * Legado 由数据库持久化；本工程先落内存版，按 host 存 name=value，
 * 后续接 Room 时只需替换本对象实现，规则引擎无感。
 */
internal object CookieStore {

    private val store = ConcurrentHashMap<String, MutableMap<String, String>>()

    /** 取某 host 的 Cookie 头串（`a=1; b=2`），无则空串。 */
    fun get(host: String): String {
        val map = store[host] ?: return ""
        return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** 用响应 Set-Cookie 头更新某 host 的 cookie。 */
    fun save(host: String, setCookie: List<String>) {
        if (setCookie.isEmpty()) return
        val map = store.getOrPut(host) { ConcurrentHashMap() }
        for (raw in setCookie) {
            val pair = raw.substringBefore(';').trim()
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            val name = pair.substring(0, eq).trim()
            val value = pair.substring(eq + 1).trim()
            if (value.isEmpty()) map.remove(name) else map[name] = value
        }
    }

    /** 用完整 Cookie 头串替换某 host 的 cookie（对齐 Legado `replaceCookie`）。 */
    fun replace(host: String, cookieHeader: String) {
        val map = store.getOrPut(host) { ConcurrentHashMap() }
        map.clear()
        cookieHeader.split(';').forEach { seg ->
            val pair = seg.trim()
            val eq = pair.indexOf('=')
            if (eq > 0) map[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
        }
    }
    /** 清除某 host 的全部 cookie，返回是否原本有记录（对齐 Legado `CookieManager.removeCookie`）。 */
    fun remove(host: String): Boolean = store.remove(host) != null
}
/**
 * 暴露给书源 JS 的 Cookie 管理器（对齐 Legado `io.legado.app.help.http.CookieManager`）。
 *
 * 书源 JS 里常见 `cookie.removeCookie(url)` / `cookie.getCookie(url)`，
 * 这里把 host 级内存 [CookieStore] 包一层 URL 解析外壳暴露出去；
 * 传入的 url 为空或非法时静默返回，不抛异常中断规则执行。
 */
internal object CookieManagerProxy {
    private fun hostOf(url: String?): String? {
        if (url.isNullOrBlank()) return null
        return try {
            java.net.URL(url).host
        } catch (_: Exception) {
            null
        }
    }
    /** 取某 URL 的 Cookie 头串（`a=1; b=2`），无则空串。 */
    fun getCookie(url: String): String = hostOf(url)?.let { CookieStore.get(it) } ?: ""
    /** 清除某 URL 的 Cookie，返回是否原本有记录。 */
    fun removeCookie(url: String): Boolean = hostOf(url)?.let { CookieStore.remove(it) } ?: false
    /** 覆盖某 URL 的 Cookie 头串。 */
    fun setCookie(url: String, cookie: String) {
        hostOf(url)?.let { CookieStore.replace(it, cookie) }
    }
    fun replaceCookie(url: String, cookie: String) = setCookie(url, cookie)
}
/** 合并两段 Cookie 头串，extra 覆盖 base 同名项（对齐 Legado `mergeCookies`）。 */
internal fun mergeCookies(base: String?, extra: String?): String? {
    if (base.isNullOrEmpty()) return extra
    if (extra.isNullOrEmpty()) return base
    val map = LinkedHashMap<String, String>()
    fun absorb(s: String) {
        s.split(';').forEach { seg ->
            val pair = seg.trim()
            if (pair.isEmpty()) return@forEach
            val eq = pair.indexOf('=')
            if (eq > 0) map[pair.substring(0, eq).trim()] = pair.substring(eq + 1).trim()
        }
    }
    absorb(base)
    absorb(extra)
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
}

/**
 * 规则引擎的网络出口（Legado 里由 `SourceNetworkProviders.impl` 提供完整网络层）。
 *
 * 本工程收敛为一个共享 OkHttp 客户端 + 一个可选的 host 级内存 CookieStore，
 * 供 `java.ajax` 与 [AnalyzeUrlCore] 的 GET/POST/header/cookie 请求使用。
 */
internal object Network {

    const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/120.0.0.0 Mobile Safari/537.36"

    /** CookieJar 伪头：出现即启用内存 Cookie 自动存取（对齐 Legado `cookieJarHeader`）。 */
    const val cookieJarHeader = "CookieJar"

    /** 第 37 批 D刀：重试退避基数（毫秒）。 */
    private const val RETRY_BACKOFF_BASE_MS = 300L

    /** 第 37 批 D刀：HTTP 磁盘缓存目录，由 [initialize] 注入；未注入则缓存面整体不启用。 */
    @Volatile
    private var httpCacheDir: File? = null

    /** 第 37 批 D刀：派生 Client 复用表（键 = 读超时/调用超时）。 */
    private val derivedClients = ConcurrentHashMap<String, OkHttpClient>()

    /**
     * 第 37 批 D刀：网络层初始化，只在 Application.onCreate 调一次。
     *
     * 只做一件事 —— 把 App 私有缓存目录交给 OkHttp 做 HTTP 磁盘缓存。
     * 重复调用安全；[client] 是 lazy，只要初始化早于首次请求即生效。
     */
    fun initialize(context: Context) {
    if (httpCacheDir != null) return
    val dir = File(context.cacheDir, "http_v1")
    runCatching { dir.mkdirs() }
    httpCacheDir = dir
    }

    val client: OkHttpClient by lazy {
    OkHttpClient.Builder()
    .apply {
    // 第 37 批 D刀：20MB HTTP 磁盘缓存。只吃服务端 Cache-Control，
    // 不强行改写响应头 —— 章节正文是动态内容，乱缓存会导致内容过期。
    httpCacheDir?.let { dir -> runCatching { cache(Cache(dir, 20L * 1024 * 1024)) } }
    }
    .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
    }

    /** 一次请求的全部可配项。 */
    data class Req(
        val url: String,
        val method: String = "GET",
        val headers: Map<String, String> = emptyMap(),
        /** 已编码的 query（GET）或表单串（POST form）。 */
        val encodedParams: String? = null,
        /** 原始请求体（POST json / 自定义 Content-Type）。 */
        val body: String? = null,
        val charset: Charset? = null,
        val retry: Int = 0,
        val readTimeoutMs: Long? = null,
        val callTimeoutMs: Long? = null,
    )

    /** 同步执行，失败按 [Req.retry] 重试后抛出。 */
    fun execute(req: Req): StrResponse {
        var lastError: Exception? = null
        for (attempt in 0..req.retry) {
            try {
                return executeOnce(req)
            } catch (e: Exception) {
                lastError = e
                // 第 37 批 D刀：原为无条件 Thread.sleep，在 Dispatchers.IO 上白占一个线程且不可取消。
                // 改为可中断等待：被中断（线程中断 / 取消信号）立即放弃重试并抛出，不再闷头睡满退避。
                // 彻底非阻塞需要把整条请求链（BookSourceEngine 四个阶段 + JS 的 java.ajax）改成
                // suspend，属更大一刀，另行评估后再动。
                if (attempt < req.retry) {
                try {
                Thread.sleep(RETRY_BACKOFF_BASE_MS * (attempt + 1))
                } catch (ie: InterruptedException) {
                Thread.currentThread().interrupt()
                throw InterruptedIOException("重试等待被中断: ${req.url}").also { it.initCause(ie) }
                }
                }
            }
        }
        throw lastError ?: IllegalStateException("请求失败: ${req.url}")
    }

    private fun executeOnce(req: Req): StrResponse {
        val httpUrl = req.url.toHttpUrlOrNull() ?: throw IllegalArgumentException("无效 URL: ${req.url}")
        val isPost = req.method.equals("POST", true)
        val useJar = req.headers.keys.any { it.equals(cookieJarHeader, true) }

        val headersBuilder = Headers.Builder()
        req.headers.forEach { (k, v) -> if (!k.equals(cookieJarHeader, true)) headersBuilder.add(k, v) }
        if (req.headers.keys.none { it.equals("User-Agent", true) }) {
            headersBuilder.add("User-Agent", DEFAULT_UA)
        }

        val charset = req.charset ?: Charset.forName("UTF-8")
        val requestBuilder: Request.Builder

        if (!isPost) {
            val finalUrl = if (req.encodedParams.isNullOrEmpty()) httpUrl
            else httpUrl.newBuilder().encodedQuery(req.encodedParams).build()
            requestBuilder = Request.Builder().url(finalUrl).get()
        } else {
            val contentType = req.headers.entries
                .firstOrNull { it.key.equals("Content-Type", true) }?.value
            val bodyText = req.body
            val encoded = req.encodedParams
            val requestBody: RequestBody = when {
                !encoded.isNullOrEmpty() || bodyText.isNullOrBlank() ->
                    (encoded ?: "").toRequestBody(
                        "application/x-www-form-urlencoded; charset=${charset.name()}".toMediaTypeOrNull()
                    )
                !contentType.isNullOrBlank() ->
                    bodyText.toRequestBody(contentType.toMediaTypeOrNull())
                else ->
                    bodyText.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            }
            requestBuilder = Request.Builder().url(httpUrl).post(requestBody)
        }
        requestBuilder.headers(headersBuilder.build())

        // 第 37 批 D刀：派生 Client 复用。
        // 先纠正上一轮我自己的一个误判：OkHttp 的 newBuilder() 会沿用同一个
        // ConnectionPool 与 Dispatcher，所以「每请求新建 = 丢 keep-alive」并不成立；
        // 真实开销是每请求多一次 Builder 深拷贝 + Client 分配。按 (读超时, 调用超时) 做键缓存。
        val effClient = if (req.readTimeoutMs != null || req.callTimeoutMs != null) {
        derivedClients.getOrPut("${req.readTimeoutMs}/${req.callTimeoutMs}") {
        client.newBuilder().apply {
        req.readTimeoutMs?.let {
        readTimeout(it, TimeUnit.MILLISECONDS)
        callTimeout(it * 2, TimeUnit.MILLISECONDS)
        }
        req.callTimeoutMs?.let { callTimeout(it, TimeUnit.MILLISECONDS) }
        }.build()
        }
        } else client

        effClient.newCall(requestBuilder.build()).execute().use { resp ->
            val bodyStr = resp.body?.string()
            if (useJar) CookieStore.save(resp.request.url.host, resp.headers.values("Set-Cookie"))
            return StrResponse(resp, bodyStr)
        }
    }

    /** 兼容旧调用面的同步 GET，返回响应体文本。 */
    fun fetch(url: String, headers: Map<String, String>? = null): String =
        execute(Req(url, headers = headers ?: emptyMap())).body ?: ""
}