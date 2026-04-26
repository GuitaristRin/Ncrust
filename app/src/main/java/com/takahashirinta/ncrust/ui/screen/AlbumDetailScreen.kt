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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumDetail
import com.takahashirinta.ncrust.network.model.AlbumSongItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: Long,
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit
) {
    var album by remember { mutableStateOf<AlbumDetail?>(null) }
    var songs by remember { mutableStateOf<List<AlbumSongItem>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    val context = LocalContext.current
    val screenWidth = LocalConfiguration.current.screenWidthDp.dp

    LaunchedEffect(albumId) {
        isLoading = true
        error = null
        try {
            val response = RetrofitClient.api.getAlbumDetail(albumId)
            android.util.Log.d("AlbumDetail", "Response code: ${response.code}")
            android.util.Log.d("AlbumDetail", "Album: ${response.album}")
            android.util.Log.d("AlbumDetail", "Songs count: ${response.songs?.size}")
            album = response.album
            songs = response.songs ?: emptyList()
        } catch (e: Exception) {
            android.util.Log.e("AlbumDetail", "Error", e)
            error = "加载失败: ${e.message}"
        } finally {
            isLoading = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("专辑详情", color = Color.White) },
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
            if (isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF1DB954))
                }
            } else if (error != null) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = Color.Red, fontSize = 16.sp)
                }
            } else if (album != null) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.Start,
                            verticalAlignment = Alignment.Top
                        ) {
                            AsyncImage(
                                model = album?.picUrl,
                                contentDescription = "专辑封面",
                                modifier = Modifier.size(screenWidth * 0.5f),
                                contentScale = ContentScale.Crop
                            )
                            Spacer(Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    album?.name ?: "",
                                    color = Color.White,
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 2,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(8.dp))
                                album?.artist?.let {
                                    Text(it.name, color = Color(0xFF1DB954), fontSize = 16.sp)
                                }
                                Spacer(Modifier.height(8.dp))
                                album?.publishTime?.let { time ->
                                    val date = java.text.SimpleDateFormat(
                                        "yyyy-MM-dd",
                                        java.util.Locale.getDefault()
                                    ).format(java.util.Date(time))
                                    Text("发行: $date", color = Color.Gray, fontSize = 14.sp)
                                }
                                album?.company?.let {
                                    Spacer(Modifier.height(4.dp))
                                    Text("厂牌: $it", color = Color.Gray, fontSize = 14.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("${songs.size} 首歌曲", color = Color.Gray, fontSize = 14.sp)
                            }
                        }
                        Spacer(Modifier.height(24.dp))
                        HorizontalDivider(color = Color(0xFF2A2A2A))
                        Spacer(Modifier.height(16.dp))
                    }

                    items(songs) { song ->
                        AlbumSongListItem(
                            song = song,
                            onPlayNext = {
                                val songItem = SongItem(
                                    id = song.id,
                                    name = song.name,
                                    artists = song.artists,
                                    album = song.album,
                                    duration = song.getDurationMs()
                                )
                                onSongClick(songItem)
                            },
                            onAddToLibrary = {
                                val songItem = SongItem(
                                    id = song.id,
                                    name = song.name,
                                    artists = song.artists,
                                    album = song.album,
                                    duration = song.getDurationMs()
                                )
                                LibraryManager.saveSong(context, songItem)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AlbumSongListItem(
    song: AlbumSongItem,
    onPlayNext: () -> Unit,
    onAddToLibrary: () -> Unit
) {
    val artistStr = song.artists?.joinToString("/") { it.name } ?: "未知歌手"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlayNext() }
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.album?.picUrl,
            contentDescription = "封面",
            modifier = Modifier.size(40.dp),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$artistStr · ${song.album?.name ?: ""}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onPlayNext) {
            Icon(
                Icons.Default.PlayArrow,
                "下一首播放",
                tint = Color(0xFF1DB954),
                modifier = Modifier.size(28.dp)
            )
        }
        IconButton(onClick = onAddToLibrary) {
            Icon(
                Icons.Default.Add,
                "加入库",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}