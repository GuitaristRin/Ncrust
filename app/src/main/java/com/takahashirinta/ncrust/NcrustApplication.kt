package com.takahashirinta.ncrust

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache

/**
 * 全局 Application，提供 Coil ImageLoader 的分级缓存配置。
 *
 * 分级依据设备总内存：
 * - ≤ 3GB：16MB 内存缓存（低端机，避免挤压音频缓冲）
 * - ≤ 6GB：24MB
 * - > 6GB：32MB
 *
 * 磁盘缓存统一 100MB，足够覆盖首页 + 几个详情页的封面。
 *
 * crossfade 默认关闭--Metro 不需要淡入，直接显示更利落。
 */
class NcrustApplication : Application(), ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val memoryBytes = memoryCacheSizeBytes(this)
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizeBytes(memoryBytes)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .maxSizeBytes(100L * 1024 * 1024)
                    .directory(cacheDir.resolve("image_cache"))
                    .build()
            }
            .build()
    }

    private fun memoryCacheSizeBytes(context: Context): Int {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        val totalMb = info.totalMem / (1024 * 1024)
        return when {
            totalMb <= 3072 -> 16 * 1024 * 1024
            totalMb <= 6144 -> 24 * 1024 * 1024
            else -> 32 * 1024 * 1024
        }
    }
}
