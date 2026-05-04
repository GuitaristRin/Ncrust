package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
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

@Composable
fun AlbumDetailScreen(
    albumId: Long,
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onInsertNext: (List<SongItem>) -> Unit = {}
) {
    var album by remember { mutableStateOf<AlbumDetail?>(null) }
    var songs by remember { mutableStateOf<List<AlbumSongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPlayAllDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(albumId) {
        isLoading = true
        error = null
        try {
            val response = RetrofitClient.api.getAlbumDetail(albumId)
            album = response.album
            songs = response.songs ?: emptyList()
        } catch (e: Exception) {
            error = "加载失败: ${e.message}"
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
        title = "专辑详情",
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
                        add("发行: $date")
                    }
                    album?.company?.let { add("厂牌: $it") }
                    add("${songs.size} 首歌曲")
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
                    actions = {
                        IconButton(onClick = { onSongClick(songItem) }) {
                            Icon(Icons.Default.PlayArrow, "播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = { LibraryManager.saveSong(context, songItem) }) {
                            Icon(Icons.Default.Add, "加入库", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                )
            }
        }
    )
}