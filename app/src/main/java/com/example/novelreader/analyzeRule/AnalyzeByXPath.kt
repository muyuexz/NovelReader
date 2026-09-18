package com.example.novelreader.analyzeRule

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.Elements

/**
 * XPath 后端。同构复刻自 Legado `io.legado.app.model.analyzeRule.AnalyzeByXPath`。
 *
 * 等价替换：Legado 用 ksoup + `selectXpath`；本工程用 jsoup 1.17.2 自带的
 * `Element.selectXpath(...)`（jsoup 1.16.1+ 内置 W3C XPath 支持），API 同构。
 *
 * 三个对齐点：
 * 1. 解析前对残缺 HTML 做包裹（`</td>` → `<tr>`、`</tr>`/`</tbody>` → `<table>`），
 *    否则表格片段根本建不出 DOM；
 * 2. `<?xml` 开头走 `Parser.xmlParser()`，避免大小写被 HTML 规范拉平；
 * 3. 规则含 `/@attr` 时切成 元素路径 + 属性名 两段，属性值包成 TextNode 返回。
 */
class AnalyzeByXPath(doc: Any) {

    private var baseElement: Element = parse(doc)

    private fun parse(doc: Any): Element {
        return when (doc) {
            is Document -> doc
            is Element -> doc
            is Elements -> doc.first() ?: Jsoup.parse("")
            is Node -> Jsoup.parse(doc.toString())
            else -> strToElement(doc.toString())
        }
    }

    private fun strToElement(html: String): Element {
        var html1 = html
        if (html1.endsWith("</td>")) {
            html1 = "<tr>$html1</tr>"
        }
        if (html1.endsWith("</tr>") || html1.endsWith("</tbody>")) {
            html1 = "<table>$html1</table>"
        }
        runCatching {
            if (html1.trim().startsWith("<?xml", true)) {
                return Jsoup.parse(html1, "", Parser.xmlParser())
            }
        }
        return Jsoup.parse(html1)
    }

    private fun getResult(xPath: String): List<Node>? {
        return try {
            if (xPath.contains("/@")) {
                val parts = xPath.split("/@", limit = 2)
                val elementPath = parts[0].ifEmpty { "." }
                val attrName = parts[1]
                val elements = baseElement.selectXpath(elementPath, Element::class.java)
                elements.map { TextNode(it.attr(attrName)) as Node }
            } else {
                baseElement.selectXpath(xPath, Node::class.java)
            }
        } catch (_: Exception) {
            null
        }
    }

    fun getElements(xPath: String): List<Node>? {
        if (xPath.isEmpty()) return null

        val nodes = ArrayList<Node>()
        val ruleAnalyzes = RuleAnalyzer(xPath)
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")

        if (rules.size == 1) {
            return getResult(rules[0])
        } else {
            val results = ArrayList<List<Node>>()
            for (rl in rules) {
                val temp = getElements(rl)
                if (!temp.isNullOrEmpty()) {
                    results.add(temp)
                    if (ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.isNotEmpty()) {
                RuleCombiner.combineResults(results, ruleAnalyzes.elementsType, nodes)
            }
        }
        return nodes
    }

    fun getStringList(xPath: String): List<String> {
        val result = ArrayList<String>()
        val ruleAnalyzes = RuleAnalyzer(xPath)
        val rules = ruleAnalyzes.splitRule("&&", "||", "%%")

        if (rules.size == 1) {
            getResult(xPath)?.map {
                result.add(getNodeText(it))
            }
            return result
        } else {
            val results = ArrayList<List<String>>()
            for (rl in rules) {
                val temp = getStringList(rl)
                if (temp.isNotEmpty()) {
                    results.add(temp)
                    if (ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            if (results.isNotEmpty()) {
                RuleCombiner.combineResults(results, ruleAnalyzes.elementsType, result)
            }
        }
        return result
    }

    fun getString(rule: String): String? {
        val ruleAnalyzes = RuleAnalyzer(rule)
        val rules = ruleAnalyzes.splitRule("&&", "||")
        if (rules.size == 1) {
            getResult(rule)?.let {
                return it.joinToString("\n") { node -> getNodeText(node) }
            }
            return null
        } else {
            val textList = arrayListOf<String>()
            for (rl in rules) {
                val temp = getString(rl)
                if (!temp.isNullOrEmpty()) {
                    textList.add(temp)
                    if (ruleAnalyzes.elementsType == "||") {
                        break
                    }
                }
            }
            return textList.joinToString("\n")
        }
    }

    private fun getNodeText(node: Node): String {
        return when (node) {
            is Element -> node.text()
            is TextNode -> node.text()
            else -> node.toString()
        }
    }
}
