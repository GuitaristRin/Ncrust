package com.takahashirinta.ncrust.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.playlist.*
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.components.*
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * 歌单详情。普通态：点歌按歌单上下文播放，长按歌曲菜单，本人歌单左滑露出「移除」。
 *
 * 编辑态（页头「编辑曲目」进入，他人歌单为「选择歌曲」）是唯一的管理模式：
 * 点按切换选中（标记画在封面上），本人完整歌单的行尾出现拖拽手柄；顶部工具条负责全选、
 * 加入其他歌单和移除。拖动过顺序后工具条切换为「保存顺序」，选择动作暂停——整表顺序是一次
 * 独立的整表替换（`spec/api/playlist-edit.md`），不和逐首移除混在一起提交。
 *
 * 所有写入在一次确认后直接交给 [PlaylistManager.perform] 后台执行，结果用轻提示报告；
 * 只有待确认 / 部分完成 / 失败才调出面板核对。
 */
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
    var editing by remember(playlistId) { mutableStateOf(false) }
    var selectedIds by remember(playlistId) { mutableStateOf<Set<Long>>(emptySet()) }
    // 编辑态下的工作顺序；与服务端顺序不同即为「顺序已调整」。
    var order by remember(playlistId) { mutableStateOf<List<Long>>(emptyList()) }
    // 已提交、尚未读回的新顺序：先按它显示，避免退出编辑态时闪回旧顺序。
    var pendingOrder by remember(playlistId) { mutableStateOf<List<Long>?>(null) }
    var revealedId by remember(playlistId) { mutableStateOf<Long?>(null) }
    var removing by remember(playlistId) { mutableStateOf<List<Long>?>(null) }
    var confirmingDelete by remember(playlistId) { mutableStateOf(false) }
    var confirmingOrder by remember(playlistId) { mutableStateOf(false) }
    var confirmingDiscard by remember(playlistId) { mutableStateOf(false) }
    var loadedCredential by remember(playlistId) { mutableStateOf(RetrofitClient.getCookie().orEmpty()) }
    var loadGeneration by remember(playlistId) { mutableIntStateOf(0) }
    val revision by PlaylistManager.revision.collectAsState()
    val management by PlaylistManager.enabled.collectAsState()
    val task by PlaylistManager.state.collectAsState()
    var showPlayAllDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    val strings = LocalStrings.current
    val p = strings.playlists
    val colors = LocalMetroColors.current
    val density = LocalDensity.current

    // 进出编辑态共用一个进度：工具条位移、封面标记、手柄、页头按钮淡出都在 draw 阶段读取它。
    val editProgress = remember { Animatable(0f) }
    LaunchedEffect(editing) {
        editProgress.animateTo(if (editing) 1f else 0f, if (editing) SokuouTweens.SheetAppear else SokuouTweens.SheetDismiss)
    }
    val chromeVisible by remember { derivedStateOf { editProgress.value > 0f } }

    val songIds = remember(songs) { songs.map { it.id } }
    val orderChanged = editing && order.isNotEmpty() && order != songIds
    val busyHere = (task.busy || task.loading) && task.request?.playlistId == playlistId
    val removingIds = if (busyHere && task.request?.mode == PlaylistPanelMode.REMOVE) task.request?.songIds.orEmpty().toSet() else emptySet()

    fun enterEdit() { revealedId = null; selectedIds = emptySet(); order = songIds; editing = true }
    fun exitEdit() { editing = false; selectedIds = emptySet(); order = emptyList() }

    fun loadSongs() {
        val token = ++loadGeneration
        val credential = RetrofitClient.getCookie().orEmpty()
        coroutineScope.launch {
            if (loadedCredential != credential) {
                songs = emptyList(); snapshot = null; userId = 0; loadedCredential = credential
                exitEdit(); pendingOrder = null
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
                val freshIds = fresh.map { it.id }
                selectedIds = selectedIds.intersect(freshIds.toSet())
                // 草稿与新内容同集才保留；否则服务端已变，草稿不再可信。
                if (editing) order = if (order.toSet() == freshIds.toSet() && order.size == freshIds.size) order else freshIds
                if (PlaylistManager.state.value.let { !it.busy && !it.loading }) pendingOrder = null
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
    LaunchedEffect(management) { if (!management) exitEdit() }
    BackHandler(enabled = editing || revealedId != null) {
        when {
            revealedId != null -> revealedId = null
            orderChanged -> confirmingDiscard = true
            else -> exitEdit()
        }
    }

    val byId = remember(songs) { songs.associateBy { it.id } }
    val visibleSongs = when {
        editing && order.isNotEmpty() -> order.mapNotNull { byId[it] }
        pendingOrder != null -> pendingOrder!!.mapNotNull { byId[it] }.takeIf { it.size == songs.size } ?: songs
        else -> songs
    }
    val coverUrl = snapshot?.info?.coverImgUrl?.takeIf { it.isNotBlank() }
        ?: playlistCoverUrl.ifEmpty { songs.firstOrNull()?.album?.picUrl }
    val complete = snapshot?.idsComplete == true && songIds == snapshot?.trackIds
    val owned = snapshot?.info?.isOwnedBy(userId) == true
    val canReorder = management && owned && complete && songs.size > 1
    val swipeEnabled = management && owned && !editing

    val listState = rememberLazyListState()
    val reorder = rememberReorderState(listState, canMove = { it is Long }) { from, to ->
        val f = order.indexOf(from as Long)
        val t = order.indexOf(to as Long)
        if (f >= 0 && t >= 0) order = order.toMutableList().apply { add(t, removeAt(f)) }
    }
    val statusTop = WindowInsets.statusBars.getTop(density)
    reorder.edgeInsets = with(density) { (statusTop + 56.dp.toPx()) to BottomOverlayInsetDp.toPx() }

    if (showPlayAllDialog) {
        PlayAllDialog(
            songCount = songs.size,
            onDismiss = { showPlayAllDialog = false },
            onReplaceAndPlay = { if (complete && songs.isNotEmpty()) onPlayPlaylist(songs, songs.first().id) },
            onInsertNext = { onInsertNext(songs) }
        )
    }
    removing?.let { ids ->
        PlaylistConfirmDialog(
            text = p.removeHint(ids.size), confirmLabel = p.removeShort,
            onConfirm = {
                removing = null
                PlaylistManager.perform(PlaylistPanelRequest(PlaylistPanelMode.REMOVE, ids, playlistId))
            },
            onDismiss = { removing = null }
        )
    }
    if (confirmingOrder) {
        PlaylistConfirmDialog(
            text = p.reorderHint(order.size), confirmLabel = p.saveOrder,
            onConfirm = {
                confirmingOrder = false
                pendingOrder = order
                PlaylistManager.perform(PlaylistPanelRequest(PlaylistPanelMode.REORDER, order, playlistId))
                exitEdit()
            },
            onDismiss = { confirmingOrder = false }
        )
    }
    if (confirmingDiscard) {
        PlaylistConfirmDialog(
            text = p.discardOrderHint, confirmLabel = p.discardOrder,
            onConfirm = { confirmingDiscard = false; exitEdit() },
            onDismiss = { confirmingDiscard = false }
        )
    }
    // 单删与批量删除走同一条路径（备份 + 读回），确认后返回上一页，结果由轻提示报告。
    if (confirmingDelete) {
        PlaylistConfirmDialog(
            text = p.deleteHint(snapshot?.info?.name ?: playlistName), confirmLabel = p.delete,
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
        listState = listState,
        topBar = if (chromeVisible) {
            {
                PlaylistEditBar(
                    progress = { editProgress.value },
                    selectionCount = selectedIds.size, totalCount = songs.size,
                    owned = owned, orderChanged = orderChanged, busy = busyHere,
                    onExit = { if (orderChanged) confirmingDiscard = true else exitEdit() },
                    onToggleAll = { selectedIds = if (selectedIds.size == songs.size) emptySet() else songIds.toSet() },
                    onRemove = { if (selectedIds.isNotEmpty()) removing = visibleSongs.map { it.id }.filter { it in selectedIds } },
                    onAddTo = { PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.ADD, visibleSongs.map { it.id }.filter { it in selectedIds })) },
                    onSaveOrder = { confirmingOrder = true }
                )
            }
        } else null,
        header = {
            PlaylistDetailHeader(
                coverUrl = coverUrl,
                title = snapshot?.info?.name ?: playlistName.ifEmpty { strings.categoryPlaylists },
                privacy = snapshot?.info?.let { if (it.privacy == 10) p.private else if (it.privacy == 0) p.public else p.unknown },
                trackCount = snapshot?.info?.trackCount?.takeIf { it >= 0 } ?: songs.size,
                description = snapshot?.description.orEmpty(), complete = complete,
                management = management, owned = owned,
                onPlay = if (complete && songs.isNotEmpty()) ({ showPlayAllDialog = true }) else null,
                onEditTracks = if (songs.isNotEmpty()) ({ if (!editing) enterEdit() }) else null,
                onEdit = { if (!editing) PlaylistManager.open(PlaylistPanelRequest(PlaylistPanelMode.EDIT, playlistId = playlistId)) },
                onDelete = { if (!editing) confirmingDelete = true },
                dimmed = { editProgress.value }
            )
        },
        content = {
            itemsIndexed(visibleSongs, key = { _, song -> song.id }) { index, song ->
                val selected = song.id in selectedIds
                PlaylistTrackRow(
                    song = song, editProgress = { editProgress.value }, editing = editing, selected = selected,
                    pendingRemoval = song.id in removingIds, reorder = reorder, showHandle = canReorder && chromeVisible,
                    revealed = revealedId == song.id, swipeEnabled = swipeEnabled && !busyHere,
                    onRevealChange = { open -> revealedId = if (open) song.id else revealedId.takeIf { it != song.id } },
                    onSwipeRemove = { removing = listOf(song.id) },
                    onClick = {
                        when {
                            revealedId != null -> revealedId = null
                            // 顺序调整中暂停选择：工具条此时只提供保存顺序。
                            editing -> if (!orderChanged && !busyHere) {
                                selectedIds = if (selected) selectedIds - song.id else selectedIds + song.id
                            }
                            complete -> onPlayPlaylist(songs, song.id)
                            else -> Toast.makeText(context, p.incomplete, Toast.LENGTH_SHORT).show()
                        }
                    },
                    // 编辑态不弹歌曲菜单：菜单里的写操作会打断当前选择。
                    onShowMenu = if (editing) null else ({
                        onShowSongMenu(song, listOf(
                            SongMenuAction(Icons.Default.LibraryAdd, strings.actionAddToLibrary) {
                                LibraryManager.saveSong(context, song)
                                Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                            },
                            SongMenuAction(Icons.AutoMirrored.Filled.PlaylistPlay, strings.actionInsertNext) { onSongInsertNext(song) },
                            SongMenuAction(Icons.AutoMirrored.Filled.PlaylistAdd, strings.actionAppendToQueue) { onSongAppendToQueue(song) }
                        ) + if (management && owned) listOf(
                            SongMenuAction(Icons.Default.PlaylistRemove, p.remove) { removing = listOf(song.id) }
                        ) else emptyList())
                    }),
                    moveActions = listOf(
                        CustomAccessibilityAction(p.moveUp) {
                            if (index > 0) { order = order.toMutableList().apply { add(index - 1, removeAt(index)) }; true } else false
                        },
                        CustomAccessibilityAction(p.moveDown) {
                            if (index < order.lastIndex) { order = order.toMutableList().apply { add(index + 1, removeAt(index)) }; true } else false
                        }
                    )
                )
            }
            if (songs.isEmpty() && snapshot != null) item { MetroText(strings.trackCountSongs(0), modifier = Modifier.padding(20.dp)) }
        }
    )
}

/**
 * 歌单内的一行。所有编辑态视觉（封面标记、手柄、被拖行提亮、待移除压暗）都由 [editProgress]
 * 与拖拽偏移在 draw 阶段驱动；行布局在普通态与编辑态之间完全相同。
 */
@Composable
internal fun LazyItemScope.PlaylistTrackRow(
    song: SongItem,
    editProgress: () -> Float,
    editing: Boolean,
    selected: Boolean,
    pendingRemoval: Boolean,
    reorder: ReorderState,
    showHandle: Boolean,
    revealed: Boolean,
    swipeEnabled: Boolean,
    onRevealChange: (Boolean) -> Unit,
    onSwipeRemove: () -> Unit,
    onClick: () -> Unit,
    onShowMenu: (() -> Unit)?,
    moveActions: List<CustomAccessibilityAction>,
) {
    val colors = LocalMetroColors.current
    val p = LocalStrings.current.playlists
    val dragging = reorder.draggingKey == song.id
    Box(
        Modifier
            .animateItem(
                fadeInSpec = tween(150, easing = MetroDefault),
                placementSpec = if (dragging) null else tween(220, easing = MetroDefault),
                fadeOutSpec = tween(150, easing = MetroDefault)
            )
            .zIndex(if (dragging) 1f else 0f)
            .graphicsLayer {
                translationY = reorder.offsetOf(song.id)
                // 等待读回确认的移除项先压暗，确认后再由 animateItem 收起。
                alpha = if (pendingRemoval) 0.4f else 1f
            }
    ) {
        SwipeRevealRow(
            open = revealed, onOpenChange = onRevealChange,
            actionLabel = p.removeShort, enabled = swipeEnabled, onAction = onSwipeRemove
        ) {
            // 被拖起的行以次级表面提亮表示「浮起」：Kanesumi 深度靠明暗，不用阴影。
            val rowColor = if (dragging) colors.surfaceVariant else colors.background
            Box(Modifier.background(rowColor)) {
                SongCard(
                    song = song,
                    style = SongCardStyle.COMPACT,
                    modifier = Modifier.semantics { if (editing) this.selected = selected },
                    coverModifier = Modifier.selectionMark(editProgress, selected, colors.primary, colors.onPrimary),
                    onClick = onClick,
                    onShowMenu = onShowMenu
                )
                if (showHandle) ReorderHandle(
                    progress = editProgress, background = rowColor,
                    modifier = Modifier.reorderHandle(reorder, song.id),
                    label = p.dragToReorder, actions = moveActions
                )
            }
        }
    }
}

/** 行尾拖拽手柄：不占行布局，盖在行尾 56dp 上，随编辑进度自右侧滑入。 */
@Composable
private fun BoxScope.ReorderHandle(
    progress: () -> Float, background: androidx.compose.ui.graphics.Color, modifier: Modifier,
    label: String, actions: List<CustomAccessibilityAction>
) {
    val colors = LocalMetroColors.current
    Box(
        Modifier
            .matchParentSize()
            .wrapContentWidth(Alignment.End)
            .width(56.dp)
            .graphicsLayer {
                val t = progress()
                alpha = t
                translationX = (1f - t) * 24.dp.toPx()
            }
            .background(background)
            .then(modifier)
            .semantics { contentDescription = label; customActions = actions },
        contentAlignment = Alignment.Center
    ) {
        MetroIcon(Icons.Default.DragHandle, contentDescription = null, tint = colors.onSurfaceVariant)
    }
}
