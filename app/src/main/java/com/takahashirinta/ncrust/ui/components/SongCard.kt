package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

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
    val artistStr = song.artists?.joinToString("/") { it.name } ?: LocalStrings.current.unknownArtist
    val albumName = song.album?.name ?: ""
    val durationStr = song.duration?.let { formatDuration(it) } ?: ""

    var isPressed by remember { mutableStateOf(false) }
    val scale by animateFloatAsState(
        targetValue = if (isPressed && style == SongCardStyle.GRID) 1.03f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        )
    )

    when (style) {
        SongCardStyle.LIST, SongCardStyle.COMPACT -> {
            val actualCoverSize = when {
                coverSize != Dp.Unspecified -> coverSize
                else -> 56.dp
            }

            Row(
                modifier = modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = onClick,
                        onLongClick = { onShowMenu?.invoke() }
                    )
                    .padding(vertical = 10.dp, horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (showCover) {
                    AsyncImage(
                        model = song.album?.picUrl,
                        contentDescription = "封面",
                        modifier = Modifier.size(actualCoverSize),
                        contentScale = ContentScale.Crop
                    )
                    Spacer(Modifier.width(12.dp))
                }

                Column(Modifier.weight(1f)) {
                    Text(
                        song.name,
                        color = if (isCurrentPlaying) MaterialTheme.colorScheme.primary else Color.White,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        buildString {
                            append(artistStr)
                            if (albumName.isNotEmpty()) append(" · $albumName")
                            if (durationStr.isNotEmpty()) append("  $durationStr")
                        },
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                if (onShowMenu != null) {
                    IconButton(onClick = onShowMenu) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = null,
                            tint = Color.Gray,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        actions?.invoke(this)
                    }
                }
            }
        }

        SongCardStyle.GRID -> {
            Column(
                modifier = modifier
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onPress = {
                                isPressed = true
                                tryAwaitRelease()
                                isPressed = false
                            },
                            onTap = { onClick() },
                            onLongPress = { onShowMenu?.invoke() }
                        )
                    }
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                    }
            ) {
                AsyncImage(
                    model = song.album?.picUrl,
                    contentDescription = "封面",
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    contentScale = ContentScale.Crop
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    song.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    "$artistStr · $albumName",
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun PlayAllCircleButton(
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .size(size)
            .background(MaterialTheme.colorScheme.primary, CircleShape)
            .combinedClickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            Icons.Default.PlayArrow,
            LocalStrings.current.playAllButton,
            tint = Color.Black,
            modifier = Modifier.size((size * 0.55f))
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
