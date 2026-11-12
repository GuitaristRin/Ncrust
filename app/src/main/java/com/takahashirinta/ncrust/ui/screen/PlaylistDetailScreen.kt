package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.unit.dp
import androidx.activity.compose.BackHandler
import androidx.compose.ui.platform.LocalContext
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.playlist.*
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.components.DetailScaffold
import com.takahashirinta.ncrust.ui.components.PlayAllDialog
import com.takahashirinta.ncrust.ui.components.PlaylistConfirmDialog
import com.takahashirinta.ncrust.ui.components.PlaylistDetailHeader
import com.takahashirinta.ncrust.ui.components.PlaylistEditBar
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.SwipeRevealRow
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch
import android.widget.Toast
import kotlinx.coroutines.CancellationException
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

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
    // 编辑态只用显式入口进入；长按仍是歌曲菜单。
    var editing by remember(playlistId) { mutableStateOf(false) }
    var selectedIds by remember(playlistId) { mutableStateOf<Set<Long>>(emptySet()) }
    var sorting by remember(playlistId) { mutableStateOf(false) }
    var draftOrder by remember(playlistId) { mutableStateOf<List<Long>>(emptyList()) }
    // 同一时刻只允许一行处于「左滑露出移除」状态，点别处先收起。
    var revealedId by remember(playlistId) { mutableStateOf<Long?>(null) }
    var confirmingRemove by remember(playlistId) { mutableStateOf(false) }
    var confirmingDelete by remember(playlistId) { mutableStateOf(false) }
    var loadedCredential by remember(playlistId) { mutableStateOf(RetrofitClient.getCookie().orEmpty()) }
    var loadGeneration by remember(playlistId) { mutableIntStateOf(0) }
    val revision by PlaylistManager.revision.collectAsState()
    val management by PlaylistManager.enabled.collectAsState()
    var showPlayAllDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current

    // 进出编辑态一个共享进度：行内勾选框/留白全部由它驱动，只重绘不重组。
    val editProgress = remember { Animatable(0f) }
    LaunchedEffect(editing) {
        editProgress.animateTo(if (editing) 1f else 0f, tween(200, easing = MetroDefault))
    }

    fun loadSongs() {
        val token = ++loadGeneration
        val credential = RetrofitClient.getCookie().orEmpty()
        coroutineScope.launch {
            if (loadedCredential != credential) {
                songs = emptyList(); snapshot = null; userId = 0; loadedCredential = credential
                editing = false; sorting = false; draftOrder = emptyList(); selectedIds = emptySet()
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
                // 服务端结果已经变了，本地排序草稿不再可信（排序写回后也是靠这次重载收尾）。
                if (sorting) { sorting = false; draftOrder = emptyList() }
                revealedId = null
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
    LaunchedEffect(management) { if (!management) { editing = false; sorting = false; selectedIds = emptySet() } }
    BackHandler(enabled = editing) {
        when {
            sorting -> { sorting = false; draftOrder = emptyList() }
            revealedId != null -> revealedId = null
            else -> { editing = false; selectedIds = emptySet() }
        }
    }

    fun moveDraft(from: Int, to: Int) {
        if (from !in draftOrder.indices || to !in draftOrder.indices || from == to) return
        draftOrder = draftOrder.toMutableList().apply { add(to, removeAt(from)) }
    }

    val byId = remember(songs) { songs.associateBy { it.id } }
    val visibleSongs = if (sorting) draftOrder.mapNotNull { byId[it] } else songs
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
    if (confirmingRemove) {
        PlaylistConfirmDialog(
            text = strings.playlists.removeHint(selectedIds.size),
            confirmLabel = strings.playlists.removeShort,
            onConfirm = {
                confirmingRemove = false
                PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.REMOVE, selectedIds.toList(), playlistId))
            },
            onDismiss = { confirmingRemove = false }
        )
    }
    // 单删与批量删除走同一条路径（备份 + 读回 + 进度汇总），不再单独开一个全屏面板。
    if (confirmingDelete) {
        PlaylistConfirmDialog(
            text = strings.playlists.deleteHint(snapshot?.info?.name ?: playlistName),
            confirmLabel = strings.playlists.delete,
            onConfirm = {
                confirmingDelete = false
                PlaylistManager.deletePlaylists(listOf(playlistId))
                onBack()
            },
            onDismiss = { confirmingDelete = false }
        )
    }

    DetailScaffold(
        title = strings.playlistDetailTitle,
        onBack = onBack,
        isLoading = isLoading,
        hasCachedContent = songs.isNotEmpty() || snapshot != null,
        error = error,
        onRetry = { loadSongs() },
        topBar = if (editing) {
            {
                PlaylistEditBar(
                    modifier = Modifier.fillMaxWidth(),
                    selectionCount = selectedIds.size,
                    owned = owned,
                    sorting = sorting,
                    canSort = owned && complete && songs.size > 1,
                    busy = false,
                    onExit = { editing = false; selectedIds = emptySet(); revealedId = null },
                    onSelectAll = { selectedIds = songs.map { it.id }.toSet() },
                    onRemove = { if (selectedIds.isNotEmpty()) confirmingRemove = true },
                    onAddTo = { PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.ADD, selectedIds.toList())) },
                    onStartSorting = { draftOrder = songs.map { it.id }; sorting = true; selectedIds = emptySet(); revealedId = null },
                    onSaveOrder = { PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.REORDER, draftOrder, playlistId)) },
                    onCancelSorting = { sorting = false; draftOrder = emptyList() }
                )
            }
        } else null,
        header = {
            PlaylistDetailHeader(
                coverUrl = coverUrl,
                title = snapshot?.info?.name ?: playlistName.ifEmpty { strings.categoryPlaylists },
                privacy = snapshot?.info?.let { if (it.privacy == 10) strings.playlists.private else if (it.privacy == 0) strings.playlists.public else strings.playlists.unknown },
                trackCount = snapshot?.info?.trackCount?.takeIf { it >= 0 } ?: songs.size,
                description = snapshot?.description.orEmpty(), complete = complete,
                // 编辑态里页头让位给顶部工具条：改名/删除要退出编辑态再做。
                management = management && !editing, owned = owned,
                onPlay = if (complete && songs.isNotEmpty()) ({ showPlayAllDialog = true }) else null,
                onEditTracks = if (songs.isNotEmpty()) ({ editing = true }) else null,
                onEdit = { PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.EDIT, playlistId = playlistId)) },
                onDelete = { confirmingDelete = true }
            )
        },
        content = {
            itemsIndexed(visibleSongs, key = { _, song -> song.id }) { index, song ->
                val row = @Composable {
                    SongCard(
                        song = song,
                        style = SongCardStyle.COMPACT,
                        modifier = Modifier.semantics { if (editing && !sorting) selected = song.id in selectedIds },
                        actions = when {
                            sorting -> ({
                                MetroIconButton({ moveDraft(index, index - 1) }, enabled = index > 0,
                                    touchTargetDp = 40.dp) {
                                    MetroIcon(Icons.Default.KeyboardArrowUp, contentDescription = strings.playlists.moveUp,
                                        tint = if (index > 0) colors.onBackground else colors.onSurfaceVariant)
                                }
                                MetroIconButton({ moveDraft(index, index + 1) }, enabled = index < visibleSongs.lastIndex,
                                    touchTargetDp = 40.dp) {
                                    MetroIcon(Icons.Default.KeyboardArrowDown, contentDescription = strings.playlists.moveDown,
                                        tint = if (index < visibleSongs.lastIndex) colors.onBackground else colors.onSurfaceVariant)
                                }
                            })
                            // 勾选框一直占着同一个 24dp 槽位，所以进出编辑态只是淡入淡出，文字不跳位。
                            else -> ({
                                Box(Modifier.size(24.dp).graphicsLayer {
                                    val progress = editProgress.value
                                    alpha = progress
                                    scaleX = 0.7f + 0.3f * progress
                                    scaleY = 0.7f + 0.3f * progress
                                }) {
                                    MetroIcon(
                                        if (song.id in selectedIds) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                        contentDescription = null, sizeDp = 24.dp
                                    )
                                }
                            })
                        },
                        onClick = {
                            when {
                                revealedId != null -> revealedId = null
                                // 排序态点行不选，避免和上下移动混淆。
                                sorting -> Unit
                                editing -> selectedIds = if (song.id in selectedIds) selectedIds - song.id else selectedIds + song.id
                                complete -> onPlayPlaylist(songs, song.id)
                                else -> Toast.makeText(context, strings.playlists.incomplete, Toast.LENGTH_SHORT).show()
                            }
                        },
                        // 编辑/排序态不弹歌曲菜单：菜单里的写操作会打断当前选择。
                        onShowMenu = if (editing) null else ({
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
                        })
                    )
                }
                // 编辑态（且本人歌单）才允许左滑露出「移除」：单个移除也走同一个确认与读回流程。
                if (editing && !sorting && owned) {
                    SwipeRevealRow(
                        open = revealedId == song.id,
                        onOpenChange = { open -> revealedId = if (open) song.id else revealedId.takeIf { it != song.id } },
                        actionLabel = strings.playlists.removeShort,
                        onAction = {
                            selectedIds = setOf(song.id)
                            confirmingRemove = true
                        }
                    ) { row() }
                } else {
                    row()
                }
            }
            if (songs.isEmpty() && snapshot != null) item { MetroText(strings.trackCountSongs(0), modifier = Modifier.padding(20.dp)) }
        }
    )
}
