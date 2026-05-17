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
import com.takahashirinta.ncrust.player.SongUrlFetcher
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow

class PlayerViewModel(application: Application) : AndroidViewModel(application) {
    val isPlaying = MutableStateFlow(false)
    val currentPosition = MutableStateFlow(0L)
    val duration = MutableStateFlow(0L)
    val progress = MutableStateFlow(0f)
    val lyrics = MutableStateFlow<List<LrcLine>>(emptyList())

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
    private var playJob: Job? = null
    private var preloadJob: Job? = null

    // Incremented on every explicit playSong call; lets preloadNextSong detect staleness.
    private var songPlayVersion = 0

    val currentQualityLabel = MutableStateFlow("无损")
    private val qualityApiLevels = listOf("standard", "higher", "exhigh", "lossless", "hires")
    private val qualityDisplayLabels = listOf("压缩", "较好", "更好", "无损", "高解析")

    private var gaplessEnabled = false
    private val PRELOAD_THRESHOLD_MS = 20_000L

    // Metadata for the in-flight preload; applied when ExoPlayer auto-transitions.
    // All reads/writes happen on the main thread.
    private var preloadedSongId = -1L
    private var preloadedTitle = ""
    private var preloadedArtist = ""
    private var preloadedArtwork = ""
    private var preloadedActualLevel = ""

    init {
        refreshGaplessSetting()

        PlaybackService.onProgressUpdate = { pos, dur ->
            currentPosition.value = pos
            duration.value = dur
            progress.value = if (dur > 0) pos.toFloat() / dur.toFloat() else 0f

            // Signal the preload window once per song (guarded by !needsPreload.value).
            if (gaplessEnabled && dur > 0 && pos > 1_000L && !needsPreload.value) {
                val remaining = dur - pos
                if (remaining in 1L..PRELOAD_THRESHOLD_MS) {
                    needsPreload.value = true
                }
            }
        }
        PlaybackService.onPlaybackEnded = { onSongEndedCallback?.invoke() }
        PlaybackService.onPlaybackPrevious = { onSongPreviousCallback?.invoke() }
        PlaybackService.onIsPlayingChanged = { playing -> isPlaying.value = playing }
        PlaybackService.onBufferingChanged = { buffering -> isBuffering.value = buffering }

        // Called on the main thread by ExoPlayer's onMediaItemTransition (AUTO reason).
        PlaybackService.onSongTransitioned = {
            if (preloadedSongId > 0) {
                currentSongId.value = preloadedSongId
                currentSongName.value = preloadedTitle
                currentSongArtist.value = preloadedArtist
                currentSongArtwork.value = preloadedArtwork
                val idx = qualityApiLevels.indexOf(preloadedActualLevel).coerceAtLeast(0)
                currentQualityLabel.value = qualityDisplayLabels.getOrElse(idx) { "无损" }
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
            isPlaying.value = false

            if (savedState.songId > 0) {
                viewModelScope.launch { fetchLyrics(savedState.songId) }
            }
        }
    }

    fun setOnSongEndedCallback(callback: () -> Unit) { onSongEndedCallback = callback }
    fun setOnSongPreviousCallback(callback: () -> Unit) { onSongPreviousCallback = callback }
    fun setOnSongTransitionedCallback(callback: () -> Unit) { onSongTransitionedCallback = callback }

    fun resetPreloadFlag() { needsPreload.value = false }

    fun refreshGaplessSetting() {
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        gaplessEnabled = prefs.getBoolean("gapless_playback", false)
    }

    private fun isOnWifi(): Boolean {
        val cm = getApplication<Application>().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        return cm.getNetworkCapabilities(cm.activeNetwork)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    fun playSong(songId: Long, title: String = "", artist: String = "", artworkUrl: String = "", quality: String = "") {
        songPlayVersion++
        preloadJob?.cancel()
        needsPreload.value = false
        refreshGaplessSetting()

        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        val selectedQuality = if (quality.isNotEmpty()) quality
        else if (isOnWifi()) qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
        else qualityApiLevels.getOrElse(prefs.getInt("mobile_quality", 1)) { "higher" }
        val qIdx = qualityApiLevels.indexOf(selectedQuality).coerceAtLeast(0)
        currentQualityLabel.value = qualityDisplayLabels.getOrElse(qIdx) { "无损" }

        playJob?.cancel()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = SongUrlFetcher.fetch(songId, selectedQuality)
                val actualIdx = qualityApiLevels.indexOf(result.actualLevel).coerceAtLeast(0)
                fetchLyrics(songId)
                withContext(Dispatchers.Main) {
                    currentQualityLabel.value = qualityDisplayLabels.getOrElse(actualIdx) { "无损" }
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

    fun preloadNextSong(songId: Long, title: String, artist: String, artworkUrl: String) {
        val capturedVersion = songPlayVersion
        preloadJob?.cancel()
        preloadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
                if (!prefs.getBoolean("gapless_playback", false)) return@launch
                val quality = if (isOnWifi())
                    qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
                else
                    qualityApiLevels.getOrElse(prefs.getInt("mobile_quality", 1)) { "higher" }
                val result = SongUrlFetcher.fetch(songId, quality)
                withContext(Dispatchers.Main) {
                    if (capturedVersion != songPlayVersion) return@withContext  // stale: a new song started
                    // Write preloaded metadata on the main thread (same thread as onSongTransitioned).
                    preloadedSongId = songId
                    preloadedTitle = title
                    preloadedArtist = artist
                    preloadedArtwork = artworkUrl
                    preloadedActualLevel = result.actualLevel
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
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "fetchLyrics failed", e)
            lyrics.value = emptyList()
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
        super.onCleared()
    }
}
