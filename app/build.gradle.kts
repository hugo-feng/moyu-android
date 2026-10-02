plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.moyu.reader"

    /**
     * 编译与目标版本对齐 Android 16（API 36）。
     *
     * 理由：目标机型是小米 14 的澎湃 OS 3，其底座就是 Android 16。
     * 只编到 35 虽然能跑，但等于放弃了针对该版本的适配声明 ——
     * Android 16 在边到边、权限、后台限制上都有行为变更，
     * 明确 target 36 才能让系统按新规则对待本应用。
     */
    compileSdk = 36

    defaultConfig {
        applicationId = "com.moyu.reader"
        minSdk = 26          // Android 8.0：覆盖 99%+ 在用设备，且能用 java.time 等现代 API
        targetSdk = 36
        versionCode = 3
        versionName = "1.0.2"

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

/**
 * 版本化产物命名。
 *
 * 让 AGP 直接产出 `moyu-reader-<版本名>.apk`，而不是千篇一律的 `app-release.apk`。
 *
 * 为什么要在**构建层**做，而不是每次构建完手工改名：
 * 手工改名依赖「我记得改」，忘一次就会把新版本覆盖到旧版本上 ——
 * 而发布包一旦被覆盖，就没法再验证「用户装的是哪一版」。
 * 放进构建配置后，文件名自带版本号，覆盖在物理上不可能发生。
 */
androidComponents {
    onVariants { variant ->
        variant.outputs.forEach { output ->
            val versionName = variant.outputs
                .firstNotNullOfOrNull { it.versionName.orNull }
                ?: project.version.toString()
            (output as? com.android.build.api.variant.impl.VariantOutputImpl)?.outputFileName?.set(
                "moyu-reader-$versionName.apk",
            )
        }
    }
}

/**
 * 把已构建的安装包归档到统一的发布目录 `releases/v<版本名>/apk/`。
 *
 * 每个版本一个**独立子目录**，因此历史版本永远不会被覆盖，
 * 也便于逐个核对「这一版装出来到底是什么样」。
 *
 * 关于 `dependsOn(packageRelease)`：Gradle 的配置校验不允许一个任务
 * 「读了别的任务的输出目录却不声明依赖」——它会直接让构建失败。
 * 这里显式声明，顺序就由 Gradle 保证，不依赖调用者写对任务顺序。
 *
 * 用法：./gradlew :app:release
 */
val archiveRelease by tasks.registering(Copy::class) {
    group = "distribution"
    description = "把发布安装包归档到 releases/v<版本>/（不覆盖历史版本）"

    val versionName = android.defaultConfig.versionName ?: "0.0.0"
    val releasesRoot = rootProject.layout.projectDirectory.dir("releases").dir("v$versionName")

    dependsOn("packageRelease")
    from(layout.buildDirectory.dir("outputs/apk/release")) {
        include("*.apk")
    }
    into(releasesRoot.dir("apk"))
    // 不改名：源文件名已由上面的 outputFileName 带上版本号

    /**
     * 归档前先清空该版本的目录。
     *
     * 必须做：AGP 不会删除**上一次构建留下的、文件名不同的**产物。
     * 例如先构建过 1.0.0 又改成 1.0.1 再构建 `build/outputs/apk/release` 里
     * 会同时存在两个文件，Copy 会把它们一起归档 —— 发布目录里就混进了旧包，
     * 而发布目录的作用恰恰是「这里的东西就是这一版」。实测踩过一次。
     */
    doFirst {
        val dest = releasesRoot.dir("apk").asFile
        if (dest.exists()) dest.deleteRecursively()
        dest.mkdirs()
    }
}

/** 构建并归档发布包。 */
tasks.register("release") {
    group = "distribution"
    description = "构建发布安装包并归档到 releases/（版本化，不覆盖历史）"
    dependsOn(archiveRelease)
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
