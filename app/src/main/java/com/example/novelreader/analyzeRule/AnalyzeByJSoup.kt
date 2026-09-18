package com.example.novelreader.analyzeRule

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.parser.Parser
import org.jsoup.select.Elements

/**
 * 书源默认规则解析器（CSS / Jsoup 后端）。
 *
 * 同构复刻自 Legado `io.legado.app.model.analyzeRule.AnalyzeByJSoup`，
 * 仅把 `com.fleeksoft.ksoup`（KMP 版）替换为本工程依赖的 jsoup 1.17.2，
 * API 完全同构。支持：
 *
 * - `tag.` / `class.` / `id.` / `text.` / `children` 简写前缀，其余走 CSS 选择器；
 * - `@` 分隔的逐级提取，末段为提取方式：`text` / `textNodes` / `ownText` / `html` / `all` / 属性名；
 * - `&&` / `||` / `%%` 组合；
 * - 索引语法：阅读旧写法 `tag.div.-1:10:2`、`tag.div!0:3`，
 *   以及类 JsonPath 写法 `tag.div[-1,3:-2:-10,2]`（`!` 开头为排除，`[-1:0]` 反向）。
 */
class AnalyzeByJSoup(doc: Any) {

    private var element: Element = parse(doc)

    private fun parse(doc: Any): Element {
        if (doc is Element) {
            return doc
        }
        if (doc is Node) {
            return Jsoup.parse(doc.toString())
        }
        runCatching {
            if (doc.toString().startsWith("<?xml", true)) {
                return Jsoup.parse(doc.toString(), Parser.xmlParser())
            }
        }
        return Jsoup.parse(doc.toString())
    }

    /** 获取列表 */
    fun getElements(rule: String) = getElements(element, rule)

    /** 合并内容列表，得到内容 */
    fun getString(ruleStr: String): String? {
        if (ruleStr.isEmpty()) return null
        val list = getStringList(ruleStr)
        if (list.isEmpty()) return null
        if (list.size == 1) return list.first()
        return list.joinToString("\n")
    }

    /** 获取一个字符串 */
    fun getString0(ruleStr: String) =
        getStringList(ruleStr).let { if (it.isEmpty()) "" else it[0] }

    /** 获取所有内容列表 */
    fun getStringList(ruleStr: String): List<String> {
        val textS = ArrayList<String>()
        if (ruleStr.isEmpty()) return textS

        val sourceRule = SourceRule(ruleStr)

        if (sourceRule.elementsRule.isEmpty()) {
            textS.add(element.data())
        } else {
            val ruleAnalyzes = RuleAnalyzer(sourceRule.elementsRule)
            val ruleStrS = ruleAnalyzes.splitRule("&&", "||", "%%")

            val results = ArrayList<List<String>>()
            for (ruleStrX in ruleStrS) {
                val temp: ArrayList<String>? =
                    if (sourceRule.isCss) {
                        val lastIndex = ruleStrX.lastIndexOf('@')
                        getResultLast(
                            element.select(ruleStrX.take(lastIndex)),
                            ruleStrX.substring(lastIndex + 1)
                        )
                    } else {
                        getResultList(ruleStrX)
                    }

                if (!temp.isNullOrEmpty()) {
                    results.add(temp)
                    if (ruleAnalyzes.elementsType == "||") break
                }
            }
            if (results.isNotEmpty()) {
                RuleCombiner.combineResults(results, ruleAnalyzes.elementsType, textS)
            }
        }
        return textS
    }

    /** 获取 Elements */
    private fun getElements(temp: Element?, rule: String): Elements {
        if (temp == null || rule.isEmpty()) return Elements()

        val elements = Elements()
        val sourceRule = SourceRule(rule)
        val ruleAnalyzes = RuleAnalyzer(sourceRule.elementsRule)
        val ruleStrS = ruleAnalyzes.splitRule("&&", "||", "%%")

        val elementsList = ArrayList<Elements>()
        if (sourceRule.isCss) {
            for (ruleStr in ruleStrS) {
                val tempS = temp.select(ruleStr)
                elementsList.add(tempS)
                if (tempS.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                    break
                }
            }
        } else {
            for (ruleStr in ruleStrS) {
                val rsRule = RuleAnalyzer(ruleStr)
                rsRule.trim()
                val rs = rsRule.splitRule("@")

                val el = if (rs.size > 1) {
                    val el = Elements()
                    el.add(temp)
                    for (rl in rs) {
                        val es = Elements()
                        for (et in el) {
                            es.addAll(getElements(et, rl))
                        }
                        el.clear()
                        el.addAll(es)
                    }
                    el
                } else ElementsSingle().getElementsSingle(temp, ruleStr)

                elementsList.add(el)
                if (el.isNotEmpty() && ruleAnalyzes.elementsType == "||") {
                    break
                }
            }
        }
        if (elementsList.isNotEmpty()) {
            RuleCombiner.combineResults(elementsList, ruleAnalyzes.elementsType, elements)
        }
        return elements
    }

    /** 获取内容列表 */
    private fun getResultList(ruleStr: String): ArrayList<String>? {
        if (ruleStr.isEmpty()) return null

        var elements = Elements()
        elements.add(element)

        val rule = RuleAnalyzer(ruleStr)
        rule.trim()
        val rules = rule.splitRule("@")

        val last = rules.size - 1
        for (i in 0 until last) {
            val es = Elements()
            for (elt in elements) {
                es.addAll(ElementsSingle().getElementsSingle(elt, rules[i]))
            }
            elements = es
        }
        return if (elements.isEmpty()) null else getResultLast(elements, rules[last])
    }

    /** 根据最后一个规则获取内容 */
    private fun getResultLast(elements: Elements, lastRule: String): ArrayList<String> {
        val textS = ArrayList<String>()
        when (lastRule) {
            "text" -> for (element in elements) {
                val text = element.text()
                if (text.isNotEmpty()) textS.add(text)
            }

            "textNodes" -> for (element in elements) {
                val tn = arrayListOf<String>()
                val contentEs = element.textNodes()
                for (item in contentEs) {
                    val text = item.text().trim()
                    if (text.isNotEmpty()) tn.add(text)
                }
                if (tn.isNotEmpty()) textS.add(tn.joinToString("\n"))
            }

            "ownText" -> for (element in elements) {
                val text = element.ownText()
                if (text.isNotEmpty()) textS.add(text)
            }

            "html" -> {
                elements.select("script").remove()
                elements.select("style").remove()
                // 对齐 Legado：`@html` 取元素内部 HTML（innerHTML），
                // `@all` 才取 outerHtml（含自身标签）。
                val html = elements.html()
                if (html.isNotEmpty()) textS.add(html)
            }

            "all" -> textS.add(elements.outerHtml())

            else -> for (element in elements) {
                val url = element.attr(lastRule)
                if (url.isBlank() || textS.contains(url)) continue
                textS.add(url)
            }
        }
        return textS
    }

    /**
     * 索引语法处理器。
     *
     * 1. 支持阅读原有写法：`:` 分隔索引，`!` 或 `.` 表示筛选方式，索引可为负数。
     *    例如 `tag.div.-1:10:2` 或 `tag.div!0:3`。
     * 2. 支持与 JsonPath 类似的 `[]` 索引写法：`[it,it,...]` 或 `[!it,it,...]`，
     *    `[!` 开头表示排除。区间格式 `start:end` 或 `start:end:step`，
     *    两端及间隔都支持负数。例如 `tag.div[-1, 3:-2:-10, 2]`；
     *    特殊用法 `tag.div[-1:0]` 可在任意位置让列表反向。
     */
    data class ElementsSingle(
        var split: Char = '.',
        var beforeRule: String = "",
        val indexDefault: MutableList<Int> = mutableListOf(),
        val indexes: MutableList<Any> = mutableListOf()
    ) {

        fun getElementsSingle(temp: Element, rule: String): Elements {

            findIndexSet(rule)

            // 获取所有元素
            var elements =
                if (beforeRule.isEmpty()) temp.children()
                else {
                    val rules = beforeRule.split(".")
                    when (rules[0]) {
                        "children" -> temp.children()
                        "class" -> temp.getElementsByClass(rules[1])
                        "tag" -> temp.getElementsByTag(rules[1])
                        "id" -> {
                            val el = temp.getElementById(rules[1])
                            if (el != null) Elements(listOf(el)) else Elements()
                        }
                        "text" -> temp.getElementsContainingOwnText(rules[1])
                        else -> temp.select(beforeRule)
                    }
                }

            val len = elements.size
            val lastIndexes = (indexDefault.size - 1).takeIf { it != -1 } ?: (indexes.size - 1)
            val indexSet = mutableSetOf<Int>()

            // 获取无重且不越界的索引集合
            if (indexes.isEmpty()) for (ix in lastIndexes downTo 0) {
                val it = indexDefault[ix]
                if (it in 0 until len) indexSet.add(it)
                else if (it < 0 && len >= -it) indexSet.add(it + len)
            } else for (ix in lastIndexes downTo 0) {
                if (indexes[ix] is Triple<*, *, *>) { // 区间
                    val (startX, endX, stepX) = indexes[ix] as Triple<Int?, Int?, Int>

                    var start = startX ?: 0
                    if (start < 0) start += len

                    var end = endX ?: (len - 1)
                    if (end < 0) end += len

                    if ((start < 0 && end < 0) || (start >= len && end >= len)) continue

                    if (start >= len) start = len - 1
                    else if (start < 0) start = 0

                    if (end >= len) end = len - 1
                    else if (end < 0) end = 0

                    if (start == end || stepX >= len) {
                        indexSet.add(start)
                        continue
                    }

                    val step =
                        if (stepX > 0) stepX else if (-stepX < len) stepX + len else 1

                    indexSet.addAll(
                        if (end > start) start..end step step else start downTo end step step
                    )
                } else { // 单个索引
                    val it = indexes[ix] as Int
                    if (it in 0 until len) indexSet.add(it)
                    else if (it < 0 && len >= -it) indexSet.add(it + len)
                }
            }

            // 根据索引集合筛选元素
            if (split == '!') {
                elements = elements.filterIndexedTo(Elements()) { i, _ -> i !in indexSet }
            } else if (split == '.') {
                val es = Elements()
                for (pcInt in indexSet) es.add(elements[pcInt])
                elements = es
            }

            return elements
        }

        private fun findIndexSet(rule: String) {

            val rus = rule.trim()
            var len = rus.length
            var curInt: Int?
            var curMinus = false
            val curList = mutableListOf<Int?>()
            var l = ""

            val head = rus.lastOrNull() == ']'

            if (head) { // 常规索引写法 [index...]
                len--
                while (len-- >= 0) {
                    var rl = rus[len]
                    if (rl == ' ') continue

                    if (rl in '0'..'9') l = rl + l
                    else if (rl == '-') curMinus = true
                    else {
                        curInt = if (l.isEmpty()) null else if (curMinus) -l.toInt() else l.toInt()

                        when (rl) {
                            ':' -> curList.add(curInt)
                            else -> {
                                if (curList.isEmpty()) {
                                    if (curInt == null) break
                                    indexes.add(curInt)
                                } else {
                                    indexes.add(
                                        Triple(
                                            curInt,
                                            curList.last(),
                                            if (curList.size == 2) curList.first() else 1
                                        )
                                    )
                                    curList.clear()
                                }

                                if (rl == '!') {
                                    split = '!'
                                    do {
                                        rl = rus[--len]
                                    } while (len > 0 && rl == ' ')
                                }

                                if (rl == '[') {
                                    beforeRule = rus.take(len)
                                    return
                                }

                                if (rl != ',') break
                            }
                        }

                        l = ""
                        curMinus = false
                    }
                }
            } else while (len-- >= 0) { // 阅读原本写法
                val rl = rus[len]
                if (rl == ' ') continue

                if (rl in '0'..'9') l = rl + l
                else if (rl == '-') curMinus = true
                else {
                    if (rl == '!' || rl == '.' || rl == ':') {
                        if (l.isNotEmpty()) {
                            indexDefault.add(if (curMinus) -l.toInt() else l.toInt())
                        }
                        if (rl != ':') {
                            split = rl
                            beforeRule = rus.take(len)
                            return
                        }
                    } else break

                    l = ""
                    curMinus = false
                }
            }

            split = ' '
            beforeRule = rus
        }
    }

    /** `@CSS:` 前缀标记：后续结果按 CSS 直选处理 */
    private class SourceRule(ruleStr: String) {
        var isCss = false
        var elementsRule: String = if (ruleStr.startsWith("@CSS:", true)) {
            isCss = true
            ruleStr.substring(5).trim()
        } else {
            ruleStr
        }
    }
}
