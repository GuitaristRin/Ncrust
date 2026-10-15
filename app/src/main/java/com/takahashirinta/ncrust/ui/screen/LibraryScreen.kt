package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.account.SessionMonitor
import com.takahashirinta.ncrust.account.SessionState
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.ui.components.SessionExpiredBanner
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.controls.MetroTabItem
import io.github.takahashirinta.kanesumi.controls.MetroTabRow
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.components.PlayAllButton
import com.takahashirinta.ncrust.ui.components.PlayAllDialog
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import android.widget.Toast

@Composable
fun LibraryScreen(
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onPlayAlbum: (Long) -> Unit,
    onPlaylistClick: (PlaylistApi.PlaylistInfo) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    // 「单曲」tab 的整列操作：全量曲目由本屏按红心 id 表补齐（分页列表只有已加载的首页），
    // 拿到之后仍然交给 MainScreen 既有的队列函数，别在这里自己拼队列。
    onPlayAllSongs: (List<SongItem>) -> Unit = {},
    onInsertAllSongsNext: (List<SongItem>) -> Unit = {},
    onAppendAllSongs: (List<SongItem>) -> Unit = {},
    refreshTrigger: Int = 0,
    // 会话失效横幅点击 → 走现有登录流程（窄屏网页登录 / 宽屏扫码）。
    onRelogin: () -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current
    // 会话失效时顶部一条横幅：收藏刷不出来/只给试听的直接原因就在这里。
    // 必须 collectAsState 订阅：直接读 StateFlow.value 不会建立快照依赖，横幅永远不刷新。
    val sessionState by SessionMonitor.state.collectAsState()
    val sessionExpired = sessionState is SessionState.Expired

    var savedSongs by remember { mutableStateOf(LibraryManager.getSavedSongs(context)) }
    var savedAlbums by remember { mutableStateOf(LibraryManager.getSavedAlbums(context)) }
    // 未获云端确认的点赞操作数量：>0 时页面顶部提示"仅本地/待重试"，不冒充已同步。
    var pendingSyncCount by remember { mutableIntStateOf(0) }
    var selectedCategory by remember { mutableIntStateOf(0) }
    val categories = listOf(strings.categoryTracks, strings.categoryAlbums, strings.categoryPlaylists)

    var playlists by remember { mutableStateOf<List<PlaylistApi.PlaylistInfo>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }

    // 整列播放：全量详情按红心 id 表分批补齐（N 首可能上千，非瞬时），期间按钮禁用 + 文案变化，
    // 结束后才弹 PlayAllDialog——绝不让对话框里出现"点了没反应"的空白期。
    var isLoadingAllSongs by remember { mutableStateOf(false) }
    var loadingAllSongsTotal by remember { mutableIntStateOf(0) }
    var playAllSongs by remember { mutableStateOf<List<SongItem>?>(null) }

    fun startPlayAll() {
        if (isLoadingAllSongs) return
        coroutineScope.launch {
            isLoadingAllSongs = true
            try {
                // id 表长度即这次要补的曲目数；未登录/未同步时为 0（退回本地列表），文案改用通用 loading。
                val expected = LibraryManager.getLikedSongIds(context).size
                loadingAllSongsTotal = expected
                // loadAllLikedSongs 内部走 Dispatchers.IO，主线程不被拖住。
                val songs = LibraryManager.loadAllLikedSongs(context)
                if (songs.isEmpty()) {
                    Toast.makeText(context, strings.noSavedSongs, Toast.LENGTH_SHORT).show()
                    return@launch
                }
                // 批次失败或曲目已下架时只拿到一部分：如实提示，不假装全拿到了。
                if (expected > songs.size) {
                    Toast.makeText(context, strings.playAllPartial(expected - songs.size), Toast.LENGTH_LONG).show()
                }
                playAllSongs = songs
            } finally {
                // 异常退出也要复位，否则按钮永远停在"加载中"。
                isLoadingAllSongs = false
            }
        }
    }

    fun loadPlaylists() {
        coroutineScope.launch {
            isLoadingPlaylists = true
            playlistError = null
            try {
                if (!CookieManager.hasCookie(context)) {
                    playlistError = strings.notLoggedInForPlaylists
                } else {
                    val userId = PlaylistApi.getCurrentUserId()
                    val result = PlaylistApi.getUserPlaylists(userId)
                    playlists = result.playlists
                }
            } catch (e: Exception) {
                playlistError = if (!CookieManager.hasCookie(context))
                    strings.notLoggedInForPlaylists
                else strings.loadFailed(e.message)
            } finally {
                isLoadingPlaylists = false
            }
        }
    }

    fun reloadLocal() {
        savedSongs = LibraryManager.getSavedSongs(context)
        savedAlbums = LibraryManager.getSavedAlbums(context)
        pendingSyncCount = LibraryManager.getPendingSyncSongIds(context).size
    }

    LaunchedEffect(selectedCategory) {
        reloadLocal()
        if (selectedCategory == 2 && playlists.isEmpty() && !isLoadingPlaylists) {
            loadPlaylists()
        }
    }

    // 进入收藏页时后台拉取云端收藏（收藏单曲 + 收藏专辑），刷新后平滑 diff 更新。
    // 失败/未登录不影响展示——本地缓存先渲染，绝不出现空白+加载动画。
    LaunchedEffect(Unit) {
        LibraryManager.refreshFromCloud(context)
        reloadLocal()
    }

    // 登录完成后（cookieRefreshTrigger 变化）再拉一次云端，保证重新安装/刚登录后库非空。
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            LibraryManager.refreshFromCloud(context)
            reloadLocal()
        }
    }

    // 收藏单曲分页懒加载：滚动到列表末尾时拉取下一批详情。
    val songListState = rememberLazyListState()
    LaunchedEffect(selectedCategory) {
        if (selectedCategory != 0) return@LaunchedEffect
        snapshotFlow { songListState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { lastIndex ->
                val total = LibraryManager.getLikedSongIds(context).size
                if (lastIndex >= savedSongs.size - 5 && savedSongs.size < total) {
                    val more = LibraryManager.loadMoreLikedSongs(context)
                    if (more.isNotEmpty()) savedSongs = more
                }
            }
    }

    // 全量曲目到手后才弹对话框：三项动作都作用于这一份完整列表，而不是已加载的那一页。
    playAllSongs?.let { songs ->
        PlayAllDialog(
            songCount = songs.size,
            onDismiss = { playAllSongs = null },
            onReplaceAndPlay = { onPlayAllSongs(songs) },
            onInsertNext = { onInsertAllSongsNext(songs) },
            onAppendToQueue = { onAppendAllSongs(songs) }
        )
    }

    Box(modifier = Modifier.fillMaxSize()) {
    ResponsiveContent {
        // 背景由 MainScreen 外层 Box 统一填充，此处不重复画一层
        Column(modifier = Modifier.fillMaxSize()) {
            // Groove 风大字页头。
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            ) {
                MetroText(
                    strings.tabLibrary,
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.pageHeading,
                )
            }
            Spacer(Modifier.height(4.dp))
            if (sessionExpired) {
                SessionExpiredBanner(onRelogin = onRelogin)
                Spacer(Modifier.height(4.dp))
            }
            MetroTabRow(
                selectedTabIndex = selectedCategory,
                items = categories.map { MetroTabItem(it) },
                onTabSelected = { index -> selectedCategory = index }
            )

            // Tab 切换用 AnimatedContent 做方向感知的横向滑入。
            // 距离 1/16 屏宽（比 NavGraph 的 1/8 更轻——同页内切 tab，不应有"翻页"的重量感）；
            // 200ms MetroDefault 曲线。向右切（index 增）新页从右入、旧页向左出；向左切反之。
            AnimatedContent(
                targetState = selectedCategory,
                transitionSpec = {
                    val goingRight = targetState > initialState
                    val enterSign = if (goingRight) 1 else -1
                    val exitSign = if (goingRight) -1 else 1
                    (slideInHorizontally(
                        animationSpec = tween(200, easing = MetroDefault),
                        initialOffsetX = { fullWidth -> enterSign * fullWidth / 16 }
                    ) + fadeIn(animationSpec = tween(160, easing = MetroDefault))) togetherWith
                    (slideOutHorizontally(
                        animationSpec = tween(200, easing = MetroDefault),
                        targetOffsetX = { fullWidth -> exitSign * fullWidth / 16 }
                    ) + fadeOut(animationSpec = tween(160, easing = MetroDefault)))
                },
                modifier = Modifier.fillMaxSize(),
                label = "LibraryCategoryContent"
            ) { category -> when (category) {
                0 -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        if (pendingSyncCount > 0) {
                            PendingSyncNotice(
                                message = strings.pendingSyncCount(pendingSyncCount),
                                retryLabel = strings.retrySync,
                                onRetry = {
                                    coroutineScope.launch {
                                        LibraryManager.retryPendingSync(context)
                                        reloadLocal()
                                    }
                                }
                            )
                        }
                        // 红心 id 表才是全量（savedSongs 只是已分页加载的首页）。
                        // 未登录/未同步时 id 表为空，退回本地已存列表，故两者取其一判断"有没有东西可放"。
                        val likedIdCount = LibraryManager.getLikedSongIds(context).size
                        val playAllCount = if (likedIdCount > 0) likedIdCount else savedSongs.size
                        if (savedSongs.isEmpty() && likedIdCount == 0) {
                            Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                                MetroText(strings.noSavedSongs, color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodyLarge)
                            }
                        } else {
                            LibraryPlayAllRow(
                                songCount = playAllCount,
                                isLoading = isLoadingAllSongs,
                                loadingLabel = if (loadingAllSongsTotal > 0)
                                    strings.loadingSongsCount(loadingAllSongsTotal)
                                else strings.loading,
                                onClick = { startPlayAll() }
                            )
                            LazyColumn(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                state = songListState,
                                contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(savedSongs, key = { it.id }) { song ->
                                    SongCard(
                                        song = song,
                                        style = SongCardStyle.LIST,
                                        onClick = { onSongClick(song) },
                                        onShowMenu = {
                                            val actions = mutableListOf(
                                                SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                                    LibraryManager.saveSong(context, song)
                                                    Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                                },
                                                SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) {
                                                    onSongInsertNext(song)
                                                },
                                                SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) {
                                                    onSongAppendToQueue(song)
                                                },
                                                SongMenuAction(Icons.Default.Delete, strings.actionRemoveFromLibrary) {
                                                    LibraryManager.removeSong(context, song.id)
                                                    reloadLocal()
                                                }
                                            )
                                            // 该歌云端未确认：给出显式"重试同步"入口，标明它是仅本地状态。
                                            if (LibraryManager.isSongPendingSync(context, song.id)) {
                                                actions.add(
                                                    SongMenuAction(Icons.Default.Sync, strings.retrySync) {
                                                        coroutineScope.launch {
                                                            LibraryManager.retryPendingSync(context)
                                                            reloadLocal()
                                                        }
                                                    }
                                                )
                                            }
                                            onShowSongMenu(song, actions)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }

                1 -> {
                    if (savedAlbums.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            MetroText(strings.noSavedAlbums, color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodyLarge)
                        }
                    } else {
                        // 自适应栅格：列数随内容栏宽度变化（宽屏多列），取代写死的 2 列。
                        LazyVerticalGrid(
                            columns = GridCells.Adaptive(minSize = 160.dp),
                            modifier = Modifier.fillMaxSize(),
                            horizontalArrangement = Arrangement.spacedBy(2.dp),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                            flingBehavior = rememberMetroFlingBehavior()
                        ) {
                            items(savedAlbums, key = { it.albumId }) { album ->
                                LibraryAlbumGridItem(
                                    album = album,
                                    modifier = Modifier.fillMaxWidth().animateItem(
                                        fadeInSpec = tween(150, easing = MetroDefault),
                                        placementSpec = tween(220, easing = MetroDefault),
                                        fadeOutSpec = tween(120, easing = MetroDefault)
                                    ),
                                    onClick = { onAlbumClick(album.albumId) },
                                    onPlayAll = { onPlayAlbum(album.albumId) }
                                )
                            }
                        }
                    }
                }

                2 -> {
                    when {
                        isLoadingPlaylists -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                MetroProgressIndicator(color = LocalMetroColors.current.primary)
                            }
                        }
                        playlistError != null -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    MetroText(playlistError!!, color = Color.Red, style = LocalMetroTypography.current.bodyMedium)
                                    Spacer(Modifier.height(8.dp))
                                    Box(
                                        modifier = Modifier
                                            .clickable(onClick = { loadPlaylists() })
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                    ) {
                                        MetroText(strings.retry, color = LocalMetroColors.current.primary)
                                    }
                                }
                            }
                        }
                        playlists.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                MetroText(strings.noPlaylists, color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodyLarge)
                            }
                        }
                        else -> {
                            LazyVerticalGrid(
                                columns = GridCells.Adaptive(minSize = 160.dp),
                                modifier = Modifier.fillMaxSize(),
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                                contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(playlists, key = { it.id }) { pl ->
                                    PlaylistGridItem(
                                        playlist = pl,
                                        modifier = Modifier.fillMaxWidth().animateItem(
                                            fadeInSpec = tween(150, easing = MetroDefault),
                                            placementSpec = tween(220, easing = MetroDefault),
                                            fadeOutSpec = tween(120, easing = MetroDefault)
                                        ),
                                        onClick = { onPlaylistClick(pl) },
                                        onPlayAll = { onPlayPlaylist(pl.id) }
                                    )
                                }
                            }
                        }
                    }
                }
            } }
        }
    }

    }
}

/**
 * 「单曲」tab 顶部的整列播放入口。
 *
 * 复用专辑/歌单 tab 的 [PlayAllButton]（Kanesumi 里刻意做成圆形，用户决策），视觉上与网格
 * 卡片右下角那颗是同一个控件；这里多一行文案，因为歌曲列表没有封面可以承载它。
 * 加载期间按钮降透明度 + 禁用、副标题变「正在加载 N 首…」，避免点了没反应。
 */
@Composable
private fun LibraryPlayAllRow(
    songCount: Int,
    isLoading: Boolean,
    loadingLabel: String,
    onClick: () -> Unit
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isLoading, onClick = onClick)
            .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlayAllButton(
            modifier = Modifier.alpha(if (isLoading) 0.4f else 1f),
            size = 44.dp,
            onClick = { if (!isLoading) onClick() }
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            MetroText(
                strings.playAllButton,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.bodyLarge
            )
            MetroText(
                if (isLoading) loadingLabel else strings.songCountFormat(songCount),
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodySmall
            )
        }
        if (isLoading) {
            MetroProgressIndicator(
                color = LocalMetroColors.current.primary,
                sizeDp = 18.dp,
                strokeDp = 2.dp
            )
        }
    }
}

@Composable
fun PlaylistGridItem(
    playlist: PlaylistApi.PlaylistInfo,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onPlayAll: () -> Unit
) {
    val strings = LocalStrings.current
    Column(modifier = modifier.clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = CoverUrls.small(playlist.coverImgUrl),
                contentDescription = strings.playlistCoverDesc,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(playlist.name, color = LocalMetroColors.current.onBackground, style = LocalMetroTypography.current.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        MetroText(strings.trackCount(playlist.trackCount), color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodySmall, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(6.dp))
    }
}

/** "仅本地/待重试"提示条：明确告知收藏尚未同步到云端，并提供重试入口。 */
@Composable
private fun PendingSyncNotice(
    message: String,
    retryLabel: String,
    onRetry: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            message,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Box(
            modifier = Modifier
                .clickable(onClick = onRetry)
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            MetroText(
                retryLabel,
                color = LocalMetroColors.current.primary,
                style = LocalMetroTypography.current.bodyMedium
            )
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
    val strings = LocalStrings.current
    Column(modifier = modifier.clickable { onClick() }) {
        Box(modifier = Modifier.fillMaxWidth().aspectRatio(1f)) {
            AsyncImage(
                model = CoverUrls.small(album.picUrl),
                contentDescription = strings.albumCoverDesc,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            PlayAllButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp),
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(6.dp))
        MetroText(album.name, color = LocalMetroColors.current.onBackground, style = LocalMetroTypography.current.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        MetroText(strings.albumArtistAndCount(album.artist, album.songCount), color = LocalMetroColors.current.onSurfaceVariant, style = LocalMetroTypography.current.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(horizontal = 6.dp))
        Spacer(Modifier.height(6.dp))
    }
}