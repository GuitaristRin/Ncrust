package com.takahashirinta.ncrust.player

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Build
import android.os.Bundle
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

/**
 * 一次「当前播放曲目」的完整快照。元数据与音频同源于 ExoPlayer 的当前 MediaItem，
 * 不再靠单独的 pending 标量槽对位——这样 UI/通知显示的标题、封面、音质档位
 * 永远与真正在响的那一首一致，不会出现「元数据跳了音源没跳」。
 */
data class NowPlaying(
    val songId: Long,
    val title: String,
    val artist: String,
    val artwork: String,
    val actualLevel: String
)

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

        // MediaItem extras：随 item 携带原始封面 URL 与实际音质档位。
        const val EXTRA_ARTWORK = "ncrust_artwork"
        const val EXTRA_ACTUAL_LEVEL = "ncrust_actual_level"

        var onProgressUpdate: ((Long, Long) -> Unit)? = null
        var onPlaybackEnded: (() -> Unit)? = null
        var onPlaybackPrevious: (() -> Unit)? = null
        var onIsPlayingChanged: ((Boolean) -> Unit)? = null
        // Fired on the main thread when ExoPlayer reports a playback error (decode/source
        // failure). Carries the song id ExoPlayer was on; the ViewModel downgrades quality
        // and retries, so a device that can't decode e.g. 24-bit FLAC still gets sound.
        var onPlaybackError: ((Long) -> Unit)? = null
        // Fired on the main thread when ExoPlayer auto-transitions to a preloaded next item.
        // Carries the full metadata of the item that actually started (same source as audio).
        var onSongTransitioned: ((NowPlaying) -> Unit)? = null
        var onBufferingChanged: ((Boolean) -> Unit)? = null
        var mediaTitle: String = "Ncrust"
        var mediaArtist: String = ""
        var mediaSongId: Long? = null
        var instance: PlaybackService? = null
    }

    // 无缝预载的下一首封面位图，按 songId 存：preload_next 时提前加载，切换瞬间按
    // 当前 MediaItem 的 songId 精确取用，不会出现"新歌标题 + 上一首封面"的过渡窗口。
    private val preloadedArtworkBitmaps = LinkedHashMap<Long, Bitmap>()

    /**
     * 构造绑定完整身份的 MediaItem：mediaId = songId，携带标题/艺人/封面。
     * 播放器队列里每一项都自带身份，切歌时从 currentMediaItem 读取即可，不会串歌。
     */
    private fun buildMediaItem(
        songId: Long,
        url: String,
        title: String,
        artist: String,
        artwork: String
    ): MediaItem {
        val metadata = MediaMetadata.Builder()
            .setTitle(title.ifEmpty { null })
            .setArtist(artist.ifEmpty { null })
            .setArtworkUri(artwork.takeIf { it.isNotEmpty() }?.let { Uri.parse(CoverUrls.large(it)) })
            .build()
        return MediaItem.Builder()
            .setMediaId(if (songId > 0) songId.toString() else url)
            .setUri(url)
            .setMediaMetadata(metadata)
            .build()
    }

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
                val sid = currentSongIdFromPlayer()
                Log.e(
                    "PlaybackService",
                    "Playback error for songId=$sid code=${error.errorCodeName}: ${error.message}",
                    error
                )
                onPlaybackError?.invoke(sid)
            }
            override fun onMediaItemTransition(
                mediaItem: androidx.media3.common.MediaItem?,
                reason: Int
            ) {
                if (mediaItem == null) return
                // 当前项一变就以它为准刷新标题/封面/音质——元数据与音频同一个对象，
                // 不可能再出现"元数据跳了音源没跳"。applyNowPlaying 内部已负责
                // updatePlaybackState()/updateNotify()，两条分支共用，不再重复调用。
                applyNowPlaying(mediaItem)
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO) {
                    val songId = mediaItem.mediaId.toLongOrNull() ?: -1L
                    val extras = mediaItem.mediaMetadata.extras
                    val actualLevel = extras?.getString(EXTRA_ACTUAL_LEVEL).orEmpty()
                    val artwork = extras?.getString(EXTRA_ARTWORK).orEmpty()
                    // 清掉已播项，保持队列紧凑。
                    if (player.currentMediaItemIndex > 0) {
                        player.removeMediaItem(0)
                    }
                    onSongTransitioned?.invoke(
                        NowPlaying(songId, mediaTitle, mediaArtist, artwork, actualLevel)
                    )
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
                onPlaybackError?.invoke(currentSongIdFromPlayer())
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
                val title = intent.getStringExtra("title").orEmpty()
                val artist = intent.getStringExtra("artist").orEmpty()
                val artwork = intent.getStringExtra("artwork").orEmpty()
                val actualLevel = intent.getStringExtra("actualLevel").orEmpty()
                // 不变量：播放器只保留"当前项 + 至多一首预载项"。
                // 若上一次预载已被新预载取代，先清掉旧预载，避免队列里堆积多首"下一首"。
                while (player.mediaItemCount > 1) {
                    player.removeMediaItem(player.mediaItemCount - 1)
                }
                // 没有任何当前项时不预载：此时 addMediaItem 会让预载项成为当前项并自动开播。
                if (player.mediaItemCount != 1) {
                    Log.d("PlaybackService", "Skip preload, no current item: $title")
                    return START_NOT_STICKY
                }
                // 同一首已在待播队列里就不再追加：重复项会让元数据/音频错位（旧实现 4 个
                // 预载入口 + 缓存命中仍入队，常见 [A,B,B] 队列，播到第二个 B 时元数据已跳到 C）。
                if (nextSongId > 0 && hasUpcomingMediaId(nextSongId)) {
                    Log.d("PlaybackService", "Skip duplicate preload: $title")
                    return START_NOT_STICKY
                }
                // 提前加载下一首封面: 无缝切换瞬间任务栏直接是新图,
                // 不再出现"新歌标题 + 上一首封面"的过渡窗口
                if (artwork.isNotEmpty()) preloadArtwork(artwork, nextSongId)
                player.addMediaItem(buildMediaItem(nextUrl, nextSongId, title, artist, artwork, actualLevel))
                Log.d("PlaybackService", "Queued next: $title songId=$nextSongId url=$nextUrl")
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
            // 手动切歌：整项替换播放列表，并绑定完整身份（mediaId + 元数据）。
            playUrl(url, songId, title ?: mediaTitle, artist ?: mediaArtist, artwork.orEmpty())
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

    private fun playUrl(url: String, songId: Long, title: String, artist: String, artwork: String) {
        Log.d("PlaybackService", "Playing: $url")
        // 手动切歌顶掉无缝队列，预载的下一首封面一并作废。
        preloadedArtworkBitmaps.clear()
        val mediaItem = buildMediaItem(url, songId, title, artist, artwork, "")
        player.setMediaItem(mediaItem)
        player.prepare()
        player.playWhenReady = true
    }

    /**
     * 构造自包含的 MediaItem：标题/歌手/封面/音质档位都挂在 item 上，与音频同源。
     * 这样无论自动 gapless 交接还是手动切歌，UI/通知都能从当前 item 直接取到正确元数据。
     */
    private fun buildMediaItem(
        url: String,
        songId: Long,
        title: String,
        artist: String,
        artwork: String,
        actualLevel: String
    ): MediaItem {
        val extras = Bundle().apply {
            putString(EXTRA_ARTWORK, artwork)
            putString(EXTRA_ACTUAL_LEVEL, actualLevel)
        }
        return MediaItem.Builder()
            .setMediaId(songId.takeIf { it > 0 }?.toString().orEmpty())
            .setUri(url)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title.ifEmpty { "Ncrust" })
                    .setArtist(artist)
                    .setArtworkUri(artwork.takeIf { it.isNotEmpty() }?.let { Uri.parse(CoverUrls.large(it)) })
                    .setExtras(extras)
                    .build()
            )
            .build()
    }

    /** 当前真正在响的歌曲 id（来自 ExoPlayer 当前 item，而非任何缓存槽）。 */
    private fun currentSongIdFromPlayer(): Long =
        player.currentMediaItem?.mediaId?.toLongOrNull()?.takeIf { it > 0 } ?: (mediaSongId ?: -1L)

    /**
     * 当前播放曲目快照（来自 ExoPlayer 当前 item）。供 UI 层在 Activity 重建后与
     * 仍在播放的 Service 对齐歌词/封面/音质——避免用持久化的过期 song 字段。
     */
    fun currentNowPlaying(): NowPlaying? {
        val item = player.currentMediaItem ?: return null
        val songId = item.mediaId.toLongOrNull()?.takeIf { it > 0 } ?: return null
        val md = item.mediaMetadata
        return NowPlaying(
            songId = songId,
            title = md.title?.toString() ?: mediaTitle,
            artist = md.artist?.toString() ?: mediaArtist,
            artwork = md.extras?.getString(EXTRA_ARTWORK).orEmpty(),
            actualLevel = md.extras?.getString(EXTRA_ACTUAL_LEVEL).orEmpty()
        )
    }

    /** 当前 item 之后是否已排入同一首歌（用于拦截重复 addMediaItem）。 */
    private fun hasUpcomingMediaId(songId: Long): Boolean {
        val id = songId.toString()
        for (i in player.currentMediaItemIndex + 1 until player.mediaItemCount) {
            if (player.getMediaItemAt(i).mediaId == id) return true
        }
        return false
    }

    /** 以当前 MediaItem 为准刷新通知栏/锁屏的标题、歌手、封面与音质。 */
    private fun applyNowPlaying(item: MediaItem) {
        // 车机浏览树的 id 形如 "song:<id>"，播放项 id 形如 "<id>"，两种都解析。
        val songId = item.mediaId.toLongOrNull()
            ?: item.mediaId.removePrefix("song:").toLongOrNull()
            ?: -1L
        val md = item.mediaMetadata
        mediaTitle = md.title?.toString() ?: "Ncrust"
        mediaArtist = md.artist?.toString() ?: ""
        mediaSongId = songId.takeIf { it > 0 }
        val artwork = md.extras?.getString(EXTRA_ARTWORK).orEmpty()
        if (artwork.isNotEmpty() && artwork != currentArtworkUrl) {
            currentArtworkUrl = artwork
            val preloaded = preloadedArtworkBitmaps.remove(songId)
            if (preloaded != null) {
                currentArtworkBitmap = preloaded
                scope.launch(Dispatchers.Main) {
                    updatePlaybackState()
                    updateNotify()
                }
            } else {
                // 回退异步加载; 旧位图保留到新封面加载完, 加载完成由 metadata 位图引用比较触发换图
                loadArtwork(artwork)
            }
        }
        updatePlaybackState()
        updateNotify()
    }

    /**
     * 提前把下一首封面加载进 [preloadedArtworkBitmaps]（按 songId 索引，不入当前位图）。
     * 无缝切换瞬间按 item 的 songId 精确取用，消除任务栏封面过渡窗口。
     */
    private fun preloadArtwork(url: String, songId: Long) {
        if (songId <= 0 || url.isEmpty()) return
        scope.launch(Dispatchers.IO) {
            try {
                val result = Coil.imageLoader(this@PlaybackService).execute(
                    ImageRequest.Builder(this@PlaybackService)
                        .data(CoverUrls.large(url))
                        .size(1024, 1024)
                        .build()
                )
                if (result is SuccessResult) {
                    val bitmap =
                        (result.drawable as BitmapDrawable).bitmap.copy(Bitmap.Config.ARGB_8888, false)
                    withContext(Dispatchers.Main) {
                        preloadedArtworkBitmaps[songId] = bitmap
                        // 只保留最近几首，避免长时间播放累积位图。
                        while (preloadedArtworkBitmaps.size > 4) {
                            val oldest = preloadedArtworkBitmaps.keys.first()
                            preloadedArtworkBitmaps.remove(oldest)
                        }
                    }
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
