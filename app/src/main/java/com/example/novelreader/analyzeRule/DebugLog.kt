package com.example.novelreader.analyzeRule

import android.util.Log

/**
 * 书源调试日志出口（对应 Legado `SourceDebugLoggers`）。
 *
 * 规则引擎本身不关心日志走向；宿主（App / 调试面板）可通过 [impl] 注入出口，
 * 未注入时落到 Logcat 的 `NovelReader.Rule` tag。
 */
internal object SourceDebugLoggers {

    const val TAG = "NovelReader.Rule"

    var impl: ((String) -> Unit)? = null

    fun log(msg: String) {
        impl?.invoke(msg) ?: Log.d(TAG, msg)
    }
}
