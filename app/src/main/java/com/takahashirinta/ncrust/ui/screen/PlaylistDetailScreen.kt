package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.runtime.*
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.playlist.*
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.components.DetailHeader
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.PlayAllDialog
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import com.takahashirinta.ncrust.ui.components.PlaylistButton
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import com.takahashirinta.ncrust.ui.components.PlaylistSelectionActions

@Composable
fun PlaylistDetailScreen(
    playlistId: Long,
    playlistName: String = "",
    playlistCoverUrl: String = "",
    onBack: () -> Unit,
    onSongClick: (SongItem) -> Unit,
    onPlayPlaylist: (List<SongItem>, Long) -> Unit = { list, id -> list.find { it.id == id }?.let(onSongClick) },
    onInsertNext: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> }
) {
    val cached = remember(playlistId) { ContentCache.getPlaylistSongs(playlistId) }
    var songs by remember(playlistId) { mutableStateOf(cached ?: emptyList()) }
    var isLoading by remember(playlistId) { mutableStateOf(cached == null) }
    var error by remember(playlistId) { mutableStateOf<String?>(null) }
    var snapshot by remember(playlistId) { mutableStateOf<PlaylistSnapshot?>(null) }
    var userId by remember(playlistId) { mutableLongStateOf(0) }
    var selecting by remember(playlistId) { mutableStateOf(false) }
    var selectedIds by remember(playlistId) { mutableStateOf<Set<Long>>(emptySet()) }
    var loadedCredential by remember(playlistId) { mutableStateOf(RetrofitClient.getCookie().orEmpty()) }
    var loadGeneration by remember(playlistId) { mutableIntStateOf(0) }
    val revision by PlaylistManager.revision.collectAsState()
    val management by PlaylistManager.enabled.collectAsState()
    var showPlayAllDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current

    fun loadSongs() {
        val token = ++loadGeneration
        val credential = RetrofitClient.getCookie().orEmpty()
        coroutineScope.launch {
            if (loadedCredential != credential) {
                songs = emptyList(); snapshot = null; userId = 0; loadedCredential = credential
                selecting = false; selectedIds = emptySet()
            }
            if (songs.isEmpty()) isLoading = true
            error = null
            try {
                val reads = PlaylistReadApi(credential)
                val metadata = reads.snapshot(playlistId)
                val fresh = PlaylistApi.getPlaylistDetail(playlistId, credential)
                if (token != loadGeneration || credential != RetrofitClient.getCookie().orEmpty()) return@launch
                snapshot = metadata
                songs = fresh
                selectedIds = selectedIds.intersect(fresh.map { it.id }.toSet())
                ContentCache.putPlaylistSongs(playlistId, fresh)
                val owner = if (credential.isNotEmpty()) runCatching { reads.userId() }.getOrDefault(0) else 0
                if (token == loadGeneration && credential == RetrofitClient.getCookie().orEmpty()) userId = owner
            } catch (cancelled: CancellationException) { throw cancelled
            } catch (e: Exception) {
                if (token != loadGeneration || credential != RetrofitClient.getCookie().orEmpty()) return@launch
                if (songs.isEmpty()) error = strings.loadFailed(e.message)
            } finally {
                if (token == loadGeneration) isLoading = false
            }
        }
    }

    LaunchedEffect(playlistId, revision) { loadSongs() }
    LaunchedEffect(management) { if (!management) { selecting = false; selectedIds = emptySet() } }
    BackHandler(enabled = selecting) { selecting = false; selectedIds = emptySet() }

    val coverUrl = snapshot?.info?.coverImgUrl?.takeIf { it.isNotBlank() }
        ?: playlistCoverUrl.ifEmpty { songs.firstOrNull()?.album?.picUrl }
    val complete = snapshot?.idsComplete == true && songs.map { it.id } == snapshot?.trackIds
    val owned = snapshot?.info?.isOwnedBy(userId) == true

    if (showPlayAllDialog) {
        PlayAllDialog(
            songCount = songs.size,
            onDismiss = { showPlayAllDialog = false },
            onReplaceAndPlay = { if (complete && songs.isNotEmpty()) onPlayPlaylist(songs, songs.first().id) },
            onInsertNext = { onInsertNext(songs) }
        )
    }

    DetailScaffold(
        title = strings.playlistDetailTitle,
        onBack = onBack,
        isLoading = isLoading,
        hasCachedContent = songs.isNotEmpty() || snapshot != null,
        error = error,
        onRetry = { loadSongs() },
        header = {
            DetailHeader(
                coverUrl = coverUrl,
                title = snapshot?.info?.name ?: playlistName.ifEmpty { strings.categoryPlaylists },
                subtitle = snapshot?.info?.let { if (it.privacy == 10) strings.playlists.private else if (it.privacy == 0) strings.playlists.public else strings.playlists.unknown },
                infoLines = listOfNotNull(strings.trackCountSongs(snapshot?.info?.trackCount?.takeIf { it >= 0 } ?: songs.size),
                    snapshot?.description?.takeIf { it.isNotBlank() }),
                onPlayAll = if (complete && songs.isNotEmpty()) ({ showPlayAllDialog = true }) else null,
                headerActions = {
                    if (!complete && snapshot != null) MetroText(strings.playlists.incomplete)
                    if (management && owned) {
                        Spacer(Modifier.height(12.dp))
                        PlaylistButton(strings.playlists.edit, { PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.EDIT, playlistId = playlistId)) }, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        PlaylistButton(strings.playlists.delete, { PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.DELETE, playlistId = playlistId)) }, modifier = Modifier.fillMaxWidth())
                    }
                    if (management && songs.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        if (!selecting) PlaylistButton(strings.playlists.selectTracks, { selecting = true }, modifier = Modifier.fillMaxWidth())
                        else PlaylistSelectionActions(selectedIds.size, owned,
                            onAll = { selectedIds = songs.map { it.id }.toSet() },
                            onCancel = { selecting = false; selectedIds = emptySet() },
                            onAdd = { PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.ADD, songs.filter { it.id in selectedIds }.map { it.id })) },
                            onRemove = { PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.REMOVE, songs.filter { it.id in selectedIds }.map { it.id }, playlistId)) })
                    }
                }
            )
        },
        content = {
            items(songs, key = { it.id }) { song ->
                SongCard(
                    song = song,
                    style = SongCardStyle.COMPACT,
                    modifier = Modifier.semantics { if (selecting) selected = song.id in selectedIds },
                    actions = if (selecting) ({
                        MetroIcon(if (song.id in selectedIds) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                            contentDescription = null, sizeDp = 24.dp)
                    }) else null,
                    onClick = {
                        if (selecting) selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
                        else if (complete) onPlayPlaylist(songs, song.id)
                        else Toast.makeText(context, strings.playlists.incomplete, Toast.LENGTH_SHORT).show()
                    },
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
                        ) + if (management && owned) listOf(
                            SongMenuAction(Icons.Default.LibraryAdd, strings.playlists.remove) {
                                PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.REMOVE, listOf(song.id), playlistId))
                            }
                        ) else emptyList())
                    }
                )
            }
            if (songs.isEmpty() && snapshot != null) item { MetroText(strings.trackCountSongs(0), modifier = Modifier.padding(20.dp)) }
        }
    )
}
