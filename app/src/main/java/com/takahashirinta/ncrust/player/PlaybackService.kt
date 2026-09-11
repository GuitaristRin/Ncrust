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
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.session.LibraryResult
import androidx.media3.session.MediaLibraryService
import androidx.media3.session.MediaLibraryService.LibraryParams
import androidx.media3.session.MediaLibraryService.MediaLibrarySession
import androidx.media3.session.MediaSession as M3MediaSession
import androidx.palette.graphics.Palette
import coil.Coil
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.google.common.collect.ImmutableList
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.SettableFuture
import com.takahashirinta.ncrust.MainActivity
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.stringsForCode
import kotlinx.coroutines.*

@OptIn(UnstableApi::class)
class PlaybackService : MediaLibraryService() {
    lateinit var player: ExoPlayer
    private var mediaSession: MediaLibrarySession? = null
    private var mediaSessionCompat: MediaSessionCompat? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var progressJob: Job? = null
    private var isServiceStarted = false
    private var currentArtworkUrl: String? = null
    private var currentArtworkBitmap: Bitmap? = null
    private var currentDominantColor: Int = 0xFF1DB954.toInt()
    // 封面加载代数: 每次 loadArtwork 自增, 完成时若代数已过期(期间又切了歌)
    // 则丢弃结果 —— 否则慢加载的上一首封面会覆盖新歌封面, 任务栏/锁屏
    // 显示上一首的图(切歌封面错位)。
    private var artworkGeneration = 0

    companion object {
        // 车机浏览树节点 id。
        const val ROOT_ID = "ncrust_root"
        const val DAILY_ID = "ncrust_daily"
        const val FM_ID = "ncrust_fm"
        const val LIKED_ID = "ncrust_liked"

        var onProgressUpdate: ((Long, Long) -> Unit)? = null
        var onPlaybackEnded: (() -> Unit)? = null
        var onPlaybackPrevious: (() -> Unit)? = null
        var onIsPlayingChanged: ((Boolean) -> Unit)? = null
        // Fired on the main thread when ExoPlayer reports a playback error (decode/source
        // failure). Carries the song id ExoPlayer was on; the ViewModel downgrades quality
        // and retries, so a device that can't decode e.g. 24-bit FLAC still gets sound.
        var onPlaybackError: ((Long) -> Unit)? = null
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
    // 无缝预载的下一首封面位图: preload_next 时提前加载, 切换瞬间直接应用,
    // 任务栏不会出现"新歌标题 + 上一首封面"的过渡窗口
    private var pendingNextArtworkBitmap: Bitmap? = null
    private var artworkPreloadGeneration = 0

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
            .setHandleAudioBecomingNoisy(true)
            // 后台/熄屏播放时持有 partial wake lock，避免 CPU 休眠导致音频欠载
            // （听感是"炒豆子"爆鸣，严重时 AudioTrack 直接死掉、进度还在跑但没声）。
            .setWakeMode(C.WAKE_MODE_NETWORK)
            // 弱网缓冲策略：默认 LoadControl 重缓冲后仅攒 5s 就续播，网络略慢于码率时
            // 会"播一点断一点"（拖带感）。这里拉高重缓冲续播阈值到 15s、并把目标缓冲
            // 扩到 30~60s，弱网下宁可多缓冲一小会儿，也不持续卡顿。
            .setLoadControl(
                DefaultLoadControl.Builder()
                    .setBufferDurationsMs(
                        /* minBufferMs = */ 30_000,
                        /* maxBufferMs = */ 60_000,
                        /* bufferForPlaybackMs = */ 2_500,
                        /* bufferForPlaybackAfterRebufferMs = */ 15_000
                    )
                    .build()
            )
            .build()

        // media3 MediaLibrarySession：对外暴露播放控制 + 浏览树，车机
        // （Android Automotive / Android Auto）据此发现应用并选歌。
        // 通知栏仍走 MediaSessionCompat，两者独立、互不干扰。
        mediaSession = MediaLibrarySession.Builder(this, player, libraryCallback()).build()

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
            override fun onPlayerError(error: PlaybackException) {
                // 之前完全没有错误处理:解码/取流失败后播放器静默停在 IDLE,
                // UI 还显示"在播",实际既没声音也不跳歌。现在上报给 ViewModel
                // 降档重试,最低档仍失败则由 ViewModel 跳歌。
                Log.e(
                    "PlaybackService",
                    "Playback error for songId=$mediaSongId code=${error.errorCodeName}: ${error.message}",
                    error
                )
                onPlaybackError?.invoke(mediaSongId ?: -1L)
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
                        // 预载位图就绪直接应用(切换瞬间任务栏就是新图),
                        // 未就绪回退异步加载
                        val preloaded = pendingNextArtworkBitmap
                        pendingNextArtworkBitmap = null
                        // 使任何在途预载作废: 迟到的预载结果不能回填 pending,
                        // 否则会污染下一次切换
                        artworkPreloadGeneration++
                        if (preloaded != null) {
                            currentArtworkBitmap = preloaded
                            scope.launch(Dispatchers.Main) {
                                updatePlaybackState()
                                updateNotify()
                            }
                        } else {
                            // 回退异步加载; 旧位图保留到新封面加载完(用户决策),
                            // 加载完成由 metadata 位图引用比较触发换图
                            loadArtwork(artwork)
                        }
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
        // 音频输出层故障（听感"哒哒哒"爆鸣，严重时 AudioTrack 死掉、进度照跑但没声）
        // 不会走 onPlayerError, 这里单独接住并交给 ViewModel 的降档重试处理。
        player.addAnalyticsListener(object : AnalyticsListener {
            override fun onAudioSinkError(
                eventTime: AnalyticsListener.EventTime,
                audioSinkError: Exception
            ) {
                Log.e("PlaybackService", "AudioSink error: ${audioSinkError.message}", audioSinkError)
                onPlaybackError?.invoke(mediaSongId ?: -1L)
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
                val nextSongId = intent.getLongExtra("songId", -1L)
                pendingNextTitle = intent.getStringExtra("title")
                pendingNextArtist = intent.getStringExtra("artist")
                pendingNextArtwork = intent.getStringExtra("artwork")
                pendingNextSongId = nextSongId
                // 提前加载下一首封面: 无缝切换瞬间任务栏直接是新图,
                // 不再出现"新歌标题 + 上一首封面"的过渡窗口
                if (!pendingNextArtwork.isNullOrEmpty()) {
                    preloadArtwork(pendingNextArtwork!!)
                }
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

    override fun onGetSession(controllerInfo: androidx.media3.session.MediaSession.ControllerInfo): MediaLibrarySession? {
        return mediaSession
    }

    /**
     * 车机浏览树：根 → 每日推荐 / 私人 FM / 我的收藏；文件夹 → 歌曲列表。
     * 车机点播时经 [onAddMediaItems] 把 song:<id> 解析成可播放 URL。
     */
    private fun libraryCallback() = object : MediaLibrarySession.Callback {
        override fun onGetLibraryRoot(
            session: MediaLibrarySession,
            browser: M3MediaSession.ControllerInfo,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<MediaItem>> =
            Futures.immediateFuture(LibraryResult.ofItem(rootItem(), params))

        override fun onGetChildren(
            session: MediaLibrarySession,
            browser: M3MediaSession.ControllerInfo,
            parentId: String,
            page: Int,
            pageSize: Int,
            params: LibraryParams?
        ): ListenableFuture<LibraryResult<ImmutableList<MediaItem>>> {
            val future = SettableFuture.create<LibraryResult<ImmutableList<MediaItem>>>()
            scope.launch {
                val children = runCatching { loadChildren(parentId) }.getOrDefault(emptyList())
                future.set(LibraryResult.ofItemList(ImmutableList.copyOf(children), params))
            }
            return future
        }

        override fun onAddMediaItems(
            mediaSession: M3MediaSession,
            controller: M3MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>
        ): ListenableFuture<MutableList<MediaItem>> {
            val future = SettableFuture.create<MutableList<MediaItem>>()
            scope.launch {
                future.set(mediaItems.map { runCatching { resolveMediaItem(it) }.getOrDefault(it) }.toMutableList())
            }
            return future
        }
    }

    private fun rootItem(): MediaItem = folderItem(ROOT_ID, "Ncrust")

    private fun folderItem(id: String, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(title)
                .setIsBrowsable(true)
                .setIsPlayable(false)
                .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                .build()
        )
        .build()

    private fun songItem(song: SongItem): MediaItem = MediaItem.Builder()
        .setMediaId("song:${song.id}")
        .setMediaMetadata(
            MediaMetadata.Builder()
                .setTitle(song.name)
                .setArtist(song.artists?.joinToString("/") { it.name })
                .setArtworkUri(song.album?.picUrl?.takeIf { it.isNotEmpty() }?.let { Uri.parse(CoverUrls.large(it)) })
                .setIsBrowsable(false)
                .setIsPlayable(true)
                .build()
        )
        .build()

    private suspend fun loadChildren(parentId: String): List<MediaItem> {
        val s = stringsForCode(getSavedLanguageCode(this))
        return when (parentId) {
            ROOT_ID -> listOf(
                folderItem(DAILY_ID, s.dailySongsTitle),
                folderItem(FM_ID, s.fmRadioTitleGeneric),
                folderItem(LIKED_ID, s.tabLibrary)
            )
            DAILY_ID -> PlaylistApi.getDailyRecommendSongs().map { songItem(it) }
            FM_ID -> PlaylistApi.getPersonalFm().map { songItem(it) }
            LIKED_ID -> LibraryManager.getSavedSongs(this).map { songItem(it) }
            else -> emptyList()
        }
    }

    /** 车机点播：把 song:<id> 解析成当前音质档的可播放 URL。 */
    private suspend fun resolveMediaItem(item: MediaItem): MediaItem {
        if (item.localConfiguration != null) return item
        val songId = item.mediaId.removePrefix("song:").toLongOrNull() ?: return item
        val result = SongUrlFetcher.fetch(songId, currentQualityLevel()) ?: return item
        return item.buildUpon().setUri(result.url).build()
    }

    private fun currentQualityLevel(): String {
        val prefs = getSharedPreferences("ncrust_settings", 0)
        val levels = listOf("standard", "higher", "exhigh", "lossless", "hires", "jyeffect", "dolby")
        return levels.getOrElse(prefs.getInt("wifi_quality", 3)) { "lossless" }
    }

    private fun playUrl(url: String) {
        Log.d("PlaybackService", "Playing: $url")
        // Clear any stale preload metadata; setMediaItem replaces the entire playlist.
        pendingNextTitle = null
        pendingNextArtist = null
        pendingNextArtwork = null
        pendingNextSongId = -1L
        // 手动切歌会顶掉无缝队列, 预载的下一首封面作废, 一并清掉
        pendingNextArtworkBitmap = null
        artworkPreloadGeneration++
        val mediaItem = androidx.media3.common.MediaItem.fromUri(url)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * 提前加载下一首封面到 pendingNextArtworkBitmap(不入当前位图)。
     * 无缝 preload_next 时调用, 切换瞬间直接应用, 消除任务栏封面过渡窗口。
     * 用独立的 preload 代数做过期校验(不影响当前歌的 loadArtwork 代数)。
     */
    private fun preloadArtwork(url: String) {
        val gen = ++artworkPreloadGeneration
        scope.launch(Dispatchers.IO) {
            try {
                val result = Coil.imageLoader(this@PlaybackService).execute(
                    ImageRequest.Builder(this@PlaybackService)
                        .data(CoverUrls.large(url))
                        .size(1024, 1024)
                        .build()
                )
                if (result is SuccessResult) {
                    if (gen != artworkPreloadGeneration) return@launch
                    pendingNextArtworkBitmap =
                        (result.drawable as BitmapDrawable).bitmap.copy(Bitmap.Config.ARGB_8888, false)
                }
            } catch (e: Exception) {
                Log.e("PlaybackService", "Preload artwork failed", e)
            }
        }
    }

    private fun loadArtwork(url: String) {
        // 旧位图**保留**到新封面加载完成再整体换掉(用户决策: 等它加载完再
        // 更换过去)——切歌瞬间不清图, 任务栏不会出现空图/系统保留旧图的
        // 不确定窗口; 加载完成后由 metadata 去重的位图引用比较触发重发。
        val gen = ++artworkGeneration
        scope.launch(Dispatchers.IO) {
            try {
                val imageLoader = Coil.imageLoader(this@PlaybackService)
                val request = ImageRequest.Builder(this@PlaybackService)
                    .data(CoverUrls.large(url))
                    // 锁屏/任务栏的媒体卡片是大尺寸位图(通常 1000px+), 512px 源会被
                    // 放大糊掉; 走图床 1080 缩略 + 1024 目标一起
                    .size(1024, 1024)
                    .build()
                val result = imageLoader.execute(request)
                // 加载期间又切了歌: 丢弃过期结果, 防止慢网下上一首封面覆盖新歌
                if (gen != artworkGeneration) return@launch
                if (result is SuccessResult) {
                    val srcBitmap = (result.drawable as BitmapDrawable).bitmap
                    val bitmap = srcBitmap.copy(Bitmap.Config.ARGB_8888, false)
                    currentArtworkBitmap = bitmap
                    // 立即重发 metadata(不等 500ms 心跳): 新封面尽快上任务栏
                    scope.launch(Dispatchers.Main) {
                        updatePlaybackState()
                        updateNotify()
                    }

                    Palette.from(bitmap).generate { palette ->
                        // palette 回调是异步的, 同样做代数校验
                        if (gen != artworkGeneration) return@generate
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

    // 上一次 setMetadata 时的 title/artist/duration 快照，用于跳过等值重发
    private var lastMetadataTitle: String? = null
    private var lastMetadataArtist: String? = null
    private var lastMetadataDuration: Long = -1L
    private var lastMetadataArtwork: String? = null
    // 上一次 setMetadata 的 ART 位图引用。光比 URL 不够: 位图是异步换的,
    // URL 换新但位图还是旧的、或位图换新但 URL 已同步 —— 用引用比较,
    // 只要位图实例变了就重发, 保证任务栏封面最终切到新歌
    private var lastMetadataBitmap: Bitmap? = null

    // setPlaybackState 去重：state 未变且距上次刷新 < STATE_MIN_INTERVAL_MS 时跳过
    // 位置精度对锁屏/通知条完全足够，跨进程 Binder 每次 1~3 ms，低端机 4Hz IPC 就吃满
    private var lastPlaybackStateInt: Int = -1
    private var lastPlaybackStateSentAt: Long = 0L
    private val STATE_MIN_INTERVAL_MS = 900L

    private fun updatePlaybackState() {
        val state = if (player.isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val position = player.currentPosition
        val dur = if (player.duration > 0) player.duration else 0L

        val now = System.currentTimeMillis()
        val stateChanged = state != lastPlaybackStateInt
        if (stateChanged || now - lastPlaybackStateSentAt >= STATE_MIN_INTERVAL_MS) {
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
            lastPlaybackStateInt = state
            lastPlaybackStateSentAt = now
        }

        // Metadata 只在 title/artist/duration/封面变化时重发——旧实现每 250ms 都要走一遍
        // MediaMetadataCompat.Builder + 跨进程 IPC 到系统 MediaSession，纯浪费。
        // 位图用**引用**比较: 实例变了(新封面加载完成)就重发, 同图不重发。
        if (mediaTitle != lastMetadataTitle || mediaArtist != lastMetadataArtist ||
            dur != lastMetadataDuration || currentArtworkUrl != lastMetadataArtwork ||
            currentArtworkBitmap !== lastMetadataBitmap
        ) {
            val builder = android.support.v4.media.MediaMetadataCompat.Builder()
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_TITLE, mediaTitle)
                .putString(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ARTIST, mediaArtist)
                .putLong(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_DURATION, dur)
            // 系统任务栏/锁屏的媒体卡优先读 MediaSession 的 ART 位图——不放进来的话
            // 系统退化用低清来源, 封面在任务栏上就是模糊的
            currentArtworkBitmap?.let {
                builder.putBitmap(android.support.v4.media.MediaMetadataCompat.METADATA_KEY_ART, it)
            }
            mediaSessionCompat?.setMetadata(builder.build())
            lastMetadataTitle = mediaTitle
            lastMetadataArtist = mediaArtist
            lastMetadataDuration = dur
            lastMetadataArtwork = currentArtworkUrl
            lastMetadataBitmap = currentArtworkBitmap
        }
    }

    private fun startProgressUpdates() {
        progressJob?.cancel()
        progressJob = scope.launch {
            while (isActive) {
                if (player.isPlaying) {
                    onProgressUpdate?.invoke(player.currentPosition, player.duration)
                    updatePlaybackState()
                }
                // 500 ms tick：歌词滚动/进度条精度感知不到差异，但把 UI 层 4Hz
                // 广播降到 2Hz，PlayerViewModel 的三个 StateFlow / SlimProgressBar
                // 每秒重绘次数直接减半，低端机主线程 snapshot 广播压力显著下降
                delay(500)
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
        mediaSession?.release()
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
