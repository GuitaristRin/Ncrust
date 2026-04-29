package com.takahashirinta.ncrust.player

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.network.SongItem

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
        val json = Gson().toJson(queue)
        getPrefs(context).edit()
            .putString(KEY_QUEUE, json)
            .putInt(KEY_QUEUE_INDEX, currentIndex)
            .apply()
    }

    fun getQueue(context: Context): Pair<List<SongItem>, Int>? {
        val prefs = getPrefs(context)
        val json = prefs.getString(KEY_QUEUE, null) ?: return null
        if (json.isEmpty() || json == "[]") return null
        return try {
            val type = object : TypeToken<List<SongItem>>() {}.type
            val queue: List<SongItem> = Gson().fromJson(json, type)
            val index = prefs.getInt(KEY_QUEUE_INDEX, 0)
            Pair(queue, index)
        } catch (e: Exception) {
            null
        }
    }

    fun clearQueue(context: Context) {
        getPrefs(context).edit()
            .remove(KEY_QUEUE)
            .remove(KEY_QUEUE_INDEX)
            .apply()
    }
}