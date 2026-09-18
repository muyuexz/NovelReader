# --- General Android optimizations ---
-keepattributes *Annotation*, InnerClasses, Signature, EnclosingMethod, SourceFile, LineNumberTable

# Keep native method names.
-keepclasseswithmembernames class * {
    native <methods>;
}

# Keep custom View constructors (used by XML inflation / viewBinding).
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet);
}
-keepclasseswithmembers class * {
    public <init>(android.content.Context, android.util.AttributeSet, int);
}

# Keep Parcelable implementations.
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# Keep enum values() / values methods.
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- kotlinx.serialization ---
-keepattributes RuntimeVisibleAnnotations, AnnotationDefault
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.example.novelreader.**$$serializer { *; }
-keepclassmembers class com.example.novelreader.** {
    *** Companion;
}

# --- Room ---
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# --- OkHttp / Okio ---
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**

# --- Retrofit ---
-keepattributes Exceptions
-keep,allowobfuscation,allowshrinking interface retrofit2.Call
-keep,allowobfuscation,allowshrinking class retrofit2.Response
-dontwarn retrofit2.**

# --- Jsoup ---
-keep class org.jsoup.** { *; }
-dontwarn org.jsoup.**

# --- Hilt / Dagger ---
-keep,allowobfuscation @interface dagger.hilt.android.HiltAndroidApp
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper
-dontwarn dagger.hilt.**

# --- Coil ---
-dontwarn coil.**

# --- Kotlin metadata ---
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**

# Keep model data classes used for JSON reflection (book source rules).
-keep class com.example.novelreader.data.local.entity.** { *; }
-keep class com.example.novelreader.domain.model.** { *; }

# --- 规则引擎：JS 侧通过 Rhino 反射按名字调用（java.ajax/getStrResponse/put/get...），
#     这些类与方法一旦被混淆，书源 JS 就会找不到成员，规则全部失效。---
-keep class com.example.novelreader.analyzeRule.** { *; }

# --- Rhino（规则引擎 JS 后端）---
-keep class org.mozilla.javascript.** { *; }
-dontwarn org.mozilla.javascript.**
-dontwarn org.mozilla.classfile.**

# --- Jayway JsonPath（规则引擎 JsonPath 后端）---
-keep class com.jayway.jsonpath.** { *; }
-dontwarn com.jayway.jsonpath.**
-dontwarn net.minidev.json.**
-dontwarn org.slf4j.**
