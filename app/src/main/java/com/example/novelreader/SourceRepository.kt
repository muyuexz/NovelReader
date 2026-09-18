package com.example.novelreader

import android.content.Context
import com.example.novelreader.analyzeRule.Book
import com.example.novelreader.analyzeRule.BookSource
import com.example.novelreader.analyzeRule.BookSourceEngine
import com.example.novelreader.analyzeRule.BookSourceParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * 书源仓库：负责把书源 JSON 装进内存，并把「关键词搜索」分发到多条书源上并发执行。
 *
 * 设计要点（对齐 Legado 的 SearchModel 分发层）：
 * - 书源优先从 `/sdcard/NovelReader-apk/src1264.json` 读取（真实回归测试集），
 *   读不到再回落到 `assets/booksources.json`，保证离线也能跑。
 * - 搜索按「限流并发 + 单源超时」执行，任一源炸掉只丢它自己的结果，不拖垮整批。
 * - 结果边出边回调，UI 可以做「流式」呈现，不必等 1264 条全部跑完。
 */
object SourceRepository {

    /** 打包进 assets 的书源文件名。 */
    const val ASSET_NAME = "booksources.json"

    /** 外部回归测试集路径。 */
    private const val EXTERNAL_SOURCES = "/sdcard/NovelReader-apk/src1264.json"

    @Volatile
    private var sources: List<BookSource> = emptyList()

    @Volatile
    private var parseFailed: Int = 0

    @Volatile
    private var loaded: Boolean = false

    val totalCount: Int get() = sources.size
    val parseFailedCount: Int get() = parseFailed

    /** 幂等加载：解析整包书源，逐条容错（返回 (成功, 失败)）。 */
    fun ensureLoaded(context: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            val text = readSourceText(context)
            if (!text.isNullOrBlank()) {
                val (ok, bad) = runCatching { BookSourceParser.parseLenient(text) }
                    .getOrElse { emptyList<BookSource>() to 0 }
                parseFailed = bad
                sources = ok
            }
            loaded = true
        }
    }

    fun all(): List<BookSource> = sources
    fun enabled(): List<BookSource> = sources.filter { it.enabled }

    /** 按书源地址反查书源：书架把快照还原成可继续阅读的 [Book] 时要用。 */
    fun findByKey(bookSourceUrl: String): BookSource? =
        if (bookSourceUrl.isBlank()) null
        else sources.firstOrNull { it.bookSourceUrl == bookSourceUrl }

    private fun readSourceText(context: Context): String? {
        // 1) 外部回归测试集（在 scoped storage 下需存储权限；读不到就静默跳过）
        runCatching {
            val external = File(EXTERNAL_SOURCES)
            if (external.exists() && external.length() > 0L) return external.readText()
        }
        // 2) App 专属外部目录：免权限，方便热替换书源而无需重新打包
        runCatching {
            val appExternal = context.getExternalFilesDir(null)?.resolve(ASSET_NAME)
            if (appExternal != null && appExternal.exists() && appExternal.length() > 0L) {
                return appExternal.readText()
            }
        }
        // 3) 兜底：打包进 assets 的默认书源
        return runCatching {
            context.assets.open(ASSET_NAME).bufferedReader().use { it.readText() }
        }.getOrNull()
    }

    /**
     * 并发搜索：把 [key] 交给最多 [maxSources] 条启用书源，[concurrency] 路限流，
     * 单源超时 [perSourceTimeoutMs] 毫秒。每搜到一批就 [onBatch] 回调一次。
     *
     * @return 命中的书籍总数。
     */
    suspend fun search(
        key: String,
        maxSources: Int = 40,
        concurrency: Int = 8,
        perSourceTimeoutMs: Long = 12000,
        onBatch: (List<Book>) -> Unit,
    ): Int {
        val targets = enabled().take(maxSources)
        val sem = Semaphore(concurrency)
        val lock = Any()
        val acc = ArrayList<Book>()
        return coroutineScope {
            val jobs = targets.map { src ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        val hits = runCatching {
                            withTimeout(perSourceTimeoutMs) { BookSourceEngine.search(src, key) }
                        }.getOrDefault(emptyList())
                        if (hits.isNotEmpty()) {
                            // 累积 + 全量重排：回调的是「当前已命中的有序快照」，
                            // 不是增量批次。UI 直接整体替换即可，列表不会因到达顺序而抖。
                            val snapshot = synchronized(lock) {
                                acc += hits
                                rankByRelevance(acc, key)
                            }
                            onBatch(snapshot)
                        }
                    }
                }
            }
            jobs.awaitAll()
            synchronized(lock) { acc.size }
        }
    }

    /**
     * 跨源排序：把「最像用户想找的那本」顶到最前。
     *
     * 排序键（依次比较）：
     * 1. 书名匹配档次：完全相等 → 前缀命中 → 包含命中 → 其它；
     * 2. 命中位置：关键词在书名里出现得越靠前越优（治「XX（大主宰）同人」这类前缀噪声）；
     * 3. 作者命中：关键词命中了作者名的排在前面（用户按作者搜时救命）；
     * 4. 书源权重 `weight` 降序（高权重源的结果更可信）；
     * 5. 书源自定义序 `customOrder` 升序；
     * 6. 书名长度升序（短书名更可能是正主，少一堆「XX（全本）TXT下载」）；
     * 7. 书名字典序，保证同分同序稳定。
     *
     * 匹配前统一走 [normalize]：全角转半角、去掉空白与标点、统一小写。
     * 这样《斗破苍穹》、《斗破苍穹！》、"斗破 苍穹" 在「斗破苍穹」下同档，不会自相残杀。
     */
    private fun rankByRelevance(list: List<Book>, key: String): List<Book> {
        val k = normalize(key)
        if (k.isEmpty()) return list
        return list.sortedWith(
            compareBy(
                { relevance(it.name, k) },
                { hitPosition(it.name, k) },
                { authorHit(it, k) },
                { -(it.source?.weight ?: 0) },
                { it.source?.customOrder ?: 0 },
                { it.name.length },
                { it.name },
            )
        )
    }

    /** 书名与关键词的匹配档次：0=完全相等，1=前缀，2=包含，3=无关。 */
    private fun relevance(name: String, key: String): Int {
        val n = normalize(name)
        if (n.isEmpty()) return 3
        return when {
            n == key -> 0
            n.startsWith(key) -> 1
            n.contains(key) -> 2
            else -> 3
        }
    }

    /** 关键词在书名中首次出现的下标；没出现给一个「非常大」的档位。 */
    private fun hitPosition(name: String, key: String): Int {
        val i = normalize(name).indexOf(key)
        return if (i < 0) Int.MAX_VALUE / 2 else i
    }

    /** 关键词命中作者名时给 0（优先），否则 1。 */
    private fun authorHit(book: Book, key: String): Int =
        if (key.isNotEmpty() && normalize(book.author).contains(key)) 0 else 1

    /**
     * 匹配用归一化：全角 → 半角，只保留字母 / 数字 / 汉字，统一小写。
     * 标点、空白、书名号一律抹掉，让「《三体》」和「三体」是同一个键。
     */
    private fun normalize(s: String): String {
        val sb = StringBuilder(s.length)
        for (ch in s) {
            var c = ch
            // 全角字符（！-～）映射回半角
            if (c.code in 0xFF01..0xFF5E) c = (c.code - 0xFEE0).toChar()
            if (c == '\u3000') c = ' '
            val lower = c.lowercaseChar()
            if (lower.isLetterOrDigit()) sb.append(lower)
        }
        return sb.toString()
    }
}
