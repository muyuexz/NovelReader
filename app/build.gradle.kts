plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.example.novelreader"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.novelreader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        viewBinding = true
        buildConfig = true
    }

    composeOptions {
        // Compatible with Kotlin 1.9.24
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
            excludes += "/META-INF/NOTICE*"
        }
    }
}

dependencies {
    // --- Core / AndroidX ---
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")

    // --- Compose (BOM keeps all compose artifact versions aligned) ---
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    // --- Networking ---
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")

    // --- HTML parsing ---
    implementation("org.jsoup:jsoup:1.17.2")

    // --- 规则引擎 JS 后端（等价替代 Legado 的 QuickJS）---
    implementation("org.mozilla:rhino:1.7.14")

    // --- 规则引擎 JsonPath 后端（等价替代 Legado 的 RJPath）---
    implementation("com.jayway.jsonpath:json-path:2.9.0")

    // --- JSON serialization (book source rules) ---
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // --- Coroutines ---
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // --- 图片加载（搜索结果 / 详情页封面）---
    // 2.6.0 对齐 Compose 1.6.x（BOM 2024.06.00），不能用 3.x（要 Compose 1.7+）。
    implementation("io.coil-kt:coil-compose:2.6.0")

    // --- Tests ---
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
}


// Offline-safe: kotlin-stdlib-jdk7/jdk8 are empty forwarder stubs past Kotlin 1.8.
// Exclude them so resolution never needs network / extra cache entries.
configurations.all {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
    // Offline-safe: only okio-jvm 3.8.0 has a cached jar locally.
    resolutionStrategy.force(
        "com.squareup.okio:okio:3.8.0",
        "com.squareup.okio:okio-jvm:3.8.0"
    )
}

// NOTE: no annotation processing (KSP / Hilt / Room) is used on purpose.
// Book sources are stored as plain JSON and the analyzers are lazy singletons,
// which keeps the build light enough to run on modest hardware.
