package com.moyu.reader

import android.app.Application
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.moyu.reader.data.AppContainer

/**
 * 应用入口。
 *
 * 依赖注入方案：**手写 AppContainer**（服务定位器），不引入 Hilt/Koin。
 * 理由是取舍后的结论：
 *   - 本应用的依赖图非常浅（数据库 + 几个仓库 + 设置），Hilt 带来的注解处理开销、
 *     构建时间与版本耦合（KSP/AGP/Hilt 三者版本必须互相匹配）远大于收益；
 *   - 手写容器在阅读器这类对启动速度敏感的应用里更可控，
 *     不会因为注解生成的初始化而拖慢冷启动。
 */
class MoyuApplication : Application(), ImageLoaderFactory {

    /** 全局依赖容器。在 onCreate 里初始化，供各 ViewModel 取用。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }

    /**
     * Coil 的图片加载器配置。
     *
     * 书本封面是本应用唯一的图片场景，且尺寸固定很小，因此：
     *   - 限制内存缓存为可用内存的 15%（默认 25% 对阅读应用过于激进）；
     *   - 磁盘缓存只给 32MB（封面压缩过，足够放上千本）。
     */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.15)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("cover_cache"))
                    .maxSizeBytes(32L * 1024 * 1024)
                    .build()
            }
            .crossfade(180)
            .build()
}
