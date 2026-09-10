package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AllInclusive
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.runtime.*
import androidx.compose.runtime.withFrameNanos
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
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 队列面板 —— 三区视图（Apple Music 式）：过去播放 / 现在播放 / 将要播放。
 *
 * 语义约定：
 *  - 过去 = index < current, 现在 = current, 将要 = index > current。
 *  - 拖拽排序仅限「将要播放」区（长按右侧三条横线），原因是过去区是播放历史，
 *    动它会破坏「现在播放的下一首」语义；infinity(FM) 模式下整个队列只读。
 *  - infinity(FM) 且将要播放为空 → 显示「相似歌曲续播」占位符。
 *  - 面板每次可见时把「现在播放」自动定位到视觉中心。
 */
@Composable
fun QueueView(
    queue: List<SongItem>,
    currentIndex: Int,
    playMode: Int = QueueModes.CYCLE,
    isActive: Boolean = false,
    interactive: Boolean = true,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit,
    onMove: (Int, Int) -> Unit = { _, _ -> }
) {
    val strings = LocalStrings.current
    val infinityActive = playMode == QueueModes.INFINITY

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current

    // 拖拽状态：draggingQueueIndex / dragTargetQueueIndex 都是队列索引（0..queue.size-1）
    var draggingQueueIndex by remember { mutableIntStateOf(-1) }
    var dragTargetQueueIndex by remember { mutableIntStateOf(-1) }
    var listTopInRoot by remember { mutableFloatStateOf(0f) }
    var draggedRowTopInRoot by remember { mutableFloatStateOf(0f) }
    var draggedRowHeight by remember { mutableFloatStateOf(0f) }
    val handleHalfPx = with(density) { 16.dp.toPx() }

    // ---- 行模型 ----
    // RowInfo(kind=PAST_HEAD/NOW_HEAD/UPCOMING_HEAD/INFINITY/SONG, queueIndex)
    val rows = remember(queue, currentIndex, infinityActive) { buildQueueRows(queue, currentIndex, infinityActive) }

    if (rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MetroText(strings.emptyQueue, color = Color.Gray, style = TextStyle(fontSize = 16.sp))
        }
        return
    }

    // 打开(变为可见)/内容变化时把「现在播放」行定位到视觉中心。
    // 旧实现只 withFrameNanos 等一帧就查 layoutInfo: LazyColumn 懒布局下目标行
    // 往往还没被 measure, 二次居中滚动提前 return, 表现就是"不自动定位"。
    // 现在循环等目标行真正进视口后再补居中滚动。
    val nowVisualRow = rows.indexOfFirst { it.kind == RowKind.NOW_HEAD }
    LaunchedEffect(isActive, queue, currentIndex) {
        if (!isActive) return@LaunchedEffect
        val target = nowVisualRow.takeIf { it >= 0 } ?: return@LaunchedEffect
        listState.scrollToItem(target)
        var attempts = 0
        while (attempts < 20) {
            withFrameNanos { }
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == target }
            if (item != null) {
                // scrollToItem(index, offset) 让目标行顶部距视口顶部 offset 像素 → 垂直居中
                listState.scrollToItem(
                    target,
                    scrollOffset = ((info.viewportEndOffset - item.size) / 2).coerceAtLeast(0)
                )
                break
            }
            attempts++
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .onGloballyPositioned { listTopInRoot = it.positionInRoot().y },
            flingBehavior = rememberMetroFlingBehavior()
        ) {
            itemsIndexed(rows, key = { _, r -> r.key }) { visualRow, row ->
                when (row.kind) {
                    RowKind.PAST_HEAD, RowKind.NOW_HEAD, RowKind.UPCOMING_HEAD -> {
                        SectionHeader(
                            title = when (row.kind) {
                                RowKind.PAST_HEAD -> strings.queueSectionPast
                                RowKind.NOW_HEAD -> strings.queueSectionNow
                                else -> strings.queueSectionUpcoming
                            }
                        )
                    }
                    RowKind.INFINITY -> InfinityPlaceholder(strings.queueInfinityPlaceholder)
                    else -> {
                        val qi = row.queueIndex
                        val song = queue.getOrNull(qi) ?: return@itemsIndexed
                        val isWillPlay = qi > currentIndex
                        // 仅将要播放且非 infinity 可长按拖动
                        val canDrag = isWillPlay && !infinityActive
                        val highlighted = qi == dragTargetQueueIndex && qi != draggingQueueIndex
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem()
                                .background(
                                    if (highlighted) LocalMetroColors.current.surfaceVariant
                                    else Color.Transparent
                                )
                                // 记录被拖行在 root 中的位置（拖拽把手坐标换算基座）
                                .onGloballyPositioned {
                                    if (qi == draggingQueueIndex) {
                                        draggedRowTopInRoot = it.positionInRoot().y
                                        draggedRowHeight = it.size.height.toFloat()
                                    }
                                }
                        ) {
                            SongCard(
                                song = song,
                                style = SongCardStyle.COMPACT,
                                // 折叠态下面板不可交互(见 PlayerCard cardExpandedForInput):
                                // 否则迷你条与导航栏之间那截屏幕上的不可见队列行会吞点击。
                                onClick = { if (interactive) onPlayIndex(qi) },
                                isCurrentPlaying = qi == currentIndex,
                                actions = {
                                    if (canDrag && interactive) {
                                        Box(
                                            modifier = Modifier
                                                .size(32.dp)
                                                .pointerInput(qi) {
                                                    detectDragGesturesAfterLongPress(
                                                        onDragStart = {
                                                            draggingQueueIndex = qi
                                                            dragTargetQueueIndex = qi
                                                        },
                                                        onDrag = { change, _ ->
                                                            change.consume()
                                                            val viewportY =
                                                                (draggedRowTopInRoot - listTopInRoot) +
                                                                    change.position.y + draggedRowHeight / 2f - handleHalfPx
                                                            val targetVisual =
                                                                itemIndexAt(listState, viewportY) ?: visualRow
                                                            // 视觉行 → 队列索引：只许落在「将要播放」区内
                                                            val next = rows.getOrNull(targetVisual)
                                                            dragTargetQueueIndex = next?.queueIndex
                                                                ?.takeIf { it > currentIndex } ?: qi
                                                        },
                                                        onDragEnd = {
                                                            val from = draggingQueueIndex
                                                            val to = dragTargetQueueIndex
                                                            draggingQueueIndex = -1
                                                            dragTargetQueueIndex = -1
                                                            if (from >= 0 && to >= 0 && from != to) {
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                onMove(from, to)
                                                            }
                                                        },
                                                        onDragCancel = {
                                                            draggingQueueIndex = -1
                                                            dragTargetQueueIndex = -1
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
                                    }
                                    MetroIconButton(
                                        onClick = {
                                            if (!interactive) return@MetroIconButton
                                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                            onRemoveIndex(qi)
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
            }
        }

        // 上/下渐隐
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

private enum class RowKind { PAST_HEAD, NOW_HEAD, UPCOMING_HEAD, SONG, INFINITY }

private data class RowInfo(val kind: RowKind, val queueIndex: Int, val key: String)

private fun buildQueueRows(
    queue: List<SongItem>,
    currentIndex: Int,
    infinityActive: Boolean
): List<RowInfo> {
    if (queue.isEmpty()) return emptyList()
    val rows = mutableListOf<RowInfo>()
    val cur = currentIndex.coerceIn(0, queue.size - 1)

    if (cur > 0) {
        rows += RowInfo(RowKind.PAST_HEAD, -1, "h-past")
        for (i in 0 until cur) rows += RowInfo(RowKind.SONG, i, "s-${queue[i].id}")
    }
    rows += RowInfo(RowKind.NOW_HEAD, cur, "h-now")
    rows += RowInfo(RowKind.SONG, cur, "s-${queue[cur].id}")
    if (cur < queue.size - 1 || infinityActive) {
        rows += RowInfo(RowKind.UPCOMING_HEAD, -1, "h-upcoming")
        for (i in (cur + 1) until queue.size) rows += RowInfo(RowKind.SONG, i, "s-${queue[i].id}")
        if (infinityActive) rows += RowInfo(RowKind.INFINITY, -1, "infinity")
    }
    return rows
}

@Composable
private fun SectionHeader(title: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            title,
            color = LocalMetroColors.current.primary,
            style = TextStyle(fontSize = 13.sp)
        )
        Spacer(Modifier.width(12.dp))
        MetroDivider(color = Color(0xFF2A2A2A), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun InfinityPlaceholder(text: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroIcon(
            imageVector = Icons.Default.AllInclusive,
            contentDescription = null,
            tint = LocalMetroColors.current.primary,
            sizeDp = 20.dp
        )
        Spacer(Modifier.width(8.dp))
        MetroText(text, color = Color.Gray, style = TextStyle(fontSize = 14.sp))
    }
}

// 视口 Y 坐标 → 命中的行索引（layoutInfo.offset 即视口内偏移）。
private fun itemIndexAt(listState: LazyListState, viewportY: Float): Int? {
    return listState.layoutInfo.visibleItemsInfo
        .firstOrNull { viewportY >= it.offset && viewportY <= it.offset + it.size }
        ?.index
}