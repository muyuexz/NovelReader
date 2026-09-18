// Top-level build file. Plugin versions declared here with `apply false`
// so that sub-projects can apply them without version conflicts.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("com.android.library") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
    // 规则引擎的 @put/@Json 解析走 Json.decodeFromString<T>（reified），需要序列化编译器插件
    id("org.jetbrains.kotlin.plugin.serialization") version "1.9.24" apply false
}
