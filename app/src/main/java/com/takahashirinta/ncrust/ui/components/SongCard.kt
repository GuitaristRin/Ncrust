package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.ColorPainter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouPresets
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.launch

enum class SongCardStyle {
    LIST,
    COMPACT,
    GRID
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun SongCard(
    song: SongItem,
    style: SongCardStyle = SongCardStyle.LIST,
    modifier: Modifier = Modifier,
    coverSize: Dp = Dp.Unspecified,
    onClick: () -> Unit = {},
    onShowMenu: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
    isCurrentPlaying: Boolean = false,
    showCover: Boolean = true
) {
    val strings = LocalStrings.current
    val artistStr = song.artists?.joinToString("/") { it.name } ?: strings.unknownArtist
    val albumName = song.album?.name ?: ""
    val durationStr = song.duration?.let { formatDuration(it) } ?: ""

    when (style) {
        SongCardStyle.LIST, SongCardStyle.COMPACT -> {
            val actualCoverSize = when {
                coverSize != Dp.Unspecified -> coverSize
                else -> 72.dp
            }

            // 无边框版式：封面贴屏幕左沿（无左 padding），文字与右侧 actions 保留 16dp 右 padding。
            // 长按触发菜单；右侧 MoreVert 按钮取消，避免与"无边框"视觉冲突。
            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { onShowMenu?.invoke() }
                    )
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showCover) {
                    AsyncImage(
                        model = CoverUrls.small(song.album?.picUrl),
                        contentDescription = strings.coverDesc,
                        // 纯色占位:避免低端机解码完成前出现"空方块"闪变(Metro 不做 crossfade,直接落图)
                        placeholder = ColorPainter(LocalMetroColors.current.surfaceVariant),
                        modifier = Modifier.size(actualCoverSize),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(14.dp))
                } else {
                    Spacer(Modifier.width(16.dp))
                }

                Column(Modifier.weight(1f)) {
                    MetroText(
                        song.name,
                        color = if (isCurrentPlaying) LocalMetroColors.current.primary else LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    MetroText(
                        buildString {
                            append(artistStr)
                            if (albumName.isNotEmpty()) append(" · $albumName")
                            if (durationStr.isNotEmpty()) append("  $durationStr")
                        },
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (actions != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        actions.invoke(this)
                    }
                }
                Spacer(Modifier.width(16.dp))
            }
        }

        SongCardStyle.GRID -> {
            // 按压缩放动画：Animatable + graphicsLayer，动画帧仅在 draw 阶段消费。
            // 只给 GRID 创建——LIST 是首页高频路径，每项省一个 Animatable + 协程作用域分配。
            val scaleAnim = remember { Animatable(1f) }
            val scaleScope = rememberCoroutineScope()
            Column(
                modifier = modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                scaleScope.launch { scaleAnim.animateTo(1.03f, SokuouPresets.QuickInteraction) }
                                tryAwaitRelease()
                                scaleScope.launch { scaleAnim.animateTo(1f, SokuouPresets.QuickInteraction) }
                            },
                            onTap = { onClick() },
                            onLongPress = { onShowMenu?.invoke() }
                        )
                    }
                    .graphicsLayer {
                        val s = scaleAnim.value
                        scaleX = s
                        scaleY = s
                    }
            ) {
                AsyncImage(
                    model = CoverUrls.small(song.album?.picUrl),
                    contentDescription = strings.coverDesc,
                    placeholder = ColorPainter(LocalMetroColors.current.surfaceVariant),
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(8.dp))
                MetroText(
                    song.name,
                    color = LocalMetroColors.current.onBackground,
                    style = LocalMetroTypography.current.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                MetroText(
                    "$artistStr · $albumName",
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = LocalMetroTypography.current.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayAllButton(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onClick: () -> Unit
) {
    // 圆形外框(用户决策): 直径 = 原边长。clip 先于 background, 裁切不产生
    // 额外合成层, 与方形按钮同价。
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(LocalMetroColors.current.primary)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        MetroIcon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = LocalStrings.current.playAllButton,
            tint = Color.Black,
            sizeDp = size * 0.55f
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
