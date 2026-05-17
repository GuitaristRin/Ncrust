package com.takahashirinta.ncrust.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.IBinder
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.util.Log
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSessionService
import androidx.media3.session.MediaSession as M3MediaSession
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.takahashirinta.ncrust.MainActivity
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
class PlaybackService : MediaSessionService() {
    lateinit var player: ExoPlayer
    private var mediaSession: M3MediaSession? = null
    private var mediaSessionCompat: MediaSessionCompat? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var isServiceStarted = false
    private var currentArtworkUrl: String? = null
    private var currentArtworkBitmap: Bitmap? = null
    private var currentDominantColor: Int = 0xFF1DB954.toInt()

    companion object {
        var onProgressUpdate: ((Long, Long) -> Unit)? = null
        var onPlaybackEnded: (() -> Unit)? = null
        var onPlaybackPrevious: (() -> Unit)? = null
        var onIsPlayingChanged: ((Boolean) -> Unit)? = null
        // Fired on the main thread when ExoPlayer auto-transitions to a preloaded next item.
        var onSongTransitioned: (() -> Unit)? = null
        var onBufferingChanged: ((Boolean) -> Unit)? = null
        var mediaTitle: String = "Ncrust"
        var mediaArtist: String = ""
        var mediaSongId: Long? = null
        var instance: PlaybackService? = null
    }

    // Metadata staged for the next gapless transition.
    private var pendingNextTitle: String? = null
    private var pendingNextArtist: String? = null
    private var pendingNextArtwork: String? = null
    private var pendingNextSongId: Long = -1L

    override fun onCreate() {
        super.onCreate()
        instance = this
        Log.d("PlaybackService", "onCreate")

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                    .build(),
                true
            )
            .build()

        mediaSessionCompat = MediaSessionCompat(this, "NcrustSession").apply {
            setFlags(
                MediaSessionCompat.FLAG_HANDLES_MEDIA_BUTTONS or
                        MediaSessionCompat.FLAG_HANDLES_TRANSPORT_CONTROLS
            )
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { player.play() }
                override fun onPause() { player.pause() }
                override fun onSkipToNext() { onPlaybackEnded?.invoke() }
                override fun onSkipToPrevious() { onPlaybackPrevious?.invoke() }
                override fun onSeekTo(pos: Long) { player.seekTo(pos) }
            })
            isActive = true
        }

        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) {
                    onPlaybackEnded?.invoke()
                }
                onBufferingChanged?.invoke(state == Player.STATE_BUFFERING)
                updatePlaybackState()
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                onIsPlayingChanged?.invoke(isPlaying)
                PlaybackStateManager.updatePlayingState(this@PlaybackService, isPlaying)
                updatePlaybackState()
                updateNotify()
            }
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                // Only handle automatic transitions triggered by ExoPlayer (gapless handoff).
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && pendingNextTitle != null) {
                    mediaTitle = pendingNextTitle!!
                    mediaArtist = pendingNextArtist ?: ""
                    mediaSongId = pendingNextSongId.takeIf { it > 0 }
                    pendingNextTitle = null
                    val artwork = pendingNextArtwork
                    pendingNextArtwork = null
                    if (!artwork.isNullOrEmpty()) {
                        currentArtworkUrl = artwork
                        loadArtwork(artwork)
                    }
                    // Remove the finished item so the playlist stays compact.
                    if (player.mediaItemCount > 1) {
                        player.removeMediaItem(0)
                    }
                    updatePlaybackState()
                    updateNotify()
                    onSongTransitioned?.invoke()
                }
            }
        })
        createNotificationChannel()
        startProgressUpdates()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        Log.d("PlaybackService", "onStartCommand action=${intent?.getStringExtra("action")}")

        when (intent?.getStringExtra("action")) {
            "preload_next" -> {
                val nextUrl = intent.getStringExtra("url") ?: return START_NOT_STICKY
                pendingNextTitle = intent.getStringExtra("title")
                pendingNextArtist = intent.getStringExtra("artist")
                pendingNextArtwork = intent.getStringExtra("artwork")
                pendingNextSongId = intent.getLongExtra("songId", -1L)
                player.addMediaItem(androidx.media3.common.MediaItem.fromUri(nextUrl))
                Log.d("PlaybackService", "Queued next: $pendingNextTitle url=$nextUrl")
                return START_NOT_STICKY
            }
            "pause" -> { player.pause() }
            "resume" -> { player.play() }
            "stop" -> {
                PlaybackStateManager.clearState(this)
                stopForeground(STOP_FOREGROUND_REMOVE)
                mediaSessionCompat?.isActive = false
                mediaSessionCompat?.release()
                mediaTitle = "Ncrust"
                mediaArtist = ""
                mediaSongId = null
                currentArtworkBitmap = null
                currentArtworkUrl = null
                stopSelf()
                return START_NOT_STICKY
            }
            "seek" -> {
                val pos = intent.getLongExtra("position", 0L)
                player.seekTo(pos)
            }
            "previous" -> onPlaybackPrevious?.invoke()
            "next" -> onPlaybackEnded?.invoke()
        }

        val url = intent?.getStringExtra("url")
        val title = intent?.getStringExtra("title")
        val artist = intent?.getStringExtra("artist")
        val artwork = intent?.getStringExtra("artwork")
        val songId = intent?.getLongExtra("songId", -1L) ?: -1L

        if (title != null) mediaTitle = title
        if (artist != null) mediaArtist = artist
        if (songId > 0) mediaSongId = songId

        if (artwork != null && artwork != currentArtworkUrl) {
            currentArtworkUrl = artwork
            loadArtwork(artwork)
        }

        if (url != null) {
            PlaybackStateManager.saveState(this, songId, mediaTitle, mediaArtist, currentArtworkUrl ?: "", true)
            playUrl(url)
        } else if (!isServiceStarted && mediaTitle != "Ncrust") {
            updateNotify()
        }

        return START_NOT_STICKY
    }

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo): M3MediaSession? {
        return null
    }

    private fun playUrl(url: String) {
        Log.d("PlaybackService", "Playing: $url")
        // Clear any stale preload metadata; setMediaItem replaces the entire playlist.
        pendingNextTitle = null
        pendingNextArtist = null
        pendingNextArtwork = null
        pendingNextSongId = -1L
        val mediaItem = androidx.media3.common.MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    private fun loadArtwork(url: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val imageLoader = ImageLoader(this@PlaybackService)
                val request = ImageRequest.Builder(this@PlaybackService)
                    .data(url)
                    .size(512, 512)
                    .build()
                val result = imageLoader.execute(request)
                if (result is SuccessResult) {
                    val srcBitmap = (result.drawable as BitmapDrawable).bitmap
                    val bitmap = srcBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    currentArtworkBitmap = bitmap

                    Palette.from(bitmap).generate { palette ->
                        palette?.getDominantColor(0xFF1DB954.toInt())?.let {
                            currentDominantColor = it
                        }
                        scope.launch(Dispatchers.Main) {
                            updateNotify()
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "Load artwork failed", e)
            }
        }
    }

    private fun updatePlaybackState() {
        val state = if (player.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val position = player.currentPosition
        val dur = if (player.duration > 0) player.duration else 0L

        mediaSessionCompat?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setState(state, position, 1f)
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                            PlaybackStateCompat.ACTION_PAUSE or
                            PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                            PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                            PlaybackStateCompat.ACTION_SEEK_TO or
                            PlaybackStateCompat.ACTION_PLAY_PAUSE
                )
                .setBufferedPosition(dur)
                .build()
        )

        mediaSessionCompat?.setMetadata(
            android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, mediaTitle)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, mediaArtist)
                .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, dur)
                .build()
        )
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    onProgressUpdate?.invoke(player.currentPosition, player.duration)
                    updatePlaybackState()
                }
                delay(250)
            }
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "ncrust_playback",
                "Ncrust 音乐播放",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "正在播放的音乐"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
        }
    }

    private fun updateNotify() {
        try {
            val n = buildNotification()
            if (!isServiceStarted) {
                startForeground(1, n)
                isServiceStarted = true
            } else {
                getSystemService(NotificationManager::class.java)?.notify(1, n)
            }
        } catch (e: Exception) {
            Log.e("PlaybackService", "Failed to update notification", e)
        }
    }

    private fun buildNotification(): Notification {
        val isPlaying = player.isPlaying

        val builder = NotificationCompat.Builder(this, "ncrust_playback")
            .setContentTitle(mediaTitle)
            .setContentText(mediaArtist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(
                PendingIntent.getActivity(this, 0,
                    Intent(this, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            .addAction(android.R.drawable.ic_media_previous, "上一首", buildPI("previous"))
            .addAction(
                if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
                if (isPlaying) "暂停" else "播放",
                buildPI(if (isPlaying) "pause" else "resume")
            )
            .addAction(android.R.drawable.ic_media_next, "下一首", buildPI("next"))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSessionCompat?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )            .setColor(currentDominantColor)
            .setColorized(true)

        if (currentArtworkBitmap != null) {
            builder.setLargeIcon(currentArtworkBitmap)
        }

        return builder.build()
    }

    private fun buildPI(action: String): PendingIntent {
        val i = Intent(this, PlaybackService::class.java).apply { putExtra("action", action) }
        return PendingIntent.getService(this, action.hashCode(), i,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onBind(intent: Intent?): IBinder? {
        return super.onBind(intent)
    }

    override fun onDestroy() {
        Log.d("PlaybackService", "onDestroy")
        instance = null
        isServiceStarted = false
        progressJob?.cancel()
        // Do NOT null the companion callbacks here — the ViewModel registers them once and
        // they must survive a service stop/restart cycle (e.g. stopSelf then play again).
        // ViewModel.onCleared() is responsible for clearing them when the ViewModel dies.
        currentArtworkBitmap = null
        mediaSessionCompat?.isActive = false
        mediaSessionCompat?.release()
        player.release()
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        Log.d("PlaybackService", "onTaskRemoved")
        stopForeground(STOP_FOREGROUND_REMOVE)
        mediaSessionCompat?.isActive = false
        mediaSessionCompat?.release()
        mediaTitle = "Ncrust"
        mediaArtist = ""
        mediaSongId = null
        currentArtworkBitmap = null
        currentArtworkUrl = null
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }
}