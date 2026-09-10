package com.takahashirinta.ncrust.ui.viewmodel

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.lyric.LrcParser
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.player.PlaybackService
import com.takahashirinta.ncrust.player.PlaybackStateManager
import com.takahashirinta.ncrust.player.PlayReporter
import com.takahashirinta.ncrust.player.SongUrlFetcher
import com.takahashirinta.ncrust.player.SongUrlResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val isPlaying = MutableStateFlow(false)
    val currentPosition = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val progress = MutableStateFlow(0f)
    val lyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    // 当前 lyrics 内容所属的歌曲 id：-1 = 尚无(切歌后旧歌词只是渐隐过渡, 不算就绪)。
    // 让 UI 的 "lyricsReady" 判断基于"歌词属于当前歌", 而非"有没有歌词数组"——
    // 否则切歌后旧歌词残留会被误判为就绪, 把无歌词的新歌切回歌词视图显示旧歌词。
    val lyricsSongId = MutableStateFlow(-1L)
    // 外文歌词的译文(tlyric),Spotify 式渲染在原句下方。
    val translatedLyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    // 歌词是否仍在加载中(网络往返未返回)。UI 用它区分「真的没歌词」与「还没加载出来」:
    // 加载中或成功为空 → 全屏默认大封面、歌词按钮置灰;加载出非空 → 自动切回歌词视图。
    val lyricsLoading = MutableStateFlow(false)
    // 设置页开关:是否显示歌词翻译。默认开——外文歌直接看到双语,中文歌 tlyric 为空不受影响。
    val showLyricsTranslation = MutableStateFlow(true)

    val isBuffering = MutableStateFlow(false)
    // Emits true when the current song enters the preload window (last 20 s).
    val needsPreload = MutableStateFlow(false)

    val currentSongId = MutableStateFlow<Long?>(null)
    val currentSongName = MutableStateFlow<String?>(null)
    val currentSongArtist = MutableStateFlow<String?>(null)
    val currentSongArtwork = MutableStateFlow<String?>(null)

    private var onSongEndedCallback: (() -> Unit)? = null
    private var onSongPreviousCallback: (() -> Unit)? = null
    private var onSongTransitionedCallback: (() -> Unit)? = null
    private var onUnplayableCallback: (() -> Unit)? = null
    private var playJob: Job? = null
    private var preloadJob: Job? = null

    // 防止同一首歌重复上报播放行为。
    private var lastReportedSongId = -1L

    // Incremented on every explicit playSong call; lets preloadNextSong detect staleness.
    private var songPlayVersion = 0

    val currentQualityIndex = MutableStateFlow(3)
    private val qualityApiLevels = listOf("standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "dolby")

    // 播放出错(如设备解码不了 24-bit FLAC / 高采样率)时自动降档重试的阶梯。
    // 每出错一次降一档,到 standard 仍失败才跳歌:保证「有声音,或跳歌」,绝不静默卡住。
    private val qualityRetryLadder = listOf("dolby", "jyeffect", "hires", "lossless", "exhigh", "higher", "standard")
    // 上一次交给 PlaybackService 的 URL 的实际档位(fetch 内部可能已降级)。
    private var lastPlayedLevel = ""
    // 已处理过的出错点 (songId@level),配合时间窗防止同一错误反复触发重试。
    private var lastErrorKey = ""
    private var lastErrorHandledAt = 0L

    private var gaplessEnabled = false
    // 进入当前歌最后 60 秒即触发下一首预载: 取链(网络往返)+ ExoPlayer 准备/buffer
    // 需要充足时间, 20s 碰到慢网络/冷缓存会来不及, 无缝退化成硬切。
    private val PRELOAD_THRESHOLD_MS = 60_000L

    // Metadata for the in-flight preload; applied when ExoPlayer auto-transitions.
    // All reads/writes happen on the main thread.
    private var preloadedSongId = -1L
    private var preloadedTitle = ""
    private var preloadedArtist = ""
    private var preloadedArtwork = ""
    private var preloadedActualLevel = ""
    // Cached stream URL from the last completed preload; used by playSong fast-path to skip fetch.
    private var preloadedUrl = ""
    // Song ID most recently requested by playSong; lets a concurrent preload detect a same-song race.
    private var latestPlaySongId = -1L

    private data class PreloadCacheEntry(
        val url: String,
        val actualLevel: String,
        // 取链时用的请求档位:播放失败降档重试时,只有档位一致才允许命中缓存,
        // 避免把上一档(可能播不出声)的 URL 原样放回播放器。
        val requestedLevel: String,
        val timestamp: Long = System.currentTimeMillis()
    )
    private val preloadCache = mutableMapOf<Long, PreloadCacheEntry>()
    private val CACHE_TTL_MS = 5 * 60 * 1_000L
    // Prevents duplicate preload launches for the same song while one is in flight.
    private var currentlyPreloadingSongId = -1L

    init {
        refreshGaplessSetting()
        // 从设置读歌词翻译开关(默认开);设置页切换时经 setLyricsTranslation 实时生效。
        showLyricsTranslation.value = getApplication<Application>()
            .getSharedPreferences("ncrust_settings", 0)
            .getBoolean("lyrics_translation", true)

        PlaybackService.onProgressUpdate = { pos, dur ->
            currentPosition.value = pos
            duration.value = dur
            progress.value = if (dur > 0) pos.toFloat() / dur.toFloat() else 0f

            // 播放行为上报: 进度达 80% 视为"听完",每首歌只上报一次。
            val sid = currentSongId.value ?: -1L
            if (sid > 0 && sid != lastReportedSongId && PlayReporter.reachedCompletion(pos, dur)) {
                lastReportedSongId = sid
                PlayReporter.reportPlay(sid, pos, dur, end = "playend", isWifi = isOnWifi())
            }

            // Signal the preload window once per song (guarded by !needsPreload.value).
            if (gaplessEnabled && dur > 0 && pos > 1_000L && !needsPreload.value) {
                val remaining = dur - pos
                if (remaining in 1L..PRELOAD_THRESHOLD_MS) {
                    needsPreload.value = true
                }
            }
        }
        PlaybackService.onPlaybackEnded = {
            // 自然播放结束时,若尚未上报则补一条 playend。
            val sid = currentSongId.value ?: -1L
            if (sid > 0 && sid != lastReportedSongId) {
                lastReportedSongId = sid
                PlayReporter.reportPlay(sid, duration.value, duration.value, end = "playend", isWifi = isOnWifi())
            }
            onSongEndedCallback?.invoke()
        }
        PlaybackService.onPlaybackPrevious = { onSongPreviousCallback?.invoke() }
        PlaybackService.onIsPlayingChanged = { playing -> isPlaying.value = playing }
        PlaybackService.onBufferingChanged = { buffering -> isBuffering.value = buffering }
        // ExoPlayer 主线程回调。播放失败 → 降档重试,而不是无声地停在 IDLE。
        PlaybackService.onPlaybackError = { sid -> handlePlaybackError(sid) }

        // Called on the main thread by ExoPlayer's onMediaItemTransition (AUTO reason).
        PlaybackService.onSongTransitioned = {
            if (preloadedSongId > 0) {
                resetLyricsForNewSong()
                currentSongId.value = preloadedSongId
                currentSongName.value = preloadedTitle
                currentSongArtist.value = preloadedArtist
                currentSongArtwork.value = preloadedArtwork
                val idx = qualityApiLevels.indexOf(preloadedActualLevel).coerceAtLeast(0)
                currentQualityIndex.value = idx
                PlaybackStateManager.saveState(
                    getApplication(), preloadedSongId,
                    preloadedTitle, preloadedArtist, preloadedArtwork, true
                )
                viewModelScope.launch { fetchLyrics(preloadedSongId) }
                preloadedSongId = -1L
                needsPreload.value = false
            }
            onSongTransitionedCallback?.invoke()
        }

        val savedState = PlaybackStateManager.getState(getApplication())
        if (savedState != null) {
            resetLyricsForNewSong()
            currentSongId.value = savedState.songId
            currentSongName.value = savedState.songName
            currentSongArtist.value = savedState.songArtist
            currentSongArtwork.value = savedState.songArtwork

            // Activity 冷重建但前台 Service 还活着的场景：不能盲写 isPlaying=false，
            // 否则 UI 显示暂停但音频还在响，用户要点多次按钮才能让状态与音频对齐。
            // 直接从 live service 拉真值，同时把 duration/position 一并同步——
            // 否则 500ms 心跳到来前 duration=0，togglePlayPause 会误走全量 playSong 分支导致重取 URL。
            val svc = PlaybackService.instance
            if (svc != null) {
                runCatching {
                    val p = svc.player
                    isPlaying.value = p.isPlaying
                    val livePos = p.currentPosition
                    val liveDur = p.duration
                    if (liveDur > 0) {
                        currentPosition.value = livePos
                        duration.value = liveDur
                        progress.value = livePos.toFloat() / liveDur.toFloat()
                    }
                }
            } else {
                isPlaying.value = false
            }

            if (savedState.songId > 0) {
                viewModelScope.launch { fetchLyrics(savedState.songId) }
            }
        }
    }

    fun setOnSongEndedCallback(callback: () -> Unit) { onSongEndedCallback = callback }
    fun setOnSongPreviousCallback(callback: () -> Unit) { onSongPreviousCallback = callback }
    fun setOnSongTransitionedCallback(callback: () -> Unit) { onSongTransitionedCallback = callback }
    fun setOnUnplayableCallback(callback: () -> Unit) { onUnplayableCallback = callback }

    /**
     * 切歌时置加载态, 但**不清空旧歌词内容**。
     *
     * 保留旧歌词是为了让 Crossfade(以 songId 为 key)能对"上一首歌词→新歌词"做平滑渐隐:
     * 如果这里立即清空, 切歌瞬间歌词面板就空了, 渐隐无从谈起。新歌词由 fetchLyrics
     * 成功覆盖; 若确无歌词, 由 PlayerCard 的 3s 阈值状态机落回大封面盖住面板。
     */
    private fun resetLyricsForNewSong() {
        lyricsLoading.value = true
        // 旧歌词是渐隐过渡素材, 不属于新歌; 在"当前歌歌词就绪"判定里立即失效
        lyricsSongId.value = -1L
    }

    fun resetPreloadFlag() { needsPreload.value = false }

    fun refreshGaplessSetting() {
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        // 默认开启: 无缝预载是播放体验的一部分, 不该让大多数用户默默用着硬切换
        gaplessEnabled = prefs.getBoolean("gapless_playback", true)
    }

    /** 设置页开关:歌词翻译开/关。写 SharedPreferences + 更新 StateFlow,播放器立即可见。 */
    fun setLyricsTranslation(enabled: Boolean) {
        showLyricsTranslation.value = enabled
        getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
            .edit().putBoolean("lyrics_translation", enabled).apply()
    }

    private fun isOnWifi(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun playSong(songId: Long, title: String = "", artist: String = "", artworkUrl: String = "", quality: String = "") {
        songPlayVersion++
        val fetchVersion = songPlayVersion
        latestPlaySongId = songId
        needsPreload.value = false
        refreshGaplessSetting()

        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        val selectedQuality = if (quality.isNotEmpty()) quality
        else if (isOnWifi()) qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
        else qualityApiLevels.getOrElse(prefs.getInt("mobile_quality", 1)) { "higher" }
        val qIdx = qualityApiLevels.indexOf(selectedQuality).coerceAtLeast(0)
        currentQualityIndex.value = qIdx

        // Fast path: URL was preloaded and cached for THIS requested level — skip network round-trip.
        // 缓存条目带档位:播放失败降档重试时,绝不会把上一档(可能已证明播不出声)的 URL 原样喂回。
        val cachedEntry = preloadCache[songId]?.takeIf {
            it.requestedLevel == selectedQuality && System.currentTimeMillis() - it.timestamp <= CACHE_TTL_MS
        }
        if (cachedEntry != null) {
            preloadedSongId = -1L; preloadedTitle = ""; preloadedArtist = ""
            preloadedArtwork = ""; preloadedActualLevel = ""; preloadedUrl = ""
            lastPlayedLevel = cachedEntry.actualLevel
            val actualIdx = qualityApiLevels.indexOf(cachedEntry.actualLevel).coerceAtLeast(0)
            currentQualityIndex.value = actualIdx
            resetLyricsForNewSong()
            currentSongId.value = songId
            currentSongName.value = title
            currentSongArtist.value = artist
            currentSongArtwork.value = artworkUrl
            val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                putExtra("url", cachedEntry.url)
                putExtra("title", title)
                putExtra("artist", artist)
                putExtra("artwork", artworkUrl)
                putExtra("songId", songId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                getApplication<Application>().startForegroundService(intent)
            else
                getApplication<Application>().startService(intent)
            isPlaying.value = true
            viewModelScope.launch { fetchLyrics(songId) }
            PlaybackStateManager.saveState(getApplication(), songId, title, artist, artworkUrl, true)
            return
        }

        playJob?.cancel()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = SongUrlFetcher.fetch(songId, selectedQuality)
                if (result == null) {
                    // 该歌在所有音质档位都取不到可播放的 URL（无版权 / 需会员且当前无订阅）。
                    // 前一个版本会兜底喂给 ExoPlayer 一个 404 的 HTML 链接导致无限缓冲"卡住"，
                    // 现在改成交由 MainScreen 跳下一首，绝不播放坏链接。
                    Log.w("PlayerViewModel", "no playable url for songId=$songId, skipping")
                    withContext(Dispatchers.Main) { onUnplayableCallback?.invoke() }
                    return@launch
                }
                // 取链期间若有更新的 playSong / 预载接管发生(版本号已前进),
                // 本次结果作废: 再发一次 "url" intent 会让 ExoPlayer setMediaItem
                // 把同一首歌重播一遍 —— 就是"听起来像拖带"的卡顿。
                if (fetchVersion != songPlayVersion) return@launch
                val actualIdx = qualityApiLevels.indexOf(result.actualLevel).coerceAtLeast(0)
                resetLyricsForNewSong()
                fetchLyrics(songId)
                withContext(Dispatchers.Main) {
                    lastPlayedLevel = result.actualLevel
                    currentQualityIndex.value = actualIdx
                    currentSongId.value = songId
                    currentSongName.value = title
                    currentSongArtist.value = artist
                    currentSongArtwork.value = artworkUrl

                    val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                        putExtra("url", result.url)
                        putExtra("title", title)
                        putExtra("artist", artist)
                        putExtra("artwork", artworkUrl)
                        putExtra("songId", songId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        getApplication<Application>().startForegroundService(intent)
                    else
                        getApplication<Application>().startService(intent)
                    isPlaying.value = true
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "fetchUrl failed", e)
            }
        }
    }

    /**
     * 播放出错(如设备解码器不认 24-bit FLAC / 高采样率,或拿到坏链接)时的降档重试。
     * ExoPlayer 主线程回调;每出错一次沿 qualityRetryLadder 降一档重新取链播放,
     * 到 standard 仍失败才交由 MainScreen 跳歌。同一 (songId@level) 3 秒内只处理一次,
     * 防止解码器反复报错触发重试风暴。
     */
    private fun handlePlaybackError(songId: Long) {
        if (songId <= 0 || songId != currentSongId.value) return
        val level = lastPlayedLevel.ifEmpty {
            qualityApiLevels.getOrElse(currentQualityIndex.value) { "lossless" }
        }
        val key = "${songId}@$level"
        val now = System.currentTimeMillis()
        if (key == lastErrorKey && now - lastErrorHandledAt < 3_000L) return
        lastErrorKey = key
        lastErrorHandledAt = now

        val idx = qualityRetryLadder.indexOf(level)
        val nextLevel = when {
            idx in 0 until qualityRetryLadder.size - 1 -> qualityRetryLadder[idx + 1]
            // 服务端可能返回阶梯之外的档位(如 sky/jymaster),从无损起往下试,不能直接跳歌。
            idx < 0 -> "lossless"
            else -> null // 已是 standard,无档可降
        }
        if (nextLevel == null) {
            Log.w("PlayerViewModel", "lowest tier also failed for songId=$songId, skipping")
            onUnplayableCallback?.invoke()
            return
        }
        Log.w("PlayerViewModel", "playback error at level=$level for songId=$songId, retrying at $nextLevel")
        playSong(
            songId,
            title = currentSongName.value ?: "",
            artist = currentSongArtist.value ?: "",
            artworkUrl = currentSongArtwork.value ?: "",
            quality = nextLevel
        )
    }

    fun preloadNextSong(songId: Long, title: String, artist: String, artworkUrl: String, allowCurrent: Boolean = false) {
        // Dedup: skip only if the SAME song is already being fetched/in queue.
        // 不能因 URL 已缓存而整体跳过——缓存意味着"省的再取链", 但下一首仍需
        // addMediaItem 入 ExoPlayer 队列才能无缝切换; 否则缓存命中时直接 return,
        // ExoPlayer 队列永远只有当前一首, 播完必然走 songEnded→playNext 硬切(= 无缝失效)。
        if (currentlyPreloadingSongId == songId) return
        // 把当前正在播的歌再入队(除单曲循环由 MainScreen 显式 allowCurrent 外)——
        // 队尾回绕/单曲队列等边界会让 [A,A] 自动过渡成"假单曲循环"。
        if (!allowCurrent && songId == currentSongId.value && songId > 0) return

        val capturedVersion = songPlayVersion
        preloadJob?.cancel()
        currentlyPreloadingSongId = songId
        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
                if (!prefs.getBoolean("gapless_playback", true)) {
                    currentlyPreloadingSongId = -1L
                    return@launch
                }
                val quality = if (isOnWifi())
                    qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
                else
                    qualityApiLevels.getOrElse(prefs.getInt("mobile_quality", 1)) { "higher" }
                // 缓存命中(同档 + TTL 内)则跳过网络, 但仍走下方入队路径。
                val cacheHit = preloadCache[songId]?.takeIf {
                    it.requestedLevel == quality &&
                        System.currentTimeMillis() - it.timestamp <= CACHE_TTL_MS
                }
                val result = cacheHit?.let { SongUrlResult(it.url, it.actualLevel) }
                    ?: SongUrlFetcher.fetch(songId, quality)
                if (result == null) {
                    // 预加载失败：可能无版权/无订阅，忽略即可，等当前歌结束时由 songEnded 跳歌。
                    currentlyPreloadingSongId = -1L
                    return@launch
                }
                withContext(Dispatchers.Main) {
                    currentlyPreloadingSongId = -1L
                    // Store in cache regardless of staleness — URL is valid even if a new song started.
                    preloadCache[songId] = PreloadCacheEntry(result.url, result.actualLevel, quality)
                    if (capturedVersion != songPlayVersion) {
                        // playSong was called while this fetch was in flight.
                        // If it was for THIS same song and hasn't completed its own fetch, take over.
                        if (songId == latestPlaySongId && currentSongId.value != songId) {
                            // 接管 = 一次新的开播动作: 版本号前进, 让并发 playSong 的
                            // 取链结果在 fetchVersion 检查处作废, 杜绝二次 setMediaItem 重播。
                            songPlayVersion++
                            playJob?.cancel()
                            val idx = qualityApiLevels.indexOf(result.actualLevel).coerceAtLeast(0)
                            currentQualityIndex.value = idx
                            lastPlayedLevel = result.actualLevel
                            resetLyricsForNewSong()
                            currentSongId.value = songId
                            currentSongName.value = title
                            currentSongArtist.value = artist
                            currentSongArtwork.value = artworkUrl
                            val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                                putExtra("url", result.url)
                                putExtra("title", title)
                                putExtra("artist", artist)
                                putExtra("artwork", artworkUrl)
                                putExtra("songId", songId)
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                                getApplication<Application>().startForegroundService(intent)
                            else
                                getApplication<Application>().startService(intent)
                            isPlaying.value = true
                            viewModelScope.launch { fetchLyrics(songId) }
                            PlaybackStateManager.saveState(getApplication(), songId, title, artist, artworkUrl, true)
                        }
                        return@withContext
                    }
                    // Normal path: add to ExoPlayer queue for gapless auto-transition.
                    preloadedSongId = songId
                    preloadedTitle = title
                    preloadedArtist = artist
                    preloadedArtwork = artworkUrl
                    preloadedActualLevel = result.actualLevel
                    preloadedUrl = result.url
                    val intent = Intent(getApplication(), PlaybackService::class.java).apply {
                        putExtra("action", "preload_next")
                        putExtra("url", result.url)
                        putExtra("title", title)
                        putExtra("artist", artist)
                        putExtra("artwork", artworkUrl)
                        putExtra("songId", songId)
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                        getApplication<Application>().startForegroundService(intent)
                    else
                        getApplication<Application>().startService(intent)
                    Log.d("PlayerViewModel", "Preload enqueued: $title")
                }
            } catch (e: Exception) {
                currentlyPreloadingSongId = -1L
                Log.w("PlayerViewModel", "Preload failed for songId=$songId", e)
            }
        }
    }

    fun fetchLyricsForSong(songId: Long) {
        viewModelScope.launch { fetchLyrics(songId) }
    }

    // 同一首歌的歌词请求只允许一个在途(playSong / onSongTransitioned / 冷启动恢复
    // 会并发发起, 不打去重会瞬间打 3×n 个请求, 触发服务端限流反而更拉胯)。
    private var lyricsFetchingSongId = -1L

    private suspend fun fetchLyrics(songId: Long) {
        if (lyricsFetchingSongId == songId) return
        lyricsFetchingSongId = songId
        // 失败重试(最多 4 次, 递增退避): 冷启动时 AppWarmup 与恢复请求同时在
        // 打网络, 歌词请求的瞬时超时/限流不该让歌词永久消失。关键是**响应层面的
        // 失败也要重试** —— 服务端风控(-460/-462)或需登录(301)返回的 code!=200
        // 响应里 lrc 为空, 旧实现当成"这首歌没歌词"直接结束, 用户必须切歌才能
        // 重新触发加载; 这些失败码是瞬时的, 退避重试大概率能拿到真歌词。
        lyricsLoading.value = true
        try {
            repeat(4) { attempt ->
                try {
                    val lyricResponse = RetrofitClient.api.getLyric(id = songId)
                    val code = lyricResponse.code
                    val lrcText = lyricResponse.lrc?.lyric ?: ""
                    val tlyricText = lyricResponse.tlyric?.lyric ?: ""
                    // code==200 且 lrc 字段存在(即使内容为空) → 服务端已确认, 结束。
                    // 其余形态(code!=200 / lrc 缺失)按瞬时故障重试。
                    val settled = code == 200 && (lyricResponse.lrc != null || tlyricText.isNotEmpty())
                    if (!settled && attempt < 3) {
                        Log.w("PlayerViewModel", "fetchLyrics unsettled songId=$songId code=$code, retry ${attempt + 1}")
                        delay(700L + attempt * 400L)
                        return@repeat
                    }
                    // 只在本请求仍是"当前歌"时写入——恢复路径与 playSong 的并发请求
                    // 返回乱序时, 旧请求不得覆盖新歌的歌词/译文
                    if (currentSongId.value == songId) {
                        if (lrcText.isNotEmpty()) {
                            lyrics.value = LrcParser.parse(lrcText)
                            // 有歌词：标记为"当前歌的歌词就绪"（供 UI 自动回切歌词视图）
                            lyricsSongId.value = songId
                        }
                        if (tlyricText.isNotEmpty()) {
                            translatedLyrics.value = LrcParser.parse(tlyricText)
                        }
                        // lrc 为空（确无歌词）: 不清空 lyrics(旧歌词供 Crossfade 渐隐),
                        // 也不设置 lyricsSongId → lyricsReady 保持 false, 由 3s 阈值落封面。
                    }
                    return
                } catch (e: Exception) {
                    // 失败不清空已有歌词(网络抖动不该把 UI 变空白), 重试后仍失败才退出
                    if (attempt == 3) {
                        Log.e("PlayerViewModel", "fetchLyrics failed for songId=$songId", e)
                        return
                    }
                    delay(700L + attempt * 400L)
                }
            }
        } finally {
            lyricsLoading.value = false
            if (lyricsFetchingSongId == songId) lyricsFetchingSongId = -1L
        }
    }

    /**
     * 只载入歌曲元数据, 不取链不播放(剪贴板分享的单曲场景)。
     * 之后用户按下播放键 → togglePlayPause 检测到 duration==0 且 songId>0,
     * 自动走全量 playSong 路径开播。
     */
    fun prepareSongWithoutPlay(songId: Long, title: String, artist: String, artworkUrl: String) {
        latestPlaySongId = songId
        resetLyricsForNewSong()
        currentSongId.value = songId
        currentSongName.value = title
        currentSongArtist.value = artist
        currentSongArtwork.value = artworkUrl
        currentPosition.value = 0L
        duration.value = 0L
        progress.value = 0f
        isPlaying.value = false
        viewModelScope.launch { fetchLyrics(songId) }
        PlaybackStateManager.saveState(
            getApplication(), songId, title, artist, artworkUrl, false
        )
    }

    fun togglePlayPause() {
        val songId = currentSongId.value
        // 只有"已载入但从未开播"的场景才走全量 playSong(剪贴板单曲/冷启动恢复态)。
        // duration==0 且当前曲正在播(如无缝过渡后心跳还没把 duration 同步回来)时
        // 若走 playSong 会重取 URL + setMediaItem, 把正在播的歌从 0 重播一遍=拖带感。
        if (duration.value == 0L && songId != null && songId > 0 && !isPlaying.value) {
            playSong(
                songId,
                title = currentSongName.value ?: "",
                artist = currentSongArtist.value ?: "",
                artworkUrl = currentSongArtwork.value ?: ""
            )
            return
        }
        isPlaying.value = !isPlaying.value
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            putExtra("action", if (isPlaying.value) "resume" else "pause")
        }
        getApplication<Application>().startService(intent)
    }

    /** 仅暂停播放,保留当前歌曲与队列(LINE 模式队尾停用)。 */
    fun pausePlayback() {
        isPlaying.value = false
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            putExtra("action", "pause")
        }
        getApplication<Application>().startService(intent)
    }

    fun seekTo(position: Long) {
        val intent = Intent(getApplication(), PlaybackService::class.java).apply {
            putExtra("action", "seek")
            putExtra("position", position)
        }
        getApplication<Application>().startService(intent)
    }

    fun stopService() {
        val app = getApplication<Application>()
        PlaybackStateManager.clearState(app)
        PlaybackStateManager.clearQueue(app)

        val intent = Intent(app, PlaybackService::class.java).apply {
            putExtra("action", "stop")
        }
        app.startService(intent)
        isPlaying.value = false
        resetLyricsForNewSong()
        currentSongId.value = null
        currentSongName.value = null
        currentSongArtist.value = null
        currentSongArtwork.value = null
    }

    override fun onCleared() {
        PlaybackService.onProgressUpdate = null
        PlaybackService.onPlaybackEnded = null
        PlaybackService.onPlaybackPrevious = null
        PlaybackService.onIsPlayingChanged = null
        PlaybackService.onSongTransitioned = null
        PlaybackService.onBufferingChanged = null
        PlaybackService.onPlaybackError = null
        super.onCleared()
    }
}
