package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.*
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import kotlinx.coroutines.launch

@Composable
fun ArtistDetailScreen(
    artistId: Long,
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> }
) {
    var artist by remember { mutableStateOf<ArtistDetail?>(null) }
    var hotSongs by remember { mutableStateOf<List<ArtistSongItem>>(emptyList()) }
    var albums by remember { mutableStateOf<List<ArtistAlbumItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    fun loadData() {
        coroutineScope.launch {
            isLoading = true
            error = null
            try {
                val albumsResponse = RetrofitClient.api.getArtistAlbums(artistId)
                if (albumsResponse.code == 200) {
                    artist = ArtistDetail(
                        id = albumsResponse.artist?.id ?: artistId,
                        name = albumsResponse.artist?.name ?: "",
                        picUrl = albumsResponse.artist?.picUrl,
                        albumSize = albumsResponse.artist?.albumSize,
                        musicSize = albumsResponse.artist?.musicSize
                    )
                    albums = albumsResponse.hotAlbums ?: emptyList()
                    val artistName = artist?.name ?: ""
                    if (artistName.isNotEmpty()) {
                        try {
                            val searchResponse = RetrofitClient.api.search(keyword = artistName, type = 1, limit = 30)
                            val searchSongs = searchResponse.result?.songs ?: emptyList()
                            hotSongs = searchSongs
                                .filter { it.artists?.any { a -> a.name == artistName } == true }
                                .map { song ->
                                    ArtistSongItem(
                                        id = song.id, name = song.name,
                                        artists = song.artists, album = song.album,
                                        dt = song.duration ?: 0
                                    )
                                }
                        } catch (_: Exception) {}
                    }
                } else {
                    error = "艺人数据加载失败 (code: ${albumsResponse.code})"
                }
            } catch (e: Exception) {
                error = "加载失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(artistId) { loadData() }

    DetailScaffold(
        title = "艺人详情",
        onBack = onBack,
        isLoading = isLoading,
        error = error,
        onRetry = { loadData() },
        header = {
            Column {
                Text(
                    artist?.name ?: "未知艺人",
                    color = Color.White,
                    fontSize = 28.sp,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Row {
                    artist?.albumSize?.let {
                        Text("专辑: $it", color = Color.Gray, fontSize = 14.sp)
                        Spacer(Modifier.width(16.dp))
                    }
                    artist?.musicSize?.let { Text("单曲: $it", color = Color.Gray, fontSize = 14.sp) }
                }
                Spacer(Modifier.height(16.dp))
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        text = { Text("专辑", color = if (selectedTab == 0) MaterialTheme.colorScheme.primary else Color.Gray, fontSize = 14.sp) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        text = { Text("单曲", color = if (selectedTab == 1) MaterialTheme.colorScheme.primary else Color.Gray, fontSize = 14.sp) })
                }
                Spacer(Modifier.height(16.dp))
            }
        },
        content = {
            when (selectedTab) {
                0 -> {
                    if (albums.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                Text("暂无专辑", color = Color.Gray, fontSize = 16.sp)
                            }
                        }
                    } else {
                        val rows = albums.chunked(2)
                        items(rows.size) { rowIndex ->
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                for (album in rows[rowIndex]) {
                                    ArtistAlbumGridItem(album = album, modifier = Modifier.weight(1f), onClick = { onAlbumClick(album.id) })
                                }
                                if (rows[rowIndex].size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(16.dp))
                        }
                    }
                }
                1 -> {
                    if (hotSongs.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                Text("暂无单曲", color = Color.Gray, fontSize = 16.sp)
                            }
                        }
                    } else {
                        items(hotSongs) { song ->
                            val songItem = SongItem(
                                id = song.id, name = song.name,
                                artists = song.artists, album = song.album,
                                duration = song.getDurationMs()
                            )
                            SongCard(
                                song = songItem,
                                style = SongCardStyle.COMPACT,
                                onClick = { onSongClick(songItem) },
                                onShowMenu = {
                                    onShowSongMenu(songItem, listOf(
                                        SongMenuAction(Icons.Default.LibraryAdd, "加入库") {
                                            LibraryManager.saveSong(context, songItem)
                                        },
                                        SongMenuAction(Icons.Default.PlaylistPlay, "插播") {
                                            onSongInsertNext(songItem)
                                        },
                                        SongMenuAction(Icons.Default.PlaylistAdd, "最后播放") {
                                            onSongAppendToQueue(songItem)
                                        }
                                    ))
                                }
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(80.dp)) }
        }
    )
}

@Composable
fun ArtistAlbumGridItem(album: ArtistAlbumItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Column(modifier = modifier.clickable { onClick() }) {
        AsyncImage(model = album.picUrl, contentDescription = "专辑封面", modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(8.dp))
        Text(album.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        album.publishTime?.let {
            val year = java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))
            Text("$year · ${album.size ?: 0}首", color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
