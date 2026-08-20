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

    // 收藏单曲分页加载：进页先拉首屏 BATCH 首详情渲染，滚动到底再补下一批。
    const val LIKED_BATCH_SIZE = 50

    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongItem>>() {}.type
    private val albumListType = object : TypeToken<List<AlbumInfo>>() {}.type
    private val idListType = object : TypeToken<List<Long>>() {}.type

    @Volatile private var cachedSongs: MutableList<SongItem>? = null
    private val songsLock = Any()
    @Volatile private var cachedAlbums: MutableList<AlbumInfo>? = null
    private val albumsLock = Any()
    // 红心歌单全部单曲 id（有序），作为分页加载的底表。
    @Volatile private var cachedLikedIds: List<Long>? = null
    private val idsLock = Any()

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
        synchronized(flushLock) {
            ctx = pendingAppContext ?: return
            pendingAppContext = null
        }

        synchronized(songsLock) { songsSnapshot = cachedSongs?.toList() ?: emptyList() }
        synchronized(albumsLock) { albumsSnapshot = cachedAlbums?.toList() ?: emptyList() }
        synchronized(idsLock) { likedIdsSnapshot = cachedLikedIds?.toList() ?: emptyList() }
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_SONGS, gson.toJson(songsSnapshot))
                .putString(KEY_ALBUMS, gson.toJson(albumsSnapshot))
                .putString(KEY_LIKED_IDS, gson.toJson(likedIdsSnapshot))
                .apply()
        }
    }

    private fun isLoggedIn(context: Context) = CookieManager.hasCookie(context)

    private fun pushLike(songId: Long, like: Boolean) {
        ioScope.launch { runCatching { PlaylistApi.likeSong(songId, like) } }
    }

    private fun pushSubAlbum(albumId: Long, sub: Boolean) {
        ioScope.launch { runCatching { PlaylistApi.subAlbum(albumId, sub) } }
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
            if (isLoggedIn(context)) pushLike(song.id, true)
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
            if (isLoggedIn(context)) added.forEach { pushLike(it.id, true) }
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
            if (isLoggedIn(context)) pushLike(songId, false)
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
        try {
            val likedIds = PlaylistApi.getLikedTrackIds(uid)
            Log.i(TAG, "refreshFromCloud: likedIds=${likedIds.size}")
            synchronized(idsLock) { cachedLikedIds = likedIds }
            val firstBatch = if (likedIds.size > LIKED_BATCH_SIZE) likedIds.take(LIKED_BATCH_SIZE) else likedIds
            val firstSongs = if (firstBatch.isNotEmpty()) PlaylistApi.getSongsByIds(firstBatch) else emptyList()
            synchronized(songsLock) { cachedSongs = firstSongs.toMutableList() }
            anySuccess = true
        } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: liked songs failed: ${e.message}")
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
        true
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
