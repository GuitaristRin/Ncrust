package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenu
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenuItem
import io.github.takahashirinta.kanesumi.core.theme.*

/** 播放保持主操作；管理和选择使用次级表面，删除经菜单进入原确认流程。 */
@Composable
fun PlaylistDetailHeader(
    coverUrl: String?, title: String, privacy: String?, trackCount: Int, description: String,
    complete: Boolean, management: Boolean, owned: Boolean, selecting: Boolean,
    onPlay: (() -> Unit)?, onSelect: (() -> Unit)?, onEdit: () -> Unit, onDelete: () -> Unit,
    selection: @Composable ColumnScope.() -> Unit = {}
) {
    val s = LocalStrings.current
    val colors = LocalMetroColors.current
    var menu by remember { mutableStateOf(false) }
    LaunchedEffect(management, owned, selecting) { menu = false }
    val metadata: @Composable ColumnScope.() -> Unit = {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                MetroText(title, style = LocalMetroTypography.current.title.copy(fontSize = 26.sp),
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(8.dp))
                MetroText(listOfNotNull(privacy, s.trackCountSongs(trackCount)).joinToString(" · "),
                    style = LocalMetroTypography.current.caption, color = colors.onSurfaceVariant)
            }
            if (onPlay != null) {
                Spacer(Modifier.width(16.dp))
                PlayAllButton(size = 48.dp, onClick = onPlay)
            }
        }
        if (description.isNotBlank()) {
            Spacer(Modifier.height(12.dp))
            MetroText(description, style = LocalMetroTypography.current.bodyMedium, color = colors.onSurfaceVariant)
        }
        if (!complete) {
            Spacer(Modifier.height(12.dp))
            MetroText(s.playlists.incomplete, style = LocalMetroTypography.current.caption,
                color = colors.onSurfaceVariant)
        }
        if (management) {
            if (selecting) {
                Spacer(Modifier.height(16.dp))
                selection()
            } else if (onSelect != null || owned) {
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (onSelect != null) PlaylistButton(s.playlists.selectTracks, onSelect, Modifier.weight(1f),
                        containerColor = colors.surfaceVariant, contentColor = colors.onBackground)
                    if (owned) Box {
                        PlaylistButton(s.playlists.manage, { menu = true },
                            containerColor = colors.surfaceVariant, contentColor = colors.onBackground)
                        MetroDropdownMenu(menu, { menu = false }, alignment = Alignment.BottomEnd) {
                            MetroDropdownMenuItem(s.playlists.edit, { menu = false; onEdit() })
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
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 20.dp), content = metadata)
            }
        }
    }
}
