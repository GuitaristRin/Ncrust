package com.takahashirinta.ncrust.library

import android.content.Context
import android.content.SharedPreferences
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.takahashirinta.ncrust.network.SongItem

object LibraryManager {
    private const val PREFS_NAME = "ncrust_library"
    private const val KEY_SONGS = "saved_songs"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    // ==================== 单曲操作 ====================

    fun saveSong(context: Context, song: SongItem) {
        val songs = getSavedSongs(context).toMutableList()
        if (songs.none { it.id == song.id }) {
            songs.add(0, song)
            saveSongList(context, songs)
        }
    }

    fun saveSongs(context: Context, newSongs: List<SongItem>) {
        val songs = getSavedSongs(context).toMutableList()
        for (song in newSongs) {
            if (songs.none { it.id == song.id }) {
                songs.add(0, song)
            }
        }
        saveSongList(context, songs)
    }

    fun removeSong(context: Context, songId: Long) {
        val songs = getSavedSongs(context).toMutableList()
        songs.removeAll { it.id == songId }
        saveSongList(context, songs)
    }

    fun getSavedSongs(context: Context): List<SongItem> {
        val json = getPrefs(context).getString(KEY_SONGS, null) ?: return emptyList()
        val type = object : TypeToken<List<SongItem>>() {}.type
        return try {
            Gson().fromJson(json, type)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun isSongSaved(context: Context, songId: Long): Boolean {
        return getSavedSongs(context).any { it.id == songId }
    }

    private fun saveSongList(context: Context, songs: List<SongItem>) {
        val json = Gson().toJson(songs)
        getPrefs(context).edit().putString(KEY_SONGS, json).apply()
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
        val songs = getSavedSongs(context).toMutableList()
        songs.removeAll { it.album?.id == albumId }
        saveSongList(context, songs)
    }
}

data class AlbumInfo(
    val albumId: Long,
    val name: String,
    val picUrl: String,
    val artist: String,
    val songCount: Int
)