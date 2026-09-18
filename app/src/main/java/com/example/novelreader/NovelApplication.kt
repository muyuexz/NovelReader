package com.example.novelreader

import android.app.Application

/**
 * Application entry point.
 *
 * No DI framework is used on purpose: the analyzers/stores in the `analyzeRule`
 * package are plain lazy singletons, so the build needs no annotation processor.
 */
class NovelApplication : Application()