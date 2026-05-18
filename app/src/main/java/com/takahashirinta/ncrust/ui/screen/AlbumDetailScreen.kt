package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumDetail
import com.takahashirinta.ncrust.network.model.AlbumSongItem
import com.takahashirinta.ncrust.ui.components.DetailHeader
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.PlayAllDialog
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import android.widget.Toast

@Composable
fun AlbumDetailScreen(
    albumId: Long,
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onInsertNext: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> }
) {
    var album by remember { mutableStateOf<AlbumDetail?>(null) }
    var songs by remember { mutableStateOf<List<AlbumSongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPlayAllDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val strings = LocalStrings.current

    LaunchedEffect(albumId) {
        isLoading = true
        error = null
        try {
            val response = RetrofitClient.api.getAlbumDetail(albumId)
            album = response.album
            songs = response.songs ?: emptyList()
        } catch (e: Exception) {
            error = strings.loadFailed(e.message)
        } finally {
            isLoading = false
        }
    }

    val songItems = remember(songs) {
        songs.map { s -> SongItem(id = s.id, name = s.name, artists = s.artists, album = s.album, duration = s.getDurationMs()) }
    }

    if (showPlayAllDialog) {
        PlayAllDialog(
            songCount = songItems.size,
            onDismiss = { showPlayAllDialog = false },
            onReplaceAndPlay = { onReplaceAndPlay(songItems) },
            onInsertNext = { onInsertNext(songItems) }
        )
    }

    DetailScaffold(
        title = strings.albumDetailTitle,
        onBack = onBack,
        isLoading = isLoading,
        error = error,
        onRetry = null,
        header = {
            DetailHeader(
                coverUrl = album?.picUrl,
                title = album?.name ?: "",
                subtitle = album?.artist?.name,
                infoLines = buildList {
                    album?.publishTime?.let { time ->
                        val date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
                            .format(java.util.Date(time))
                        add(strings.albumReleaseDate(date))
                    }
                    album?.company?.let { add(strings.albumLabel(it)) }
                    add(strings.trackCountSongs(songs.size))
                },
                onPlayAll = if (songItems.isNotEmpty()) ({ showPlayAllDialog = true }) else null
            )
        },
        content = {
            items(songs) { song ->
                val songItem = SongItem(
                    id = song.id,
                    name = song.name,
                    artists = song.artists,
                    album = song.album,
                    duration = song.getDurationMs()
                )
                SongCard(
                    song = songItem,
                    style = SongCardStyle.COMPACT,
                    onClick = { onSongClick(songItem) },
                    onShowMenu = {
                        onShowSongMenu(songItem, listOf(
                            SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                LibraryManager.saveSong(context, songItem)
                                Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                            },
                            SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) {
                                onSongInsertNext(songItem)
                            },
                            SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) {
                                onSongAppendToQueue(songItem)
                            }
                        ))
                    }
                )
            }
        }
    )
}
