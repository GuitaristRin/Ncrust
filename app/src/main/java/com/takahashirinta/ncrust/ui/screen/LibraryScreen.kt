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
import com.takahashirinta.ncrust.network.SongItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onPlayAlbum: (Long) -> Unit,
    onInsertNext: (SongItem) -> Unit = {},
    onAppendToQueue: (SongItem) -> Unit = {}
) {
    val context = LocalContext.current
    var savedSongs by remember { mutableStateOf(LibraryManager.getSavedSongs(context)) }
    var savedAlbums by remember { mutableStateOf(LibraryManager.getSavedAlbums(context)) }
    var selectedCategory by remember { mutableIntStateOf(0) }
    val categories = listOf("单曲", "专辑", "歌单")

    LaunchedEffect(selectedCategory) {
        savedSongs = LibraryManager.getSavedSongs(context)
        savedAlbums = LibraryManager.getSavedAlbums(context)
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
                    text = { Text(title, color = if (selectedCategory == index) Color(0xFF1DB954) else Color.Gray, fontSize = 14.sp) }
                )
            }
        }

        when (selectedCategory) {
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
                                onInsertNext = { onInsertNext(song) },
                                onAppendToQueue = { onAppendToQueue(song) },
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
            2 -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("歌单（即将推出）", color = Color.Gray, fontSize = 16.sp)
                }
            }
        }
    }
}

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
        IconButton(onClick = onInsertNext) {
            Icon(Icons.Default.PlaylistPlay, "插播", tint = Color.White, modifier = Modifier.size(24.dp))
        }
        IconButton(onClick = onAppendToQueue) {
            Icon(Icons.Default.PlaylistAdd, "加入播放列表", tint = Color.White, modifier = Modifier.size(24.dp))
        }
        IconButton(onClick = onRemove) {
            Icon(Icons.Default.Delete, "移除", tint = Color.Gray, modifier = Modifier.size(20.dp))
        }
    }
}

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