package com.takahashirinta.ncrust.ui.viewmodel

import android.app.Application
import android.content.Intent
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

    val currentSongId = MutableStateFlow<Long?>(null)
    val currentSongName = MutableStateFlow<String?>(null)
    val currentSongArtist = MutableStateFlow<String?>(null)
    val currentSongArtwork = MutableStateFlow<String?>(null)

    private var onSongEndedCallback: (() -> Unit)? = null
    private var onSongPreviousCallback: (() -> Unit)? = null
    private var playJob: Job? = null

    val currentQualityLabel = MutableStateFlow("无损")
    private val qualityApiLevels = listOf("standard", "higher", "exhigh", "lossless", "hires")
    private val qualityDisplayLabels = listOf("压缩", "较好", "更好", "无损", "高解析")

    init {
        PlaybackService.onProgressUpdate = { pos, dur ->
            currentPosition.value = pos
            duration.value = dur
            progress.value = if (dur > 0) pos.toFloat() / dur.toFloat() else 0f
        }
        PlaybackService.onPlaybackEnded = {
            onSongEndedCallback?.invoke()
        }
        PlaybackService.onPlaybackPrevious = {
            onSongPreviousCallback?.invoke()
        }
        PlaybackService.onIsPlayingChanged = { playing ->
            isPlaying.value = playing
        }

        val app = getApplication<Application>()

        val savedState = PlaybackStateManager.getState(app)
        if (savedState != null) {
            currentSongId.value = savedState.songId
            currentSongName.value = savedState.songName
            currentSongArtist.value = savedState.songArtist
            currentSongArtwork.value = savedState.songArtwork
            isPlaying.value = false  // 启动时不自动恢复播放，等用户主动触发

            if (savedState.songId > 0) {
                viewModelScope.launch {
                    fetchLyrics(savedState.songId)
                }
            }
        }
    }

    fun setOnSongEndedCallback(callback: () -> Unit) {
        onSongEndedCallback = callback
    }

    fun setOnSongPreviousCallback(callback: () -> Unit) {
        onSongPreviousCallback = callback
    }

    fun playSong(songId: Long, title: String = "", artist: String = "", artworkUrl: String = "", quality: String = "") {
        val prefs = getApplication<Application>().getSharedPreferences("ncrust_settings", 0)
        val selectedQuality = if (quality.isNotEmpty()) quality
        else qualityApiLevels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
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
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        getApplication<Application>().startForegroundService(intent)
                    } else {
                        getApplication<Application>().startService(intent)
                    }
                    isPlaying.value = true
                }
            } catch (e: Exception) {
                Log.e("PlayerViewModel", "fetchUrl failed", e)
            }
        }
    }

    private suspend fun fetchLyrics(songId: Long) {
        try {
            val lyricResponse = RetrofitClient.api.getLyric(id = songId)
            val lrcText = lyricResponse.lrc?.lyric ?: ""
            if (lrcText.isNotEmpty()) {
                val parsed = LrcParser.parse(lrcText)
                lyrics.value = parsed
            }
        } catch (e: Exception) {
            Log.e("PlayerViewModel", "fetchLyrics failed", e)
            lyrics.value = emptyList()
        }
    }

    fun togglePlayPause() {
        val songId = currentSongId.value
        if (duration.value == 0L && songId != null && songId > 0) {
            // 启动后恢复状态时无媒体加载，重新拉取 URL 并播放
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
        PlaybackStateManager.clearQueue(app)   // 新增

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
        super.onCleared()
    }
}