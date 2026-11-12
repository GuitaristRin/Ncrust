package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.*

/**
 * 库 → 播放列表。
 *
 * 版式与单曲列表同源：封面贴左沿、无填充按钮、无每行播放圆钮 —— 行本身只负责「打开」，
 * 长按进入批量选择。选择态用封面角上的勾标表达，勾标只写 `graphicsLayer`，所以进入/退出
 * 选择态不会引起行内重排。
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
    val markProgress = remember { Animatable(if (selecting) 1f else 0f) }
    LaunchedEffect(selecting) {
        markProgress.animateTo(if (selecting) 1f else 0f, tween(180, easing = MetroDefault))
    }
    Crossfade(waiting, modifier = Modifier.fillMaxSize().background(colors.background),
        animationSpec = SokuouTweens.CoverFade, label = "PlaylistCollectionLoad") { showLoader ->
        if (showLoader) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MetroProgressIndicator() }
        else Column(Modifier.fillMaxSize()) {
            if (selecting) {
                PlaylistSelectionBar(
                    count = selectedIds.size,
                    allSelected = selectedIds.size == collection.items.size && collection.items.isNotEmpty(),
                    onCancel = onCancelSelect, onSelectAll = onSelectAll, onDelete = onDeleteSelected
                )
            }
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
                    // 选择态里不再提出「新建」：工具条已经占住了这一段的主要动作。
                    PlaylistGroupHeading(s.playlists.owned, owned.size,
                        if (management && !selecting) onCreate else null)
                }
                items(owned, key = { it.id }) { item ->
                    PlaylistCollectionRow(item, selecting, item.id in selectedIds,
                        selectable = management, selectProgress = { markProgress.value },
                        onClick = { if (selecting) onToggleSelect(item) else onOpen(item) },
                        onLongClick = { onLongPress(item) })
                }
                if (subscribed.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "subscribed") {
                    PlaylistGroupHeading(s.playlists.subscribed, subscribed.size)
                }
                items(subscribed, key = { it.id }) { item ->
                    // 收藏来的歌单不可管理：选择态里不给勾标，点击仍然是打开。
                    PlaylistCollectionRow(item, selecting, item.id in selectedIds,
                        selectable = false, selectProgress = { markProgress.value },
                        onClick = { onOpen(item) },
                        onLongClick = { onLongPress(item) })
                }
                if (collection.items.isEmpty() && error == null) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    MetroText(s.noPlaylists, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
                }
            }
        }
    }
}

/** 选择态工具条：只保留「选完要做什么」，取消/全选/删除三个动作都是文字按钮。 */
@Composable
private fun PlaylistSelectionBar(
    count: Int, allSelected: Boolean, onCancel: () -> Unit, onSelectAll: () -> Unit, onDelete: () -> Unit
) {
    val s = LocalStrings.current
    val colors = LocalMetroColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        MetroIconButton(onClick = onCancel) {
            MetroIcon(Icons.Default.Close, contentDescription = s.cancel, tint = colors.onBackground)
        }
        MetroText(s.playlists.playlistSelectionCount(count), modifier = Modifier.weight(1f),
            color = colors.onBackground, maxLines = 1)
        BarTextAction(s.playlists.selectAll, enabled = !allSelected, onClick = onSelectAll)
        BarTextAction(s.playlists.delete, enabled = count > 0, onClick = onDelete, primary = true)
    }
}

@Composable
private fun PlaylistGroupHeading(title: String, count: Int, onCreate: (() -> Unit)? = null) {
    val colors = LocalMetroColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 18.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        MetroText(title, style = LocalMetroTypography.current.title.copy(fontSize = 22.sp), maxLines = 1,
            overflow = TextOverflow.Ellipsis)
        MetroText(count.toString(), style = LocalMetroTypography.current.caption, color = colors.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        if (onCreate != null) BarTextAction(LocalStrings.current.playlists.create, enabled = true, onClick = onCreate)
    }
}

/**
 * 单行：封面贴左沿（与 SongCard 的无边框列表同源），右侧没有播放圆钮。
 * 选择态勾标画在封面右下角，不参与布局，因此进出选择态不会让文字跳位。
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
    Box(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 76.dp)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(end = 16.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                PlaylistArtwork(playlist.coverImgUrl, Modifier.size(64.dp))
                // 勾标压在封面右下角：只重绘，不占布局，所以进出选择态文字不跳位。
                if (selecting && selectable) {
                    Box(
                        Modifier.size(64.dp).graphicsLayer {
                            val progress = selectProgress()
                            alpha = progress
                            scaleX = 0.6f + 0.4f * progress
                            scaleY = 0.6f + 0.4f * progress
                        },
                        contentAlignment = Alignment.BottomEnd
                    ) {
                        Box(
                            Modifier.padding(4.dp).size(22.dp)
                                .background(Color.Black.copy(alpha = 0.45f)),
                            contentAlignment = Alignment.Center
                        ) {
                            MetroIcon(
                                if (selected) Icons.Default.CheckBox else Icons.Default.CheckBoxOutlineBlank,
                                contentDescription = null, sizeDp = 20.dp,
                                tint = if (selected) colors.primary else Color.White
                            )
                        }
                    }
                }
            }
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
