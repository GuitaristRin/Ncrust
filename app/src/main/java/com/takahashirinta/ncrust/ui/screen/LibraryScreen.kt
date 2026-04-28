package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import kotlinx.coroutines.launch
import com.takahashirinta.ncrust.auth.CookieManager

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onPlayAlbum: (Long) -> Unit,
    onPlaylistClick: (PlaylistApi.PlaylistInfo) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    // 每次进入页面重新从本地加载
    var savedSongs by remember { mutableStateOf(LibraryManager.getSavedSongs(context)) }
    var savedAlbums by remember { mutableStateOf(LibraryManager.getSavedAlbums(context)) }
    var selectedCategory by remember { mutableIntStateOf(0) }
    val categories = listOf("单曲", "专辑", "歌单")

    // 云歌单状态
    var playlists by remember { mutableStateOf<List<PlaylistApi.PlaylistInfo>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }

    fun loadPlaylists() {
        coroutineScope.launch {
            isLoadingPlaylists = true
            playlistError = null
            try {
                if (!CookieManager.hasCookie(context)) {
                    playlistError = "未登录！请前往用户页登录"
                } else {
                    val userId = PlaylistApi.getCurrentUserId()
                    val result = PlaylistApi.getUserPlaylists(userId)
                    playlists = result.playlists
                }
            } catch (e: Exception) {
                playlistError = if (!CookieManager.hasCookie(context))
                    "未登录！请前往用户页登录"
                else "加载失败: ${e.message}"
            } finally {
                isLoadingPlaylists = false
            }
        }
    }

    // 每次切到这个 Tab 重新刷新库结构
    LaunchedEffect(selectedCategory) {
        savedSongs = LibraryManager.getSavedSongs(context)
        savedAlbums = LibraryManager.getSavedAlbums(context)
        if (selectedCategory == 2 && playlists.isEmpty() && !isLoadingPlaylists) {
            loadPlaylists()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        TabRow(
            selectedTabIndex = selectedCategory,
            containerColor = Color(0xFF121212),
            contentColor = Color(0xFF1DB954)
        ) {
            categories.forEachIndexed { index, title ->
                Tab(
                    selected = selectedCategory == index,
                    onClick = { selectedCategory = index },
                    text = {
                        Text(
                            title,
                            color = if (selectedCategory == index) Color(0xFF1DB954) else Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                )
            }
        }

        when (selectedCategory) {
            // ====== 本地单曲 ======
            0 -> {
                if (savedSongs.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无收藏歌曲", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = 72.dp)
                    ) {
                        items(savedSongs, key = { it.id }) { song ->
                            LibrarySongListItem(
                                song = song,
                                onPlay = { onSongClick(song) },
                                onRemove = {
                                    LibraryManager.removeSong(context, song.id)
                                    savedSongs = LibraryManager.getSavedSongs(context)
                                    savedAlbums = LibraryManager.getSavedAlbums(context)
                                }
                            )
                        }
                    }
                }
            }

            // ====== 本地专辑（从单曲派生） ======
            1 -> {
                if (savedAlbums.isEmpty()) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text("暂无收藏专辑", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                        verticalArrangement = Arrangement.spacedBy(16.dp),
                        contentPadding = PaddingValues(bottom = 72.dp)
                    ) {
                        val rows = savedAlbums.chunked(2)
                        items(rows.size) { rowIndex ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(16.dp)
                            ) {
                                for (album in rows[rowIndex]) {
                                    LibraryAlbumGridItem(
                                        album = album,
                                        modifier = Modifier.weight(1f),
                                        onClick = { onAlbumClick(album.albumId) },
                                        onPlayAll = { onPlayAlbum(album.albumId) }
                                    )
                                }
                                if (rows[rowIndex].size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }
            }

            // ====== 云歌单 ======
            2 -> {
                when {
                    isLoadingPlaylists -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Color(0xFF1DB954))
                        }
                    }
                    playlistError != null -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(playlistError!!, color = Color.Red, fontSize = 14.sp)
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { loadPlaylists() }) {
                                    Text("重试", color = Color(0xFF1DB954))
                                }
                            }
                        }
                    }
                    playlists.isEmpty() -> {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("暂无歌单", color = Color.Gray, fontSize = 16.sp)
                        }
                    }
                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(bottom = 72.dp)
                        ) {
                            val rows = playlists.chunked(2)
                            items(rows.size) { rowIndex ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    for (pl in rows[rowIndex]) {
                                        PlaylistGridItem(
                                            playlist = pl,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onPlaylistClick(pl) },
                                            onPlayAll = { onPlayPlaylist(pl.id) }
                                        )
                                    }
                                    if (rows[rowIndex].size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== 云歌单封面项 ====================
@Composable
fun PlaylistGridItem(
    playlist: PlaylistApi.PlaylistInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayAll: () -> Unit
) {
    Column(modifier = modifier.clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = playlist.coverImgUrl,
                contentDescription = "歌单封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .background(Color(0xFF1DB954), shape = CircleShape)
                    .clickable { onPlayAll() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    "播放全部",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(playlist.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${playlist.trackCount}首", color = Color.Gray, style = MaterialTheme.typography.bodySmall)
    }
}

// ==================== 库单曲行 ====================
@Composable
fun LibrarySongListItem(
    song: SongItem,
    onPlay: () -> Unit,
    onInsertNext: () -> Unit = {},
    onAppendToQueue: () -> Unit = {},
    onRemove: () -> Unit
) {
    val artistStr = song.artists?.joinToString("/") { it.name } ?: "未知歌手"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.album?.picUrl,
            contentDescription = "封面",
            modifier = Modifier.size(48.dp),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$artistStr · ${song.album?.name ?: ""}", color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onInsertNext) { Icon(Icons.Default.PlaylistPlay, "插播", tint = Color.White, modifier = Modifier.size(24.dp)) }
        IconButton(onClick = onAppendToQueue) { Icon(Icons.Default.PlaylistAdd, "加入播放列表", tint = Color.White, modifier = Modifier.size(24.dp)) }
        IconButton(onClick = onRemove) { Icon(Icons.Default.Delete, "移除", tint = Color.Gray, modifier = Modifier.size(20.dp)) }
    }
}

// ==================== 库专辑封面项 ====================
@Composable
fun LibraryAlbumGridItem(
    album: AlbumInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayAll: () -> Unit
) {
    Column(modifier = modifier.clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = album.picUrl,
                contentDescription = "专辑封面",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(8.dp)
                    .size(36.dp)
                    .background(Color(0xFF1DB954), shape = CircleShape)
                    .clickable { onPlayAll() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    "播放全部",
                    tint = Color.Black,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Text(album.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text("${album.artist} · ${album.songCount}首", color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}