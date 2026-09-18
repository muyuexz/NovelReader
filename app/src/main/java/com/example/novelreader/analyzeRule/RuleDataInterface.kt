package com.example.novelreader.analyzeRule

/**
 * 规则数据接口。
 *
 * 同构复刻自 Legado `io.legado.app.model.analyzeRule.RuleDataInterface`：
 * 小于 10000 字符的变量进内存 map，超长变量（整章正文、大 JSON）走 bigVariable 旁路，
 * 避免正文把内存中的变量表撑爆。
 */
interface RuleDataInterface {

    val variableMap: HashMap<String, String>

    fun putVariable(key: String, value: String?): Boolean {
        val keyExist = variableMap.containsKey(key)
        return when {
            value == null -> {
                variableMap.remove(key)
                putBigVariable(key, null)
                keyExist
            }

            value.length < 10000 -> {
                putBigVariable(key, null)
                variableMap[key] = value
                true
            }

            else -> {
                variableMap.remove(key)
                putBigVariable(key, value)
                keyExist
            }
        }
    }

    fun putBigVariable(key: String, value: String?)

    fun getVariable(key: String): String {
        return variableMap[key] ?: getBigVariable(key) ?: ""
    }

    fun getBigVariable(key: String): String?
}
