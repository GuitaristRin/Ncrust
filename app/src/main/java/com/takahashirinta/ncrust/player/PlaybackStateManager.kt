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

    // 完整播放会话 key（队列 + 索引 + 模式 + FM + 乱序序列，一次原子写盘）。
    private const val KEY_SESSION = "session"

    // 复用一个 Gson 实例：new Gson() 会构建反射映射表，几百首歌频繁调用时反射初始化非常热。
    // Gson 本身线程安全。
    private val gson = Gson()
    private val sessionType = object : TypeToken<PlaybackSession>() {}.type

    /**
     * 一次完整的播放会话。队列、当前索引、播放模式、FM 标记、乱序序列作为一个整体
     * 原子持久化/恢复——进程被杀重启后它们要么全部恢复、要么全部不恢复，不会出现
     * 「队列恢复了但模式丢了 / FM 退出了 / 乱序序列与队列对不上」这类发散状态。
     *
     * 当前播放曲目由 queue[queueIndex] 派生（播放时二者恒等）；仅当队列为空
     * （剪贴板单曲等场景）时才回退到 [SavedState] 的单曲字段。
     */
    data class PlaybackSession(
        val queue: List<SongItem> = emptyList(),
        val queueIndex: Int = -1,
        val playMode: Int = 0,
        val fmMode: Boolean = false,
        val shuffledIndices: List<Int> = emptyList(),
        val shuffledPosition: Int = 0
    )

    // 会话写盘 debounce：连续 addToQueue / insertNext / removeFromQueue 会累计触发。
    // 200 ms 合并一次能把连续 20 首歌的加入压成 1 次 IO，避免主线程 Gson.toJson 抖动。
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var pendingSession: PlaybackSession? = null
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

    // ---------- 播放会话持久化（原子） ----------
    fun saveSession(context: Context, session: PlaybackSession) {
        synchronized(flushLock) {
            pendingSession = session
            pendingAppContext = context.applicationContext
            flushJob?.cancel()
            flushJob = ioScope.launch {
                delay(200L)
                flushPendingSession()
            }
        }
    }

    private suspend fun flushPendingSession() {
        val session: PlaybackSession
        val ctx: Context
        synchronized(flushLock) {
            session = pendingSession ?: return
            ctx = pendingAppContext ?: return
            pendingSession = null
            pendingAppContext = null
        }
        try {
            // Gson 反射序列化是 CPU 密集，切 Default 避免 IO 线程池被占。
            val json = withContext(Dispatchers.Default) { gson.toJson(session, sessionType) }
            withContext(Dispatchers.IO) {
                ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
                    .putString(KEY_SESSION, json)
                    .apply()
            }
        } catch (_: Exception) {
            clearSession(ctx)
        }
    }

    suspend fun getSession(context: Context): PlaybackSession? {
        val json = getPrefs(context).getString(KEY_SESSION, null) ?: return null
        if (json.isEmpty()) return null
        return try {
            // Gson 反射反序列化是 CPU 密集，切 Default 避免主线程卡顿（队列几百首时 50ms+）。
            withContext(Dispatchers.Default) {
                gson.fromJson(json, sessionType) as PlaybackSession?
            }
        } catch (e: Exception) {
            clearSession(context)
            null
        }
    }

    fun clearSession(context: Context) {
        // 也取消任何飞行中的 debounce 写，避免 clearSession 之后又被延迟写覆盖回去
        synchronized(flushLock) {
            flushJob?.cancel()
            pendingSession = null
            pendingAppContext = null
        }
        getPrefs(context).edit().remove(KEY_SESSION).apply()
    }
}
