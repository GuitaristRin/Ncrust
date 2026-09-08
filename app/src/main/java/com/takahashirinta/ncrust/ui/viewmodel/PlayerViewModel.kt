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
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val isPlaying = MutableStateFlow(false)
    val currentPosition = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val progress = MutableStateFlow(0f)
    val lyrics = MutableStateFlow<List<LrcLine>>(emptyList())
    // 外文歌词的译文(tlyric),Spotify 式渲染在原句下方。
    val translatedLyrics = MutableStateFlow<List<LrcLine>>(emptyList())
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
    private val PRELOAD_THRESHOLD_MS = 20_000L

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

    fun resetPreloadFlag() { needsPreload.value = false }

    fun refreshGaplessSetting() {
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        gaplessEnabled = prefs.getBoolean("gapless_playback", false)
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
                val actualIdx = qualityApiLevels.indexOf(result.actualLevel).coerceAtLeast(0)
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

    fun preloadNextSong(songId: Long, title: String, artist: String, artworkUrl: String) {
        // Dedup: skip if URL already cached (valid TTL) or same song is already being fetched.
        if (preloadCache[songId]?.let { System.currentTimeMillis() - it.timestamp <= CACHE_TTL_MS } == true) return
        if (currentlyPreloadingSongId == songId) return

        val capturedVersion = songPlayVersion
        preloadJob?.cancel()
        currentlyPreloadingSongId = songId
        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
                if (!prefs.getBoolean("gapless_playback", false)) {
                    currentlyPreloadingSongId = -1L
                    return@launch
                }
                val quality = if (isOnWifi())
                    qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
                else
                    qualityApiLevels.getOrElse(prefs.getInt("mobile_quality", 1)) { "higher" }
                val result = SongUrlFetcher.fetch(songId, quality)
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
                            playJob?.cancel()
                            val idx = qualityApiLevels.indexOf(result.actualLevel).coerceAtLeast(0)
                            currentQualityIndex.value = idx
                            lastPlayedLevel = result.actualLevel
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

    private suspend fun fetchLyrics(songId: Long) {
        try {
            val lyricResponse = RetrofitClient.api.getLyric(id = songId)
            val lrcText = lyricResponse.lrc?.lyric ?: ""
            if (lrcText.isNotEmpty()) {
                lyrics.value = LrcParser.parse(lrcText)
            }
            // tlyric = 外文歌词译文;空则清空旧译文,避免切歌后残留上一首。
            val tlyricText = lyricResponse.tlyric?.lyric ?: ""
            translatedLyrics.value = if (tlyricText.isNotEmpty()) LrcParser.parse(tlyricText) else emptyList()
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "fetchLyrics failed", e)
            lyrics.value = emptyList()
            translatedLyrics.value = emptyList()
        }
    }

    fun togglePlayPause() {
        val songId = currentSongId.value
        if (duration.value == 0L && songId != null && songId > 0) {
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
