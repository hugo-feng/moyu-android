plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.moyu.reader"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.moyu.reader"
        minSdk = 26          // Android 8.0：覆盖 99%+ 在用设备，且能用 java.time 等现代 API
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // 资源裁剪：只保留中文，显著减小 APK
        resourceConfigurations += listOf("zh", "en")
    }

    // 为「调试安装包」与「可分发安装包」分别签名。
    // 发布包用仓库内的自签名 keystore（开源项目常见做法，便于他人直接构建出可安装的 APK）。
    // 注意：这是演示用密钥，上架应用商店前必须换成你自己的正式密钥。
    signingConfigs {
        create("releaseLocal") {
            storeFile = file("keystore/moyu-release.jks")
            storePassword = "moyureader"
            keyAlias = "moyu"
            keyPassword = "moyureader"
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            isMinifyEnabled = false
            isDebuggable = true
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = signingConfigs.getByName("releaseLocal")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/LICENSE*",
                "/META-INF/NOTICE*",
                "DebugProbesKt.bin",
                "kotlin-tooling-metadata.json",
            )
        }
    }

    // Room 的 schema 导出目录：把 schema 纳入版本控制，便于后续写数据库迁移
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
        arg("room.incremental", "true")
        arg("room.generateKotlin", "true")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

dependencies {
    // —— 基础 ——
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")

    // —— Compose（用 BOM 统一版本，避免各库版本互相打架）——
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // —— 导航 ——
    implementation("androidx.navigation:navigation-compose:2.8.3")

    // —— 数据库（Room）+ KSP 注解处理 ——
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // —— 偏好设置（DataStore，替代已废弃的 SharedPreferences）——
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // —— 序列化 ——
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")

    // —— 图片加载（封面）——
    implementation("io.coil-kt:coil-compose:2.7.0")

    // 注意：这里**没有** media3 / exoplayer。
    // 朗读用的是系统 TextToSpeech（见 reader/TtsController.kt），它走系统服务，不需要媒体库；
    // 而 media3 会往清单里合并进 ACCESS_NETWORK_STATE 权限 —— 一个完全离线的阅读器
    // 不该在权限列表里出现任何与网络有关的东西，那是用户第一眼看的地方。

    // —— 文档访问（SAF 选文件夹 / 选文件）——
    implementation("androidx.documentfile:documentfile:1.0.1")

    // —— HTML 解析（EPUB 章节正文提取）——
    // 不自己写标签剥离：HTML 解析用正则必然在某些文档上出错，
    // jsoup 是 Java 生态里事实标准的 HTML 解析库。
    // 只用它的解析能力，不碰它的网络能力，因此同样不需要任何网络权限。
    implementation("org.jsoup:jsoup:1.18.1")

    // —— 测试 ——
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Robolectric：让 Room / Android framework 相关测试直接跑在 JVM 上（无需模拟器）
    testImplementation("org.robolectric:robolectric:4.13")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation(platform("androidx.compose:compose-bom:2024.10.01"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
