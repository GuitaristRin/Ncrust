package com.takahashirinta.ncrust.lyric

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 一条缓存的歌词原文与译文（tlyric 可为空串）。 */
data class CachedLyrics(
    val lrc: String,
    val tlyric: String,
    val timestamp: Long
)

/**
 * 歌词本地缓存。
 *
 * 目的：进程被杀后重进时，无需再等网络即可立即恢复当前歌歌词，消除
 * 「冷启动歌词按钮灰掉 / 歌词消失、必须切歌才回来」的问题。
 *
 * 存储：SharedPreferences 内一个 JSON map（songId -> CachedLyrics）。
 * 内存镜像避免重复读盘；容量上限 [MAX_ENTRIES]，超出按 timestamp 淘汰最旧。
 * 只缓存服务端 code==200 的权威结果（含"确无歌词"的空串），失败不写，
 * 这样下次重试仍会打网络而不是命中一个假的空结果。
 */
object LyricsCache {
    private const val PREFS = "ncrust_lyrics_cache"
    private const val KEY_ENTRIES = "entries"
    private const val MAX_ENTRIES = 200

    private val gson = Gson()
    private val type = object : TypeToken<MutableMap<String, CachedLyrics>>() {}.type
    private val lock = Any()

    @Volatile
    private var memory: MutableMap<String, CachedLyrics>? = null

    private fun loadLocked(context: Context): MutableMap<String, CachedLyrics> {
        memory?.let { return it }
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ENTRIES, null)
        val map = if (raw.isNullOrEmpty()) {
            mutableMapOf()
        } else {
            runCatching { gson.fromJson<MutableMap<String, CachedLyrics>>(raw, type) }
                .getOrNull() ?: mutableMapOf()
        }
        memory = map
        return map
    }

    suspend fun get(context: Context, songId: Long): CachedLyrics? = withContext(Dispatchers.IO) {
        synchronized(lock) { loadLocked(context)[songId.toString()] }
    }

    suspend fun put(context: Context, songId: Long, lrc: String, tlyric: String) {
        withContext(Dispatchers.IO) {
            synchronized(lock) {
                val map = loadLocked(context)
                map[songId.toString()] = CachedLyrics(lrc, tlyric, System.currentTimeMillis())
                if (map.size > MAX_ENTRIES) {
                    map.entries
                        .sortedBy { it.value.timestamp }
                        .take(map.size - MAX_ENTRIES)
                        .forEach { map.remove(it.key) }
                }
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_ENTRIES, gson.toJson(map))
                    .apply()
            }
        }
    }
}
