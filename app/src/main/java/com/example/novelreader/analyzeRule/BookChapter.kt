package com.example.novelreader.analyzeRule

/**
 * 章节实体（同构复刻 Legado `io.legado.app.data.entities.BookChapter` 的最小面）。
 *
 * 实现 [BookChapterLike]：规则里 `chapter.title`、`java.get("title")` 可用，
 * 且章节级变量（正文分页时 `java.put` 的中间态）落在章节自己身上。
 */
class BookChapter(
    var url: String = "",
    override var title: String = "",
    var bookUrl: String = "",
    var index: Int = 0,
    var isVip: Boolean = false,
    var isPay: Boolean = false,
    var updateTime: Long? = null,
) : BookChapterLike {

    private val variableMap = HashMap<String, String>()

    override fun putVariable(key: String, value: String?) {
        if (value == null) variableMap.remove(key) else variableMap[key] = value
    }

    override fun getVariable(key: String): String? = variableMap[key]

    override fun toString(): String = "BookChapter(index=$index, title=$title)"
}
