package com.example.novelreader.analyzeRule

/**
 * [RuleDataInterface] 的内存实现：小值进 map，超长值进 bigVariable 旁路。
 *
 * 用于「搜索/详情/目录/正文」四个阶段的临时变量容器（书源 JS 里的 `java.put/get`）。
 */
open class RuleDataStore : RuleDataInterface {

    override val variableMap = HashMap<String, String>()

    private val bigVariableMap = HashMap<String, String>()

    override fun putBigVariable(key: String, value: String?) {
        if (value == null) {
            bigVariableMap.remove(key)
        } else {
            bigVariableMap[key] = value
        }
    }

    override fun getBigVariable(key: String): String? = bigVariableMap[key]

    fun clear() {
        variableMap.clear()
        bigVariableMap.clear()
    }
}
