package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.library.SearchHistoryManager
import com.takahashirinta.ncrust.network.AlbumSearchItem
import com.takahashirinta.ncrust.network.ArtistSearchItem
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.ui.components.AlbumSearchItem
import com.takahashirinta.ncrust.ui.components.ArtistSearchItem
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.viewmodel.SearchViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalTextStyle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.theme.themeColorForIndex
import com.takahashirinta.ncrust.ui.theme.desaturateColor
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onInsertNext: (SongItem) -> Unit = {},
    onAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
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
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            // Search input
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .background(desaturatedFill)
            ) {
                TextField(
                    value = query,
                    onValueChange = { viewModel.onQueryChanged(it) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = {}),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    placeholder = {
                        Text(
                            strings.searchPlaceholder,
                            color = Color.White.copy(alpha = 0.5f)
                        )
                    },
                    textStyle = LocalTextStyle.current.copy(color = Color.White),
                    trailingIcon = {
                        Row {
                            if (isLoading) CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = currentThemeColor
                            )
                            if (query.isNotEmpty()) IconButton(onClick = { viewModel.clearQuery() }) {
                                Icon(Icons.Default.Clear, strings.clearSearchButton, tint = Color.White.copy(alpha = 0.7f))
                            }
                        }
                    },
                    colors = TextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedContainerColor = Color.Transparent,
                        unfocusedContainerColor = Color.Transparent,
                        cursorColor = Color.White,
                        focusedIndicatorColor = Color.Transparent,
                        unfocusedIndicatorColor = Color.Transparent
                    )
                )
            }

            if (showHistory) {
                // History view — shown when query is empty and history exists
                LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                                menuContent = {
                                    DropdownMenuItem(
                                        text = { Text(strings.playButton, color = Color.White, fontSize = 14.sp) },
                                        onClick = { onSongClick(song) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.actionInsertNext, color = Color.White, fontSize = 14.sp) },
                                        onClick = { onInsertNext(song) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.actionAddToLibrary, color = Color.White, fontSize = 14.sp) },
                                        onClick = { LibraryManager.saveSong(context, song)
 Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show() }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.searchHistoryDelete, color = Color.Red.copy(alpha = 0.85f), fontSize = 14.sp) },
                                        onClick = {
                                            SearchHistoryManager.remove(context, SearchHistoryManager.TYPE_SONG, item.id)
                                            refreshHistory()
                                        }
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
                                menuContent = {
                                    DropdownMenuItem(
                                        text = { Text(strings.albumDetailTitle, color = Color.White, fontSize = 14.sp) },
                                        onClick = { onAlbumClick(item.id) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.searchHistoryDelete, color = Color.Red.copy(alpha = 0.85f), fontSize = 14.sp) },
                                        onClick = {
                                            SearchHistoryManager.remove(context, SearchHistoryManager.TYPE_ALBUM, item.id)
                                            refreshHistory()
                                        }
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
                                menuContent = {
                                    DropdownMenuItem(
                                        text = { Text(strings.artistDetailTitle, color = Color.White, fontSize = 14.sp) },
                                        onClick = { onArtistClick(item.id) }
                                    )
                                    DropdownMenuItem(
                                        text = { Text(strings.searchHistoryDelete, color = Color.Red.copy(alpha = 0.85f), fontSize = 14.sp) },
                                        onClick = {
                                            SearchHistoryManager.remove(context, SearchHistoryManager.TYPE_ARTIST, item.id)
                                            refreshHistory()
                                        }
                                    )
                                }
                            )
                        }
                    }

                    item { Spacer(Modifier.height(72.dp)) }
                }
            } else if (query.isNotEmpty()) {
                // Search results
                TabRow(
                    selectedTabIndex = when (currentType) {
                        1 -> 0
                        10 -> 1
                        100 -> 2
                        else -> 0
                    },
                    containerColor = MaterialTheme.colorScheme.background,
                    contentColor = MaterialTheme.colorScheme.primary
                ) {
                    categories.forEachIndexed { index, title ->
                        Tab(
                            selected = when {
                                index == 0 -> currentType == 1
                                index == 1 -> currentType == 10
                                index == 2 -> currentType == 100
                                else -> false
                            },
                            onClick = {
                                viewModel.onTypeChanged(
                                    when (index) {
                                        0 -> 1
                                        1 -> 10
                                        2 -> 100
                                        else -> 1
                                    }
                                )
                            },
                            text = {
                                Text(
                                    title,
                                    color = when {
                                        index == 0 && currentType == 1 -> MaterialTheme.colorScheme.primary
                                        index == 1 && currentType == 10 -> MaterialTheme.colorScheme.primary
                                        index == 2 && currentType == 100 -> MaterialTheme.colorScheme.primary
                                        else -> Color.Gray
                                    },
                                    fontSize = 14.sp
                                )
                            }
                        )
                    }
                }

                error?.let {
                    Text(
                        strings.loadFailed(it),
                        color = Color.Red,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                when (currentType) {
                    1 -> {
                        if (songs.isEmpty() && !isLoading) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(strings.searchSongsEmpty, color = Color.Gray, fontSize = 16.sp)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
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
                                Text(strings.searchAlbumsEmpty, color = Color.Gray, fontSize = 16.sp)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(albums, key = { it.id }) { album ->
                                    AlbumSearchItem(
                                        album = album,
                                        onClick = {
                                            SearchHistoryManager.addAlbum(context, album)
                                            onAlbumClick(album.id)
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
                                Text(strings.searchArtistsEmpty, color = Color.Gray, fontSize = 16.sp)
                            }
                        } else {
                            LazyColumn(modifier = Modifier.fillMaxSize()) {
                                items(artists, key = { it.id }) { artist ->
                                    ArtistSearchItem(
                                        artist = artist,
                                        onClick = {
                                            SearchHistoryManager.addArtist(context, artist)
                                            onArtistClick(artist.id)
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }
            // If query is empty and no history: show nothing (just the search bar)
        }
    }
}

@Composable
private fun SearchHistorySectionHeader(title: String, clearLabel: String, onClear: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            title,
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClear) {
            Text(clearLabel, color = Color.Gray, fontSize = 12.sp)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SearchHistoryItemCard(
    item: SearchHistoryManager.HistoryItem,
    onClick: () -> Unit,
    menuContent: @Composable ColumnScope.() -> Unit
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
                Text(
                    item.title,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!item.subtitle.isNullOrEmpty()) {
                    Text(
                        item.subtitle,
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
        DropdownMenu(
            expanded = showMenu,
            onDismissRequest = { showMenu = false },
            shape = RectangleShape,
            containerColor = Color(0xFF282828)
        ) {
            menuContent()
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
            IconButton(onClick = onAddToLibrary) {
                Icon(
                    Icons.Default.Add,
                    strings.actionAddToLibrary,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onInsertNext) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    strings.actionInsertNext,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onAppendToQueue) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    strings.actionAddToPlaylist,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    )
}
