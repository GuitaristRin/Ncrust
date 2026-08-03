package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.library.SearchHistoryManager
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.components.AlbumSearchItem
import com.takahashirinta.ncrust.ui.components.ArtistSearchItem
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.desaturateColor
import com.takahashirinta.ncrust.ui.theme.themeColorForIndex
import com.takahashirinta.ncrust.ui.viewmodel.SearchViewModel
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenu
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenuItem
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.controls.MetroTabItem
import io.github.takahashirinta.kanesumi.controls.MetroTabRow
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import android.widget.Toast

enum class BatchQueueAction { PLAY_NOW, INSERT_NEXT, APPEND }

@Composable
fun SearchScreen(
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onInsertNext: (SongItem) -> Unit = {},
    onAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    onAlbumBatch: (albumId: Long, action: BatchQueueAction) -> Unit = { _, _ -> },
    onArtistBatch: (artistName: String, action: BatchQueueAction) -> Unit = { _, _ -> },
    themeIndex: Int = 0
) {
    val viewModel: SearchViewModel = viewModel()
    val query by viewModel.query.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentType by viewModel.currentType.collectAsState()
    val context = LocalContext.current
    val strings = LocalStrings.current
    val categories = listOf(strings.searchCategoryTracks, strings.searchCategoryAlbums, strings.searchCategoryArtists)

    val currentThemeColor = themeColorForIndex(themeIndex)
    val desaturatedFill = desaturateColor(currentThemeColor)

    // Search history state — loaded from SharedPreferences, refreshed whenever query clears
    var songHistory by remember { mutableStateOf(SearchHistoryManager.getSongs(context)) }
    var albumHistory by remember { mutableStateOf(SearchHistoryManager.getAlbums(context)) }
    var artistHistory by remember { mutableStateOf(SearchHistoryManager.getArtists(context)) }

    fun refreshHistory() {
        songHistory = SearchHistoryManager.getSongs(context)
        albumHistory = SearchHistoryManager.getAlbums(context)
        artistHistory = SearchHistoryManager.getArtists(context)
    }

    LaunchedEffect(query) {
        if (query.isEmpty()) refreshHistory()
    }

    val showHistory = query.isEmpty() &&
        (songHistory.isNotEmpty() || albumHistory.isNotEmpty() || artistHistory.isNotEmpty())

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景由 MainScreen 外层 Box 统一填充，此处不重复画一层
        Column(modifier = Modifier.fillMaxSize()) {
            // Search input
            // BasicTextField 本身没有 M3 TextField 的隐式 56dp min-height 与内 padding，
            // 得手动在 decorationBox 里补齐——heightIn(min=56.dp) 保住触控区高度，内容
            // 纵向居中、左右 16dp 内边距对齐 M3 视觉。否则搜索框会塌成一条 20dp 高的
            // 细条，看着像被压扁的 Chip。
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(desaturatedFill)
            ) {
                BasicTextField(
                    value = query,
                    onValueChange = { viewModel.onQueryChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = {}),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    textStyle = TextStyle(color = Color.White, fontSize = 18.sp),
                    cursorBrush = SolidColor(Color.White),
                    decorationBox = { innerTextField ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 56.dp)
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            if (query.isEmpty()) {
                                MetroText(
                                    text = strings.searchPlaceholder,
                                    color = Color.White.copy(alpha = 0.5f),
                                    style = TextStyle(fontSize = 18.sp),
                                )
                            }
                            innerTextField()
                            Row(
                                modifier = Modifier.align(Alignment.CenterEnd),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (isLoading) MetroProgressIndicator(
                                    sizeDp = 24.dp,
                                    color = currentThemeColor,
                                )
                                if (query.isNotEmpty()) MetroIconButton(onClick = { viewModel.clearQuery() }) {
                                    MetroIcon(
                                        imageVector = Icons.Default.Clear,
                                        contentDescription = strings.clearSearchButton,
                                        tint = Color.White.copy(alpha = 0.7f),
                                    )
                                }
                            }
                        }
                    }
                )
            }

            // 三态过渡：History（有历史时空 query）/ Results（输入非空）/ Empty（空 query 无历史）。
                // 用 Crossfade + SokuouTweens.CoverFade 消除清空搜索框时"结果列表 → 历史"的硬切。
                val searchContentState = when {
                    showHistory -> SearchContentState.History
                    query.isNotEmpty() -> SearchContentState.Results
                    else -> SearchContentState.Empty
                }
                Crossfade(
                    targetState = searchContentState,
                    animationSpec = SokuouTweens.CoverFade,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    label = "SearchContentCrossfade"
                ) { state -> when (state) {
                    SearchContentState.History -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                            flingBehavior = rememberMetroFlingBehavior()
                        ) {
                    if (songHistory.isNotEmpty()) {
                        item {
                            SearchHistorySectionHeader(
                                title = strings.searchCategoryTracks,
                                onClear = {
                                    SearchHistoryManager.clearSection(context, SearchHistoryManager.TYPE_SONG)
                                    refreshHistory()
                                },
                                clearLabel = strings.searchHistoryClear
                            )
                        }
                        items(songHistory, key = { "s_${it.id}" }) { item ->
                            val song = item.toSongItem()
                            SearchHistoryItemCard(
                                item = item,
                                onClick = {
                                    onSongClick(song)
                                },
                                menuContent = { onDismiss ->
                                    MetroDropdownMenuItem(
                                        text = strings.playButton,
                                        textColor = Color.White,
                                        onClick = { onDismiss(); onSongClick(song) },
                                    )
                                    MetroDropdownMenuItem(
                                        text = strings.actionInsertNext,
                                        textColor = Color.White,
                                        onClick = { onDismiss(); onInsertNext(song) },
                                    )
                                    MetroDropdownMenuItem(
                                        text = strings.actionAddToLibrary,
                                        textColor = Color.White,
                                        onClick = {
                                            onDismiss()
                                            LibraryManager.saveSong(context, song)
                                            Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                        },
                                    )
                                    MetroDropdownMenuItem(
                                        text = strings.searchHistoryDelete,
                                        textColor = Color.Red.copy(alpha = 0.85f),
                                        onClick = {
                                            onDismiss()
                                            SearchHistoryManager.remove(context, SearchHistoryManager.TYPE_SONG, item.id)
                                            refreshHistory()
                                        },
                                    )
                                }
                            )
                        }
                    }

                    if (albumHistory.isNotEmpty()) {
                        item {
                            SearchHistorySectionHeader(
                                title = strings.searchCategoryAlbums,
                                onClear = {
                                    SearchHistoryManager.clearSection(context, SearchHistoryManager.TYPE_ALBUM)
                                    refreshHistory()
                                },
                                clearLabel = strings.searchHistoryClear
                            )
                        }
                        items(albumHistory, key = { "a_${it.id}" }) { item ->
                            SearchHistoryItemCard(
                                item = item,
                                onClick = { onAlbumClick(item.id) },
                                menuContent = { onDismiss ->
                                    MetroDropdownMenuItem(
                                        text = strings.albumDetailTitle,
                                        textColor = Color.White,
                                        onClick = { onDismiss(); onAlbumClick(item.id) },
                                    )
                                    MetroDropdownMenuItem(
                                        text = strings.searchHistoryDelete,
                                        textColor = Color.Red.copy(alpha = 0.85f),
                                        onClick = {
                                            onDismiss()
                                            SearchHistoryManager.remove(context, SearchHistoryManager.TYPE_ALBUM, item.id)
                                            refreshHistory()
                                        },
                                    )
                                }
                            )
                        }
                    }

                    if (artistHistory.isNotEmpty()) {
                        item {
                            SearchHistorySectionHeader(
                                title = strings.searchCategoryArtists,
                                onClear = {
                                    SearchHistoryManager.clearSection(context, SearchHistoryManager.TYPE_ARTIST)
                                    refreshHistory()
                                },
                                clearLabel = strings.searchHistoryClear
                            )
                        }
                        items(artistHistory, key = { "r_${it.id}" }) { item ->
                            SearchHistoryItemCard(
                                item = item,
                                onClick = { onArtistClick(item.id) },
                                menuContent = { onDismiss ->
                                    MetroDropdownMenuItem(
                                        text = strings.artistDetailTitle,
                                        textColor = Color.White,
                                        onClick = { onDismiss(); onArtistClick(item.id) },
                                    )
                                    MetroDropdownMenuItem(
                                        text = strings.searchHistoryDelete,
                                        textColor = Color.Red.copy(alpha = 0.85f),
                                        onClick = {
                                            onDismiss()
                                            SearchHistoryManager.remove(context, SearchHistoryManager.TYPE_ARTIST, item.id)
                                            refreshHistory()
                                        },
                                    )
                                }
                            )
                        }
                    }

                        }
                    }
                    SearchContentState.Results -> {
                        Column(modifier = Modifier.fillMaxSize()) {
                            MetroTabRow(
                                items = categories.map { MetroTabItem(it) },
                                selectedTabIndex = when (currentType) {
                                    1 -> 0
                                    10 -> 1
                                    100 -> 2
                                    else -> 0
                                },
                                onTabSelected = { index ->
                                    viewModel.onTypeChanged(
                                        when (index) {
                                            0 -> 1
                                            1 -> 10
                                            2 -> 100
                                            else -> 1
                                        }
                                    )
                                }
                            )

                error?.let {
                    MetroText(
                        text = strings.loadFailed(it),
                        color = Color.Red,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }

                when (currentType) {
                    1 -> {
                        if (songs.isEmpty() && !isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                MetroText(
                                    text = strings.searchSongsEmpty,
                                    color = Color.Gray,
                                    style = TextStyle(fontSize = 16.sp),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(songs, key = { it.id }) { item ->
                                    SongCard(
                                        song = item,
                                        style = SongCardStyle.LIST,
                                        coverSize = 72.dp,
                                        onClick = {
                                            SearchHistoryManager.addSong(context, item)
                                            onSongClick(item)
                                        },
                                        onShowMenu = {
                                            onShowSongMenu(item, listOf(
                                                SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                                    SearchHistoryManager.addSong(context, item)
                                                    LibraryManager.saveSong(context, item)
                                                    Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                                },
                                                SongMenuAction(Icons.Default.PlaylistPlay, strings.actionInsertNext) {
                                                    SearchHistoryManager.addSong(context, item)
                                                    onInsertNext(item)
                                                },
                                                SongMenuAction(Icons.Default.PlaylistAdd, strings.actionAppendToQueue) {
                                                    SearchHistoryManager.addSong(context, item)
                                                    onAppendToQueue(item)
                                                }
                                            ))
                                        }
                                    )
                                }
                            }
                        }
                    }

                    10 -> {
                        if (albums.isEmpty() && !isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                MetroText(
                                    text = strings.searchAlbumsEmpty,
                                    color = Color.Gray,
                                    style = TextStyle(fontSize = 16.sp),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(albums, key = { it.id }) { album ->
                                    AlbumSearchItem(
                                        album = album,
                                        onClick = {
                                            SearchHistoryManager.addAlbum(context, album)
                                            onAlbumClick(album.id)
                                        },
                                        menuContent = {
                                            // AlbumSearchItem 内部仍是 M3 DropdownMenuItem
                                            // (那个组件本次未迁,菜单契约保持不变)。
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { androidx.compose.material3.Text(strings.playAllButton, color = Color.White, fontSize = 14.sp) },
                                                onClick = {
                                                    SearchHistoryManager.addAlbum(context, album)
                                                    onAlbumBatch(album.id, BatchQueueAction.PLAY_NOW)
                                                }
                                            )
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { androidx.compose.material3.Text(strings.actionInsertNext, color = Color.White, fontSize = 14.sp) },
                                                onClick = {
                                                    SearchHistoryManager.addAlbum(context, album)
                                                    onAlbumBatch(album.id, BatchQueueAction.INSERT_NEXT)
                                                }
                                            )
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { androidx.compose.material3.Text(strings.actionAppendToQueue, color = Color.White, fontSize = 14.sp) },
                                                onClick = {
                                                    SearchHistoryManager.addAlbum(context, album)
                                                    onAlbumBatch(album.id, BatchQueueAction.APPEND)
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }

                    100 -> {
                        if (artists.isEmpty() && !isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                MetroText(
                                    text = strings.searchArtistsEmpty,
                                    color = Color.Gray,
                                    style = TextStyle(fontSize = 16.sp),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                                flingBehavior = rememberMetroFlingBehavior()
                            ) {
                                items(artists, key = { it.id }) { artist ->
                                    ArtistSearchItem(
                                        artist = artist,
                                        onClick = {
                                            SearchHistoryManager.addArtist(context, artist)
                                            onArtistClick(artist.id)
                                        },
                                        menuContent = {
                                            // 同上,ArtistSearchItem 未迁,菜单契约保持 M3。
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { androidx.compose.material3.Text(strings.playAllButton, color = Color.White, fontSize = 14.sp) },
                                                onClick = {
                                                    SearchHistoryManager.addArtist(context, artist)
                                                    onArtistBatch(artist.name, BatchQueueAction.PLAY_NOW)
                                                }
                                            )
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { androidx.compose.material3.Text(strings.actionInsertNext, color = Color.White, fontSize = 14.sp) },
                                                onClick = {
                                                    SearchHistoryManager.addArtist(context, artist)
                                                    onArtistBatch(artist.name, BatchQueueAction.INSERT_NEXT)
                                                }
                                            )
                                            androidx.compose.material3.DropdownMenuItem(
                                                text = { androidx.compose.material3.Text(strings.actionAppendToQueue, color = Color.White, fontSize = 14.sp) },
                                                onClick = {
                                                    SearchHistoryManager.addArtist(context, artist)
                                                    onArtistBatch(artist.name, BatchQueueAction.APPEND)
                                                }
                                            )
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
                        }
                    }
                    SearchContentState.Empty -> {
                        // Query 空且无历史：只留搜索框在上面，下方空白。
                        Spacer(Modifier.fillMaxSize())
                    }
                } }
        }
    }
}

private enum class SearchContentState { History, Results, Empty }

@Composable
private fun SearchHistorySectionHeader(title: String, clearLabel: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            text = title,
            color = Color.White,
            style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f),
        )
        // TextButton 替换:直角矩形 tap target,无背景 -- 与 Metro 的 "borderless"
        // 原则契合。padding 补齐视觉高度。
        Box(
            modifier = Modifier
                .clickable(onClick = onClear)
                .padding(horizontal = 12.dp, vertical = 8.dp),
        ) {
            MetroText(
                text = clearLabel,
                color = Color.Gray,
                style = TextStyle(fontSize = 12.sp),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchHistoryItemCard(
    item: SearchHistoryManager.HistoryItem,
    onClick: () -> Unit,
    menuContent: @Composable ColumnScope.(onDismiss: () -> Unit) -> Unit,
) {
    var showMenu by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = onClick,
                    onLongClick = { showMenu = true }
                )
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            AsyncImage(
                model = item.coverUrl,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                contentScale = ContentScale.Crop
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                MetroText(
                    text = item.title,
                    color = Color.White,
                    style = LocalMetroTypography.current.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!item.subtitle.isNullOrEmpty()) {
                    MetroText(
                        text = item.subtitle,
                        color = Color.Gray,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        MetroDropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            containerColor = Color(0xFF282828),
        ) {
            menuContent { showMenu = false }
        }
    }
}

private fun SearchHistoryManager.HistoryItem.toSongItem(): SongItem = SongItem(
    id = id,
    name = title,
    artists = subtitle?.let { listOf(ArtistItem(name = it)) },
    album = AlbumItem(id = null, name = null, picUrl = coverUrl),
    duration = null
)

@Composable
fun SongSearchItem(
    song: SongItem,
    onPlay: () -> Unit,
    onAddToLibrary: () -> Unit,
    onInsertNext: () -> Unit = {},
    onAppendToQueue: () -> Unit = {}
) {
    val strings = LocalStrings.current
    SongCard(
        song = song,
        style = SongCardStyle.LIST,
        coverSize = 56.dp,
        onClick = onPlay,
        actions = {
            MetroIconButton(onClick = onAddToLibrary) {
                MetroIcon(
                    imageVector = Icons.Default.Add,
                    contentDescription = strings.actionAddToLibrary,
                    tint = Color.White,
                )
            }
            MetroIconButton(onClick = onInsertNext) {
                MetroIcon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = strings.actionInsertNext,
                    tint = Color.White,
                )
            }
            MetroIconButton(onClick = onAppendToQueue) {
                MetroIcon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = strings.actionAddToPlaylist,
                    tint = Color.White,
                )
            }
        }
    )
}
