package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArtistDetailScreen(
    artistId: Long,
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit
) {
    var artist by remember { mutableStateOf<ArtistDetail?>(null) }
    var hotSongs by remember { mutableStateOf<List<ArtistSongItem>>(emptyList()) }
    var albums by remember { mutableStateOf<List<ArtistAlbumItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    val coroutineScope = rememberCoroutineScope()

    fun loadData() {
        coroutineScope.launch {
            isLoading = true
            error = null
            try {
                val detailResponse = RetrofitClient.api.getArtistDetail(artistId)
                android.util.Log.d("ArtistDetail", "Response code: ${detailResponse.code}")

                if (detailResponse.code == 200) {
                    artist = detailResponse.data?.artist ?: detailResponse.artist
                    hotSongs = detailResponse.data?.hotSongs ?: detailResponse.hotSongs ?: emptyList()
                } else {
                    error = "艺人数据加载失败 (code: ${detailResponse.code})"
                }

                try {
                    val albumsResponse = RetrofitClient.api.getArtistAlbums(artistId)
                    android.util.Log.d("ArtistAlbums", "Response code: ${albumsResponse.code}")
                    if (albumsResponse.code == 200) {
                        albums = albumsResponse.hotAlbums ?: albumsResponse.albums ?: emptyList()
                    }
                } catch (e: Exception) {
                    android.util.Log.e("ArtistAlbums", "Albums load error", e)
                }

            } catch (e: Exception) {
                android.util.Log.e("ArtistDetail", "Error", e)
                error = "加载失败: ${e.message}"
            } finally {
                isLoading = false
            }
        }
    }

    LaunchedEffect(artistId) {
        loadData()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("艺人详情", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            )
        },
        containerColor = Color(0xFF121212)
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Color(0xFF1DB954))
                    }
                }
                error != null -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error!!, color = Color.Red, fontSize = 16.sp)
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = { loadData() }) {
                                Text("重试")
                            }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp)
                    ) {
                        item {
                            Text(
                                artist?.name ?: "未知艺人",
                                color = Color.White,
                                fontSize = 36.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(bottom = 16.dp)
                            )
                            Row {
                                artist?.albumSize?.let {
                                    Text("专辑: $it", color = Color.Gray, fontSize = 14.sp)
                                    Spacer(Modifier.width(16.dp))
                                }
                                artist?.musicSize?.let {
                                    Text("单曲: $it", color = Color.Gray, fontSize = 14.sp)
                                }
                            }
                            Spacer(Modifier.height(24.dp))
                            TabRow(
                                selectedTabIndex = selectedTab,
                                containerColor = Color(0xFF121212),
                                contentColor = Color(0xFF1DB954)
                            ) {
                                Tab(
                                    selected = selectedTab == 0,
                                    onClick = { selectedTab = 0 },
                                    text = { Text("专辑", color = if (selectedTab == 0) Color(0xFF1DB954) else Color.Gray, fontSize = 14.sp) }
                                )
                                Tab(
                                    selected = selectedTab == 1,
                                    onClick = { selectedTab = 1 },
                                    text = { Text("单曲", color = if (selectedTab == 1) Color(0xFF1DB954) else Color.Gray, fontSize = 14.sp) }
                                )
                            }
                            Spacer(Modifier.height(16.dp))
                        }

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
                                        ArtistSongListItem(song = song, onPlayNext = {
                                            val songItem = SongItem(id = song.id, name = song.name, artists = song.artists, album = song.album, duration = song.getDurationMs())
                                            onSongClick(songItem)
                                        })
                                    }
                                }
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
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

@Composable
fun ArtistSongListItem(song: ArtistSongItem, onPlayNext: () -> Unit) {
    val artistStr = song.artists?.joinToString("/") { it.name } ?: "未知歌手"
    Row(modifier = Modifier.fillMaxWidth().clickable { onPlayNext() }.padding(vertical = 8.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = song.album?.picUrl, contentDescription = "封面", modifier = Modifier.size(40.dp), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$artistStr · ${song.album?.name ?: ""}", color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onPlayNext) {
            Icon(Icons.Default.PlayArrow, "下一首播放", tint = Color(0xFF1DB954), modifier = Modifier.size(28.dp))
        }
    }
}