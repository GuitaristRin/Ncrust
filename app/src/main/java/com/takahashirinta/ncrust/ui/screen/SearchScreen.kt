package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.AlbumSearchItem
import com.takahashirinta.ncrust.network.ArtistSearchItem
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.components.AlbumSearchItem
import com.takahashirinta.ncrust.ui.components.ArtistSearchItem
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.viewmodel.SearchViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.text.TextStyle
import androidx.compose.material3.LocalTextStyle
import com.takahashirinta.ncrust.ui.theme.themeColorForIndex
import com.takahashirinta.ncrust.ui.theme.desaturateColor

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
    val categories = listOf("单曲", "专辑", "艺人")

    val currentThemeColor = themeColorForIndex(themeIndex)
    val desaturatedFill = desaturateColor(currentThemeColor)
    Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
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
                        "搜索歌曲、专辑、艺人",
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
                            Icon(Icons.Default.Clear, "清空", tint = Color.White.copy(alpha = 0.7f))
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
                it,
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
                        Text("搜索歌曲", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(songs, key = { it.id }) { item ->
                            SongCard(
                                song = item,
                                style = SongCardStyle.LIST,
                                coverSize = 56.dp,
                                onClick = { onSongClick(item) },
                                onShowMenu = {
                                    onShowSongMenu(item, listOf(
                                        SongMenuAction(Icons.Default.LibraryAdd, "加入库") {
                                            LibraryManager.saveSong(context, item)
                                        },
                                        SongMenuAction(Icons.Default.PlaylistPlay, "插播") {
                                            onInsertNext(item)
                                        },
                                        SongMenuAction(Icons.Default.PlaylistAdd, "最后播放") {
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
                        Text("搜索专辑", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(albums, key = { it.id }) { album ->
                            AlbumSearchItem(
                                album = album,
                                onClick = { onAlbumClick(album.id) }
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
                        Text("搜索艺人", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(artists, key = { it.id }) { artist ->
                            ArtistSearchItem(
                                artist = artist,
                                onClick = { onArtistClick(artist.id) }
                            )
                        }
                    }
                }
            }
        }
    }

    }
}

@Composable
fun SongSearchItem(
    song: SongItem,
    onPlay: () -> Unit,
    onAddToLibrary: () -> Unit,
    onInsertNext: () -> Unit = {},
    onAppendToQueue: () -> Unit = {}
) {
    SongCard(
        song = song,
        style = SongCardStyle.LIST,
        coverSize = 56.dp,
        onClick = onPlay,
        actions = {
            IconButton(onClick = onAddToLibrary) {
                Icon(
                    Icons.Default.Add,
                    "加入库",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onInsertNext) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    "插播",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
            IconButton(onClick = onAppendToQueue) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistAdd,
                    "加入播放列表",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    )
}
