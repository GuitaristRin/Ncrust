package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.ui.components.PlayAllCircleButton
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import android.widget.Toast

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onPlayAlbum: (Long) -> Unit,
    onPlaylistClick: (PlaylistApi.PlaylistInfo) -> Unit = {},
    onPlayPlaylist: (Long) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> }
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current

    var savedSongs by remember { mutableStateOf(LibraryManager.getSavedSongs(context)) }
    var savedAlbums by remember { mutableStateOf(LibraryManager.getSavedAlbums(context)) }
    var selectedCategory by remember { mutableIntStateOf(0) }
    val categories = listOf(strings.categoryTracks, strings.categoryAlbums, strings.categoryPlaylists)

    var playlists by remember { mutableStateOf<List<PlaylistApi.PlaylistInfo>>(emptyList()) }
    var isLoadingPlaylists by remember { mutableStateOf(false) }
    var playlistError by remember { mutableStateOf<String?>(null) }
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

    LaunchedEffect(selectedCategory) {
        savedSongs = LibraryManager.getSavedSongs(context)
        savedAlbums = LibraryManager.getSavedAlbums(context)
        if (selectedCategory == 2 && playlists.isEmpty() && !isLoadingPlaylists) {
            loadPlaylists()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
    ResponsiveContent {
        Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            TabRow(
                selectedTabIndex = selectedCategory,
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.primary
            ) {
                categories.forEachIndexed { index, title ->
                    Tab(
                        selected = selectedCategory == index,
                        onClick = { selectedCategory = index },
                        text = {
                            Text(
                                title,
                                color = if (selectedCategory == index) MaterialTheme.colorScheme.primary else Color.Gray,
                                fontSize = 14.sp
                            )
                        }
                    )
                }
            }

            when (selectedCategory) {
                0 -> {
                    if (savedSongs.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(strings.noSavedSongs, color = Color.Gray, fontSize = 16.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = 72.dp)
                        ) {
                            items(savedSongs, key = { it.id }) { song ->
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
                                            },
                                            SongMenuAction(Icons.Default.Delete, strings.actionRemoveFromLibrary) {
                                                LibraryManager.removeSong(context, song.id)
                                                savedSongs = LibraryManager.getSavedSongs(context)
                                                savedAlbums = LibraryManager.getSavedAlbums(context)
                                            }
                                        ))
                                    }
                                )
                            }
                        }
                    }
                }

                1 -> {
                    if (savedAlbums.isEmpty()) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(strings.noSavedAlbums, color = Color.Gray, fontSize = 16.sp)
                        }
                    } else {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            contentPadding = PaddingValues(bottom = 72.dp)
                        ) {
                            val rows = savedAlbums.chunked(2)
                            items(rows.size) { rowIndex ->
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                                ) {
                                    for (album in rows[rowIndex]) {
                                        LibraryAlbumGridItem(
                                            album = album,
                                            modifier = Modifier.weight(1f),
                                            onClick = { onAlbumClick(album.albumId) },
                                            onPlayAll = { onPlayAlbum(album.albumId) }
                                        )
                                    }
                                    if (rows[rowIndex].size == 1) {
                                        Spacer(modifier = Modifier.weight(1f))
                                    }
                                }
                            }
                        }
                    }
                }

                2 -> {
                    when {
                        isLoadingPlaylists -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                            }
                        }
                        playlistError != null -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(playlistError!!, color = Color.Red, fontSize = 14.sp)
                                    Spacer(Modifier.height(8.dp))
                                    TextButton(onClick = { loadPlaylists() }) {
                                        Text(strings.retry, color = MaterialTheme.colorScheme.primary)
                                    }
                                }
                            }
                        }
                        playlists.isEmpty() -> {
                            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Text(strings.noPlaylists, color = Color.Gray, fontSize = 16.sp)
                            }
                        }
                        else -> {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                contentPadding = PaddingValues(bottom = 72.dp)
                            ) {
                                val rows = playlists.chunked(2)
                                items(rows.size) { rowIndex ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                                    ) {
                                        for (pl in rows[rowIndex]) {
                                            PlaylistGridItem(
                                                playlist = pl,
                                                modifier = Modifier.weight(1f),
                                                onClick = { onPlaylistClick(pl) },
                                                onPlayAll = { onPlayPlaylist(pl.id) }
                                            )
                                        }
                                        if (rows[rowIndex].size == 1) {
                                            Spacer(modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
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
                model = playlist.coverImgUrl,
                contentDescription = strings.playlistCoverDesc,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            PlayAllCircleButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(playlist.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(strings.trackCount(playlist.trackCount), color = Color.Gray, style = MaterialTheme.typography.bodySmall)
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
                model = album.picUrl,
                contentDescription = strings.albumCoverDesc,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            PlayAllCircleButton(
                modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
                onClick = onPlayAll
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(album.name, color = Color.White, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(strings.albumArtistAndCount(album.artist, album.songCount), color = Color.Gray, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}