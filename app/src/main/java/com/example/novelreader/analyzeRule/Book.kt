package com.example.novelreader.analyzeRule

/**
 * 书籍实体（同构复刻 Legado `io.legado.app.data.entities.Book` 的最小面）。
 *
 * 同时实现 [BookLike] 与 [RuleDataInterface]：
 * - [BookLike] 让规则里 `book.name`、`java.get("bookName")` 拿得到书名；
 * - [RuleDataInterface] 让书级变量（`java.put` 写在书上的键值）有地方落，
 *   超长值（大 JSON、整章正文）自动走 bigVariable 旁路。
 */
class Book(
    var bookUrl: String = "",
    override var name: String = "",
    var author: String = "",
    var kind: String? = null,
    var intro: String? = null,
    var coverUrl: String? = null,
    var tocUrl: String = "",
    var wordCount: String? = null,
    var lastChapter: String? = null,
    var origin: String = "",
    var originName: String = "",
    var type: Int = 0,
    var variable: String? = null,
) : BookLike, RuleDataInterface {

    /**
     * 目录真实章节数。搜索阶段书源通常不给（或给的是错的），
     * 拉到目录后用 chapters.size 回填，搜索卡片 / 详情页据此显示一致的数字。
     */
    var chapterCount: Int = 0

    /** 命中该书源；搜索阶段回填，供详情/目录/正文复用同一条书源的规则与请求头。 */
    var source: BookSource? = null

    override val variableMap = HashMap<String, String>()

    private val bigVariableMap = HashMap<String, String>()

    override fun putBigVariable(key: String, value: String?) {
        if (value == null) bigVariableMap.remove(key) else bigVariableMap[key] = value
    }

    override fun getBigVariable(key: String): String? = bigVariableMap[key]

    override fun toString(): String = "Book(name=$name, author=$author, origin=$originName)"
}
