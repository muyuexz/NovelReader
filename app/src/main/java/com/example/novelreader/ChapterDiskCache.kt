package com.example.novelreader

import android.content.Context
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger

/**
 * 第19批：章节正文磁盘缓存。
 *
 * 全文搜索与阅读页共用。命中即免网络 —— **这是检索覆盖度能够收敛的关键**：
 * 在此之前，补拉失败的章节每次搜索都会原样再失败一遍，命中数被永久钉死；
 * 有了这层缓存，每次搜索都会在上一轮的成果上继续补齐，逐步逼近全书真实命中数。
 *
 * 存储形态：`filesDir/chapter_cache_v1/<url 的 MD5>`，纯文本正文。
 * 单条上限 [MAX_BODY] 字符（超长正文不入盘），文件数上限 [MAX_FILES]，
 * 超出后按最后修改时间淘汰最旧的一半。
 */
object ChapterDiskCache {

    private const val DIR_NAME = "chapter_cache_v1"
    private const val MAX_BODY = 512 * 1024
    private const val MAX_FILES = 4000
    /** 每 [TRIM_EVERY] 次写入做一次淘汰检查，避免每次 put 都遍历目录。 */
    private const val TRIM_EVERY = 64
    private val puts = AtomicInteger(0)

    private fun dir(ctx: Context): File? = runCatching {
        File(ctx.applicationContext.filesDir, DIR_NAME).apply { if (!isDirectory) mkdirs() }
    }.getOrNull()

    private fun name(url: String): String? = runCatching {
        val d = MessageDigest.getInstance("MD5").digest(url.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder(d.size * 2)
        for (byte in d) {
            val v = byte.toInt() and 0xFF
            if (v < 0x10) sb.append('0')
            sb.append(Integer.toHexString(v))
        }
        sb.toString()
    }.getOrNull()

    /** 读缓存；未命中或读失败返回 null。 */
    fun get(ctx: Context, url: String): String? = runCatching {
        val d = dir(ctx) ?: return@runCatching null
        val n = name(url) ?: return@runCatching null
        val f = File(d, n)
        if (!f.isFile) return@runCatching null
        f.readText().takeIf { it.isNotBlank() }
    }.getOrNull()

    /** 写缓存；正文为空或超长时跳过。 */
    fun put(ctx: Context, url: String, body: String) {
        if (body.isBlank() || body.length > MAX_BODY) return
        runCatching {
            val d = dir(ctx) ?: return@runCatching
            val n = name(url) ?: return@runCatching
            val f = File(d, n)
            if (!f.isFile || f.length() < body.length) f.writeText(body)
        }
        if (puts.incrementAndGet() % TRIM_EVERY == 0) trim(ctx)
    }

    /** 文件数超限时，按最后修改时间删掉最旧的一部分。 */
    private fun trim(ctx: Context) {
        runCatching {
            val d = dir(ctx) ?: return@runCatching
            val files = d.listFiles() ?: return@runCatching
            if (files.size <= MAX_FILES) return@runCatching
            val stale = files.sortedBy { it.lastModified() }
            stale.take(stale.size - MAX_FILES / 2).forEach { it.delete() }
        }
    }
}
