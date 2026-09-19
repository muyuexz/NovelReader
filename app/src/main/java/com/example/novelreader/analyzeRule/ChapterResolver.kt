package com.example.novelreader.analyzeRule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * 第 43 批（第44批升级）：详情 + 目录的「多源取数评优」解析器。
 *
 * 背景（要治的病）：
 * 搜索按「书名 + 作者」聚合后，只有排序最靠前的那条被当作代表书，其余兄弟源挂在
 * [Book.altSources] 上。可是详情页原先只用代表书的 `book.source` 单源去
 * getBookInfo / getChapterList —— 代表源只要是个「有搜索规则、缺 ruleBookInfo /
 * ruleToc」的残源，用户就会看到「32 个源命中」却「目录解析为空、封面作者简介全未知」。
 *
 * 本解析器把「代表源 + 全部兄弟源」铺成候选集，限流并发取数：
 * - 每个候选跑 getBookInfo + getChapterList，能出非空目录的才进评优池；
 * - 首个成功后再给一个采集窗口，等并发中的优质源回包，避免「快而残」的源抢先定格；
 * - 最后按 [score] 选「适配信息最好、准确率最高」的一条（第44批刀B：由竞速改为评优）；
 * - 全部失败返回 null，UI 侧回落原有错误文案（可附已尝试源数）。
 *
 * 设计约束：纯逻辑，不碰 Compose / Context；单候选异常只丢自己；显式重抛
 * [CancellationException]，不吞协程取消。
 */
object ChapterResolver {

    /** 解析结果：胜出的书 + 它解析出的目录。 */
    data class Result(val book: Book, val chapters: List<BookChapter>)

    /**
     * @param candidates 候选书（通常 = 代表书 + book.altSources），按优先级排列。
     * @param concurrency 同时在跑的候选数上限（避免几十个源一次性打满网络）。
     * @param perCandidateTimeoutMs 单候选超时（含详情 + 目录两次请求）。
     * @param onProgress 每有一个候选返回就回调一次（tried = 已返回数，total = 候选总数）。
     */
    suspend fun resolve(
        candidates: List<Book>,
        concurrency: Int = 6,
        perCandidateTimeoutMs: Long = 10_000L,
        collectWindowMs: Long = 2_000L,
        onProgress: ((tried: Int, total: Int) -> Unit)? = null,
    ): Result? = withContext(Dispatchers.IO) {
        val uniq = candidates.filter { it.source != null }.distinctBy { it.bookUrl }
        if (uniq.isEmpty()) return@withContext null

        val total = uniq.size
        val tried = AtomicInteger(0)
        // 第44批刀B：不再「谁快谁赢」。全部成功候选进评优池，最后按 score 择优。
        val results = Collections.synchronizedList(ArrayList<Result>())
        val allDone = CompletableDeferred<Unit>()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val sem = Semaphore(concurrency)
        val workers = uniq.map { b ->
            scope.launch {
                sem.withPermit {
                    val src = b.source ?: return@withPermit
                    val list = try {
                        withTimeout(perCandidateTimeoutMs) {
                            BookSourceEngine.getBookInfo(src, b)
                            BookSourceEngine.getChapterList(src, b)
                        }
                    } catch (ce: CancellationException) {
                        throw ce
                    } catch (t: Throwable) {
                        emptyList()
                    }
                    val n = tried.incrementAndGet()
                    runCatching { onProgress?.invoke(n, total) }
                    // 只认「能出非空目录」的源；空目录 / 异常不进评优池。
                    if (list.isNotEmpty()) results.add(Result(b, list))
                }
            }
        }
        scope.launch {
            workers.joinAll()
            allDone.complete(Unit)
        }
        // 采集窗口：等「全部候选跑完」或「首个成功后再多等 collectWindowMs」，
        // 给并发中的优质源一个回包机会，避免个别快而残的源抢先定格。
        val hardDeadline = perCandidateTimeoutMs + collectWindowMs + 2_000L
        var waited = 0L
        while (waited < hardDeadline) {
            delay(150L)
            waited += 150L
            if (allDone.isCompleted || results.isNotEmpty()) {
                // 有源成功了但还有兄弟在跑：再等一个短窗口收编更优解。
                if (results.isNotEmpty() && !allDone.isCompleted) delay(collectWindowMs)
                break
            }
        }
        scope.cancel()
        val pool = synchronized(results) { results.toList() }
        // 评优：分高者胜；同分保留更靠前的候选（候选序 = 优先级），maxByOrNull 天然保先。
        pool.maxByOrNull { score(it.book, it.chapters) }
    }

    /**
     * 第44批刀B：候选源「适配度」评分 —— 分数越高越该被详情页 / 阅读页采用。
     *
     * 评分维度（对齐「适配信息最好 + 准确率最高」）：
     * - 规则完备度（权重最高）：有目录规则 + 有详情规则 = 真能出目录与名片；缺详情 / 缺目录依次减分；
     * - 详情字段完整度：作者 / 简介 / 封面 / 分类 / 状态 / 字数，填得越全越像一本「真的书」；
     * - 目录丰度：章节数越多越可信，但封顶 300 章，避免超长目录单方面压死其它维度；
     * - 书源权重 weight：高权重源的结果更可信（与搜索排序键同源）。
     */
    private fun score(book: Book, chapters: List<BookChapter>): Int {
        val src = book.source
        var s = 0
        if (!src?.ruleToc?.chapterList.isNullOrBlank()) s += 40
        if (src?.ruleBookInfo != null) s += 30
        if (!book.author.isNullOrBlank()) s += 6
        if (!book.intro.isNullOrBlank()) s += 6
        if (!book.coverUrl.isNullOrBlank()) s += 6
        if (!book.kind.isNullOrBlank()) s += 4
        if (!book.status.isNullOrBlank()) s += 3
        if (!book.wordCount.isNullOrBlank()) s += 3
        s += minOf(chapters.size, 300) / 10
        s += (src?.weight ?: 0) / 5
        return s
    }
}
