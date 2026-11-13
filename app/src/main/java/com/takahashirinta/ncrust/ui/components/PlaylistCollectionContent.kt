package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.PlaylistApi.PlaylistInfo
import com.takahashirinta.ncrust.playlist.PlaylistCollection
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.*

/**
 * 库 → 播放列表。
 *
 * 版式与单曲列表同源：封面贴左沿、无填充按钮、无每行播放圆钮 —— 行本身只负责「打开」，长按弹出
 * 自下而上的操作表（多选从表里进入）。选择标记画在封面上、工具条叠在列表顶端，进出选择态不引起重排。
 */
@Composable
fun PlaylistCollectionContent(
    collection: PlaylistCollection,
    ownerId: Long,
    loading: Boolean,
    error: String?,
    management: Boolean,
    onCreate: () -> Unit,
    onRetry: () -> Unit,
    onOpen: (PlaylistInfo) -> Unit,
    selecting: Boolean = false,
    selectedIds: Set<Long> = emptySet(),
    onToggleSelect: (PlaylistInfo) -> Unit = {},
    onLongPress: (PlaylistInfo) -> Unit = {},
    onSelectAll: () -> Unit = {},
    onCancelSelect: () -> Unit = {},
    onDeleteSelected: () -> Unit = {}
) {
    val s = LocalStrings.current
    val colors = LocalMetroColors.current
    val owned = remember(collection.items, ownerId) { collection.items.filter { it.isOwnedBy(ownerId) } }
    val subscribed = remember(collection.items, ownerId) { collection.items.filterNot { it.isOwnedBy(ownerId) } }
    val waiting = loading && collection.items.isEmpty()
    val selectProgress = remember { Animatable(0f) }
    LaunchedEffect(selecting) {
        selectProgress.animateTo(if (selecting) 1f else 0f, if (selecting) SokuouTweens.SheetAppear else SokuouTweens.SheetDismiss)
    }
    val barVisible by remember { derivedStateOf { selectProgress.value > 0f } }
    Crossfade(waiting, modifier = Modifier.fillMaxSize().background(colors.background),
        animationSpec = SokuouTweens.CoverFade, label = "PlaylistCollectionLoad") { showLoader ->
        if (showLoader) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MetroProgressIndicator() }
        // clipToBounds：选择工具条从页签下方滑出，而不是从屏幕顶端掉下来盖住页签。
        else Box(Modifier.fillMaxSize().clipToBounds()) {
            Column(Modifier.fillMaxSize()) {
                // 状态放在滚动区域之外，插入提示时不能被 LazyGrid 的锚点保留滚出视口。
                if (error != null || !collection.complete) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                        MetroText(error ?: s.playlists.incomplete, color = colors.onSurfaceVariant)
                        if (error != null) PlaylistButton(s.retry, onRetry,
                            containerColor = colors.surfaceVariant, contentColor = colors.onBackground)
                    }
                }
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(340.dp),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(24.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                    flingBehavior = rememberMetroFlingBehavior()
                ) {
                    if (owned.isNotEmpty() || management) item(span = { GridItemSpan(maxLineSpan) }, key = "owned") {
                        PlaylistGroupHeading(s.playlists.owned, owned.size, if (management) onCreate else null)
                    }
                    items(owned, key = { it.id }) { item ->
                        PlaylistCollectionRow(item, selecting, item.id in selectedIds,
                            selectable = management, selectProgress = { selectProgress.value },
                            onClick = { if (selecting) onToggleSelect(item) else onOpen(item) },
                            onLongClick = { onLongPress(item) })
                    }
                    if (subscribed.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "subscribed") {
                        PlaylistGroupHeading(s.playlists.subscribed, subscribed.size)
                    }
                    items(subscribed, key = { it.id }) { item ->
                        // 收藏来的歌单不可管理：选择态里压暗、不给标记，点击仍然是打开。
                        PlaylistCollectionRow(item, selecting, false,
                            selectable = false, selectProgress = { selectProgress.value },
                            onClick = { onOpen(item) },
                            onLongClick = { onLongPress(item) })
                    }
                    if (collection.items.isEmpty() && error == null) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                        MetroText(s.noPlaylists, color = colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
                    }
                }
            }
            if (barVisible) PlaylistSelectionBar(
                progress = { selectProgress.value },
                count = selectedIds.size,
                allSelected = owned.isNotEmpty() && selectedIds.containsAll(owned.map { it.id }),
                onCancel = onCancelSelect, onSelectAll = onSelectAll, onDelete = onDeleteSelected
            )
        }
    }
}

/**
 * 选择态工具条。叠在列表顶端（恰好盖住「我创建的」分组标题），不插进布局把列表往下推；
 * 自上方滑入，进度只在 graphicsLayer 读取。
 */
@Composable
private fun PlaylistSelectionBar(
    progress: () -> Float, count: Int, allSelected: Boolean,
    onCancel: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit
) {
    val s = LocalStrings.current
    val colors = LocalMetroColors.current
    Row(
        Modifier.fillMaxWidth()
            .graphicsLayer { val t = progress(); translationY = -size.height * (1f - t); alpha = t }
            .background(colors.surface)
            .height(56.dp).padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroIconButton(onClick = onCancel) {
            MetroIcon(Icons.Default.Close, contentDescription = s.cancel, tint = colors.onBackground)
        }
        MetroText(s.playlists.playlistSelectionCount(count), modifier = Modifier.weight(1f).padding(start = 4.dp),
            color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
            style = LocalMetroTypography.current.title.copy(fontSize = 18.sp))
        BarIconAction(if (allSelected) Icons.Default.Deselect else Icons.Default.SelectAll, s.playlists.selectAll,
            enabled = true, onClick = onSelectAll)
        BarIconAction(Icons.Default.DeleteOutline, s.playlists.delete, enabled = count > 0, onClick = onDelete)
    }
}

@Composable
private fun PlaylistGroupHeading(title: String, count: Int, onCreate: (() -> Unit)? = null) {
    val colors = LocalMetroColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 4.dp, top = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetroText(title, style = LocalMetroTypography.current.title.copy(fontSize = 20.sp), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        MetroText(count.toString(), style = LocalMetroTypography.current.caption, color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        if (onCreate != null) {
            val label = LocalStrings.current.playlists.create
            Row(
                Modifier.clickable(role = Role.Button, onClick = onCreate).padding(horizontal = 12.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroIcon(Icons.Default.Add, contentDescription = null, tint = colors.primary, sizeDp = 18.dp)
                Spacer(Modifier.width(6.dp))
                MetroText(label, color = colors.primary)
            }
        }
    }
}

/**
 * 单行：封面贴左沿（与 SongCard 的无边框列表同源），右侧没有播放圆钮。
 * 选择态标记画在封面上（[selectionMark]），不参与布局，所以进出选择态文字不跳位。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun PlaylistCollectionRow(
    playlist: PlaylistInfo,
    selecting: Boolean,
    selected: Boolean,
    selectable: Boolean,
    selectProgress: () -> Float,
    onClick: () -> Unit,
    onLongClick: () -> Unit
) {
    val s = LocalStrings.current
    val colors = LocalMetroColors.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = 76.dp)
            .graphicsLayer { if (!selectable) alpha = 1f - 0.55f * selectProgress() }
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .semantics { if (selecting && selectable) this.selected = selected }
            .padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        PlaylistArtwork(playlist.coverImgUrl, Modifier.size(64.dp).then(
            if (selectable) Modifier.selectionMark(selectProgress, selected, colors.primary, colors.onPrimary) else Modifier))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MetroText(playlist.name, style = LocalMetroTypography.current.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val count = if (playlist.trackCount >= 0) s.trackCount(playlist.trackCount) else s.playlists.unknown
            MetroText(
                listOfNotNull(count, s.playlists.private.takeIf { playlist.privacy == 10 }).joinToString(" · "),
                style = LocalMetroTypography.current.caption, color = colors.onSurfaceVariant
            )
        }
    }
}

@Composable
internal fun PlaylistArtwork(url: String?, modifier: Modifier = Modifier, large: Boolean = false) {
    val colors = LocalMetroColors.current
    Box(modifier.background(colors.surfaceVariant), contentAlignment = Alignment.Center) {
        if (url.isNullOrBlank()) {
            MetroIcon(Icons.AutoMirrored.Filled.QueueMusic, contentDescription = null, sizeDp = 32.dp,
                tint = colors.onSurfaceVariant)
        } else AsyncImage(if (large) CoverUrls.large(url) else CoverUrls.small(url), contentDescription = LocalStrings.current.playlistCoverDesc,
            placeholder = ColorPainter(colors.surfaceVariant), error = ColorPainter(colors.surfaceVariant),
            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}
