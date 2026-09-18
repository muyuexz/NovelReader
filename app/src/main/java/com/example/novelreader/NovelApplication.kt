package com.example.novelreader

import android.app.Application
import com.example.novelreader.analyzeRule.Network

/**
 * Application entry point.
 *
 * No DI framework is used on purpose: the analyzers/stores in the `analyzeRule`
 * package are plain lazy singletons, so the build needs no annotation processor.
 */
class NovelApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // 第 24 批：先装崩溃落盘，再干别的 —— 任何启动期异常都留痕。
        runCatching { CrashLogger.install(this) }
        // 第 37 批 D刀：把 App 私有缓存目录交给网络层（HTTP 磁盘缓存用）。
        runCatching { Network.initialize(this) }
    }
}