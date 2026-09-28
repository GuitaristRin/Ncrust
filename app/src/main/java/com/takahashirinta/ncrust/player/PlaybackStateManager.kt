package com.takahashirinta.ncrust.player

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
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

    // 会话信封的键。必须显式拼写、与 PlaybackSession 的字段签名解耦：PlaybackSession
    // 不在 -keep 规则里，R8 会剪掉它的字段 Signature 注解；若让 Gson 反射整个类，
    // 反序列化只看得到裸 List，队列元素全部变成 LinkedTreeMap，恢复会话时
    // ClassCastException 闪退（android-v1.3.2 发布包的启动闪退即此因）。
    // 队列元素类型由 songListType 的匿名 TypeToken 携带 —— 匿名类被 proguard 规则
    // keep，release 下泛型签名完好；LibraryManager / LyricsCache 是同一模式。
    private const val JSON_QUEUE = "queue"
    private const val JSON_QUEUE_INDEX = "queueIndex"
    private const val JSON_PLAY_MODE = "playMode"
    private const val JSON_FM_MODE = "fmMode"
    private const val JSON_SHUFFLED_INDICES = "shuffledIndices"
    private const val JSON_SHUFFLED_POSITION = "shuffledPosition"

    // android-v1.3.2 首版以混淆后的字段名落盘（queue→a、queueIndex→b、playMode→c、
    // fmMode→d、shuffledIndices→e、shuffledPosition→f）。读取时兼容一次，让升级
    // 用户上一次写下的会话仍能恢复；新写入立即使用显式键。
    private const val LEGACY_QUEUE = "a"
    private const val LEGACY_QUEUE_INDEX = "b"
    private const val LEGACY_PLAY_MODE = "c"
    private const val LEGACY_FM_MODE = "d"
    private const val LEGACY_SHUFFLED_INDICES = "e"
    private const val LEGACY_SHUFFLED_POSITION = "f"

    // 复用一个 Gson 实例：new Gson() 会构建反射映射表，几百首歌频繁调用时反射初始化非常热。
    // Gson 本身线程安全。
    private val gson = Gson()

    // 队列元素类型只能由这里的匿名 TypeToken 携带；不要改成 Class 字面量，
    // 也不要依赖 PlaybackSession 的字段签名（release 下被 R8 剪掉，见信封键注释）。
    private val songListType = object : TypeToken<List<SongItem>>() {}.type

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
            val json = withContext(Dispatchers.Default) { serializeSession(session) }
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
            withContext(Dispatchers.Default) { parseSession(json) }
        } catch (e: Exception) {
            clearSession(context)
            null
        }
    }

    // ---------- 会话信封序列化（显式键，不依赖任何 data class 的字段签名） ----------

    internal fun serializeSession(session: PlaybackSession): String {
        val obj = JsonObject()
        // 队列经显式 TypeToken 转 JsonElement 后整体嵌入信封，保持单一原子键。
        obj.add(JSON_QUEUE, gson.toJsonTree(session.queue, songListType))
        obj.addProperty(JSON_QUEUE_INDEX, session.queueIndex)
        obj.addProperty(JSON_PLAY_MODE, session.playMode)
        obj.addProperty(JSON_FM_MODE, session.fmMode)
        // Int 列表序列化不依赖泛型信息（数字字面量自描述），可直接转。
        obj.add(JSON_SHUFFLED_INDICES, gson.toJsonTree(session.shuffledIndices))
        obj.addProperty(JSON_SHUFFLED_POSITION, session.shuffledPosition)
        return obj.toString()
    }

    internal fun parseSession(json: String): PlaybackSession? {
        val obj = JsonParser.parseString(json).asJsonObject
        val legacy = !obj.has(JSON_QUEUE)
        fun pick(newKey: String, legacyKey: String): JsonElement? =
            obj.get(if (legacy) legacyKey else newKey)?.takeUnless { it.isJsonNull }

        val queue: List<SongItem> = gson.fromJson(
            pick(JSON_QUEUE, LEGACY_QUEUE) ?: return null,
            songListType
        ) ?: return null
        val shuffledIndices = pick(JSON_SHUFFLED_INDICES, LEGACY_SHUFFLED_INDICES)
            ?.let { if (it.isJsonArray) it.asJsonArray.map { e -> e.asInt } else emptyList() }
            ?: emptyList()
        return PlaybackSession(
            queue = queue,
            queueIndex = pick(JSON_QUEUE_INDEX, LEGACY_QUEUE_INDEX)?.asInt ?: -1,
            playMode = pick(JSON_PLAY_MODE, LEGACY_PLAY_MODE)?.asInt ?: 0,
            fmMode = pick(JSON_FM_MODE, LEGACY_FM_MODE)?.asBoolean ?: false,
            shuffledIndices = shuffledIndices,
            shuffledPosition = pick(JSON_SHUFFLED_POSITION, LEGACY_SHUFFLED_POSITION)?.asInt ?: 0
        )
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
