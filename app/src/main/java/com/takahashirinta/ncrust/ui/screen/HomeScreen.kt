package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.Crossfade
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
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.ui.anim.sokuou.SokuouTweens
import com.takahashirinta.ncrust.ui.components.PlayAllCircleButton
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

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
    // 初始 state 从 ContentCache 读取。有缓存则立即渲染，无需 spinner。
    // 后台仍会刷新——请求返回后写回缓存 + 更新 state；LazyColumn 通过 key diff 平滑替换。
    var dailySongs by remember { mutableStateOf(ContentCache.homeDailySongs ?: emptyList()) }
    var playlists by remember { mutableStateOf(ContentCache.homeRecommendPlaylists ?: emptyList()) }
    val newSongs = remember {
        mutableStateListOf<SongItem>().apply { ContentCache.homeNewSongs?.let { addAll(it) } }
    }
    // 冷启动（三块数据都空）才显示全屏 loader；有任一缓存则跳过。
    var isLoading by remember {
        mutableStateOf(dailySongs.isEmpty() && playlists.isEmpty() && newSongs.isEmpty())
    }
    var isLoadingMore by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var hasMore by remember { mutableStateOf(true) }
    var offset by remember { mutableIntStateOf(newSongs.size) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    fun loadDailySongs() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val list = PlaylistApi.getDailyRecommendSongs()
                withContext(Dispatchers.Main) {
                    dailySongs = list
                    ContentCache.homeDailySongs = list
                }
            } catch (e: Exception) {
                android.util.Log.e("DailySongs", "Error", e)
            }
        }
    }

    fun loadPlaylists() {
        coroutineScope.launch(Dispatchers.IO) {
            try {
                val list = PlaylistApi.getRecommendPlaylists()
                withContext(Dispatchers.Main) {
                    playlists = list
                    ContentCache.homeRecommendPlaylists = list
                }
            } catch (_: Exception) { }
        }
    }

    fun loadNewSongs(reset: Boolean) {
        coroutineScope.launch(Dispatchers.IO) {
            if (reset) {
                offset = 0
                // 只在完全没内容时才切 loading（冷启动）；有缓存时静默刷新。
                if (newSongs.isEmpty()) isLoading = true
            } else {
                isLoadingMore = true
            }
            error = null
            try {
                val list = PlaylistApi.getTopSongs(limit = 10, offset = offset)
                hasMore = list.size >= 10
                withContext(Dispatchers.Main) {
                    if (reset) {
                        newSongs.clear()
                        newSongs.addAll(list)
                        ContentCache.homeNewSongs = list.toList()
                        isLoading = false
                    } else {
                        newSongs.addAll(list)
                        isLoadingMore = false
                    }
                    offset += list.size
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { error = strings.loadFailed(e.message); isLoading = false; isLoadingMore = false }
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

    val context = androidx.compose.ui.platform.LocalContext.current

    // Crossfade：冷启动 loader → 内容平滑过渡，避免"黑屏 spinner → 跳变到列表"的观感。
    // 有缓存时 isLoading 一开始就是 false，Crossfade 直接落到内容分支，没有额外开销。
    Crossfade(
        targetState = isLoading,
        animationSpec = SokuouTweens.CoverFade,
        modifier = Modifier.fillMaxSize(),
        label = "HomeContentCrossfade"
    ) { loading ->
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
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
                                                    Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
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
                                SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                    LibraryManager.saveSong(context, song)
                                    Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
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
                if (isLoadingMore) {
                    item { Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp)) } }
                }
                if (!hasMore && newSongs.isNotEmpty()) {
                    item { Text(strings.noMoreContent, color = Color.Gray, modifier = Modifier.fillMaxWidth().padding(16.dp), textAlign = TextAlign.Center) }
                }
                }  // close LazyColumn
            }  // close ResponsiveContent
        }  // close else block
    }  // close Crossfade lambda
}

@Composable
fun PlaylistCardItem(playlist: PlaylistApi.PlaylistCard, onClick: () -> Unit, onPlayAll: () -> Unit) {
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