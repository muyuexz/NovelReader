package com.example.novelreader.analyzeRule

import android.content.Context
import com.example.novelreader.ChapterDiskCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

/**
 * 第 52 批 L2：总字数「本地估算」引擎。
 *
 * 思路：**不依赖任何书源上报、也不依赖别的源配合** ——
 * 直接抽样若干章正文，数出平均每章字数，再乘全书章节数。
 * 因为正文获取能力、正文清洗、磁盘缓存本工程都已具备，这条路自给自足，
 * 对「偏大（字节数）」「偏小（抓错字段）」「源没给」三类脏值一律有效。
 *
 * 成本控制：只在 [WordCountResolver.needsEstimate] 判定源值可疑时才调用；
 * 结果由 [WordCountStore] 按「书名 + 作者」持久化 —— 同一本书不管从哪个源进来，
 * 只付一次代价，之后全源复用。这就是「一劳永逸」的落点。
 *
 * 失败一律返回 null（样本不足、网络不通、源规则失效），调用方回落原逻辑。
 */
object WordCountEstimator {

    /** 抽样章数：首 / 中 / 尾均匀取。样本越多越准，流量也越大。 */
    // 第 54 批：4 → 8。样本太少时，首章（短序章）、尾章（短公告/后记）会把
    // 均值明显拉低（实测「约444万」vs 官方 533 万，矮约 17%），翻倍后波动收敛。
    private const val SAMPLE_SIZE = 8

    /** 至少要有几个成功样本才认为估算可信。 */
    private const val MIN_SAMPLES = 2

    /**
     * 抽样估算全书总字数（字）；不可信时返回 null。
     *
     * 阻塞式网络与解析都跑在 [Dispatchers.IO]；协程被取消（用户离开详情页）时
     * 循环会在下一次迭代前自然退出。
     */
    suspend fun estimate(
        ctx: Context,
        book: Book,
        chapters: List<BookChapter>,
    ): Long? = withContext(Dispatchers.IO) {
        val source = book.source ?: return@withContext null
        val real = chapters.filter { ChapterStats.isRealChapter(it.title) }
            .ifEmpty { chapters }
        if (real.size < MIN_SAMPLES) return@withContext null

        val picks = pick(real, SAMPLE_SIZE)
        val lens = ArrayList<Long>(picks.size)
        for (ch in picks) {
            if (!currentCoroutineContext().isActive) break
            val body = fetch(ctx, source, book, ch) ?: continue
            val len = countWords(body)
            if (len <= 0L) continue
            lens += len
        }
        if (lens.size < MIN_SAMPLES) return@withContext null

        // 第 54 批：均值改「去极值均值（trimmed mean）」——样本够 4 个时，
        // 先去掉一个最高、一个最低再平均。首/尾取样挑中的短序章、短公告是
        // 主要低估源，去极值比直接 sum/ok 稳得多，也不会被单个超大章虚高。
        val sorted = lens.sorted()
        val used = if (sorted.size >= 4) sorted.subList(1, sorted.size - 1) else sorted
        val avg = used.sum() / used.size
        val est = avg * real.size
        if (est > 0L) est else null
    }

    /** 首 / 中 / 尾均匀抽样，最多 [n] 章。 */
    private fun pick(list: List<BookChapter>, n: Int): List<BookChapter> {
        val size = list.size
        if (size <= n) return list
        val out = LinkedHashSet<BookChapter>(n)
        for (i in 0 until n) {
            val idx = (size - 1).toLong() * i / (n - 1)
            out += list[idx.toInt()]
        }
        return out.toList()
    }

    /** 取正文：先查磁盘缓存（阅读/搜索时可能已存），未命中才走网络，拿到即回写缓存。 */
    private fun fetch(
        ctx: Context,
        source: BookSource,
        book: Book,
        chapter: BookChapter,
    ): String? {
        val url = chapter.url
        if (url.isBlank()) return null
        ChapterDiskCache.get(ctx, url)?.takeIf { it.isNotBlank() }?.let { return it }
        val raw = runCatching { BookSourceEngine.getContent(source, book, chapter) }.getOrNull()
            ?: return null
        val clean = runCatching { BookSourceEngine.cleanContent(raw) }.getOrNull() ?: raw
        if (clean.isNotBlank()) runCatching { ChapterDiskCache.put(ctx, url, clean) }
        return clean
    }

    /** 正文字数口径：去掉所有空白字符后的长度（与阅读页显示一致）。 */
    private fun countWords(body: String): Long {
        var n = 0L
        for (c in body) if (!c.isWhitespace()) n++
        return n
    }
}
