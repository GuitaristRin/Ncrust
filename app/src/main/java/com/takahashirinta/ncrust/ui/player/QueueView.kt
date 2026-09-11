package com.takahashirinta.ncrust.ui.player

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
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
import kotlinx.coroutines.delay
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
    // 队列面板标题行(含按钮)的高度 px —— 自动定位按「整个队列区域」居中,
    // 不是只按列表视口居中(标题行占了面板上部, 光按视口中心会偏下)
    queueHeaderHeightPx: Float = 0f,
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
    // 悬浮层可见性独立于 draggingQueueIndex: 落位时先撤 draggingQueueIndex
    // (列表项本体现身、让位归零), 悬浮层多留一帧盖住交接处, 下一帧再撤——
    // 保证"悬浮层 → 列表项"的交接跨帧重叠、像素一致, 不出现空帧/跳变
    var overlayVisible by remember { mutableStateOf(false) }
    // 被拖歌曲 id: 悬浮层渲染用(拖拽结束 draggingQueueIndex 清空后仍能渲染)
    var draggedSongId by remember { mutableStateOf<Long?>(null) }
    // 被拖行本体(列表项)的隐藏状态, 独立于 draggingQueueIndex:
    // 落位时 draggingQueueIndex 与 dragTargetQueueIndex 必须与重排同帧清空
    // (让位归零用 tween(0) 与槽位跳变精确相消, B 才不会跳); 本体是否隐藏
    // 由 hiddenSongId 单独控制 —— 先随布局移到新槽位再现形, 残影不可能出现
    var hiddenSongId by remember { mutableStateOf<Long?>(null) }
    // 悬浮行的视口顶位置(由手指驱动, 与列表项生命周期完全解耦)
    var overlayTop by remember { mutableFloatStateOf(0f) }
    // 拖拽开始时: 被拖行的视口偏移 + 按下位置(root 坐标) + 累计滚动量
    var slotOffset0 by remember { mutableFloatStateOf(0f) }
    var downY by remember { mutableFloatStateOf(0f) }
    var scrollDelta by remember { mutableFloatStateOf(0f) }
    var draggedRowHeight by remember { mutableFloatStateOf(0f) }
    // 拖到视口上/下边缘时的自动滚动: -1=向上, 1=向下, 0=停
    var autoScrollDir by remember { mutableFloatStateOf(0f) }
    val dragScrollScope = rememberCoroutineScope()
    var dragScrollJob by remember { mutableStateOf<Job?>(null) }
    // 落定动画作用域: 松手后行回正/滑入 + 让位落稳后提交。与手势协程分离,
    // 因为 awaitEachGesture 是受限作用域, 不能直接 delay/animateTo
    val dragSettleScope = rememberCoroutineScope()

    // ---- 行模型 ----
    // RowInfo(kind=PAST_HEAD/NOW_HEAD/UPCOMING_HEAD/INFINITY/SONG, queueIndex)
    val rows = remember(queue, currentIndex, infinityActive) { buildQueueRows(queue, currentIndex, infinityActive) }

    if (rows.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MetroText(strings.emptyQueue, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 16.sp))
        }
        return
    }

    // 打开(变为可见)/切歌时把「现在播放」那首歌定位到**整个队列区域**的中心
    // (含面板标题行: 标题行占面板上部, 只按列表视口居中会偏下 headerH/2)。
    // - 面板刚打开: 等视口测量出来 → 硬定位到目标行 → 等目标行真正 compose
    //   进视口(懒布局) → 补居中偏移。用 snapshotFlow 等条件成立, 比数帧稳。
    // - 队列已打开时切歌: 用 animateScrollToItem 平滑滑动到中心; 目标行在视口
    //   外时先滑过去, 等它 compose 后再补居中(不能只 scrollToItem 到顶部)。
    val nowVisualRow = rows.indexOfFirst { it.kind == RowKind.NOW_HEAD }
    // 目标 = 「现在播放」标题下面那首歌, 而不是标题本身 —— 定位的是正在播的行
    val nowSongVisualRow = (nowVisualRow + 1).takeIf { it < rows.size } ?: nowVisualRow
    // 让目标行垂直居中(相对整个队列面板)的滚动偏移。
    // 注意 scrollToItem 的 scrollOffset 语义是"正值把行往上滚出视口"(官方文档:
    // positive offset ... will scroll the item further upward, taking it partly offscreen),
    // 要让行落到视口内 (视口高-行高-标题高)/2 处, 必须传**负值**。
    fun centerScrollOffset(target: Int): Int {
        val info = listState.layoutInfo
        val item = info.visibleItemsInfo.first { it.index == target }
        val desired = ((info.viewportEndOffset - item.size - queueHeaderHeightPx) / 2f)
            .toInt().coerceAtLeast(0)
        return -desired
    }
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
            listState.scrollToItem(target, scrollOffset = centerScrollOffset(target))
        } else {
            // 队列已打开且切歌: 平滑滑到中心。目标行已在视口内直接带偏移动画,
            // 否则先滑过去, 再等它 compose 进视口后补居中(避免停在顶部)。
            val info = listState.layoutInfo
            val item = info.visibleItemsInfo.firstOrNull { it.index == target }
            if (item != null) {
                listState.animateScrollToItem(target, scrollOffset = centerScrollOffset(target))
            } else {
                listState.animateScrollToItem(target)
                snapshotFlow { listState.layoutInfo.visibleItemsInfo.any { it.index == target } }
                    .first { it }
                listState.animateScrollToItem(target, scrollOffset = centerScrollOffset(target))
            }
        }
        wasQueueOpen.value = true
    }

    // 拖拽手势挂在根节点而不是列表项上: 根节点不随 LazyColumn 回收,
    // 自动滚动把被拖行的槽位滚出视口时手势不会断、卡片不会"飞不见"。
    // 只在手指按下落在把手上时接管(否则不消费任何事件, 列表滚动/点击正常)。
    // 关键: pointerInput 只以 interactive 为 key —— rows/currentIndex 经
    // rememberUpdatedState 读取。若把 rows 放进 key, 落位重排队列会重启
    // pointerInput, 正在执行落位提交的协程被取消 → 拖拽状态泄漏,
    // 第二次就拖不动了(只能拖动一次)。
    val rowsRef by rememberUpdatedState(rows)
    val currentIndexRef by rememberUpdatedState(currentIndex)
    val infinityActiveRef by rememberUpdatedState(infinityActive)
    val queueRef by rememberUpdatedState(queue)
    val dragGesturesModifier = Modifier.pointerInput(interactive) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val rowsNow = rowsRef
            val idxNow = currentIndexRef
            val targetVisual = itemIndexAt(listState, down.position.y)
                ?: return@awaitEachGesture
            val row = rowsNow.getOrNull(targetVisual) ?: return@awaitEachGesture
            val qi = row.queueIndex
            if (qi <= idxNow || infinityActiveRef) return@awaitEachGesture
            // 把手命中区: 行右端 16dp 内边距 + 16dp 尾距 + 48dp 移除钮左侧的 32dp 把手
            val pad = with(density) { 16.dp.toPx() }
            val spacer = with(density) { 16.dp.toPx() }
            val removeW = with(density) { 48.dp.toPx() }
            val handleW = with(density) { 32.dp.toPx() }
            val handleLeft = size.width - pad - spacer - removeW - handleW
            val handleRight = size.width - pad - spacer - removeW
            if (down.position.x !in handleLeft..handleRight) return@awaitEachGesture

            // ---- 接管拖拽 ----
            draggingQueueIndex = qi
            dragTargetQueueIndex = qi
            overlayVisible = true
            val songId = queueRef.getOrNull(qi)?.id
            draggedSongId = songId
            hiddenSongId = songId
            slotOffset0 = listState.layoutInfo.visibleItemsInfo
                .firstOrNull { it.index == targetVisual }
                ?.offset?.toFloat() ?: down.position.y
            downY = down.position.y
            scrollDelta = 0f
            overlayTop = slotOffset0
            autoScrollDir = 0f
            // 边缘自动滚动: 手指贴近上/下边缘(或已在边缘外)时每帧滚一段,
            // 滚动期间按悬浮行中心实时刷新插入目标
            dragScrollJob?.cancel()
            dragScrollJob = dragScrollScope.launch {
                while (isActive) {
                    val dir = autoScrollDir
                    if (dir != 0f) {
                        // 16px/帧 ≈ 960px/s, 比 24px/帧 缓和, 边缘滚动不显得失控
                        val consumed = listState.scrollBy(dir * 16f)
                        if (consumed != 0f) {
                            scrollDelta += consumed
                            val center = overlayTop + draggedRowHeight / 2f
                            val tv = itemIndexAt(listState, center)
                            if (tv != null) {
                                val next = rowsRef.getOrNull(tv)
                                if (next != null && next.queueIndex > idxNow) {
                                    dragTargetQueueIndex = next.queueIndex
                                }
                            }
                        }
                    }
                    withFrameNanos { }
                }
            }
            // 拖拽主循环: 悬浮行 1:1 跟随手指(根坐标, 无滚动/位移反馈),
            // 钉在可见区上下边缘, 并实时判定插入目标
            drag(down.id) { change ->
                change.consume()
                val vh = listState.layoutInfo.viewportEndOffset.toFloat()
                val rowH = draggedRowHeight
                overlayTop = (slotOffset0 + (change.position.y - downY))
                    .coerceIn(0f, (vh - rowH).coerceAtLeast(0f))
                val center = overlayTop + rowH / 2f
                val edge = with(density) { 72.dp.toPx() }
                autoScrollDir = when {
                    center < edge -> -1f
                    center > vh - edge -> 1f
                    else -> 0f
                }
                val tv = itemIndexAt(listState, center) ?: targetVisual
                val next = rowsRef.getOrNull(tv)
                dragTargetQueueIndex = next?.queueIndex?.takeIf { it > idxNow } ?: qi
            }
            // 拖拽结束(手指抬起): 状态稳定后才更新队列。落位动画放到
            // dragSettleScope(手势作用域是受限的, 不能 delay/animateTo);
            // pointerInput 不以 rows 为 key, 落位重排不会重启手势,
            // 归零一定完整执行 → 下一次拖拽正常。
            dragScrollJob?.cancel()
            dragScrollJob = null
            autoScrollDir = 0f
            val from = draggingQueueIndex
            val to = dragTargetQueueIndex
            val vh = listState.layoutInfo.viewportEndOffset.toFloat()
            if (from >= 0 && to >= 0 && from == to) {
                // 原位松手: 悬浮行从当前位置阻尼回正到槽位, 其余行让位弹簧归位
                dragTargetQueueIndex = -1
                val startTop = overlayTop
                val slotTop = (slotOffset0 - scrollDelta)
                    .coerceIn(0f, (vh - draggedRowHeight).coerceAtLeast(0f))
                dragSettleScope.launch {
                    val anim = Animatable(startTop)
                    anim.animateTo(slotTop, sokuouSpring(response = 0.18f, dampingRatio = 1f)) {
                        overlayTop = value
                    }
                    // 等让位弹簧彻底落稳
                    delay(150)
                    // dragging 与 target 同帧清空: 让位归零(tween(0))不再受
                    // spring 影响, 与槽位变化精确相消
                    draggingQueueIndex = -1
                    dragTargetQueueIndex = -1
                    // 本体在槽位现形(悬浮层同一位置盖住), 下一帧撤悬浮层
                    hiddenSongId = null
                    withFrameNanos { }
                    overlayVisible = false
                    draggedSongId = null
                    overlayTop = 0f
                }
            } else if (from >= 0 && to >= 0) {
                // 落位: 悬浮行从手指位置**快速滑进目标槽位**(≤半行), 滑动与
                // 让位弹簧同窗口落稳后提交重排。关键: dragging/target 与重排
                // **同帧**清空(让位归零 tween(0) 与槽位跳变精确相消, B 不跳);
                // 本体保持隐藏先随布局移到新槽位(悬浮层盖住目标), 布局落地后
                // 才现形 —— 列表项可见的每一帧都被悬浮层遮挡, 残影不可能发生
                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                val startTop = overlayTop
                val targetTop = (slotOffset0 + (to - from) * draggedRowHeight - scrollDelta)
                    .coerceIn(0f, (vh - draggedRowHeight).coerceAtLeast(0f))
                val dropTo = to
                dragSettleScope.launch {
                    val anim = Animatable(startTop)
                    anim.animateTo(targetTop, sokuouSpring(response = 0.16f, dampingRatio = 1f)) {
                        overlayTop = value
                    }
                    // 让位弹簧落稳缓冲(残差降到亚像素再提交)
                    delay(150)
                    onMove(from, dropTo)
                    draggingQueueIndex = -1
                    dragTargetQueueIndex = -1
                    // 重排布局落地: 列表项移到新槽位(仍隐藏), 悬浮层盖住目标
                    withFrameNanos { }
                    withFrameNanos { }
                    // 列表项在新槽位现形(悬浮层同一位置盖住, 无残影)
                    hiddenSongId = null
                    withFrameNanos { }
                    // 撤悬浮层, 交接完成
                    overlayVisible = false
                    draggedSongId = null
                    overlayTop = 0f
                }
            } else {
                draggingQueueIndex = -1
                dragTargetQueueIndex = -1
                overlayVisible = false
                draggedSongId = null
                hiddenSongId = null
                overlayTop = 0f
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize().then(dragGesturesModifier)) {
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
                        // 目标行高亮淡入淡出(120ms): 落定瞬间高亮消失不再"pop",
        // 否则重排那一帧背景色突变 = 闪一下
                        val highlightColor by animateColorAsState(
                            targetValue = if (highlighted) LocalMetroColors.current.surfaceVariant
                            else Color.Transparent,
                            animationSpec = tween(120),
                            label = "queueHighlight"
                        )
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
                                    // 被拖行的本体隐藏(alpha=0), 视觉由列表外的悬浮层渲染——
                                    // 悬浮层不随 LazyColumn 滚动回收, 行槽位滚出视口也不会"消失"
                                    alpha = if (hiddenSongId == song.id) 0f else 1f
                                    // 其余行用弹簧让位, 形成"挖出空位"的联动感
                                    translationY = animatedShift
                                }
                                .background(highlightColor)
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
                                        // 把手只是视觉提示; 拖拽手势挂在 QueueView 根节点
                                        // (根节点不随列表项回收, 自动滚动把槽位滚出视口也不会断手势)
                                        Box(
                                            modifier = Modifier.size(32.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            MetroIcon(
                                                imageVector = Icons.Default.DragHandle,
                                                contentDescription = null,
                                                tint = LocalMetroColors.current.onSurfaceVariant,
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
                                            tint = LocalMetroColors.current.onSurfaceVariant,
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

                        // 悬浮的被拖行: 拎出 LazyColumn 渲染(列表项本体隐藏)。
        // 位置 = overlayTop(手指驱动的视口位置), 与列表项生命周期无关,
        // 自动滚动把槽位滚出视口也不受影响。alpha=1: 与落定后列表项本体
        // 的透明度完全一致, 交接瞬间没有亮度跳变。
        if (overlayVisible) {
            val draggedSong = draggedSongId?.let { id -> queue.firstOrNull { it.id == id } }
            if (draggedSong != null) {
                SongCard(
                    song = draggedSong,
                    style = SongCardStyle.COMPACT,
                    onClick = {},
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .zIndex(1f)
                        .graphicsLayer {
                            translationY = overlayTop
                        }
                )
            }
        }
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
        MetroDivider(color = LocalMetroColors.current.divider, modifier = Modifier.weight(1f))
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
        MetroText(text, color = LocalMetroColors.current.onSurfaceVariant, style = TextStyle(fontSize = 14.sp))
    }
}

// 视口 Y 坐标 → 命中的行索引（layoutInfo.offset 即视口内偏移）。
private fun itemIndexAt(listState: LazyListState, viewportY: Float): Int? {
    return listState.layoutInfo.visibleItemsInfo
        .firstOrNull { viewportY >= it.offset && viewportY <= it.offset + it.size }
        ?.index
}