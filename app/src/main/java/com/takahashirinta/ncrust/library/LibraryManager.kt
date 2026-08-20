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

    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongItem>>() {}.type
    private val albumListType = object : TypeToken<List<AlbumInfo>>() {}.type

    @Volatile private var cachedSongs: MutableList<SongItem>? = null
    private val songsLock = Any()
    @Volatile private var cachedAlbums: MutableList<AlbumInfo>? = null
    private val albumsLock = Any()

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
        synchronized(flushLock) {
            ctx = pendingAppContext ?: return
            pendingAppContext = null
        }
        synchronized(songsLock) { songsSnapshot = cachedSongs?.toList() ?: emptyList() }
        synchronized(albumsLock) { albumsSnapshot = cachedAlbums?.toList() ?: emptyList() }
        runCatching {
            prefs(ctx).edit()
                .putString(KEY_SONGS, gson.toJson(songsSnapshot))
                .putString(KEY_ALBUMS, gson.toJson(albumsSnapshot))
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

    /** 收藏页「专辑」栏：返回云端收藏的专辑（本地缓存）。未同步前回退为旧派生行为。 */
    fun getSavedAlbums(context: Context): List<AlbumInfo> {
        val albums = ensureAlbumsLoaded(context)
        synchronized(albumsLock) {
            if (albums.isNotEmpty()) return albums.toList()
        }
        // 尚未做过云端同步（本地无专辑缓存）时，回退到从收藏单曲派生，保证兼容旧数据。
        return getSavedAlbumsLegacy(context)
    }

    private fun getSavedAlbumsLegacy(context: Context): List<AlbumInfo> {
        val songs = getSavedSongs(context)
        val albumMap = linkedMapOf<Long, AlbumInfo>()
        for (song in songs) {
            val al = song.album ?: continue
            if (al.id == null) continue
            val albumId = al.id
            if (!albumMap.containsKey(albumId)) {
                albumMap[albumId] = AlbumInfo(
                    albumId = albumId,
                    name = al.name ?: "未知专辑",
                    picUrl = al.picUrl ?: "",
                    artist = song.artists?.firstOrNull()?.name ?: "未知歌手",
                    songCount = 1
                )
            } else {
                val existing = albumMap[albumId]!!
                albumMap[albumId] = existing.copy(songCount = existing.songCount + 1)
            }
        }
        return albumMap.values.toList()
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

        val likedSongs = try { PlaylistApi.getLikedSongs(uid) } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: getLikedSongs failed: ${e.message}")
            return@withContext false
        }
        val cloudAlbums = try { PlaylistApi.getSubscribedAlbums() } catch (e: Exception) {
            Log.e(TAG, "refreshFromCloud: getSubscribedAlbums failed: ${e.message}")
            return@withContext false
        }
        Log.i(TAG, "refreshFromCloud: uid=$uid liked=${likedSongs.size} albums=${cloudAlbums.size}")

        // 红心歌单详情已带完整元数据，直接整体重建缓存（云端为真源）。
        synchronized(songsLock) { cachedSongs = likedSongs.toMutableList() }
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
        scheduleFlush(context)
        true
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
