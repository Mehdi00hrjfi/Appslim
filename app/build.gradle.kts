plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.appslim.analyzer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.appslim.analyzer"
        minSdk = 29            // Android 10 起（报告 §14：适配 Android 10-16）
        targetSdk = 34
        versionCode = 24
        versionName = "0.5.10"
    }

    signingConfigs {
        create("release") {
            storeFile = file("../keystore.jks")
            storePassword = "android"
            keyAlias = "appslim"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            // 开启 R8：仅做 Tree-Shaking 移除未用图标/库（material-icons-extended 占 30MB+），
            // 配合 -dontobfuscate 不改名，反射按字符串名调用（Xposed/Shizuku）仍然安全。
            // debuggable=false 是滚动流畅的关键（避免 Compose 在 debuggable 下的高 CPU 开销）。
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    // ---- Jetpack Compose（报告 §4 推荐技术栈） ----
    val composeBom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(composeBom)
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.5")
    implementation("androidx.lifecycle:lifecycle-process:2.8.5")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // ---- Room 数据库（采集记录持久化） ----
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // ---- Kotlin 协程 ----
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // ---- LSPosed Hook API（仅编译期，由框架在运行时提供） ----
    compileOnly("de.robv.android.xposed:api:82")

    // ---- Shizuku（非 Root 模式，报告 §4） ----
    implementation("dev.rikka.shizuku:api:13.1.5")
    implementation("dev.rikka.shizuku:provider:13.1.5")
}
