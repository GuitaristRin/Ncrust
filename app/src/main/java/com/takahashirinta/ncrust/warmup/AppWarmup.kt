package com.takahashirinta.ncrust.warmup

import android.content.Context
import coil.Coil
import coil.request.ImageRequest
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.network.PlaylistApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 冷启动一次性预热：Splash 遮挡期间跑完所有可提前的 IO，等首页真的进入时数据+封面都在本地。
 *
 * 目的：把"进入 Home 后 loader + 图片解码 + 首次 Composition"堆在一起的爆发抹平——
 * 让 splash 期间的 CPU/网络/磁盘齐上，用户看到的第一帧 Home 是完成态。
 *
 * ready 由 splash 订阅：为 true 时才允许淡出（配合 splash 侧的最短驻留时间）。
 * 总兜底 [TIMEOUT_MS]：无论网络多慢，超时后强制置 ready，防止启动被卡死。
 */
object AppWarmup {

    private val _ready = MutableStateFlow(false)
    val ready: StateFlow<Boolean> = _ready.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var started = false

    private const val TIMEOUT_MS = 3_000L
    // Home 里 tile 显示尺寸约 160dp，取 320px 覆盖 xxhdpi 单张封面
    private const val COVER_PX = 320
    // 每类内容预取多少张封面——盖住首屏可见部分即可
    private const val PREFETCH_PER_SECTION = 6

    // 全部 SharedPreferences 文件名——IO 线程一次性触碰，让主线程 getSharedPreferences 命中缓存。
    // 与各 Manager 里的 PREFS_NAME 常量保持同步。
    private val PREFS_FILES = arrayOf(
        "ncrust_prefs",           // CookieManager
        "ncrust_settings",        // ThemeManager / LanguageManager / PlayerViewModel
        "ncrust_library",         // LibraryManager
        "ncrust_playback_state",  // PlaybackStateManager
        "search_history"          // SearchHistoryManager
    )

    fun start(context: Context) {
        if (started) return
        started = true
        val app = context.applicationContext

        // 阶段零：立即触碰所有 SharedPreferences 文件，把 XML 解析从主线程拉走。
        // SharedPreferences 按 name 单例，首次 getSharedPreferences 会同步读磁盘+解析 XML
        // （eMMC 上单文件 30~100ms）。这里 IO 线程先跑一遍，后续主线程 get* 就都是内存命中。
        // 独立 launch 不进 withTimeout：这些操作本身很快，即使个别慢也不该阻塞 ready 判定。
        scope.launch {
            for (name in PREFS_FILES) {
                runCatching { app.getSharedPreferences(name, Context.MODE_PRIVATE) }
            }
        }

        scope.launch {
            withTimeoutOrNull(TIMEOUT_MS) {
                // 阶段一：三条 Home 请求并发写入 ContentCache
                coroutineScope {
                    val dailyDeferred = async {
                        runCatching { PlaylistApi.getDailyRecommendSongs() }.getOrNull()
                    }
                    val plsDeferred = async {
                        runCatching { PlaylistApi.getRecommendPlaylists() }.getOrNull()
                    }
                    val topDeferred = async {
                        runCatching { PlaylistApi.getTopSongs(limit = 10, offset = 0) }.getOrNull()
                    }
                    dailyDeferred.await()?.let { ContentCache.homeDailySongs = it }
                    plsDeferred.await()?.let { ContentCache.homeRecommendPlaylists = it }
                    topDeferred.await()?.let { ContentCache.homeNewSongs = it }
                }

                // 阶段二：封面预取到 Coil 全局 ImageLoader 的内存+磁盘缓存
                // AsyncImage 后续读同一 loader，直接命中
                val loader = Coil.imageLoader(app)
                val urls = buildList {
                    ContentCache.homeDailySongs?.take(PREFETCH_PER_SECTION)?.forEach {
                        it.album?.picUrl?.takeIf(String::isNotBlank)?.let(::add)
                    }
                    ContentCache.homeRecommendPlaylists?.take(PREFETCH_PER_SECTION)?.forEach {
                        it.coverUrl.takeIf(String::isNotBlank)?.let(::add)
                    }
                    ContentCache.homeNewSongs?.take(PREFETCH_PER_SECTION)?.forEach {
                        it.album?.picUrl?.takeIf(String::isNotBlank)?.let(::add)
                    }
                }.distinct()

                coroutineScope {
                    urls.map { url ->
                        async {
                            runCatching {
                                loader.execute(
                                    ImageRequest.Builder(app)
                                        .data(url)
                                        .size(COVER_PX, COVER_PX)
                                        .build()
                                )
                            }
                        }
                    }.awaitAll()
                }
            }
            _ready.value = true
        }
    }
}
