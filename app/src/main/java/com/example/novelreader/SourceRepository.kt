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
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

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
                // 第40批：历史快照里同一个站可能挂着多个换名实例（旧口径只按
                // url_name 去重，拦不住）。装载时一次性按站级键收敛并落盘，
                // 避免老数据每次启动都要再算一遍。
                val deduped = dedupeByStation(ok)
                sources = deduped
                if (deduped.size != ok.size) {
                    runCatching { writeSnapshot(context, deduped) }
                }
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
    /**
     * 第42批：在 [list] 里按「等步长」均匀抽 [n] 条，保持原有相对顺序。
     *
     * 旧实现 take(N) 取的是列表头部 N 条，而列表顺序 = 用户导入顺序，
     * 于是 3000 条源每次搜索永远只搜前 N 条，第 1500 条的好源永远轮不上。
     * 改为按 index 等步长抽样后，每次搜索都能覆盖源池的各个段位。
     */
    private fun <T> sampleEvenly(list: List<T>, n: Int): List<T> {
        if (n <= 0 || list.size <= n) return list
        val out = ArrayList<T>(n)
        val step = list.size.toDouble() / n
        for (i in 0 until n) {
            out.add(list[(i * step).toInt().coerceIn(0, list.size - 1)])
        }
        return out
    }

    suspend fun search(
        key: String,
        maxSources: Int = 500,
        concurrency: Int = 8,
        perSourceTimeoutMs: Long = 8000,
        onBatch: (List<Book>) -> Unit,
        onProgress: ((done: Int, total: Int) -> Unit)? = null,
        shouldStop: () -> Boolean = { false },
    ): Int {
        // 第40批：治「无论导入多少条源，命中永远只有几条」。
        // 第42批：① 默认档 300 → 500；② take(N) 改为「均匀抽样」——
        // 旧 take(N) 按列表顺序取头部，而列表顺序 = 导入顺序，3000 条源里
        // 后面导入的好源永远轮不上；现在按等步长在全量源池抽 N 条，覆盖各段位。
        // maxSources <= 0 表示全量（不抽样），默认 500 条均衡档。
        // 首波 48 条排在最前先发，用户几秒内就能看到第一批结果。
        val all = enabled()
        val targets = if (maxSources <= 0) all else sampleEvenly(all, maxSources)
        val sem = Semaphore(concurrency)
        val lock = Any()
        val acc = ArrayList<Book>()
        val total = targets.size
        val done = AtomicInteger(0)
        // 第40批：首波 48 条先发（默认并发 8，约 6 轮就能出水），
        // 让用户几秒内看到第一批结果；其余源继续在后台把池子灌满。
        val fastWave = 48
        val progressStep = 6
        val ordered = if (total <= fastWave) targets
        else targets.take(fastWave) + targets.drop(fastWave)
        val lastTick = AtomicLong(0L)
        return coroutineScope {
            val jobs = ordered.map { src ->
                async(Dispatchers.IO) {
                    sem.withPermit {
                        if (shouldStop()) return@withPermit
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
                                // 第40批：distinctBy / aggregate 一并挪进锁内——它们会写
                                // Book.altSources，出锁并发跑就是数据竞争（会互相清空兄弟源）。
                                val snapshot = synchronized(lock) {
                                    acc += hits
                                    rankByRelevance(acc, key)
                                        .distinctBy { it.bookUrl }
                                        .let { aggregate(it) }
                                }
                                if (!shouldStop()) onBatch(snapshot)
                            }
                        }
                        // 第40批：进度上报。逐源回调会刷爆 UI，这里按
                        //「每 6 源 / 距上次 ≥400ms / 收尾」三档节流。
                        val d = done.incrementAndGet()
                        val now = System.currentTimeMillis()
                        val prev = lastTick.get()
                        if (d == total || d % progressStep == 0 || now - prev >= 400L) {
                            if (d == total || lastTick.compareAndSet(prev, now)) {
                                runCatching { onProgress?.invoke(d, total) }
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
        // 第43批刀A：记录每组的「占位下标」，让代表能在 out 里原地换成规则最完备的那条。
        val slot = HashMap<String, Int>()
        for (b in list) {
            val n = normalize(b.name)
            if (n.isEmpty()) {
                out += b
                continue
            }
            val key = n + "\u0001" + normalize(b.author)
            val g = groups.getOrPut(key) { ArrayList() }
            if (g.isEmpty()) {
                // 第43批刀A：记下这组在 out 里的占位位置，稍后可原地换成最优代表。
                slot[key] = out.size
                out += b
            }
            g += b
        }
        // 同组每个成员都能看到「除自己以外的全部兄弟源」，换到哪一本都列得全。
        for ((key, g) in groups) {
            if (g.size <= 1) continue
            // 第43批刀A（治本）：组内先按「规则完备度」稳定排序，让真能出目录的源优先当代表。
            // sortedBy 是稳定排序：同一档内保持原相关性顺序，所以只把代表顶上去，
            // 代表仍占据该组在 out 中的原下标 —— 列表观感与既有排序键完全不变。
            val ordered = g.sortedBy { ruleCompleteness(it.source) }
            val rep = ordered.first()
            val holder = g[0]
            if (rep !== holder) {
                val idx = slot[key]
                if (idx != null && idx in out.indices) out[idx] = rep
            }
            // 第34批：代表条目封面为空时，从同组兄弟源取第一个可用封面回填。
            // 多源里只要有任意一个源给了封面，列表这条就不会秃着。
            val cover = ordered.firstOrNull { !it.coverUrl.isNullOrBlank() }?.coverUrl
            if (cover != null) {
                for (b in ordered) if (b.coverUrl.isNullOrBlank()) b.coverUrl = cover
            }
            for (b in ordered) {
                b.altSources = ordered.filter { it !== b }
            }
        }
        // 第44批刀A：聚合组按「命中源数量」降序 —— 命中的书源越多，说明越可能是用户
        // 想找的那本，整组顶到搜索结果第一位。sortedByDescending 是稳定排序：同数量组保持
        // 原相关性顺序；未成组的单条结果数量为 1，自然沉到底部，列表观感只变「强的更前」。
        val groupSizeOf = HashMap<Int, Int>()
        for ((key, idx) in slot) groupSizeOf[idx] = groups[key]?.size ?: 1
        return out.withIndex()
            .sortedByDescending { (i, _) -> groupSizeOf[i] ?: 1 }
            .map { it.value }
    }

    /**
     * 第43批刀A：书源「规则完备度」档位，数值越小越该当聚合代表。
     *
     * 背景：聚合代表原先只看 `rankByRelevance` 的相关性排序，压根不看这个源
     * 有没有目录 / 详情规则。结果只要代表源是个「有搜索规则、缺 ruleToc」的残源，
     * 整组 32 个兄弟源在详情页就形同虚设（目录解析为空、封面作者全未知）。
     *
     * - 0：有目录规则（`ruleToc.chapterList` 非空）+ 有详情规则 —— 名片与目录都能出；
     * - 1：只有目录规则 —— 详情名片可能缺字段，但目录能出，仍算可用；
     * - 2：只有搜索规则 —— 详情/目录都空，当代表会让整组失效，排到最后。
     *
     * 排序稳定：同档内保持原相关性顺序，所以仅影响「谁当代表」，不打乱列表观感。
     */
    private fun ruleCompleteness(src: BookSource?): Int {
        val hasToc = !src?.ruleToc?.chapterList.isNullOrBlank()
        val hasInfo = src?.ruleBookInfo != null
        return when {
            hasToc && hasInfo -> 0
            hasToc -> 1
            else -> 2
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

    /** 只负责把书源快照落盘，返回是否写入成功；不改内存态。 */
    private fun writeSnapshot(context: Context, list: List<BookSource>): Boolean =
        runCatching {
            val dir = context.getExternalFilesDir(null) ?: return@runCatching false
            if (!dir.exists()) dir.mkdirs()
            dir.resolve(IMPORTED_NAME).writeText(
                writeJson.encodeToString(ListSerializer(BookSource.serializer()), list),
            )
            true
        }.getOrDefault(false)

    /**
     * 第40批：按「站级键」收敛重复书源，保留每组第一条。
     *
     * 收敛键用 [BookSource.dedupeKey]（URL 归一化）而不是 [BookSource.getKey]，
     * 因为 getKey 把书源名一起拼了进来——同一个站换个名字就变成两条源，
     * 正是「导入很多条、实际只有几条生效」的根因之一。
     */
    private fun dedupeByStation(list: List<BookSource>): List<BookSource> {
        if (list.size <= 1) return list
        val seen = HashSet<String>(list.size * 2)
        val out = ArrayList<BookSource>(list.size)
        for (s in list) if (seen.add(s.dedupeKey())) out += s
        return out
    }

    /**
     * 第40批：手动触发一次「站级去重」，返回清理掉的重复源条数。
     * 供书源管理页的「清理重复源」入口调用（历史快照里可能早已堆了重复）。
     */
    @Synchronized
    fun dedupeExisting(context: Context): Int {
        val deduped = dedupeByStation(sources)
        val removed = sources.size - deduped.size
        if (removed > 0) writeAndReload(context, deduped)
        return removed
    }
    private fun writeAndReload(context: Context, list: List<BookSource>) {
        writeSnapshot(context, list)
        sources = list
        parseFailed = 0
        loaded = true
    }

    /**
     * 导入书源：把 [incoming] 合并进现有集合，按 [BookSource.dedupeKey]（站级归一化 URL）
     * 去重，同键以新导入的为准；整体落盘并热重载。返回「净新增」条数。
     */
    @Synchronized
    fun import(context: Context, incoming: List<BookSource>): Int {
        val merged = sources.toMutableList()
        val index = HashMap<String, Int>(merged.size * 2)
        // 第40批：建键从 getKey()（url_name，把书源名也拼了进去）换成 dedupeKey()。
        // 同一个站换个名字再导一次，过去会被当成新源追加，于是「导了几百条、
        // 实际只有几十个站」——搜索时同站重复源互相挤占配额，命中率被稀释。
        // 现在同站收敛为一条（保留路径，所以同站不同接口的有效源不会被误合并）。
        merged.forEachIndexed { i, s -> index[s.dedupeKey()] = i }
        var added = 0
        for (s in incoming) {
            val k = s.dedupeKey()
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
