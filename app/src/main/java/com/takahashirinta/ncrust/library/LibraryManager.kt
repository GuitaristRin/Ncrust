package com.takahashirinta.ncrust.library

import android.content.Context
import androidx.compose.runtime.Immutable
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.network.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

object LibraryManager {
    private const val PREFS_NAME = "ncrust_library"
    private const val KEY_SONGS = "saved_songs"

    // 内存缓存：首次调用时从 SharedPreferences 反序列化一次，后续所有读写都命中内存。
    // 旧实现每次 saveSong 都要 fromJson + toJson 一份完整列表，收藏 500 首后单次 100+ ms 冻结主线程。
    // 单例保存 SongItem 引用，不做深拷贝——SongItem 由 Gson 反序列化得到，不应被外部就地修改。
    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongItem>>() {}.type

    @Volatile private var cachedSongs: MutableList<SongItem>? = null
    private val cacheLock = Any()

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var flushJob: Job? = null
    private var pendingAppContext: Context? = null
    private val flushLock = Any()

    private fun ensureLoaded(context: Context): MutableList<SongItem> {
        val current = cachedSongs
        if (current != null) return current
        return synchronized(cacheLock) {
            val again = cachedSongs
            if (again != null) return@synchronized again
            val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_SONGS, null)
            val list: MutableList<SongItem> = if (json.isNullOrEmpty()) {
                mutableListOf()
            } else {
                try {
                    val parsed: List<SongItem>? = gson.fromJson(json, songListType)
                    parsed?.toMutableList() ?: mutableListOf()
                } catch (_: Exception) {
                    mutableListOf()
                }
            }
            cachedSongs = list
            list
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
        val snapshot: List<SongItem>
        synchronized(flushLock) {
            ctx = pendingAppContext ?: return
            pendingAppContext = null
        }
        synchronized(cacheLock) {
            val cached = cachedSongs ?: return
            snapshot = cached.toList()
        }
        try {
            val json = gson.toJson(snapshot)
            ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_SONGS, json).apply()
        } catch (_: Exception) {
            // 序列化失败几乎不可能发生，静默忽略
        }
    }

    // ==================== 单曲操作 ====================

    fun saveSong(context: Context, song: SongItem) {
        val songs = ensureLoaded(context)
        synchronized(cacheLock) {
            if (songs.none { it.id == song.id }) {
                songs.add(0, song)
                scheduleFlush(context)
            }
        }
    }

    fun saveSongs(context: Context, newSongs: List<SongItem>) {
        val songs = ensureLoaded(context)
        var changed = false
        synchronized(cacheLock) {
            for (song in newSongs) {
                if (songs.none { it.id == song.id }) {
                    songs.add(0, song)
                    changed = true
                }
            }
        }
        if (changed) scheduleFlush(context)
    }

    fun removeSong(context: Context, songId: Long) {
        val songs = ensureLoaded(context)
        val removed: Boolean
        synchronized(cacheLock) {
            removed = songs.removeAll { it.id == songId }
        }
        if (removed) scheduleFlush(context)
    }

    fun getSavedSongs(context: Context): List<SongItem> {
        val songs = ensureLoaded(context)
        // 返回快照，防止调用方边遍历边被后台修改而 ConcurrentModificationException
        return synchronized(cacheLock) { songs.toList() }
    }

    fun isSongSaved(context: Context, songId: Long): Boolean {
        val songs = ensureLoaded(context)
        return synchronized(cacheLock) { songs.any { it.id == songId } }
    }

    // ==================== 专辑操作（派生） ====================

    fun getSavedAlbums(context: Context): List<AlbumInfo> {
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

    fun getSongsByAlbumId(context: Context, albumId: Long): List<SongItem> {
        return getSavedSongs(context).filter { it.album?.id == albumId }
    }

    fun removeAlbum(context: Context, albumId: Long) {
        val songs = ensureLoaded(context)
        val removed: Boolean
        synchronized(cacheLock) {
            removed = songs.removeAll { it.album?.id == albumId }
        }
        if (removed) scheduleFlush(context)
    }
}

@Immutable
data class AlbumInfo(
    val albumId: Long,
    val name: String,
    val picUrl: String,
    val artist: String,
    val songCount: Int
)
