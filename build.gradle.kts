// 顶层构建配置：集中声明插件版本，子模块只 apply 不写版本。
//
// 版本选择依据（2024-11 时点，刻意选相互兼容、已在大量项目中验证过的组合）：
//   AGP 8.7.2  ←→  Gradle 8.9   ←→  JDK 17
//   Kotlin 2.0.21（Compose 编译器已随 Kotlin 2.0 内置，不再单独配 composeOptions）
//   KSP 2.0.21-1.0.28（与 Kotlin 版本严格配对，用于 Room 注解处理）
plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.21" apply false
    id("com.google.devtools.ksp") version "2.0.21-1.0.28" apply false
}
