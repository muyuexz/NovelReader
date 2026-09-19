package com.example.novelreader.analyzeRule

import android.content.Context
import org.json.JSONObject

/**
 * 第 52 批 L2：字数「本地估算」结果的持久化。
 *
 * 用 SharedPreferences 存一个 JSON 对象（归一化键 → 估算字数），
 * 与 [com.example.novelreader.ShelfRepository] 保持同一套路数（刻意不引 Room/KSP）。
 *
 * 键 = 书名 + 作者（都做了 lowercase + 去空白）。
 * 于是**同一本书不管从哪个源进来，共用同一个估算值** ——
 * 一个源的问题只需付一次估算代价，之后全源受益。
 */
object WordCountStore {

    private const val PREF = "novelreader_wordcount_v1"
    private const val KEY = "estimates"

    /** 条目上限，防无限增长。 */
    private const val MAX_ENTRIES = 2000

    /** 取这本书的历史估算值；没有返回 null。 */
    fun get(ctx: Context, book: Book): Long? = runCatching {
        val k = key(book) ?: return@runCatching null
        val json = prefs(ctx).getString(KEY, null) ?: return@runCatching null
        val obj = JSONObject(json)
        if (!obj.has(k)) return@runCatching null
        obj.optLong(k, 0L).takeIf { it > 0L }
    }.getOrNull()

    /** 存这本书的估算值；非法值忽略。 */
    fun put(ctx: Context, book: Book, value: Long) {
        if (value <= 0L) return
        runCatching {
            val k = key(book) ?: return@runCatching
            val p = prefs(ctx)
            val obj = runCatching { JSONObject(p.getString(KEY, null) ?: "{}") }
                .getOrElse { JSONObject() }
            if (!obj.has(k) && obj.length() >= MAX_ENTRIES) return@runCatching
            obj.put(k, value)
            p.edit().putString(KEY, obj.toString()).apply()
        }
    }

    private fun prefs(ctx: Context) = ctx.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE)

    /** 归一化键：书名 + 作者，抹平大小写与空白差异。 */
    private fun key(book: Book): String? {
        val name = book.name.trim().lowercase().replace(Regex("\\s+"), "")
        if (name.isEmpty()) return null
        val author = book.author.trim().lowercase().replace(Regex("\\s+"), "")
        return name + "|" + author
    }
}
