package com.example.novelreader.analyzeRule

import java.net.URI

/**
 * URL 工具（对齐 Legado `NetworkUtils` 在规则引擎里用到的三个面）。
 */
internal object NetworkUtils {

    /** 相对 URL 绝对化；relative 已是绝对地址时原样返回。 */
    fun getAbsoluteURL(baseUrl: String?, relative: String): String {
        if (relative.isEmpty()) return relative
        return try {
            val r = URI(relative)
            if (r.isAbsolute) relative else resolveUrl(baseUrl, relative)
        } catch (_: Exception) {
            resolveUrl(baseUrl, relative)
        }
    }

    /** 取 `scheme://host[:port]`；非法返回 null。 */
    fun getBaseUrl(url: String): String? {
        return try {
            val u = URI(url)
            if (!u.isAbsolute) return null
            val sb = StringBuilder()
            u.scheme?.let { sb.append(it).append("://") }
            u.host?.let { sb.append(it) }
            if (u.port != -1) sb.append(':').append(u.port)
            if (sb.isEmpty()) null else sb.toString()
        } catch (_: Exception) {
            null
        }
    }

    /** 取 host（对齐 Legado `getSubDomain` 的域名提取面）；解析失败原样返回。 */
    fun getSubDomain(url: String): String {
        return try {
            URI(url).host ?: url
        } catch (_: Exception) {
            url
        }
    }
}
