package com.takahashirinta.ncrust.library

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.network.AlbumSearchItem
import com.takahashirinta.ncrust.network.ArtistSearchItem
import com.takahashirinta.ncrust.network.SongItem

object SearchHistoryManager {
    private const val PREFS_NAME = "search_history"
    private const val MAX_ITEMS = 10
    private val TTL_MS = 14L * 24 * 60 * 60 * 1000

    const val TYPE_SONG = 1
    const val TYPE_ALBUM = 10
    const val TYPE_ARTIST = 100

    data class HistoryItem(
        val id: Long,
        val title: String,
        val coverUrl: String?,
        val subtitle: String?,
        val timestamp: Long = System.currentTimeMillis()
    )

    fun addSong(context: Context, song: SongItem) = add(
        context, TYPE_SONG,
        HistoryItem(
            id = song.id,
            title = song.name,
            coverUrl = song.album?.picUrl,
            subtitle = song.artists?.firstOrNull()?.name
        )
    )

    fun addAlbum(context: Context, album: AlbumSearchItem) = add(
        context, TYPE_ALBUM,
        HistoryItem(
            id = album.id,
            title = album.name,
            coverUrl = album.picUrl,
            subtitle = album.artist?.name
        )
    )

    fun addArtist(context: Context, artist: ArtistSearchItem) = add(
        context, TYPE_ARTIST,
        HistoryItem(
            id = artist.id,
            title = artist.name,
            coverUrl = artist.picUrl,
            subtitle = null
        )
    )

    fun getSongs(context: Context) = getAll(context, TYPE_SONG)
    fun getAlbums(context: Context) = getAll(context, TYPE_ALBUM)
    fun getArtists(context: Context) = getAll(context, TYPE_ARTIST)

    fun remove(context: Context, type: Int, id: Long) {
        val key = keyFor(type)
        val items = load(context, key)
        items.removeAll { it.id == id }
        save(context, key, items)
    }

    fun clearSection(context: Context, type: Int) {
        save(context, keyFor(type), mutableListOf())
    }

    private fun add(context: Context, type: Int, item: HistoryItem) {
        val key = keyFor(type)
        val now = System.currentTimeMillis()
        val items = load(context, key)
        items.removeAll { now - it.timestamp > TTL_MS }
        items.removeAll { it.id == item.id }
        items.add(0, item.copy(timestamp = now))
        if (items.size > MAX_ITEMS) items.subList(MAX_ITEMS, items.size).clear()
        save(context, key, items)
    }

    private fun getAll(context: Context, type: Int): List<HistoryItem> {
        val key = keyFor(type)
        val now = System.currentTimeMillis()
        val items = load(context, key)
        val filtered = items.filter { now - it.timestamp <= TTL_MS }
        if (filtered.size != items.size) save(context, key, filtered.toMutableList())
        return filtered
    }

    private fun keyFor(type: Int) = when (type) {
        TYPE_SONG -> "songs"
        TYPE_ALBUM -> "albums"
        else -> "artists"
    }

    private fun load(context: Context, key: String): MutableList<HistoryItem> {
        val json = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(key, null) ?: return mutableListOf()
        return try {
            val type = object : TypeToken<MutableList<HistoryItem>>() {}.type
            Gson().fromJson(json, type) ?: mutableListOf()
        } catch (_: Exception) {
            mutableListOf()
        }
    }

    private fun save(context: Context, key: String, items: List<HistoryItem>) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(key, Gson().toJson(items)).apply()
    }
}
