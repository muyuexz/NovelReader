package com.example.novelreader.analyzeRule

/**
 * 规则引擎用到的模式常量（原 Legado 位于 `io.legado.app.constant.AppPattern`）。
 *
 * 注：上游 `AppPattern.kt` 的 GitHub 路径跨版本迁移，本轮两次联网取原文均失败
 * （raw 返回空、jsDelivr 报文件不存在）。故这里按 [AnalyzeUrlCore] 的调用语义复刻，
 * 行为对齐，后续拿到上游原文若有差异再回填。
 */
internal object AppPattern {

    /**
     * URL 选项前缀：`,` + 可选空白 + `{`。
     *
     * [AnalyzeUrlCore] 用法：
     * - `range.first` 之前为 URL 本体（含 baseUrl 截断）
     * - `range.last + 1` 起为选项 JSON（从 `{` 开始）
     *
     * 之所以只匹配前缀而非整段 `,{...}`，是因为上游 `substring(range.last + 1)`
     * 要把含 `{` 的完整 JSON 交给反序列化器。
     */
    val urlParamPattern = Regex("""\s*,\s*\{""")

    /** 响应 Content-Type 是否为 XML（上游整串 matches）。 */
    val xmlContentTypeRegex = Regex(""".*xml.*""", RegexOption.IGNORE_CASE)
}
