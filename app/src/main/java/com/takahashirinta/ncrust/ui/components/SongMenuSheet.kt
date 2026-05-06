package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.SongItem
import kotlinx.coroutines.launch

data class SongMenuAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

@Composable
fun SongMenuSheet(
    song: SongItem,
    actions: List<SongMenuAction>,
    onDismiss: () -> Unit
) {
    val progress = remember { Animatable(0f) }
    val coroutineScope = rememberCoroutineScope()
    var sheetHeightPx by remember { mutableStateOf(0f) }

    LaunchedEffect(Unit) {
        progress.animateTo(1f, tween(400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
    }

    fun animateDismiss() {
        coroutineScope.launch {
            progress.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
            onDismiss()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Scrim — 点击收起
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = progress.value * 0.6f }
                .background(Color.Black)
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() }
                ) { animateDismiss() }
        )

        // Sheet 主体
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .onSizeChanged { sheetHeightPx = it.height.toFloat() }
                .graphicsLayer { translationY = size.height * (1f - progress.value) }
                .background(MaterialTheme.colorScheme.surface)
        ) {
            // 信息区：可拖拽下滑收起
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp)
                    .pointerInput(Unit) {
                        detectVerticalDragGestures(
                            onDragStart = {},
                            onDragEnd = {
                                coroutineScope.launch {
                                    if (progress.value < 0.6f) {
                                        progress.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                                        onDismiss()
                                    } else {
                                        progress.animateTo(1f, tween(300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
                                    }
                                }
                            },
                            onVerticalDrag = { change, dragAmount ->
                                change.consume()
                                if (sheetHeightPx > 0f) {
                                    coroutineScope.launch {
                                        progress.snapTo(
                                            (progress.value - dragAmount / sheetHeightPx).coerceIn(0f, 1f)
                                        )
                                    }
                                }
                            }
                        )
                    },
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 直角封面，贴屏左边缘，112dp = 2x 迷你播放栏封面高
                AsyncImage(
                    model = song.album?.picUrl,
                    contentDescription = null,
                    modifier = Modifier.size(112.dp),
                    contentScale = ContentScale.Crop
                )
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        song.name,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        song.artists?.joinToString("/") { it.name } ?: "",
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val albumName = song.album?.name
                    if (!albumName.isNullOrEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            albumName,
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            HorizontalDivider(color = Color(0xFF2A2A2A))

            // 可扩展操作列表
            actions.forEach { action ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(
                            indication = null,
                            interactionSource = remember { MutableInteractionSource() }
                        ) {
                            action.onClick()
                            animateDismiss()
                        }
                        .padding(horizontal = 16.dp, vertical = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        action.icon,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(16.dp))
                    Text(
                        action.label,
                        color = Color.White,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }

            Spacer(Modifier.windowInsetsBottomHeight(WindowInsets.navigationBars))
        }
    }
}
