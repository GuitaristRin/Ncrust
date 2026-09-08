package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.network.SongItem
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

@Composable
fun QueueView(
    queue: List<SongItem>,
    currentIndex: Int,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onMove: (Int, Int) -> Unit = { _, _ -> }
) {
    val strings = LocalStrings.current
    if (queue.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MetroText(strings.emptyQueue, color = Color.Gray, style = TextStyle(fontSize = 16.sp))
        }
        return
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    // 拖拽状态：dragIndex = 正在拖的行；targetIndex = 当前悬停目标（高亮）。
    var draggingIndex by remember { mutableIntStateOf(-1) }
    var dragTargetIndex by remember { mutableIntStateOf(-1) }
    // 列表容器在 root 的 Y + 被拖行的高度：把手局部坐标 → 列表视口坐标的换算基座。
    var listTopInRoot by remember { mutableFloatStateOf(0f) }
    var draggedRowTopInRoot by remember { mutableFloatStateOf(0f) }
    var draggedRowHeight by remember { mutableFloatStateOf(0f) }
    val handleHalfPx = with(density) { 16.dp.toPx() }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { listTopInRoot = it.positionInRoot().y },
            flingBehavior = rememberMetroFlingBehavior()
        ) {
            // key = song.id 让 LazyColumn 在插入/删除时按身份 diff，只重建真正变化的项。
            // 缺少 key 时删首行/中间行会重组所有可见项 → 低端机上一次修改 20+ 首歌 recomposition
            itemsIndexed(queue, key = { _, s -> s.id }) { index, song ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (index == dragTargetIndex && index != draggingIndex)
                                LocalMetroColors.current.surfaceVariant
                            else Color.Transparent
                        )
                        .onGloballyPositioned {
                            if (index == draggingIndex) {
                                draggedRowTopInRoot = it.positionInRoot().y
                                draggedRowHeight = it.size.height.toFloat()
                            }
                        }
                ) {
                    SongCard(
                        song = song,
                        style = SongCardStyle.COMPACT,
                        onClick = { onPlayIndex(index) },
                        isCurrentPlaying = index == currentIndex,
                        actions = {
                            // 拖拽把手：抓取后上下拖动排序。位置 = 把手局部坐标换算到视口。
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .pointerInput(index) {
                                        detectDragGestures(
                                            onDragStart = {
                                                draggingIndex = index
                                                dragTargetIndex = index
                                            },
                                            onDrag = { change, _ ->
                                                change.consume()
                                                val viewportY = (draggedRowTopInRoot - listTopInRoot) +
                                                    change.position.y + draggedRowHeight / 2f - handleHalfPx
                                                dragTargetIndex = itemIndexAt(listState, viewportY) ?: index
                                            },
                                            onDragEnd = {
                                                val from = draggingIndex
                                                val to = dragTargetIndex
                                                draggingIndex = -1
                                                dragTargetIndex = -1
                                                if (from >= 0 && to >= 0 && from != to) {
                                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                    onMove(from, to)
                                                }
                                            },
                                            onDragCancel = {
                                                draggingIndex = -1
                                                dragTargetIndex = -1
                                            }
                                        )
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                MetroIcon(
                                    imageVector = Icons.Default.DragHandle,
                                    contentDescription = null,
                                    tint = Color.Gray,
                                    sizeDp = 20.dp
                                )
                            }
                            MetroIconButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    onRemoveIndex(index)
                                }
                            ) {
                                MetroIcon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = null,
                                    tint = Color.Gray,
                                    sizeDp = 20.dp
                                )
                            }
                        }
                    )
                }
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(LocalMetroColors.current.background, Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, LocalMetroColors.current.background)
                    )
                )
        )
    }
}

// 视口 Y 坐标 → 命中的行索引（layoutInfo.offset 即视口内偏移）。
private fun itemIndexAt(listState: LazyListState, viewportY: Float): Int? {
    return listState.layoutInfo.visibleItemsInfo
        .firstOrNull { viewportY >= it.offset && viewportY <= it.offset + it.size }
        ?.index
}
