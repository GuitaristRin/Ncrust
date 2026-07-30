package com.takahashirinta.ncrust.ui.screen
import com.takahashirinta.ncrust.ui.theme.LocalNcrustTypography
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors

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
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.*
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import android.widget.Toast

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
    // 命中缓存则用缓存作为初始 state，屏幕进入时立即有内容渲染。
    // hotSongs 需要额外一次搜索，不入 ContentCache（保持 API 面收敛）——只对主体的 artist + albums 做缓存。
    val cached = remember(artistId) { ContentCache.getArtistAlbums(artistId) }
    var artist by remember(artistId) {
        mutableStateOf(
            cached?.let {
                ArtistDetail(
                    id = it.artist?.id ?: artistId,
                    name = it.artist?.name ?: "",
                    picUrl = it.artist?.picUrl,
                    albumSize = it.artist?.albumSize,
                    musicSize = it.artist?.musicSize
                )
            }
        )
    }
    var hotSongs by remember(artistId) { mutableStateOf<List<ArtistSongItem>>(emptyList()) }
    var albums by remember(artistId) { mutableStateOf<List<ArtistAlbumItem>>(cached?.hotAlbums ?: emptyList()) }
    var isLoading by remember(artistId) { mutableStateOf(cached == null) }
    var error by remember(artistId) { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current

    fun loadData(showLoader: Boolean) {
        coroutineScope.launch {
            if (showLoader) isLoading = true
            error = null
            try {
                val albumsResponse = RetrofitClient.api.getArtistAlbums(artistId)
                if (albumsResponse.code == 200) {
                    ContentCache.putArtistAlbums(artistId, albumsResponse)
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
                    // 缓存兜底：有旧内容时刷新失败不显示错误。
                    if (artist == null) error = strings.artistDataLoadFailed(albumsResponse.code)
                }
            } catch (e: Exception) {
                if (artist == null) error = strings.loadFailed(e.message)
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(artistId) { loadData(showLoader = cached == null) }

    // albumRows / hotSongItems 上移到 Composable 作用域，用 remember 缓存，
    // 避免 items 块内反复分组 / 反复构造 SongItem 破坏稳定性
    val albumRows = remember(albums) { albums.chunked(2) }
    val hotSongItems = remember(hotSongs) {
        hotSongs.map { s ->
            SongItem(id = s.id, name = s.name, artists = s.artists, album = s.album, duration = s.getDurationMs())
        }
    }

    DetailScaffold(
        title = strings.artistDetailTitle,
        onBack = onBack,
        isLoading = isLoading,
        hasCachedContent = artist != null,
        error = error,
        onRetry = { loadData(showLoader = true) },
        header = {
            // 顶部 statusBar + 56dp 为浮层返回箭头让路（浮层箭头会盖在这块黑色上）。
            Column(modifier = Modifier.statusBarsPadding()) {
                Spacer(Modifier.height(56.dp))
                Column(modifier = Modifier.padding(horizontal = 16.dp)) {
                    Text(
                        artist?.name ?: strings.unknownArtistName,
                        color = Color.White,
                        fontSize = 32.sp,
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal
                    )
                    Spacer(Modifier.height(6.dp))
                    Row {
                        artist?.albumSize?.let {
                            Text(strings.artistAlbumCount(it), color = Color.Gray, fontSize = 14.sp)
                            Spacer(Modifier.width(16.dp))
                        }
                        artist?.musicSize?.let { Text(strings.artistSongCount(it), color = Color.Gray, fontSize = 14.sp) }
                    }
                }
                Spacer(Modifier.height(20.dp))
                TabRow(
                    selectedTabIndex = selectedTab,
                    containerColor = LocalNcrustColors.current.background,
                    contentColor = LocalNcrustColors.current.primary
                ) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 },
                        text = { Text(strings.categoryAlbums, color = if (selectedTab == 0) LocalNcrustColors.current.primary else Color.Gray, fontSize = 14.sp) })
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 },
                        text = { Text(strings.categoryTracks, color = if (selectedTab == 1) LocalNcrustColors.current.primary else Color.Gray, fontSize = 14.sp) })
                }
                Spacer(Modifier.height(12.dp))
            }
        },
        content = {
            when (selectedTab) {
                0 -> {
                    if (albums.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                Text(strings.noAlbums, color = Color.Gray, fontSize = 16.sp)
                            }
                        }
                    } else {
                        items(albumRows, key = { row -> row.first().id }) { row ->
                            // 边到边 tile：spacedBy 2dp 制造 Metro 拼贴感。
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                for (album in row) {
                                    ArtistAlbumGridItem(album = album, modifier = Modifier.weight(1f), onClick = { onAlbumClick(album.id) })
                                }
                                if (row.size == 1) Spacer(modifier = Modifier.weight(1f))
                            }
                            Spacer(Modifier.height(2.dp))
                        }
                    }
                }
                1 -> {
                    if (hotSongs.isEmpty()) {
                        item {
                            Box(modifier = Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                                Text(strings.noHotSongs, color = Color.Gray, fontSize = 16.sp)
                            }
                        }
                    } else {
                        items(hotSongItems, key = { it.id }) { songItem ->
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
                }
            }
        }
    )
}

@Composable
fun ArtistAlbumGridItem(album: ArtistAlbumItem, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val strings = LocalStrings.current
    Column(modifier = modifier.clickable { onClick() }) {
        AsyncImage(model = album.picUrl, contentDescription = strings.albumCoverDesc, modifier = Modifier.fillMaxWidth().aspectRatio(1f), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(6.dp))
        Text(album.name, color = Color.White, style = LocalNcrustTypography.current.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        album.publishTime?.let {
            val year = java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault()).format(java.util.Date(it))
            Text("$year · ${strings.trackCount(album.size ?: 0)}", color = Color.Gray, style = LocalNcrustTypography.current.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        }
        Spacer(Modifier.height(6.dp))
    }
}
