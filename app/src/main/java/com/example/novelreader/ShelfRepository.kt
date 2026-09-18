package com.example.novelreader

import android.content.Context
import androidx.compose.runtime.Immutable
import com.example.novelreader.analyzeRule.Book
import com.example.novelreader.analyzeRule.BookChapter
import com.example.novelreader.analyzeRule.BookSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 书架条目：一本书的轻量快照 + 阅读进度。
 *
 * 用 kotlinx.serialization 把整包序列化成 JSON 存进 SharedPreferences。
 * 刻意不引 Room / KSP —— `app/build.gradle.kts` 明确写了不跑注解处理器，
 * 这点保持不动，书架的持久化只需要「读一次、整体覆写」的语义。
 */
/**
 * 第 27 批：书架条目里保存的「兄弟书源」引用。
 *
 * 只留重建换源列表所需的最小字段：书源地址 + 该书在该源上的书地址 / 目录地址。
 * 还原时由调用方用 [SourceRepository.findByKey] 把书源对象查回来。
 */
@Serializable
@Immutable
data class AltSourceRef(
    val sourceUrl: String,
    val bookUrl: String,
    val tocUrl: String = "",
    val originName: String = "",
)

@Serializable
@Immutable
data class ShelfEntry(
    val bookUrl: String,
    val name: String,
    val author: String = "",
    val kind: String? = null,
    val intro: String? = null,
    val coverUrl: String? = null,
    val tocUrl: String = "",
    val wordCount: String? = null,
    val lastChapter: String? = null,
    val origin: String = "",
    val originName: String = "",
    /** 命中该书源的书源地址；书架靠它反查回同一条书源规则。 */
    val sourceUrl: String = "",
    val addedAt: Long = 0L,
    val lastReadChapterUrl: String? = null,
    val lastReadChapterTitle: String? = null,
    val lastReadChapterIndex: Int = -1,
    val lastReadAt: Long = 0L,
    /** 第13批：页内位置（1 基正文页；0 = 未记录，按第 1 页起步）。 */
    val lastReadPage: Int = 0,
    /** 第 27 批：这本书在其它书源上的副本（同名 + 同作者聚合时挂上的兄弟源）。 */
    val altSources: List<AltSourceRef> = emptyList(),
) {
    /** 书架内的唯一键：书源地址 + 书地址。 */
    val key: String get() = sourceUrl + "|" + bookUrl

    /** 用书源对象把条目还原成「能继续拉目录 / 读正文」的 [Book]。 */
    fun toBook(
        source: BookSource?,
        resolveSource: (String) -> BookSource? = { null },
    ): Book = Book(
        bookUrl = bookUrl,
        name = name,
        author = author,
        kind = kind,
        intro = intro,
        coverUrl = coverUrl,
        tocUrl = tocUrl,
        wordCount = wordCount,
        lastChapter = lastChapter,
        origin = origin,
        originName = originName,
    ).also { bk ->
        bk.source = source
        // 第 27 批：兄弟源一并还原，否则「从书架进入阅读页」也只看到 1 个源。
        // 查不到书源对象（源被删）的兄弟直接丢弃，避免换源点下去静默失败。
        bk.altSources = altSources.mapNotNull { ref ->
            val s = resolveSource(ref.sourceUrl) ?: return@mapNotNull null
            Book(
                bookUrl = ref.bookUrl,
                name = name,
                author = author,
                tocUrl = ref.tocUrl,
                origin = ref.sourceUrl,
                originName = ref.originName,
            ).also { it.source = s }
        }
    }

    companion object {
        const val NO_CHAPTER = -1

        /**
         * 由 [Book] 生成条目；[old] 非空表示「已在书架」，此时保留加入时间与阅读进度，
         * 只刷新书名 / 简介 / 字数 / 最新章节这类元数据。
         */
        fun from(book: Book, sourceUrl: String, old: ShelfEntry? = null): ShelfEntry = ShelfEntry(
            bookUrl = book.bookUrl,
            name = book.name,
            author = book.author,
            kind = book.kind,
            intro = book.intro,
            coverUrl = book.coverUrl,
            tocUrl = book.tocUrl,
            wordCount = book.wordCount,
            lastChapter = book.lastChapter,
            origin = book.origin,
            originName = book.originName,
            sourceUrl = sourceUrl,
            addedAt = old?.addedAt?.takeIf { it > 0L } ?: System.currentTimeMillis(),
            lastReadChapterUrl = old?.lastReadChapterUrl,
            lastReadChapterTitle = old?.lastReadChapterTitle,
            lastReadChapterIndex = old?.lastReadChapterIndex ?: NO_CHAPTER,
            lastReadAt = old?.lastReadAt ?: 0L,
            lastReadPage = old?.lastReadPage ?: 0,
            // 第 27 批：把当前聚合到的兄弟源一并落盘。
            // 新的聚合结果为空时保留旧记录，避免「从书架还原后再加一次书架」把兄弟源清掉。
            altSources = run {
                val fresh = book.altSources.mapNotNull { s ->
                    val u = s.source?.bookSourceUrl?.takeIf { it.isNotBlank() }
                        ?: s.origin.takeIf { it.isNotBlank() }
                    if (u.isNullOrBlank()) null
                    else AltSourceRef(u, s.bookUrl, s.tocUrl, s.originName)
                }
                if (fresh.isNotEmpty()) fresh else old?.altSources ?: emptyList()
            },
        )
    }
}

/**
 * 书架仓库：内存缓存 + 单文件 JSON 持久化。
 *
 * 全部写操作都是「改内存 → 整体落盘」，条目量级很小（几百本撑死几十 KB），
 * 不存在性能问题，也免掉了数据库那套东西。
 */
object ShelfRepository {

    private const val PREFS = "shelf_prefs"
    private const val KEY_ENTRIES = "entries_v1"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    @Volatile
    private var cache: List<ShelfEntry> = emptyList()

    @Volatile
    private var loaded: Boolean = false

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 幂等加载：整包读一次，坏数据静默回落成空书架。 */
    @Synchronized
    fun ensureLoaded(context: Context): List<ShelfEntry> {
        if (loaded) return cache
        val text = runCatching { prefs(context).getString(KEY_ENTRIES, null) }.getOrNull()
        cache = if (text.isNullOrBlank()) {
            emptyList()
        } else {
            runCatching {
                json.decodeFromString(ListSerializer(ShelfEntry.serializer()), text)
            }.getOrDefault(emptyList())
        }
        loaded = true
        return cache
    }

    /** 书架全部条目，按「最近阅读 → 加入时间」倒序。 */
    fun all(context: Context): List<ShelfEntry> =
        ensureLoaded(context).sortedByDescending {
            it.lastReadAt.takeIf { t -> t > 0L } ?: it.addedAt
        }

    fun contains(context: Context, bookUrl: String, sourceUrl: String): Boolean =
        ensureLoaded(context).any { it.bookUrl == bookUrl && it.sourceUrl == sourceUrl }

    fun find(context: Context, bookUrl: String, sourceUrl: String): ShelfEntry? =
        ensureLoaded(context).firstOrNull { it.bookUrl == bookUrl && it.sourceUrl == sourceUrl }

    /** 加入书架。返回 true = 新增，false = 原本就在（仅刷新了元数据）。 */
    @Synchronized
    fun add(context: Context, book: Book, sourceUrl: String): Boolean {
        val cur = ensureLoaded(context).toMutableList()
        val i = cur.indexOfFirst { it.bookUrl == book.bookUrl && it.sourceUrl == sourceUrl }
        val entry = ShelfEntry.from(book, sourceUrl, cur.getOrNull(i))
        return if (i >= 0) {
            cur[i] = entry
            cache = cur
            persist(context)
            false
        } else {
            cur.add(0, entry)
            cache = cur
            persist(context)
            true
        }
    }

    /** 按唯一键移出书架。 */
    @Synchronized
    fun remove(context: Context, key: String) {
        val cur = ensureLoaded(context).toMutableList()
        if (cur.removeAll { it.key == key }) {
            cache = cur
            persist(context)
        }
    }

    /** 按「书地址 + 书源地址」移出书架。 */
    @Synchronized
    fun removeBy(context: Context, bookUrl: String, sourceUrl: String) {
        val cur = ensureLoaded(context).toMutableList()
        if (cur.removeAll { it.bookUrl == bookUrl && it.sourceUrl == sourceUrl }) {
            cache = cur
            persist(context)
        }
    }

    /** 记录阅读进度：翻到某章时更新「读到最后哪一章」。 */
    @Synchronized
    fun updateProgress(
        context: Context,
        bookUrl: String,
        sourceUrl: String,
        chapter: BookChapter,
        page: Int = 0,
    ) {
        val cur = ensureLoaded(context).toMutableList()
        val i = cur.indexOfFirst { it.bookUrl == bookUrl && it.sourceUrl == sourceUrl }
        if (i < 0) return
        cur[i] = cur[i].copy(
            lastReadChapterUrl = chapter.url,
            lastReadChapterTitle = chapter.title,
            lastReadChapterIndex = chapter.index,
            lastReadAt = System.currentTimeMillis(),
            lastReadPage = page.coerceAtLeast(0),
        )
        cache = cur
        persist(context)
    }

    /** 用最新拉到的详情刷新书架条目里的元数据（简介 / 字数 / 最新章节）。 */
    @Synchronized
    fun refreshMeta(context: Context, book: Book, sourceUrl: String) {
        val cur = ensureLoaded(context).toMutableList()
        val i = cur.indexOfFirst { it.bookUrl == book.bookUrl && it.sourceUrl == sourceUrl }
        if (i < 0) return
        cur[i] = cur[i].copy(
            name = book.name,
            author = book.author,
            kind = book.kind,
            intro = book.intro,
            coverUrl = book.coverUrl,
            tocUrl = book.tocUrl,
            wordCount = book.wordCount,
            lastChapter = book.lastChapter,
            originName = book.originName,
        )
        cache = cur
        persist(context)
    }

    private fun persist(context: Context) {
        runCatching {
            prefs(context).edit()
                .putString(
                    KEY_ENTRIES,
                    json.encodeToString(ListSerializer(ShelfEntry.serializer()), cache),
                )
                .apply()
        }
    }
}
