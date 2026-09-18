package com.example.novelreader

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 第 24 批：崩溃落盘取证。
 *
 * 老板设备上 ADB / Accessibility 通道不可用，抓不到 logcat。这里挂一个全局
 * 未捕获异常兜底：只要 App 被任何异常带走，就先把完整堆栈写进文件，重启后
 * 把文件发我，第一时间拿到真凶。
 *
 * 落盘位置（多写，哪个能写用哪个）：
 *  1. 公共 Download/nr_crash.log（MediaStore，免 root 可见，覆盖写）
 *  2. App 外部私有目录 files/nr_crash.log（一定可写，追加）
 */
object CrashLogger {

    private const val FILE_NAME = "nr_crash.log"

    fun install(context: Context) {
        val app = context.applicationContext
        val prev = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { dump(app, thread, throwable) }
            prev?.uncaughtException(thread, throwable)
        }
    }

    private fun dump(context: Context, thread: Thread, throwable: Throwable) {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US).format(Date())
        val head = buildString {
            append("===== NovelReader crash =====\n")
            append("time   : ").append(time).append('\n')
            append("thread : ").append(thread.name).append('\n')
            append("device : ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" / Android ").append(Build.VERSION.RELEASE)
                .append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("app    : ").append(context.packageName).append('\n')
            append("------------------------------\n")
        }
        val body = head + sw.toString() + "\n\n"

        writePublic(context, body)
        writePrivate(context, body)
    }

    /** 公共 Download（MediaStore，覆盖写，保证是最近一次崩溃）。 */
    private fun writePublic(context: Context, body: String) {
        runCatching {
            val resolver = context.contentResolver
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    android.os.Environment.DIRECTORY_DOWNLOADS)
            }
            val uri = resolver.insert(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values,
            ) ?: return@runCatching
            resolver.openOutputStream(uri)?.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        }
    }

    /** App 外部私有目录（追加，绝不丢）。 */
    private fun writePrivate(context: Context, body: String) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: context.filesDir
            File(dir, FILE_NAME).appendText(body, Charsets.UTF_8)
        }
    }
}
