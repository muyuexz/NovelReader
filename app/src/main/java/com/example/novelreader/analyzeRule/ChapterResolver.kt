package com.example.novelreader.analyzeRule

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger

/**
 * 第 43 批：详情 + 目录的「多源竞速」解析器。
 *
 * 背景（要治的病）：
 * 搜索按「书名 + 作者」聚合后，只有排序最靠前的那条被当作代表书，其余兄弟源挂在
 * [Book.altSources] 上。可是详情页原先只用代表书的 `book.source` 单源去
 * getBookInfo / getChapterList —— 代表源只要是个「有搜索规则、缺 ruleBookInfo /
 * ruleToc」的残源，用户就会看到「32 个源命中」却「目录解析为空、封面作者简介全未知」。
 *
 * 本解析器把「代表源 + 全部兄弟源」铺成候选集，限流并发竞速：
 * - 谁先返回非空目录谁胜出（[Result.book] 即胜出源，其详情字段已被回填）；
 * - 胜出后立刻取消其余候选，不让用户为慢源 / 死源买单；
 * - 全部失败返回 null，UI 侧回落原有错误文案（可附已尝试源数）。
 *
 * 设计约束：纯逻辑，不碰 Compose / Context；单候选异常只丢自己；显式重抛
 * [CancellationException]，不吞协程取消。
 */
object ChapterResolver {

    /** 竞速结果：胜出的书 + 它解析出的目录。 */
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
        onProgress: ((tried: Int, total: Int) -> Unit)? = null,
    ): Result? = withContext(Dispatchers.IO) {
        val uniq = candidates.filter { it.source != null }.distinctBy { it.bookUrl }
        if (uniq.isEmpty()) return@withContext null

        val total = uniq.size
        val tried = AtomicInteger(0)
        val winner = CompletableDeferred<Result?>()
        val job = SupervisorJob()
        val scope = CoroutineScope(job + Dispatchers.IO)
        val sem = Semaphore(concurrency)
        val workers = uniq.map { b ->
            scope.launch {
                sem.withPermit {
                    if (winner.isCompleted) return@withPermit
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
                    if (list.isNotEmpty()) winner.complete(Result(b, list))
                }
            }
        }
        scope.launch {
            workers.joinAll()
            winner.complete(null)
        }
        val r = winner.await()
        scope.cancel()
        r
    }
}
