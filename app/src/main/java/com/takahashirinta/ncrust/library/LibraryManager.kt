package com.takahashirinta.ncrust.library

import android.content.Context
import android.util.Log
import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 云同步收藏库。
 *
 * 语义（与网易云官方一致）：
 *  - 「收藏单曲」 = 网易云「收藏/我喜欢」（weapi /api/radio/like）；
 *  - 「收藏专辑」 = 网易云「我收藏的专辑」（weapi /api/album/sub），与单曲解耦。
 *
 * 本地用 SharedPreferences 缓存云端状态（收藏单曲 + 收藏专辑），保证：
 *   - 进收藏页/登录时后台拉取（refreshFromCloud）刷新，云端为真源；
 *   - 未拉取/未登录/失败时直接显示本地缓存，绝不出现空白+加载动画；
 *   - 收藏/取消动作先落本地缓存即时生效，再异步推到云端。
 *
 * 该对象保持公开 API 不变，所有既有调用点（收藏按钮、各详情页「加入收藏」）无需改动。
 */
object LibraryManager {
    private const val PREFS_NAME = "ncrust_library"
    private const val KEY_SONGS = "saved_songs"
    private const val KEY_ALBUMS = "saved_albums"
    private const val KEY_LIKED_IDS = "liked_ids"
    // 未获云端确认的点赞操作：songId -> 期望状态(true=红心/false=取消)。写入成功且读回校验通过前，
    // 该歌保留在待同步表中，UI 据此显示"仅本地/待重试"，绝不在本地冒充"云端已同步"。
    private const val KEY_PENDING_SONGS = "pending_like_songs"

    // 收藏单曲分页加载：进页先拉首屏 BATCH 首详情渲染，滚动到底再补下一批。
    const val LIKED_BATCH_SIZE = 50

    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongItem>>() {}.type
    private val albumListType = object : TypeToken<List<AlbumInfo>>() {}.type
    private val idListType = object : TypeToken<List<Long>>() {}.type
    private val pendingSongsType = object : TypeToken<MutableMap<Long, Boolean>>() {}.type

    @Volatile private var cachedSongs: MutableList<SongItem>? = null
    private val songsLock = Any()
    @Volatile private var cachedAlbums: MutableList<AlbumInfo>? = null
    private val albumsLock = Any()
    // 红心歌单全部单曲 id（有序），作为分页加载的底表。
    @Volatile private var cachedLikedIds: List<Long>? = null
    private val idsLock = Any()
    // 未获云端确认的点赞操作（见 KEY_PENDING_SONGS）。
    @Volatile private var cachedPendingSongs: MutableMap<Long, Boolean>? = null
    private val pendingLock = Any()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null
    private var pendingAppContext: Context? = null
    private val flushLock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun ensureSongsLoaded(context: Context): MutableList<SongItem> {
        val current = cachedSongs
        if (current != null) return current
        return synchronized(songsLock) {
            cachedSongs ?: run {
                val parsed = runCatching {
                    val json = prefs(context).getString(KEY_SONGS, null)
                    if (json.isNullOrEmpty()) emptyList<SongItem>()
                    else (gson.fromJson<List<SongItem>>(json, songListType) ?: emptyList())
                }.getOrDefault(emptyList())
                cachedSongs = parsed.toMutableList()
                parsed.toMutableList()
            }
        }
    }

    private fun ensureAlbumsLoaded(context: Context): MutableList<AlbumInfo> {
        val current = cachedAlbums
        if (current != null) return current
        return synchronized(albumsLock) {
            cachedAlbums ?: run {
                val parsed = runCatching {
                    val json = prefs(context).getString(KEY_ALBUMS, null)
                    if (json.isNullOrEmpty()) emptyList<AlbumInfo>()
                    else (gson.fromJson<List<AlbumInfo>>(json, albumListType) ?: emptyList())
                }.getOrDefault(emptyList())
                cachedAlbums = parsed.toMutableList()
                parsed.toMutableList()
            }
        }
    }

    private fun ensureLikedIdsLoaded(context: Context): List<Long> {
        val current = cachedLikedIds
        if (current != null) return current
        return synchronized(idsLock) {
            cachedLikedIds ?: run {
                val parsed = runCatching {
                    val json = prefs(context).getString(KEY_LIKED_IDS, null)
                    if (json.isNullOrEmpty()) emptyList<Long>()
                    else (gson.fromJson<List<Long>>(json, idListType) ?: emptyList())
                }.getOrDefault(emptyList())
                cachedLikedIds = parsed
                parsed
            }
        }
    }

    private fun ensurePendingLoaded(context: Context): MutableMap<Long, Boolean> {
        val current = cachedPendingSongs
        if (current != null) return current
        return synchronized(pendingLock) {
            cachedPendingSongs ?: run {
                val parsed = runCatching {
                    val json = prefs(context).getString(KEY_PENDING_SONGS, null)
                    if (json.isNullOrEmpty()) mutableMapOf<Long, Boolean>()
                    else (gson.fromJson<MutableMap<Long, Boolean>>(json, pendingSongsType)
                        ?: mutableMapOf())
                }.getOrDefault(mutableMapOf())
                cachedPendingSongs = parsed
                parsed
            }
        }
    }

    private fun scheduleFlush(context: Context) {
        synchronized(flushLock) {
            pendingAppContext = context.applicationContext
            flushJob?.cancel()
            flushJob = ioScope.launch {
                delay(300L)
                flushToDisk()
            }
        }
    }

    private fun flushToDisk() {
        val ctx: Context
        val songsSnapshot: List<SongItem>
        val albumsSnapshot: List<AlbumInfo>
        val likedIdsSnapshot: List<Long>
        val pendingSnapshot: Map<Long, Boolean>
        synchronized(flushLock) {
            ctx = pendingAppContext ?: return
            pendingAppContext = null
        }

        synchronized(songsLock) { songsSnapshot = cachedSongs?.toList() ?: emptyList() }
        synchronized(albumsLock) { albumsSnapshot = cachedAlbums?.toList() ?: emptyList() }
        synchronized(idsLock) { likedIdsSnapshot = cachedLikedIds?.toList() ?: emptyList() }
        synchronized(pendingLock) { pendingSnapshot = cachedPendingSongs?.toMap() ?: emptyMap() }
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_SONGS, gson.toJson(songsSnapshot))
                .putString(KEY_ALBUMS, gson.toJson(albumsSnapshot))
                .putString(KEY_LIKED_IDS, gson.toJson(likedIdsSnapshot))
                .putString(KEY_PENDING_SONGS, gson.toJson(pendingSnapshot))
                .apply()
        }
    }

    /**
     * 预热内存缓存：在 IO 线程把收藏单曲/专辑/红心 id 从 SharedPreferences 解析进内存。
     * 避免首次在组合期（主线程）调用 [getSavedSongs] / [isSongSaved] 时同步读盘 + Gson 卡顿。
     */
    fun preload(context: Context) {
        val app = context.applicationContext
        ioScope.launch {
            ensureSongsLoaded(app)
            ensureAlbumsLoaded(app)
            ensureLikedIdsLoaded(app)
            ensurePendingLoaded(app)
        }
    }

    private fun isLoggedIn(context: Context) = CookieManager.hasCookie(context)

    private fun markPending(context: Context, songId: Long, desired: Boolean?) {
        val pending = ensurePendingLoaded(context)
        synchronized(pendingLock) {
            if (desired == null) pending.remove(songId) else pending[songId] = desired
        }
        scheduleFlush(context)
    }

    /** 未获云端确认的点赞操作（songId -> 期望红心状态），供 UI 显示"仅本地/待重试"。 */
    fun getPendingSyncSongIds(context: Context): Set<Long> =
        synchronized(pendingLock) { ensurePendingLoaded(context).keys.toSet() }

    fun isSongPendingSync(context: Context, songId: Long): Boolean =
        synchronized(pendingLock) { ensurePendingLoaded(context).containsKey(songId) }

    /**
     * 推送一次点赞/取消到云端。返回 true 仅当服务端业务码成功**且**随后的读回校验一致。
     * 结果决定 [markPending]：不一致则保留待同步标记，绝不冒充"云端已同步"。
     */
    private suspend fun pushLikeBlocking(context: Context, songId: Long, like: Boolean): Boolean {
        markPending(context, songId, like)
        val startedAt = System.currentTimeMillis()
        val ok = runCatching { PlaylistApi.likeSong(songId, like) }.getOrDefault(false)
        Log.i(
            TAG,
            "pushLike songId=$songId like=$like ok=$ok elapsedMs=${System.currentTimeMillis() - startedAt}"
        )
        if (!ok) return false
        val verified = verifyLikeState(context, songId, like)
        if (verified) markPending(context, songId, null)
        else Log.w(TAG, "pushLike read-back mismatch songId=$songId like=$like, keep pending")
        return verified
    }

    private fun pushLike(context: Context, songId: Long, like: Boolean) {
        val app = context.applicationContext
        ioScope.launch { pushLikeBlocking(app, songId, like) }
    }

    /**
     * 写后读回校验：重新读红心歌单 ids，检查目标歌是否落在期望状态。
     * 读端失败视为"未确认"（保留待同步），不误判为成功。
     */
    private suspend fun verifyLikeState(context: Context, songId: Long, like: Boolean): Boolean {
        return try {
            val uid = PlaylistApi.getCurrentUserId()
            when (val result = PlaylistApi.getLikedTrackIds(uid)) {
                is PlaylistApi.LikedIdsResult.Success -> (songId in result.ids) == like
                is PlaylistApi.LikedIdsResult.Failure -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "verifyLikeState failed songId=$songId: ${e.message}")
            false
        }
    }

    /**
     * 重试所有待同步的点赞操作（用户手动触发或后台补偿）。
     * 依据当前本地收藏状态决定期望方向：在本地收藏中 → 红心；否则 → 取消红心。
     */
    suspend fun retryPendingSync(context: Context) = withContext(Dispatchers.IO) {
        if (!isLoggedIn(context)) return@withContext
        val ids = getPendingSyncSongIds(context)
        if (ids.isEmpty()) return@withContext
        for (songId in ids) {
            val desired = isSongSaved(context, songId)
            pushLikeBlocking(context, songId, desired)
        }
    }

    private fun pushSubAlbum(albumId: Long, sub: Boolean) {
        ioScope.launch {
            val ok = runCatching { PlaylistApi.subAlbum(albumId, sub) }.getOrDefault(false)
            if (!ok) Log.w(TAG, "subAlbum not confirmed albumId=$albumId sub=$sub")
        }
    }

    // ==================== 单曲（收藏） ====================

    /** 收藏（收藏）单曲：先落本地缓存，再异步同步到云端。 */
    fun saveSong(context: Context, song: SongItem) {
        val songs = ensureSongsLoaded(context)
        val added: Boolean
        synchronized(songsLock) {
            added = songs.none { it.id == song.id }
            if (added) songs.add(0, song)
        }
        if (added) {
            scheduleFlush(context)
            if (isLoggedIn(context)) pushLike(context, song.id, true)
        }
    }

    fun saveSongs(context: Context, newSongs: List<SongItem>) {
        val songs = ensureSongsLoaded(context)
        val added = mutableListOf<SongItem>()
        synchronized(songsLock) {
            for (song in newSongs) {
                if (songs.none { it.id == song.id }) {
                    songs.add(0, song)
                    added.add(song)
                }
            }
        }
        if (added.isNotEmpty()) {
            scheduleFlush(context)
            if (isLoggedIn(context)) added.forEach { pushLike(context, it.id, true) }
        }
    }

    /** 取消收藏（或为取消收藏）单曲：移除本地缓存，异步同步云端。 */
    fun removeSong(context: Context, songId: Long) {
        val songs = ensureSongsLoaded(context)
        val removed: Boolean
        synchronized(songsLock) {
            removed = songs.removeAll { it.id == songId }
        }
        if (removed) {
            scheduleFlush(context)
            if (isLoggedIn(context)) pushLike(context, songId, false)
        }
    }

    fun getSavedSongs(context: Context): List<SongItem> {
        val songs = ensureSongsLoaded(context)
        return synchronized(songsLock) { songs.toList() }
    }

    fun isSongSaved(context: Context, songId: Long): Boolean {
        val songs = ensureSongsLoaded(context)
        return synchronized(songsLock) { songs.any { it.id == songId } }
    }

    fun getSongsByAlbumId(context: Context, albumId: Long): List<SongItem> {
        val songs = ensureSongsLoaded(context)
        return synchronized(songsLock) { songs.filter { it.album?.id == albumId } }
    }

    // ==================== 专辑（云端收藏） ====================

    /** 收藏页「专辑」栏：返回云端「收藏的专辑」（album_sublist 缓存）。纯云端，不派生。 */
    fun getSavedAlbums(context: Context): List<AlbumInfo> {
        val albums = ensureAlbumsLoaded(context)
        return synchronized(albumsLock) { albums.toList() }
    }

    /** 收藏专辑（订阅云端）：先落本地缓存，再异步同步云端。 */
    fun subscribeAlbum(context: Context, album: AlbumInfo) {
        val albums = ensureAlbumsLoaded(context)
        synchronized(albumsLock) {
            albums.removeAll { it.albumId == album.albumId }
            albums.add(0, album)
        }
        scheduleFlush(context)
        if (isLoggedIn(context)) pushSubAlbum(album.albumId, true)
    }

    /** 取消收藏专辑：仅移除专辑订阅（不影响收藏单曲，二者解耦）。 */
    fun removeAlbum(context: Context, albumId: Long) {
        val albums = ensureAlbumsLoaded(context)
        val removed: Boolean
        synchronized(albumsLock) {
            removed = albums.removeAll { it.albumId == albumId }
        }
        if (removed) {
            scheduleFlush(context)
            if (isLoggedIn(context)) pushSubAlbum(albumId, false)
        }
    }

    // ==================== 云端同步 ====================

    /**
     * 从云端拉取收藏单曲 + 收藏专辑刷新本地缓存（云端为真源）。
     * 未登录返回 false。返回是否成功拉取（供调用方判断是否需要提示）。
     */
    suspend fun refreshFromCloud(context: Context): Boolean = withContext(Dispatchers.IO) {
        if (!CookieManager.hasCookie(context)) {
            Log.w(TAG, "refreshFromCloud: no cookie, skip")
            return@withContext false
        }
        val uid: Long = try {
            PlaylistApi.getCurrentUserId()
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: getCurrentUserId failed: ${e.message}")
            return@withContext false
        }

        // 单曲与专辑各自独立尝试、独立提交：任一步失败不致整体放弃。
        var anySuccess = false

        // 单曲：先拉全量有序 trackIds 作底表，只需首屏分批详情即可渲染(懒加载)。
        // 关键：只有服务端**权威确认**（Success）才覆盖本地；Failure（网络/业务码/结构异常）
        // 时保留现有本地收藏，绝不把一次抖动折叠成空列表覆盖用户数据。
        when (val liked = PlaylistApi.getLikedTrackIds(uid)) {
            is PlaylistApi.LikedIdsResult.Failure -> {
                Log.w(TAG, "refreshFromCloud: liked read failed (${liked.reason}); keep local cache")
            }
            is PlaylistApi.LikedIdsResult.Success -> {
                val likedIds = liked.ids
                Log.i(TAG, "refreshFromCloud: likedIds=${likedIds.size}")
                synchronized(idsLock) { cachedLikedIds = likedIds }
                val firstBatch = if (likedIds.size > LIKED_BATCH_SIZE) likedIds.take(LIKED_BATCH_SIZE) else likedIds
                val firstSongs = runCatching {
                    if (firstBatch.isNotEmpty()) PlaylistApi.getSongsByIds(firstBatch) else emptyList()
                }.getOrDefault(emptyList())
                // 详情批量拉取失败时不要用空列表覆盖（firstBatch 非空却拿到空 = 异常）；
                // 云端确认"收藏为空"（firstBatch 为空）时才允许清空。
                if (firstBatch.isEmpty() || firstSongs.isNotEmpty()) {
                    synchronized(songsLock) { cachedSongs = firstSongs.toMutableList() }
                } else {
                    Log.w(TAG, "refreshFromCloud: liked detail fetch empty for ${firstBatch.size} ids, keep local")
                }
                reconcilePending(context, likedIds)
                anySuccess = true
            }
        }

        try {
            val cloudAlbums = PlaylistApi.getSubscribedAlbums()
            Log.i(TAG, "refreshFromCloud: albums=${cloudAlbums.size}")
            synchronized(albumsLock) {
                cachedAlbums = cloudAlbums.map {
                    AlbumInfo(
                        albumId = it.albumId,
                        name = it.name,
                        picUrl = it.picUrl,
                        artist = it.artist,
                        songCount = it.songCount
                    )
                }.toMutableList()
            }
            anySuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: albums failed: ${e.message}")
        }

        if (anySuccess) scheduleFlush(context)
        anySuccess
    }

    /**
     * 用云端权威红心集合收敛待同步表：期望状态与云端一致的操作视为已确认，移出待同步。
     * 不一致的（写失败 / 最终一致尚未生效 / 取消被拒）继续保留，等待重试。
     */
    private fun reconcilePending(context: Context, cloudLikedIds: List<Long>) {
        val cloudSet = cloudLikedIds.toHashSet()
        val pending = ensurePendingLoaded(context)
        var resolved = 0
        synchronized(pendingLock) {
            val it = pending.entries.iterator()
            while (it.hasNext()) {
                val (id, desired) = it.next()
                if (desired == (id in cloudSet)) {
                    it.remove()
                    resolved++
                }
            }
        }
        if (resolved > 0) {
            Log.i(TAG, "refreshFromCloud: reconciled $resolved pending like ops")
            scheduleFlush(context)
        }
    }

    /** 红心歌单全部单曲 id（有序），供收藏页做分页懒加载的底表。 */
    fun getLikedSongIds(context: Context): List<Long> = ensureLikedIdsLoaded(context)

    /**
     * 分页拉取下一批收藏单曲详情（滚动到底时调用）。会追加进本地缓存并返回新渲染项。
     */
    suspend fun loadMoreLikedSongs(context: Context): List<SongItem> = withContext(Dispatchers.IO) {
        val allIds = ensureLikedIdsLoaded(context)
        val loaded = synchronized(songsLock) { cachedSongs?.size ?: 0 }
        if (allIds.isEmpty() || loaded >= allIds.size) return@withContext emptyList()
        val sliceEnd = minOf(loaded + LIKED_BATCH_SIZE, allIds.size)
        val slice = allIds.subList(loaded, sliceEnd)
        val fetched = try { PlaylistApi.getSongsByIds(slice) } catch (e: Exception) {
            Log.e(TAG, "loadMoreLikedSongs failed: ${e.message}")
            return@withContext emptyList()
        }
        synchronized(songsLock) {
            val songs = cachedSongs ?: mutableListOf()
            val seen = songs.mapTo(mutableSetOf()) { it.id }
            for (s in fetched) if (s.id !in seen) songs.add(s)
            songs.toList()
        }.also { scheduleFlush(context) }
    }

    private const val TAG = "LibraryManager"
}

@Immutable
data class AlbumInfo(
    val albumId: Long,
    val name: String,
    val picUrl: String,
    val artist: String,
    val songCount: Int
)
