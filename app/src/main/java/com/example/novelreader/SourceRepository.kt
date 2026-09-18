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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * 书源仓库：负责把书源 JSON 装进内存，并把「关键词搜索」分发到多条书源上并发执行。
 *
 * 设计要点（对齐 Legado 的 SearchModel 分发层）：
 * - 第25批：内置书源整条通路下线。assets 打包源与外部回归测试集全部拆除，
 *   App 初始零书源，一切书源由用户在「书源管理」导入后持久化到外部私有快照。
 * - 搜索按「限流并发 + 单源超时」执行，任一源炸掉只丢它自己的结果，不拖垮整批。
 * - 结果边出边回调，UI 可以做「流式」呈现，不必等 1264 条全部跑完。
 */
object SourceRepository {

    // 第25批：内置书源常量与回落链路已整体移除，App 不再携带任何预置书源。
    /** 第21批：用户在「书源管理」里导入/编辑后的快照文件名（App 专属外部目录，免权限）。 */
    private const val IMPORTED_NAME = "booksources_imported.json"

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
        // 0) 第21批：用户导入/编辑过的快照优先——存即代表用户当前意志，
        //    不再回落到测试集 / assets，避免「导入完重启又变回去」。
        runCatching {
            val imported = context.getExternalFilesDir(null)?.resolve(IMPORTED_NAME)
            if (imported != null && imported.exists() && imported.length() > 0L) {
                return imported.readText()
            }
        }
        // 1) 第25批：内置书源通路全部拆除——外部回归测试集、App 专属外部目录、
        //    assets 打包源三条路径都不再装载。App 初始零书源，
        //    一切书源都由用户在「书源管理」里导入，并落盘为下面的快照。
        return null
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
                        // 第 24 批加固：整段（含 filter / rank / onBatch 回调）都吞
                        // 异常。原实现 runCatching 只包住 withTimeout，isRelevant /
                        // rankByRelevance / onBatch 都在外面，单源一抛就炸穿协程。
                        runCatching {
                        val hits = runCatching {
                            withTimeout(perSourceTimeoutMs) { BookSourceEngine.search(src, key) }
                        }.getOrDefault(emptyList()).filter { isRelevant(it, key) }
                        if (hits.isNotEmpty()) {
                            // 累积 + 全量重排：回调的是「当前已命中的有序快照」，
                            // 不是增量批次。UI 直接整体替换即可，列表不会因到达顺序而抖。
                            val snapshot = synchronized(lock) {
                                acc += hits
                                rankByRelevance(acc, key)
                            // 第 24 批：按 bookUrl 去重，避免多源同一本书造成列表重复。
                            // 第 26 批：再按「书名 + 作者」聚合，同名书合并成一条（其余源进 altSources）。
                            }.distinctBy { it.bookUrl }.let { aggregate(it) }
                            onBatch(snapshot)
                        }
                        }
                    }
                }
            }
            jobs.awaitAll()
            synchronized(lock) { acc.size }
        }
    }

    /**
     * 第 26 批：书名 + 作者聚合。
     *
     * 同一本书会在多个书源里各搜到一条，书源不同则 [Book.bookUrl] 不同，
     * 上一批的 `distinctBy { bookUrl }` 拦不住，列表里就出现「一条书重复 N 遍」。
     * 这里把「同名 + 同作者」的结果收成一条：第一条（排序最靠前、通常最可信）
     * 作为代表，其余收进它的 [Book.altSources]，供阅读页换源。
     *
     * 归一键沿用 [normalize]（全角转半角、去空白与标点、统一小写），
     * 所以《斗破苍穹》与「斗破 苍穹！」不会被拆成两条。
     * 书名为空容不下聚合，直接各自成条，避免把不相关的书搅一起。
     */
    private fun aggregate(list: List<Book>): List<Book> {
        // 每次快照都是全量重排，先清空历史挂载，避免上一轮的兄弟源残留。
        list.forEach { it.altSources = emptyList() }
        val out = ArrayList<Book>()
        // 第 27 批：先按 key 收集每组的全部成员，聚合完再「对称」互相挂载。
        // 第 26 批的缺陷：只有代表书挂上了兄弟表，用户一旦换源切到非代表书，
        // 新 currentBook 的 altSources 是空的，换源弹窗就只剩它自己。
        val groups = LinkedHashMap<String, MutableList<Book>>()
        for (b in list) {
            val n = normalize(b.name)
            if (n.isEmpty()) {
                out += b
                continue
            }
            val key = n + "\u0001" + normalize(b.author)
            val g = groups.getOrPut(key) { ArrayList() }
            if (g.isEmpty()) out += b
            g += b
        }
        // 同组每个成员都能看到「除自己以外的全部兄弟源」，换到哪一本都列得全。
        for (g in groups.values) {
            if (g.size <= 1) continue
            // 第34批：代表条目封面为空时，从同组兄弟源取第一个可用封面回填。
            // 多源里只要有任意一个源给了封面，列表这条就不会秃着。
            val cover = g.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
            if (cover != null) {
                for (b in g) if (b.coverUrl.isNullOrBlank()) b.coverUrl = cover
            }
            for (b in g) {
                b.altSources = g.filter { it !== b }
            }
        }
        return out
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
     * 相关性命中判定：书名或作者里**必须**命中关键词，才允许进入结果列表。
     *
     * 目的：不少书源搜不到时会把「整站书库 / 分类页」原样吐回来，或按正文、简介里
     * 擦到的一个字硬凑结果——这些在 [rankByRelevance] 里只会被丢进第 3 档垫底。
     * 与其让用户在噪声里翻，不如在入库前直接剔掉，列表里只留「比较准的」。
     *
     * 关键词按空白 / 标点切成 token（顺带兼容「书名 作者」这类多词查询），
     * 任一 token 命中书名或作者即算相关。归一化后为空（纯符号）时不设门槛。
     */
    private fun isRelevant(book: Book, key: String): Boolean {
        val ts = tokens(key)
        if (ts.isEmpty()) return true
        val n = normalize(book.name)
        val a = normalize(book.author)
        return ts.any { n.contains(it) || a.contains(it) }
    }

    /** 把查询词切成归一化 token：全角转半角后，非字母数字一律当分隔符。 */
    private fun tokens(key: String): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (ch in key) {
            var c = ch
            if (c.code in 0xFF01..0xFF5E) c = (c.code - 0xFEE0).toChar()
            if (c == '\u3000') c = ' '
            val lower = c.lowercaseChar()
            if (lower.isLetterOrDigit()) {
                sb.append(lower)
            } else if (sb.isNotEmpty()) {
                out.add(sb.toString())
                sb.clear()
            }
        }
        if (sb.isNotEmpty()) out.add(sb.toString())
        return out
    }

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
    // ==================================================================
    // 第21批：书源「写」能力 —— 导入 / 删除 / 启停（书源管理页用）
    // ==================================================================
    /** 落盘用的 Json（宽松、带默认值，键名与 Legado 对齐）。 */
    private val writeJson = Json {
        encodeDefaults = true
        explicitNulls = false
    }

    /** 当前内存快照（书源管理页展示用）。 */
    fun snapshot(): List<BookSource> = sources

    /** 整包落盘 + 热重载内存与计数。 */
    @Synchronized
    private fun writeAndReload(context: Context, list: List<BookSource>) {
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return
            if (!dir.exists()) dir.mkdirs()
            dir.resolve(IMPORTED_NAME).writeText(
                writeJson.encodeToString(ListSerializer(BookSource.serializer()), list),
            )
        }
        sources = list
        parseFailed = 0
        loaded = true
    }

    /**
     * 导入书源：把 [incoming] 合并进现有集合，按 [BookSource.getKey]（url_name）去重，
     * 同键以新导入的为准；整体落盘并热重载。返回「净新增」条数。
     */
    @Synchronized
    fun import(context: Context, incoming: List<BookSource>): Int {
        val merged = sources.toMutableList()
        val index = HashMap<String, Int>(merged.size * 2)
        merged.forEachIndexed { i, s -> index[s.getKey()] = i }
        var added = 0
        for (s in incoming) {
            val k = s.getKey()
            val i = index[k]
            if (i == null) {
                // 第22批：新导入的书源默认启用——导入即可用，不必再去点开关。
                s.enabled = true
                index[k] = merged.size
                merged.add(s)
                added++
            } else {
                // 第22批：同键书源只刷新规则，保留用户对该源的启停选择。
                s.enabled = merged[i].enabled
                merged[i] = s
            }
        }
        writeAndReload(context, merged)
        return added
    }

    /** 按唯一键批量删除，返回实际删除条数。 */
    @Synchronized
    fun delete(context: Context, keys: Set<String>): Int {
        if (keys.isEmpty()) return 0
        val kept = sources.filter { it.getKey() !in keys }
        val removed = sources.size - kept.size
        if (removed > 0) writeAndReload(context, kept)
        return removed
    }

    /** 单条启用 / 停用并落盘。 */
    @Synchronized
    fun setEnabled(context: Context, key: String, enabled: Boolean) {
        val list = sources.map { src ->
            if (src.getKey() == key) {
                src.enabled = enabled
                src
            } else {
                src
            }
        }
        writeAndReload(context, list)
    }
}
