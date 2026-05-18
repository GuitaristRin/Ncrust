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

    val currentQualityIndex = MutableStateFlow(3)
    private val qualityApiLevels = listOf("standard", "higher", "exhigh", "lossless", "hires")

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

    private data class PreloadCacheEntry(val url: String, val actualLevel: String, val timestamp: Long = System.currentTimeMillis())
    private val preloadCache = mutableMapOf<Long, PreloadCacheEntry>()
    private val CACHE_TTL_MS = 5 * 60 * 1_000L
    // Prevents duplicate preload launches for the same song while one is in flight.
    private var currentlyPreloadingSongId = -1L

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
        latestPlaySongId = songId
        needsPreload.value = false
        refreshGaplessSetting()

        // Fast path: URL was preloaded and cached — skip network round-trip entirely.
        val cachedEntry = preloadCache[songId]?.takeIf { System.currentTimeMillis() - it.timestamp <= CACHE_TTL_MS }
        if (cachedEntry != null) {
            preloadedSongId = -1L; preloadedTitle = ""; preloadedArtist = ""
            preloadedArtwork = ""; preloadedActualLevel = ""; preloadedUrl = ""
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

        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        val selectedQuality = if (quality.isNotEmpty()) quality
        else if (isOnWifi()) qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
        else qualityApiLevels.getOrElse(prefs.getInt("mobile_quality", 1)) { "higher" }
        val qIdx = qualityApiLevels.indexOf(selectedQuality).coerceAtLeast(0)
        currentQualityIndex.value = qIdx

        playJob?.cancel()
        playJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = SongUrlFetcher.fetch(songId, selectedQuality)
                val actualIdx = qualityApiLevels.indexOf(result.actualLevel).coerceAtLeast(0)
                fetchLyrics(songId)
                withContext(Dispatchers.Main) {
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
                withContext(Dispatchers.Main) {
                    currentlyPreloadingSongId = -1L
                    // Store in cache regardless of staleness — URL is valid even if a new song started.
                    preloadCache[songId] = PreloadCacheEntry(result.url, result.actualLevel)
                    if (capturedVersion != songPlayVersion) {
                        // playSong was called while this fetch was in flight.
                        // If it was for THIS same song and hasn't completed its own fetch, take over.
                        if (songId == latestPlaySongId && currentSongId.value != songId) {
                            playJob?.cancel()
                            val idx = qualityApiLevels.indexOf(result.actualLevel).coerceAtLeast(0)
                            currentQualityIndex.value = idx
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
