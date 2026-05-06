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
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.components.DetailHeader
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.PlayAllDialog
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import kotlinx.coroutines.launch

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    playlistName: String = "",
    playlistCoverUrl: String = "",
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onInsertNext: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> }
) {
    var songs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPlayAllDialog by remember { mutableStateOf(false) }
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

    LaunchedEffect(playlistId) { loadSongs() }

    val coverUrl = playlistCoverUrl.ifEmpty { songs.firstOrNull()?.album?.picUrl }

    if (showPlayAllDialog) {
        PlayAllDialog(
            songCount = songs.size,
            onDismiss = { showPlayAllDialog = false },
            onReplaceAndPlay = { onReplaceAndPlay(songs) },
            onInsertNext = { onInsertNext(songs) }
        )
    }

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
                infoLines = listOf("${songs.size} 首歌曲"),
                onPlayAll = if (songs.isNotEmpty()) ({ showPlayAllDialog = true }) else null
            )
        },
        content = {
            items(songs) { song ->
                SongCard(
                    song = song,
                    style = SongCardStyle.COMPACT,
                    onClick = { onSongClick(song) },
                    onShowMenu = {
                        onShowSongMenu(song, listOf(
                            SongMenuAction(Icons.Default.LibraryAdd, "加入库") {
                                LibraryManager.saveSong(context, song)
                            },
                            SongMenuAction(Icons.Default.PlaylistPlay, "插播") {
                                onSongInsertNext(song)
                            },
                            SongMenuAction(Icons.Default.PlaylistAdd, "最后播放") {
                                onSongAppendToQueue(song)
                            }
                        ))
                    }
                )
            }
        }
    )
}
