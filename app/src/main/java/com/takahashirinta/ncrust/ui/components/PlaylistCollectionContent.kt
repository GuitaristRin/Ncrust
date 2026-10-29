package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.network.PlaylistApi.PlaylistInfo
import com.takahashirinta.ncrust.playlist.PlaylistCollection
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.*

/** 窄屏一列，宽屏按内容宽度排多列；名称获得独立于方封面的阅读空间。 */
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
    onPlay: (Long) -> Unit
) {
    val s = LocalStrings.current
    val owned = remember(collection.items, ownerId) { collection.items.filter { it.isOwnedBy(ownerId) } }
    val subscribed = remember(collection.items, ownerId) { collection.items.filterNot { it.isOwnedBy(ownerId) } }
    val waiting = loading && collection.items.isEmpty()
    Crossfade(waiting, modifier = Modifier.fillMaxSize().background(LocalMetroColors.current.background),
        animationSpec = SokuouTweens.CoverFade, label = "PlaylistCollectionLoad") { showLoader ->
        if (showLoader) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { MetroProgressIndicator() }
        else Column(Modifier.fillMaxSize()) {
            // 状态放在滚动区域之外，插入提示时不能被 LazyGrid 的锚点保留滚出视口。
            if (error != null || !collection.complete) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    MetroText(error ?: s.playlists.incomplete, color = LocalMetroColors.current.onSurfaceVariant)
                    if (error != null) PlaylistButton(s.retry, onRetry,
                        containerColor = LocalMetroColors.current.surfaceVariant,
                        contentColor = LocalMetroColors.current.onBackground)
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(340.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
                contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                flingBehavior = rememberMetroFlingBehavior()
            ) {
                if (owned.isNotEmpty() || management) item(span = { GridItemSpan(maxLineSpan) }, key = "owned") {
                    PlaylistGroupHeading(s.playlists.owned, owned.size, if (management) onCreate else null)
                }
                items(owned, key = { it.id }) { item -> PlaylistCollectionRow(item, { onOpen(item) }, { onPlay(item.id) }) }
                if (subscribed.isNotEmpty()) item(span = { GridItemSpan(maxLineSpan) }, key = "subscribed") {
                    PlaylistGroupHeading(s.playlists.subscribed, subscribed.size)
                }
                items(subscribed, key = { it.id }) { item -> PlaylistCollectionRow(item, { onOpen(item) }, { onPlay(item.id) }) }
                if (collection.items.isEmpty() && error == null) item(span = { GridItemSpan(maxLineSpan) }, key = "empty") {
                    MetroText(s.noPlaylists, color = LocalMetroColors.current.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp))
                }
            }
        }
    }
}

@Composable
private fun PlaylistGroupHeading(title: String, count: Int, onCreate: (() -> Unit)? = null) {
    val colors = LocalMetroColors.current
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f)) {
            MetroText(title, style = LocalMetroTypography.current.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
            MetroText(count.toString(), style = LocalMetroTypography.current.caption, color = colors.onSurfaceVariant)
        }
        if (onCreate != null) PlaylistButton(LocalStrings.current.playlists.create, onCreate,
            containerColor = colors.surfaceVariant, contentColor = colors.onBackground)
    }
}

@Composable
fun PlaylistCollectionRow(playlist: PlaylistInfo, onOpen: () -> Unit, onPlay: () -> Unit) {
    val s = LocalStrings.current
    val colors = LocalMetroColors.current
    Row(Modifier.fillMaxWidth().heightIn(min = 88.dp).clickable(role = Role.Button, onClick = onOpen)
        .padding(end = 16.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        PlaylistArtwork(playlist.coverImgUrl, Modifier.size(72.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MetroText(playlist.name, style = LocalMetroTypography.current.bodyLarge,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            val count = if (playlist.trackCount >= 0) s.trackCount(playlist.trackCount) else s.playlists.unknown
            MetroText(listOfNotNull(count, s.playlists.private.takeIf { playlist.privacy == 10 }).joinToString(" · "),
                style = LocalMetroTypography.current.caption, color = colors.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        PlayAllButton(size = 44.dp, onClick = onPlay)
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
