package com.example.novelreader.analyzeRule

/**
 * 书源基类（对应 Legado `BaseSource` 在规则引擎侧的最小契约）。
 *
 * 规则引擎只依赖 `put/get/putVariable/getVariable` 与 `enableDangerousApi`，
 * 不关心书源实体还有多少字段，因此这里只抽规则所需的最小面。
 */
interface BaseSource : RuleDataInterface {

    /** 是否允许 JS 调用危险 API（ajax、文件、Intent 等）。默认关闭。 */
    val enableDangerousApi: Boolean get() = false

    /** 书源 URL，规则里 `{{sourceUrl}}` 之类会用到。 */
    val bookSourceUrl: String get() = ""

    fun put(key: String, value: String): String {
        putVariable(key, value)
        return value
    }

    fun get(key: String): String = getVariable(key)

    /**
     * 书源级请求头（对应 Legado `source.getHeaderMap(hasLoginHeader, evalJS)`）。
     *
     * `header` 规则本身要先经规则引擎求值（可能是 `<js>` 或 JSON 字符串），
     * 因此这里把求值器当参数传进来，由实现方决定怎么算。
     * 规则引擎侧默认不注入任何请求头。
     */
    fun getHeaderMap(
        hasLoginHeader: Boolean,
        evalJS: (String) -> Any?
    ): Map<String, String> = emptyMap()

    /**
     * 是否启用 CookieJar（对应 Legado `source.enabledCookieJar`）。
     *
     * 可空：未显式配置时按 false 处理。书源实体持有的是 JSON 里的原值，
     * 需要 CookieJar 的场景在 `AnalyzeUrlCore` 里以 `== true` 判定。
     */
    val enabledCookieJar: Boolean? get() = null
}
