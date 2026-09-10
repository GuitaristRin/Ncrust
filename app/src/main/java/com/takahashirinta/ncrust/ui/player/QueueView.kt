package com.takahashirinta.ncrust.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.anim.sokuou.sokuouSpring
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 队列面板 —— 三区视图（Apple Music 式）：过去播放 / 现在播放 / 将要播放。
 *
 * 语义约定：
 *  - 过去 = index < current, 现在 = current, 将要 = index > current。
 *  - 拖拽排序仅限「将要播放」区（手指碰到右侧三条横线即开始拖动，无需长按），
 *    原因是过去区是播放历史，动它会破坏「现在播放的下一首」语义；
 *    infinity(FM) 模式下整个队列只读。
 *  - 拖动过程是纯视觉联动：被拖行 1:1 跟随手指（无动画、无滞后，跟手），
 *    中间行用 Sokuou 阻尼弹簧让位；真正的队列(stack)只在手指抬起、
 *    状态稳定后才更新一次，落位用弹簧 placement 平滑收尾。
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
    val haptic = LocalHapticFeedback.current
    val density = LocalDensity.current

    // 拖拽状态：draggingQueueIndex / dragTargetQueueIndex 都是队列索引（0..queue.size-1）
    var draggingQueueIndex by remember { mutableIntStateOf(-1) }
    var dragTargetQueueIndex by remember { mutableIntStateOf(-1) }
    // 被拖行的垂直位移(跟随手指)与把手按下时的起始 y —— 联动视觉只用这两个
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var dragStartY by remember { mutableFloatStateOf(0f) }
    var draggedRowHeight by remember { mutableFloatStateOf(0f) }
    // 被拖行的视觉行下标(rows 下标, 拖拽期间列表滚动后布局位置会变, 用它查实时 offset)
    var draggedVisualRow by remember { mutableIntStateOf(-1) }
    // 拖到视口上/下边缘时的自动滚动: -1=向上, 1=向下, 0=停
    var autoScrollDir by remember { mutableFloatStateOf(0f) }
    val dragScrollScope = rememberCoroutineScope()
    var dragScrollJob by remember { mutableStateOf<Job?>(null) }
    // 落定动画作用域: 松手后行从当前位置回正 / 滑到目标槽位, 由阻尼弹簧驱动
    val dragSettleScope = rememberCoroutineScope()

    // ---- 行模型 ----
    // RowInfo(kind=PAST_HEAD/NOW_HEAD/UPCOMING_HEAD/INFINITY/SONG, queueIndex)
    val rows = remember(queue, currentIndex, infinityActive) { buildQueueRows(queue, currentIndex, infinityActive) }

    if (rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MetroText(strings.emptyQueue, color = Color.Gray, style = TextStyle(fontSize = 16.sp))
        }
        return
    }

    // 打开(变为可见)/切歌时把「现在播放」那首歌定位到视觉中心。
    // - 面板刚打开: 等视口测量出来 → 硬定位到目标行 → 等目标行真正 compose
    //   进视口(懒布局) → 补居中偏移。用 snapshotFlow 等条件成立, 比数帧稳。
    // - 队列已打开时切歌: 用 animateScrollToItem 平滑滑动, 让「现在播放」块
    //   滑到中心(Apple Music 语义); 旧实现每次 scrollToItem 硬跳, 连播时跳闪。
    val nowVisualRow = rows.indexOfFirst { it.kind == RowKind.NOW_HEAD }
    // 目标 = 「现在播放」标题下面那首歌, 而不是标题本身 —— 定位的是正在播的行
    val nowSongVisualRow = (nowVisualRow + 1).takeIf { it < rows.size } ?: nowVisualRow
    val wasQueueOpen = remember { mutableStateOf(false) }
    LaunchedEffect(isActive, currentIndex, nowVisualRow) {
        if (!isActive) {
            wasQueueOpen.value = false
            return@LaunchedEffect
        }
        val target = nowSongVisualRow.takeIf { it >= 0 } ?: return@LaunchedEffect
        if (!wasQueueOpen.value) {
            // 面板首次打开: 硬定位 + 等目标行 measure 后居中
            snapshotFlow { listState.layoutInfo.viewportEndOffset }.first { it > 0 }
            listState.scrollToItem(target)
            snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == target } }
                .first { it }
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.first { it.index == target }
            // scrollToItem(index, offset) 让目标行顶部距视口顶部 offset 像素 → 垂直居中
            listState.scrollToItem(
                target,
                scrollOffset = ((info.viewportEndOffset - item.size) / 2).coerceAtLeast(0)
            )
        } else {
            // 队列已打开且切歌: 平滑滑到中心。目标行已在视口内时直接带偏移动画,
            // 否则先滑到目标行再补居中(跨区切歌时也能连贯)。
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == target }
            if (item != null) {
                listState.animateScrollToItem(
                    target,
                    scrollOffset = ((info.viewportEndOffset - item.size) / 2).coerceAtLeast(0)
                )
            } else {
                listState.animateScrollToItem(target)
            }
        }
        wasQueueOpen.value = true
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
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
                        // 将要播放且非 infinity 可拖: 手指碰到把手即拖动, 无需长按
                        val canDrag = isWillPlay && !infinityActive
                        val highlighted = qi == dragTargetQueueIndex && qi != draggingQueueIndex
                        // 拖动时的让位目标: 向下拖 → 中间行上移一行; 向上拖 → 中间行下移一行
                        val shiftTarget = when {
                            qi == draggingQueueIndex -> 0f
                            draggingQueueIndex >= 0 && dragTargetQueueIndex >= 0 &&
                                qi > draggingQueueIndex && qi <= dragTargetQueueIndex -> -draggedRowHeight
                            draggingQueueIndex >= 0 && dragTargetQueueIndex >= 0 &&
                                qi < draggingQueueIndex && qi >= dragTargetQueueIndex -> draggedRowHeight
                            else -> 0f
                        }
                        // Sokuou 阻尼弹簧: 拖动中让位动作有惯性与阻尼, 不硬切不振荡。
                        // 落定瞬间(dragging 已清)让位直接归零——与槽位跳变同帧发生、
                        // 相互抵消, 其余卡片不产生多余移动("落定后还抖一下"的来源)。
                        val animatedShift = animateFloatAsState(
                            targetValue = shiftTarget,
                            animationSpec = if (draggingQueueIndex >= 0)
                                sokuouSpring(response = 0.22f, dampingRatio = 1f)
                            else tween(0),
                            label = "queueRowShift"
                        ).value
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .animateItem(
                                    // placement 恒定瞬时: 重排的视觉完全由拖拽的让位位移承担,
                                    // 落定瞬间槽位跳变 + 让位归零同帧相消, 零视觉变化;
                                    // 若落定帧切回 spring, animateItem 会把整轮重排再播一遍
                                    // (= 用户看到的"重排动画")
                                    placementSpec = tween(0)
                                )
                                .graphicsLayer {
                                    // 被拖行直接跟随手指(graphicsLayer 帧内读取, 不触发重组);
                                    // 其余行用弹簧让位, 形成"挖出空位"的联动感
                                    translationY = if (qi == draggingQueueIndex) dragOffsetY else animatedShift
                                    alpha = if (qi == draggingQueueIndex) 0.92f else 1f
                                }
                                .zIndex(if (qi == draggingQueueIndex) 1f else 0f)
                                .background(
                                    if (highlighted || qi == draggingQueueIndex)
                                        LocalMetroColors.current.surfaceVariant
                                    else Color.Transparent
                                )
                                // 记录被拖行高度（让位距离换算基座）
                                .onGloballyPositioned {
                                    if (qi == draggingQueueIndex) {
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
                                                    detectDragGestures(
                                                        onDragStart = { startOffset ->
                                                            draggingQueueIndex = qi
                                                            dragTargetQueueIndex = qi
                                                            dragStartY = startOffset.y
                                                            dragOffsetY = 0f
                                                            draggedVisualRow = visualRow
                                                            autoScrollDir = 0f
                                                            // 拖到视口上/下边缘时列表自动滚动: 每帧按方向滚一段,
                                                            // 并补偿 dragOffsetY, 让被拖行始终停在手指下
                                                            dragScrollJob?.cancel()
                                                            dragScrollJob = dragScrollScope.launch {
                                                                while (isActive) {
                                                                    val dir = autoScrollDir
                                                                    if (dir != 0f) {
                                                                        val consumed = listState.scrollBy(dir * 24f)
                                                                        if (consumed != 0f) {
                                                                            dragOffsetY += consumed
                                                                            // 列表滚动后插入位置会变: 用行的实时视口位置
                                                                            // 重新判定目标槽位, 松手落位才是正确的 to
                                                                            val draggedItem = listState.layoutInfo
                                                                                .visibleItemsInfo
                                                                                .firstOrNull {
                                                                                    it.index == draggedVisualRow
                                                                                }
                                                                            val rowCenter =
                                                                                ((draggedItem?.offset ?: 0)).toFloat() +
                                                                                    dragOffsetY + draggedRowHeight / 2f
                                                                            val targetVisual =
                                                                                itemIndexAt(listState, rowCenter)
                                                                            if (targetVisual != null) {
                                                                                val next = rows.getOrNull(targetVisual)
                                                                                if (next != null &&
                                                                                    next.queueIndex > currentIndex
                                                                                ) {
                                                                                    dragTargetQueueIndex = next.queueIndex
                                                                                }
                                                                            }
                                                                        }
                                                                    }
                                                                    withFrameNanos { }
                                                                }
                                                            }
                                                        },
                                                        onDrag = { change, _ ->
                                                            change.consume()
                                                            // 关键: 累计增量, 不能取绝对值。
                                                            // 被拖行自身带着 graphicsLayer translation, 指针事件的
                                                            // 局部坐标会被该位移反变换——用绝对值计算 offset 会形成
                                                            // 反馈环: 行只跟手一半距离, 每帧都在过冲/回摆 = 抖动。
                                                            // 累计增量后: 行 1:1 跟随手指, 局部坐标保持按下时的值,
                                                            // 增量收敛到 0, 不再振荡。
                                                            dragOffsetY += change.position.y - dragStartY
                                                            // 用被拖行的实时视口 offset(列表滚动后布局位置会变)
                                                            val draggedItem = listState.layoutInfo
                                                                .visibleItemsInfo
                                                                .firstOrNull { it.index == draggedVisualRow }
                                                            val itemOffset = (draggedItem?.offset ?: 0).toFloat()
                                                            val viewportH =
                                                                listState.layoutInfo.viewportEndOffset.toFloat()
                                                            // 行不能飞出队列可见区: 手指出界时行钉在视口上/下边缘,
                                                            // 而不是跟着手指飞出可见范围
                                                            val minOff = -itemOffset
                                                            val maxOff =
                                                                (viewportH - draggedRowHeight - itemOffset)
                                                                    .coerceAtLeast(minOff)
                                                            dragOffsetY = dragOffsetY.coerceIn(minOff, maxOff)
                                                            val rowCenterViewportY =
                                                                itemOffset + dragOffsetY + draggedRowHeight / 2f
                                                            // 边缘自动滚动: 行贴近上/下边缘(或已钉在边缘、手指仍在外)
                                                            // 时跟手滚 —— 钉住只负责"行不飞出", 不负责停滚
                                                            val edge = with(density) { 72.dp.toPx() }
                                                            autoScrollDir = when {
                                                                rowCenterViewportY < edge -> -1f
                                                                rowCenterViewportY > viewportH - edge -> 1f
                                                                else -> 0f
                                                            }
                                                            val targetVisual =
                                                                itemIndexAt(listState, rowCenterViewportY) ?: visualRow
                                                            // 视觉行 → 队列索引：只许落在「将要播放」区内
                                                            val next = rows.getOrNull(targetVisual)
                                                            dragTargetQueueIndex = next?.queueIndex
                                                                ?.takeIf { it > currentIndex } ?: qi
                                                        },
                                                        onDragEnd = {
                                                            // 手指抬起 = 状态稳定, 才真正更新队列(stack)
                                                            dragScrollJob?.cancel()
                                                            dragScrollJob = null
                                                            autoScrollDir = 0f
                                                            val from = draggingQueueIndex
                                                            val to = dragTargetQueueIndex
                                                            if (from >= 0 && to >= 0 && from == to) {
                                                                // 原位松手: 被拖行**从当前位置**阻尼回正, 其余行让位
                                                                // 同时弹簧归位 —— 只有"回到原槽"需要动画
                                                                dragTargetQueueIndex = -1
                                                                val startOffset = dragOffsetY
                                                                dragSettleScope.launch {
                                                                    val anim = Animatable(startOffset)
                                                                    anim.animateTo(
                                                                        0f,
                                                                        sokuouSpring(response = 0.18f, dampingRatio = 1f)
                                                                    ) { dragOffsetY = value }
                                                                    draggingQueueIndex = -1
                                                                    dragOffsetY = 0f
                                                                    draggedVisualRow = -1
                                                                }
                                                            } else if (from >= 0 && to >= 0) {
                                                                // 落位: 行已经拖到目标槽位(其余行也让好位了), **瞬时落定**,
                                                                // 不做滑行动画 —— 槽位跳变 + 让位归零同帧发生, 视觉零变化;
                                                                // 做动画反而像"重排动画"(用户明确不要)
                                                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                                                onMove(from, to)
                                                                draggingQueueIndex = -1
                                                                dragTargetQueueIndex = -1
                                                                dragOffsetY = 0f
                                                                draggedVisualRow = -1
                                                            } else {
                                                                draggingQueueIndex = -1
                                                                dragTargetQueueIndex = -1
                                                                dragOffsetY = 0f
                                                                draggedVisualRow = -1
                                                            }
                                                        },
                                                        onDragCancel = {
                                                            dragScrollJob?.cancel()
                                                            dragScrollJob = null
                                                            autoScrollDir = 0f
                                                            draggingQueueIndex = -1
                                                            dragTargetQueueIndex = -1
                                                            dragOffsetY = 0f
                                                            draggedVisualRow = -1
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