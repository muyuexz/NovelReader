package javax.lang.model

/**
 * 最小桩实现：补上 Android 运行时缺失的 `javax.lang.model.SourceVersion`。
 *
 * 背景：
 * Rhino 1.7.14 的 `org.mozilla.javascript.JavaMembers` 在静态初始化块里执行
 *
 * ```
 * STRICT_REFLECTIVE_ACCESS = SourceVersion.latestSupported().ordinal() > 8;
 * ```
 *
 * 用于判断运行环境是否 Java 9+。Android 没有 `javax.lang.model.SourceVersion`，
 * 于是 `JavaMembers.<clinit>` 抛 `NoClassDefFoundError: javax/lang/model/SourceVersion`，
 * 导致整个 LiveConnect（把 Java 对象暴露给 JS）永久不可用——书源 JS 里
 * `java.ajax(...)`、`source.getKey()` 全部失败。
 *
 * 这里返回 RELEASE_8（ordinal = 8，`8 > 8` 为 false），让 Rhino 走
 * Java 8 的 `AccessibleObject.isAccessible()/setAccessible()` 反射路径，
 * 而不是 Android 上并不存在的 Java 9+ 严格反射 API。
 */
enum class SourceVersion {
    RELEASE_0,
    RELEASE_1,
    RELEASE_2,
    RELEASE_3,
    RELEASE_4,
    RELEASE_5,
    RELEASE_6,
    RELEASE_7,
    RELEASE_8;

    companion object {
        /** 对齐 JDK 语义：返回当前支持的最高源码版本。此处刻意停在 RELEASE_8。 */
        @JvmStatic
        fun latestSupported(): SourceVersion = RELEASE_8
    }
}
