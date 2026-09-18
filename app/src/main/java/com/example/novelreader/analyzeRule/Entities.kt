package com.example.novelreader.analyzeRule

/**
 * 规则引擎侧对「书籍」的最小契约（对应 Legado `BookLike`）。
 *
 * 规则里 `book.name`、`java.get("bookName")` 等会用到；
 * 规则引擎不关心书籍实体的其它字段，只暴露名字。
 */
interface BookLike {
    val name: String
}

/**
 * 规则引擎侧对「章节」的最小契约（对应 Legado `BookChapterLike`）。
 *
 * JS 侧可通过 `chapter.title` 取标题，通过 `java.put/get` 读写章节级变量。
 */
interface BookChapterLike {
    val title: String
    fun putVariable(key: String, value: String?)
    fun getVariable(key: String): String?
}
