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
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.components.DetailHeader
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    playlistName: String = "",
    playlistCoverUrl: String = "",
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit
) {
    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun loadSongs() {
        coroutineScope.launch {
            isLoading = true
            error = null
            try {
                songs = PlaylistApi.getPlaylistDetail(playlistId)
            } catch (e: Exception) {
                error = "加载失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(playlistId) {
        loadSongs()
    }

    val coverUrl = playlistCoverUrl.ifEmpty { songs.firstOrNull()?.album?.picUrl }

    DetailScaffold(
        title = "歌单详情",
        onBack = onBack,
        isLoading = isLoading,
        error = error,
        onRetry = { loadSongs() },
        header = {
            DetailHeader(
                coverUrl = coverUrl,
                title = playlistName.ifEmpty { "歌单" },
                subtitle = null,
                infoLines = listOf("${songs.size} 首歌曲")
            )
        },
        content = {
            items(songs) { song ->
                SongCard(
                    song = song,
                    style = SongCardStyle.COMPACT,
                    onClick = { onSongClick(song) },
                    actions = {
                        IconButton(onClick = { onSongClick(song) }) {
                            Icon(Icons.Default.PlayArrow, "播放", tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                        }
                        IconButton(onClick = { LibraryManager.saveSong(context, song) }) {
                            Icon(Icons.Default.Add, "加入库", tint = Color.White, modifier = Modifier.size(24.dp))
                        }
                    }
                )
            }
        }
    )
}