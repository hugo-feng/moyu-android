# ============================================================
# 墨阅 · R8/ProGuard 规则
# ============================================================

# —— 保留行号与源文件信息，便于线上崩溃回溯（R8 会重映射）——
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepattributes RuntimeVisibleAnnotations,RuntimeVisibleParameterAnnotations

# —— kotlinx.serialization ——
# 序列化依赖编译期生成的 serializer，必须保留 @Serializable 类的伴生对象
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**
-keepclassmembers class **$$serializer { *; }
-keepclasseswithmembers class ** {
    static **$* *;
}
-keepclasseswithmembers class kotlinx.serialization.** {
    *;
}
-keep,includedescriptorclasses class com.moyu.reader.**$$serializer { *; }
-keepclassmembers class com.moyu.reader.** {
    *** Companion;
}
-keepclasseswithmembers class com.moyu.reader.** {
    kotlinx.serialization.KSerializer serializer(...);
}

# —— Room 生成的实现类通过反射实例化，保留入口 ——
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# —— jsoup 的可选依赖（R8 会对其报缺失警告，实际不用的路径）——
-dontwarn org.jsoup.**
-keep class org.jsoup.** { *; }

# —— 协程 ——
-dontwarn kotlinx.coroutines.**
-keepclassmembers class kotlinx.coroutines.** { volatile <fields>; }

# —— Compose 运行时不需要额外规则（AGP 已内置），但保留预览注解 ——
-dontwarn androidx.compose.**

# —— 保留 TTS 相关的系统服务图标类（部分 ROM 需要）——
-dontwarn android.speech.tts.**

# —— 移除日志（release 包不应输出调试日志）——
-assumenosideeffects class android.util.Log {
    public static *** v(...);
    public static *** d(...);
}
