package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.library.LibraryManager
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.components.PlayAllButton
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.widget.Toast

// 新歌速递容量：一次装够，不做分页。首页只是一瞥的展示位，
// 不是深度浏览入口——用户想探索会去搜索/歌单。
private const val NEW_SONGS_LIMIT = 20

@Composable
fun HomeScreen(
    onSongClick: (SongItem) -> Unit,
    onPlaylistClick: (Long) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {},
    onPlayDailyAll: ((List<SongItem>) -> Unit)? = null,
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    onPlayFm: (() -> Unit)? = null
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
    var error by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // 当前登录用户 id：电台卡标题用。未登录时 uid 为 null，不渲染电台卡。
    var userId by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(Unit) {
        userId = runCatching { PlaylistApi.getCurrentUserId() }.getOrNull()
    }

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

    fun loadNewSongs() {
        coroutineScope.launch(Dispatchers.IO) {
            if (newSongs.isEmpty()) isLoading = true
            error = null
            try {
                val list = PlaylistApi.getTopSongs(limit = NEW_SONGS_LIMIT, offset = 0)
                withContext(Dispatchers.Main) {
                    newSongs.clear()
                    newSongs.addAll(list)
                    ContentCache.homeNewSongs = list.toList()
                    isLoading = false
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    error = strings.loadFailed(e.message)
                    isLoading = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        loadDailySongs()
        loadPlaylists()
        loadNewSongs()
    }

    val context = androidx.compose.ui.platform.LocalContext.current

    fun songMenu(song: SongItem): List<SongMenuAction> = listOf(
        SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
            LibraryManager.saveSong(context, song)
            Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
        },
        SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) { onSongInsertNext(song) },
        SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) { onSongAppendToQueue(song) }
    )

    Crossfade(
        targetState = isLoading,
        animationSpec = SokuouTweens.CoverFade,
        modifier = Modifier.fillMaxSize(),
        label = "HomeContentCrossfade"
    ) { loading ->
        if (loading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                MetroProgressIndicator(color = LocalMetroColors.current.primary)
            }
        } else {
            ResponsiveContent {
                LazyColumn(
                    state = listState,
                    // 背景由 MainScreen 外层 Box 统一填充，子屏不重复画一层（消除 overdraw）
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                    flingBehavior = rememberMetroFlingBehavior()
                ) {
                    // Groove 风页头：statusBar + 大字页面名，代替 TopAppBar。
                    item {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .statusBarsPadding()
                                .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
                        ) {
                            MetroText(
                                strings.tabHome,
                                color = Color.White,
                                style = LocalMetroTypography.current.pageHeading,
                            )
                        }
                        Spacer(Modifier.height(12.dp))
                    }

                    // 每日推荐：横滑大 tile；点击整块进入播放。
                    if (dailySongs.isNotEmpty()) {
                        item {
                            SectionHeader(
                                title = strings.dailySongsTitle,
                                onPlayAll = { onPlayDailyAll?.invoke(dailySongs) },
                                actions = {
                                    // 手动刷新每日推荐(#26):重新拉取并 diff 平滑替换
                                    MetroIconButton(onClick = { loadDailySongs() }) {
                                        MetroIcon(
                                            Icons.Default.Refresh,
                                            contentDescription = strings.refreshLabel,
                                            tint = Color.Gray,
                                            sizeDp = 22.dp
                                        )
                                    }
                                }
                            )
                        }
                        item {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                contentPadding = PaddingValues(horizontal = 0.dp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(dailySongs.take(12), key = { it.id }) { song ->
                                    DailySongTile(
                                        song = song,
                                        onClick = { onSongClick(song) },
                                        onLongClick = { onShowSongMenu(song, songMenu(song)) }
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(28.dp)) }
                    }

                    // 推荐歌单：横滑大 tile，首位放私人 FM 电台卡
                    if (playlists.isNotEmpty()) {
                        item { SectionHeader(title = strings.recommendPlaylistTitle) }
                        item {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                // 电台：形制同普通歌单 tile，标题"{uid}的电台"，副标题"无限播放"
                                if (userId != null && onPlayFm != null) {
                                    item(key = "fm") {
                                        FmRadioTile(
                                            uid = userId!!,
                                            title = strings.fmRadioTitle(userId!!),
                                            subtitle = strings.fmRadioSubtitle,
                                            onClick = { onPlayFm() }
                                        )
                                    }
                                }
                                items(playlists, key = { it.id }) { pl ->
                                    PlaylistTile(
                                        playlist = pl,
                                        onClick = { onPlaylistClick(pl.id) },
                                        onPlayAll = { onPlayPlaylist(pl.id) }
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(28.dp)) }
                    } else if (userId != null && onPlayFm != null) {
                        // 推荐歌单接口失败/未登录返回空时, 电台入口不能跟着消失——
                        // 这是用户唯一能进 FM 的通道, 单独给一行。
                        item { SectionHeader(title = strings.recommendPlaylistTitle) }
                        item {
                            LazyRow(
                                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                item(key = "fm") {
                                    FmRadioTile(
                                        uid = userId!!,
                                        title = strings.fmRadioTitle(userId!!),
                                        subtitle = strings.fmRadioSubtitle,
                                        onClick = { onPlayFm() }
                                    )
                                }
                            }
                        }
                        item { Spacer(Modifier.height(28.dp)) }
                    }

                    // 新歌：边到边直列
                    item { SectionHeader(title = strings.newSongsTitle) }
                    item { Spacer(Modifier.height(6.dp)) }
                    items(newSongs, key = { it.id }) { song ->
                        SongCard(
                            song = song,
                            style = SongCardStyle.LIST,
                            onClick = { onSongClick(song) },
                            onShowMenu = { onShowSongMenu(song, songMenu(song)) }
                        )
                    }
                }
            }
        }
    }
}

/** 分区标题：中字号 Regular，左对齐 16dp；右侧可选"播放全部"按钮 + 自定义动作。 */
@Composable
private fun SectionHeader(
    title: String,
    onPlayAll: (() -> Unit)? = null,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            title,
            color = Color.White,
            style = LocalMetroTypography.current.title,
            modifier = Modifier.weight(1f)
        )
        actions()
        if (onPlayAll != null) {
            MetroIconButton(onClick = onPlayAll) {
                MetroIcon(
                    Icons.Default.PlayArrow,
                    contentDescription = LocalStrings.current.playAllButton,
                    tint = LocalMetroColors.current.primary,
                    sizeDp = 28.dp,
                )
            }
        }
    }
}

/** 每日推荐大 tile：160dp 方封面，下方歌名 + 歌手。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun DailySongTile(song: SongItem, onClick: () -> Unit, onLongClick: () -> Unit) {
    val strings = LocalStrings.current
    Column(
        modifier = Modifier
            .width(160.dp)
            .combinedClickableFallback(onClick, onLongClick)
    ) {
        AsyncImage(
            model = CoverUrls.small(song.album?.picUrl),
            contentDescription = strings.coverDesc,
            modifier = Modifier.fillMaxWidth().aspectRatio(1f),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(6.dp))
        MetroText(
            song.name,
            color = Color.White,
            style = LocalMetroTypography.current.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        MetroText(
            song.artists?.joinToString("/") { it.name } ?: strings.unknownArtist,
            color = Color.Gray,
            style = LocalMetroTypography.current.label,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

/** 推荐歌单大 tile：160dp 方封面 + 圆播放按钮。 */
@Composable
private fun PlaylistTile(playlist: PlaylistApi.PlaylistCard, onClick: () -> Unit, onPlayAll: () -> Unit) {
    val strings = LocalStrings.current
    Column(modifier = Modifier.width(160.dp).clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = CoverUrls.small(playlist.coverUrl),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                size = 34.dp,
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(
            playlist.name,
            color = Color.White,
            style = LocalMetroTypography.current.caption,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        MetroText(
            strings.trackCountSongs(playlist.trackCount),
            color = Color.Gray,
            style = LocalMetroTypography.current.label,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

/** 私人 FM 电台大 tile：形制同 PlaylistTile，封面用大幅图标占位。 */
@Composable
private fun FmRadioTile(
    uid: Long,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Column(modifier = Modifier.width(160.dp).clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .background(LocalMetroColors.current.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            MetroText(
                uid.toString(),
                color = LocalMetroColors.current.primary,
                style = LocalMetroTypography.current.headlineMedium
            )
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                size = 34.dp,
                onClick = onClick
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(
            title,
            color = Color.White,
            style = LocalMetroTypography.current.caption,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        MetroText(
            subtitle,
            color = Color.Gray,
            style = LocalMetroTypography.current.label,
            modifier = Modifier.padding(horizontal = 6.dp)
        )
    }
}

/** 点击 + 长按合并到一个 modifier，避免每个 tile 内部重复样板。 */
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableFallback(onClick: () -> Unit, onLongClick: () -> Unit): Modifier =
    this.combinedClickable(onClick = onClick, onLongClick = onLongClick)
