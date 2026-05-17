package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.ui.components.PlayAllCircleButton
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

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
    onPlayDailyAll: ((List<SongItem>) -> Unit)? = null,
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> }
) {
    val strings = LocalStrings.current
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
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        }
        return
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    Box(modifier = Modifier.fillMaxSize()) {
        ResponsiveContent {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                contentPadding = PaddingValues(bottom = 72.dp)
            ) {
                // 日推区域
                if (dailySongs.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp, 12.dp, 16.dp, 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(strings.dailySongsTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                            IconButton(onClick = { onPlayDailyAll?.invoke(dailySongs) }) {
                                Icon(Icons.Default.PlayArrow, strings.playAllButton, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp))
                            }
                        }
                    }
                    item {
                        val columns = dailySongs.chunked(5)
                        LazyRow(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            items(columns.size) { colIndex ->
                                Column(modifier = Modifier.fillParentMaxWidth(0.9f)) {
                                    columns[colIndex].forEach { song ->
                                        SongCard(
                                            song = song,
                                            style = SongCardStyle.LIST,
                                            onClick = { onSongClick(song) },
                                            onShowMenu = {
                                            onShowSongMenu(song, listOf(
                                                SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                                    LibraryManager.saveSong(context, song)
                                                },
                                                SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) {
                                                    onSongInsertNext(song)
                                                },
                                                SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) {
                                                    onSongAppendToQueue(song)
                                                }
                                            ))
                                        }
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }
                // 推荐歌单
                if (playlists.isNotEmpty()) {
                    item { Text(strings.recommendPlaylistTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 4.dp)) }
                    item {
                        LazyRow(modifier = Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            items(playlists) { pl -> PlaylistCardItem(playlist = pl, onClick = { onPlaylistClick(pl.id) }, onPlayAll = { onPlayPlaylist(pl.id) }) }
                        }
                    }
                    item { Spacer(Modifier.height(24.dp)) }
                }

                // 新歌速递
                item { Text(strings.newSongsTitle, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(16.dp, 4.dp, 16.dp, 12.dp)) }
                items(newSongs.toList()) { song ->
                    SongCard(
                        song = song,
                        style = SongCardStyle.LIST,
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
                if (isLoadingMore) {
                    item { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) } }
                }
                if (!hasMore && newSongs.isNotEmpty()) {
                    item { Text(strings.noMoreContent, color = Color.Gray, modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center) }
                }
            }
        }

    }
}

@Composable
fun PlaylistCardItem(playlist: PlaylistCard, onClick: () -> Unit, onPlayAll: () -> Unit) {
    val strings = LocalStrings.current
    Column(modifier = Modifier.width(140.dp).clickable { onClick() }) {
        Box(modifier = Modifier.size(140.dp)) {
            AsyncImage(model = playlist.coverUrl, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            PlayAllCircleButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                size = 30.dp,
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(playlist.name, color = Color.White, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(strings.trackCountSongs(playlist.trackCount), color = Color.Gray, fontSize = 11.sp)
    }
}