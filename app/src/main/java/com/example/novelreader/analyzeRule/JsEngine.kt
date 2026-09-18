package com.example.novelreader.analyzeRule

import org.mozilla.javascript.Context
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeJavaObject
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.mozilla.javascript.Undefined
import org.mozilla.javascript.Wrapper

/**
 * 轻量 JS 执行器（Rhino）。
 *
 * Legado 用 QuickJS；JVM/Android 上语义最接近的等价替代是 Rhino。
 * Rhino 的 `evaluateString` 本身返回最后一条表达式的完成值，
 * 因此不需要 QuickJS 那套 IIFE + eval 包装。
 *
 * 行为对齐点：
 * - 整数型 Double 归一为 Long（QuickJS 返回 Integer，避免 "12.0" 这种脏输出）；
 * - `Undefined` / `NOT_FOUND` 统一归为 null；
 * - Java 对象经 `Context.javaToJS` 包装，JS 侧可直接 `java.ajax(...)`。
 */
internal object JsEngine {

    fun newScope(): Scriptable {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            ctx.languageVersion = Context.VERSION_ES6
            return ctx.initStandardObjects()
        } finally {
            Context.exit()
        }
    }

    /**
     * 建立子作用域：以 [parent] 为原型链末端。
     *
     * 用于「每次 evalJS 用独立子 scope」——书源 JS 顶层的 `let/const/var`
     * 落在子 scope，不会污染共享的 [parent]（避免重复执行报
     * `redeclaration of 'xxx'`），同时仍能沿原型链访问 [parent] 上的 jsLib 自由函数。
     */
    fun newChildScope(parent: Scriptable?): Scriptable {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            ctx.languageVersion = Context.VERSION_ES6
            return if (parent == null) {
                ctx.initStandardObjects()
            } else {
                ctx.newObject(parent)
            }
        } finally {
            Context.exit()
        }
    }

    fun putBinding(scope: Scriptable, name: String, value: Any?) {
        // 注意：Context.javaToJS 内部会调用 Context.getCurrentContext()，
        // 若当前线程未 Context.enter() 会直接抛
        // IllegalStateException("No Context associated with current Thread")。
        // 因此这里必须自己进入/退出 Context（Rhino 的 enter 可重入，嵌套安全）。
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            ctx.languageVersion = Context.VERSION_ES6
            val jsValue: Any? = when (value) {
                null -> null
                is String, is Number, is Boolean -> value
                is Scriptable -> value
                else -> Context.javaToJS(value, scope)
            }
            ScriptableObject.putProperty(scope, name, jsValue)
        } finally {
            Context.exit()
        }
    }

    fun eval(scope: Scriptable, jsStr: String): Any? {
        val ctx = Context.enter()
        try {
            ctx.optimizationLevel = -1
            ctx.languageVersion = Context.VERSION_ES6
            val raw = ctx.evaluateString(scope, jsStr, "rule", 1, null)
            return toKotlin(raw)
        } finally {
            Context.exit()
        }
    }

    /** JS 返回的对象节点（对齐 Legado `JsEngines.asJsObject`）。 */
    fun asJsObject(value: Any?): Scriptable? {
        return when {
            value is NativeObject -> value
            value is Scriptable && value !is NativeArray -> value
            else -> null
        }
    }

    fun getProperty(scope: Scriptable, name: String): Any? {
        val v = ScriptableObject.getProperty(scope, name)
        if (v === Scriptable.NOT_FOUND || v === Undefined.instance) return null
        return toKotlin(v)
    }

    fun toKotlin(value: Any?): Any? {
        return when (value) {
            null -> null
            Undefined.instance -> null
            is NativeJavaObject -> toKotlin(value.unwrap())
            is Wrapper -> toKotlin(value.unwrap())
            is CharSequence -> value.toString()
            is Double -> if (value % 1.0 == 0.0 && !value.isInfinite()) value.toLong() else value
            is Int, is Long, is Boolean -> value
            else -> value
        }
    }
}