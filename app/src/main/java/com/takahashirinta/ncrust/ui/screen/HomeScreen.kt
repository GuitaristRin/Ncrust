package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import androidx.compose.ui.platform.LocalConfiguration

data class PlaylistCard(
    val id: Long,
    val name: String,
    val coverUrl: String,
    val playCount: Long,
    val trackCount: Int = 0
)

@Composable
fun HomeScreen(
    onSongClick: (SongItem) -> Unit,
    onPlaylistClick: (Long) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {},
    onPlayDailyAll: ((List<SongItem>) -> Unit)? = null
) {
    var dailySongs by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var playlists by remember { mutableStateOf<List<PlaylistCard>>(emptyList()) }
    val newSongs = remember { mutableStateListOf<SongItem>() }
    var isLoading by remember { mutableStateOf(true) }
    var isLoadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var offset by remember { mutableIntStateOf(0) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    fun loadDailySongs() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.eapiPost(
                    "https://music.163.com/eapi/v2/discovery/recommend/songs",
                    emptyMap()
                )
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val recommendArray = json.optJSONArray("recommend") ?: json.optJSONArray("data")
                val list = mutableListOf<SongItem>()
                if (recommendArray != null) {
                    for (i in 0 until recommendArray.length()) {
                        val s = recommendArray.getJSONObject(i)
                        val ar = s.optJSONArray("artists")
                        val artists: List<ArtistItem>? = if (ar != null) {
                            val arList = mutableListOf<ArtistItem>()
                            for (j in 0 until ar.length()) {
                                arList.add(ArtistItem(name = ar.getJSONObject(j).optString("name")))
                            }
                            arList
                        } else null
                        val al = s.optJSONObject("album")
                        val album = if (al != null) AlbumItem(
                            id = al.optLong("id"),
                            name = al.optString("name"),
                            picUrl = al.optString("picUrl")
                        ) else null
                        list.add(SongItem(
                            id = s.optLong("id"),
                            name = s.optString("name"),
                            artists = artists,
                            album = album,
                            duration = s.optLong("duration")
                        ))
                    }
                }
                withContext(Dispatchers.Main) { dailySongs = list }
            } catch (e: Exception) {
                android.util.Log.e("DailySongs", "Error", e)
            }
        }
    }

    fun loadPlaylists() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val response = RetrofitClient.eapiPost(
                    "https://music.163.com/eapi/v1/discovery/recommend/resource",
                    emptyMap()
                )
                val body = response.body?.string() ?: ""
                val json = JSONObject(body)
                val recommendArray = json.optJSONArray("recommend")
                val list = mutableListOf<PlaylistCard>()
                if (recommendArray != null) {
                    for (i in 0 until minOf(recommendArray.length(), 10)) {
                        val item = recommendArray.getJSONObject(i)
                        list.add(PlaylistCard(
                            id = item.optLong("id"),
                            name = item.optString("name"),
                            coverUrl = item.optString("picUrl"),
                            playCount = item.optLong("playCount"),
                            trackCount = if (item.optString("name") == "私人雷达") 35 else item.optInt("trackCount")
                        ))
                    }
                }
                withContext(Dispatchers.Main) { playlists = list }
            } catch (_: Exception) { }
        }
    }

    fun loadNewSongs(reset: Boolean) {
        coroutineScope.launch(Dispatchers.IO) {
            if (reset) { isLoading = true; offset = 0 } else { isLoadingMore = true }
            error = null
            try {
                val body = RetrofitClient.get(
                    "https://music.163.com/api/v1/discovery/new/songs?limit=10&offset=$offset"
                )
                val json = JSONObject(body)
                val dataArray = json.optJSONArray("data") ?: json.optJSONArray("songs")
                val list = mutableListOf<SongItem>()
                if (dataArray != null) {
                    for (i in 0 until dataArray.length()) {
                        val s = dataArray.getJSONObject(i)
                        val ar = s.optJSONArray("artists")
                        val artists: List<ArtistItem>? = if (ar != null) {
                            val arList = mutableListOf<ArtistItem>()
                            for (j in 0 until ar.length()) arList.add(ArtistItem(name = ar.getJSONObject(j).optString("name")))
                            arList
                        } else null
                        val al = s.optJSONObject("album")
                        val album = if (al != null) AlbumItem(id = al.optLong("id"), name = al.optString("name"), picUrl = al.optString("picUrl")) else null
                        list.add(SongItem(id = s.optLong("id"), name = s.optString("name"), artists = artists, album = album, duration = s.optLong("duration")))
                    }
                }
                hasMore = list.size >= 10
                withContext(Dispatchers.Main) {
                    if (reset) { newSongs.clear(); newSongs.addAll(list); isLoading = false }
                    else { newSongs.addAll(list); isLoadingMore = false }
                    offset += list.size
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = "加载失败: ${e.message}"; isLoading = false; isLoadingMore = false }
            }
        }
    }

    val shouldLoadMore = remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            last >= newSongs.size - 3 && !isLoadingMore && hasMore && newSongs.isNotEmpty()
        }
    }
    LaunchedEffect(shouldLoadMore.value) { if (shouldLoadMore.value) loadNewSongs(false) }

    LaunchedEffect(Unit) {
        loadDailySongs()
        loadPlaylists()
        loadNewSongs(true)
    }

    if (isLoading) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF1DB954)) }
        return
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)),
        contentPadding = PaddingValues(bottom = 72.dp)
    ) {
// 日推区域
        if (dailySongs.isNotEmpty()) {
            item {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("🌅 每日推荐", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    IconButton(onClick = { onPlayDailyAll?.invoke(dailySongs) }) {
                        Icon(Icons.Default.PlayArrow, "播放全部", tint = Color(0xFF1DB954), modifier = Modifier.size(28.dp))
                    }
                }
            }
            item {
                val columns = dailySongs.chunked(5)
                val screenWidth = LocalConfiguration.current.screenWidthDp.dp
                LazyRow(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(columns.size) { colIndex ->
                        Column(
                            modifier = Modifier.width(screenWidth * 0.82f)
                        ) {
                            columns[colIndex].forEach { song ->
                                HomeSongListItem(song = song, onPlay = { onSongClick(song) })
                            }
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }
        // 推荐歌单
        if (playlists.isNotEmpty()) {
            item { Text("📋 推荐歌单", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 4.dp)) }
            item {
                LazyRow(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(playlists) { pl -> PlaylistCardItem(playlist = pl, onClick = { onPlaylistClick(pl.id) }, onPlayAll = { onPlayPlaylist(pl.id) }) }
                }
            }
            item { Spacer(Modifier.height(24.dp)) }
        }

        // 新歌速递
        item { Text("🆕 新歌速递", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 12.dp)) }
        items(newSongs.toList()) { song -> HomeSongListItem(song = song, onPlay = { onSongClick(song) }) }
        if (isLoadingMore) {
            item { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Color(0xFF1DB954), modifier = Modifier.size(24.dp)) } }
        }
        if (!hasMore && newSongs.isNotEmpty()) {
            item { Text("— 没有更多了 —", color = Color.Gray, modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center) }
        }
    }
}

@Composable
fun DailySongCard(song: SongItem, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val artistStr = song.artists?.joinToString("/") { it.name } ?: ""
    Column(modifier = modifier.clickable { onClick() }.padding(bottom = 8.dp)) {
        AsyncImage(model = song.album?.picUrl, contentDescription = null, modifier = Modifier.aspectRatio(1f), contentScale = ContentScale.Crop)
        Spacer(Modifier.height(4.dp))
        Text(song.name, color = Color.White, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (artistStr.isNotEmpty()) Text(artistStr, color = Color.Gray, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun PlaylistCardItem(playlist: PlaylistCard, onClick: () -> Unit, onPlayAll: () -> Unit) {
    Column(modifier = Modifier.width(140.dp).clickable { onClick() }) {
        Box(modifier = Modifier.size(140.dp)) {
            AsyncImage(model = playlist.coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp).size(30.dp).background(Color(0xFF1DB954), shape = CircleShape).clickable { onPlayAll() }, contentAlignment = Alignment.Center) {
                Icon(Icons.Default.PlayArrow, "播放全部", tint = Color.Black, modifier = Modifier.size(18.dp))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(playlist.name, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text("${playlist.trackCount}首歌曲", color = Color.Gray, fontSize = 11.sp)
    }
}

@Composable
fun HomeSongListItem(song: SongItem, onPlay: () -> Unit) {
    val artistStr = song.artists?.joinToString("/") { it.name } ?: "未知歌手"
    Row(modifier = Modifier.fillMaxWidth().clickable { onPlay() }.padding(vertical = 8.dp, horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
        AsyncImage(model = song.album?.picUrl, contentDescription = null, modifier = Modifier.size(48.dp), contentScale = ContentScale.Crop)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(song.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("$artistStr · ${song.album?.name ?: ""}", color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        IconButton(onClick = onPlay) { Icon(Icons.Default.PlayArrow, "播放", tint = Color(0xFF1DB954), modifier = Modifier.size(28.dp)) }
    }
}