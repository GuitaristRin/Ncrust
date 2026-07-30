package com.takahashirinta.ncrust.player

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.network.SongItem
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object PlaybackStateManager {
    private const val PREFS_NAME = "ncrust_playback_state"
    private const val KEY_SONG_ID = "song_id"
    private const val KEY_SONG_NAME = "song_name"
    private const val KEY_SONG_ARTIST = "song_artist"
    private const val KEY_SONG_ARTWORK = "song_artwork"
    private const val KEY_IS_PLAYING = "is_playing"
    private const val KEY_HAS_STATE = "has_state"

    // 队列持久化 key
    private const val KEY_QUEUE = "queue"
    private const val KEY_QUEUE_INDEX = "queue_index"

    // 复用一个 Gson 实例：new Gson() 会构建反射映射表，几百首歌频繁调用时反射初始化非常热。
    // Gson 本身线程安全。
    private val gson = Gson()
    private val songListType = object : TypeToken<List<SongItem>>() {}.type

    // 队列写盘 debounce：连续 addToQueue / insertNext / removeFromQueue 会累计触发。
    // 200 ms 合并一次能把连续 20 首歌的加入压成 1 次 IO，避免主线程 Gson.toJson 抖动。
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingQueue: List<SongItem>? = null
    private var pendingIndex: Int = 0
    private var pendingAppContext: Context? = null
    private var flushJob: Job? = null
    private val flushLock = Any()

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ---------- 单曲状态 ----------
    fun saveState(context: Context, songId: Long, title: String, artist: String, artwork: String, isPlaying: Boolean) {
        getPrefs(context).edit()
            .putBoolean(KEY_HAS_STATE, true)
            .putLong(KEY_SONG_ID, songId)
            .putString(KEY_SONG_NAME, title)
            .putString(KEY_SONG_ARTIST, artist)
            .putString(KEY_SONG_ARTWORK, artwork)
            .putBoolean(KEY_IS_PLAYING, isPlaying)
            .apply()
    }

    fun updatePlayingState(context: Context, isPlaying: Boolean) {
        getPrefs(context).edit().putBoolean(KEY_IS_PLAYING, isPlaying).apply()
    }

    fun clearState(context: Context) {
        getPrefs(context).edit().clear().apply()
    }

    fun hasState(context: Context): Boolean {
        return getPrefs(context).getBoolean(KEY_HAS_STATE, false)
    }

    data class SavedState(
        val songId: Long,
        val songName: String,
        val songArtist: String,
        val songArtwork: String,
        val isPlaying: Boolean
    )

    fun getState(context: Context): SavedState? {
        val prefs = getPrefs(context)
        if (!prefs.getBoolean(KEY_HAS_STATE, false)) return null
        return SavedState(
            songId = prefs.getLong(KEY_SONG_ID, 0),
            songName = prefs.getString(KEY_SONG_NAME, "") ?: "",
            songArtist = prefs.getString(KEY_SONG_ARTIST, "") ?: "",
            songArtwork = prefs.getString(KEY_SONG_ARTWORK, "") ?: "",
            isPlaying = prefs.getBoolean(KEY_IS_PLAYING, false)
        )
    }

    // ---------- 队列持久化 ----------
    fun saveQueue(context: Context, queue: List<SongItem>, currentIndex: Int) {
        synchronized(flushLock) {
            pendingQueue = queue
            pendingIndex = currentIndex
            pendingAppContext = context.applicationContext
            flushJob?.cancel()
            flushJob = ioScope.launch {
                delay(200L)
                flushPendingQueue()
            }
        }
    }

    private suspend fun flushPendingQueue() {
        val queue: List<SongItem>
        val index: Int
        val ctx: Context
        synchronized(flushLock) {
            queue = pendingQueue ?: return
            index = pendingIndex
            ctx = pendingAppContext ?: return
            pendingQueue = null
            pendingAppContext = null
        }
        try {
            // Gson 反射序列化是 CPU 密集，切 Default 避免 IO 线程池被占。
            val json = withContext(Dispatchers.Default) { gson.toJson(queue) }
            withContext(Dispatchers.IO) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_QUEUE, json)
                    .putInt(KEY_QUEUE_INDEX, index)
                    .apply()
            }
        } catch (_: Exception) {
            clearQueue(ctx)
        }
    }

    suspend fun getQueue(context: Context): Pair<List<SongItem>, Int>? {
        val prefs = getPrefs(context)
        val json = prefs.getString(KEY_QUEUE, null) ?: return null
        if (json.isEmpty() || json == "[]") return null
        return try {
            // Gson 反射反序列化是 CPU 密集，切 Default 避免主线程卡顿（队列几百首时 50ms+）。
            val queue: List<SongItem> = withContext(Dispatchers.Default) {
                gson.fromJson(json, songListType)
            }
            val index = prefs.getInt(KEY_QUEUE_INDEX, 0)
            Pair(queue, index)
        } catch (e: Exception) {
            clearQueue(context)
            null
        }
    }

    fun clearQueue(context: Context) {
        // 也取消任何飞行中的 debounce 写，避免 clearQueue 之后又被延迟写覆盖回去
        synchronized(flushLock) {
            flushJob?.cancel()
            pendingQueue = null
            pendingAppContext = null
        }
        getPrefs(context).edit()
            .remove(KEY_QUEUE)
            .remove(KEY_QUEUE_INDEX)
            .apply()
    }
}
