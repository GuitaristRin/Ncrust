package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenu
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenuItem
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.*

/**
 * 歌单详情页头。播放保持主操作（圆钮与专辑页同源）；管理动作是一行平铺的次级按钮：
 * 「编辑曲目」「编辑信息」两个高频入口直接可见，低频且破坏性的「删除」收进「⋯」菜单。
 *
 * 旧版「编辑播放列表 + 管理▾」里两个入口文案相同却指向不同的事（曲目 / 元数据），这里拆开命名。
 * [dimmed] 为编辑态提供：按钮保留原位只降低不透明度，避免页头高度随模式变化。
 */
@Composable
fun PlaylistDetailHeader(
    coverUrl: String?, title: String, privacy: String?, trackCount: Int, description: String,
    complete: Boolean, management: Boolean, owned: Boolean,
    onPlay: (() -> Unit)?, onEditTracks: (() -> Unit)?, onEdit: () -> Unit, onDelete: () -> Unit,
    dimmed: () -> Float = { 0f },
) {
    val s = LocalStrings.current
    val colors = LocalMetroColors.current
    val type = LocalMetroTypography.current
    var menu by remember { mutableStateOf(false) }
    var expanded by remember(description) { mutableStateOf(false) }
    LaunchedEffect(management, owned) { menu = false }
    val metadata: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                MetroText(title, style = type.title.copy(fontSize = 26.sp), maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(6.dp))
                MetroText(listOfNotNull(privacy, s.trackCountSongs(trackCount)).joinToString(" · "),
                    style = type.caption, color = colors.onSurfaceVariant)
            }
            if (onPlay != null) {
                Spacer(Modifier.width(16.dp))
                PlayAllButton(size = 48.dp, onClick = onPlay)
            }
        }
        if (description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            // 长简介默认三行，点按展开；不另放「更多」按钮占位。
            MetroText(description, style = type.bodyMedium, color = colors.onSurfaceVariant,
                maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { expanded = !expanded })
        }
        if (!complete) {
            Spacer(Modifier.height(12.dp))
            MetroText(s.playlists.incomplete, style = type.caption, color = colors.onSurfaceVariant)
        }
        if (management && (onEditTracks != null || owned)) {
            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth().graphicsLayer { alpha = 1f - 0.62f * dimmed().coerceIn(0f, 1f) },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val pad = PaddingValues(horizontal = 14.dp, vertical = 10.dp)
                // 本人歌单进「编辑曲目」；别人的歌单同一位置只做选择（把歌加进自己的歌单）。
                if (onEditTracks != null) MetroButton(
                    if (owned) s.playlists.editTracks else s.playlists.selectTracks, onEditTracks,
                    Modifier.weight(1f, fill = false), leadingIcon = Icons.Default.Checklist,
                    contentPadding = pad, textStyle = type.bodyMedium
                )
                if (owned) {
                    MetroButton(s.playlists.editInfo, onEdit, Modifier.weight(1f, fill = false),
                        leadingIcon = Icons.Default.Edit, contentPadding = pad, textStyle = type.bodyMedium)
                    Box {
                        MetroIconButton(onClick = { menu = true }) {
                            MetroIcon(Icons.Default.MoreHoriz, contentDescription = s.playlists.manage, tint = colors.onBackground)
                        }
                        MetroDropdownMenu(menu, { menu = false }, alignment = Alignment.BottomEnd) {
                            MetroDropdownMenuItem(s.playlists.delete, { menu = false; onDelete() })
                        }
                    }
                }
            }
        }
    }
    BoxWithConstraints(Modifier.fillMaxWidth().background(colors.background)) {
        if (maxWidth >= 600.dp) {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 56.dp, bottom = 24.dp),
                verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                PlaylistArtwork(coverUrl, Modifier.size(220.dp), large = true)
                Column(Modifier.weight(1f), content = metadata)
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                // 没有封面的新列表不留整屏空方块，仍让占位接触两侧边缘。
                PlaylistArtwork(coverUrl, if (coverUrl.isNullOrBlank()) Modifier.fillMaxWidth().height(160.dp)
                    else Modifier.fillMaxWidth().aspectRatio(1f), large = true)
                Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 12.dp), content = metadata)
            }
        }
    }
}
