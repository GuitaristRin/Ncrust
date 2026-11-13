package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 编辑态的选中标记直接画在封面上：进入编辑态封面压暗并出现方框，选中后整块铺强调色加勾。
 *
 * 为什么画在封面上而不是行尾放勾选框：行尾槽位要么常驻（非编辑态白占一列宽），要么进出编辑态
 * 时插入（文字重排、省略号跳动）。封面本来就贴左沿、尺寸固定，覆盖它不改变任何布局。
 * [progress] 只在 draw 阶段读取，进出编辑态只重绘；选中切换是硬切（Kanesumi 状态色不做过渡）。
 */
fun Modifier.selectionMark(progress: () -> Float, selected: Boolean, accent: Color, onAccent: Color): Modifier =
    drawWithContent {
        drawContent()
        val p = progress().coerceIn(0f, 1f)
        if (p <= 0f) return@drawWithContent
        val side = minOf(size.width, size.height, 26.dp.toPx())
        val origin = Offset((size.width - side) / 2f, (size.height - side) / 2f)
        if (selected) {
            // 选中铺满不透明强调色：半透明会让封面（尤其占位图标）透出来显得脏。
            drawRect(accent.copy(alpha = p))
            val check = Path().apply {
                moveTo(origin.x + side * 0.16f, origin.y + side * 0.52f)
                lineTo(origin.x + side * 0.40f, origin.y + side * 0.74f)
                lineTo(origin.x + side * 0.86f, origin.y + side * 0.26f)
            }
            drawPath(check, onAccent.copy(alpha = p),
                style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Square, join = StrokeJoin.Miter))
        } else {
            drawRect(Color.Black.copy(alpha = 0.45f * p))
            val stroke = 2.dp.toPx()
            drawRect(Color.White.copy(alpha = p), topLeft = origin + Offset(stroke / 2, stroke / 2),
                size = Size(side - stroke, side - stroke), style = Stroke(width = stroke))
        }
    }

/**
 * LazyColumn 内的拖拽排序。没有引入第三方 reorderable 库（铁律：不新增依赖），按官方 DragDropList
 * 示例的思路实现：
 *
 * - 位移 = 起拖时的布局偏移 + 手指累计位移 − 当前布局偏移。数据交换后布局在下一帧才更新，
 *   这个式子在更新前后都给出同一个视觉位置，所以被拖行不会闪跳。
 * - 交换后要等布局确认被拖行已到新索引（[expectedIndex]）再判断下一次交换；否则旧布局会让同一对
 *   行在一帧内来回换两次。
 * - 靠近上/下边缘时逐帧滚动列表，滚动量加回手指累计位移，被拖行保持在手指下。上下边缘扣掉
 *   覆盖在列表上的工具条与底部叠层（[edgeInsets]），否则拖到迷你播放条后面才开始滚。
 *
 * 位移只经 [offsetOf] 在 graphicsLayer 中读取，拖动过程中列表行不重组。
 */
@Stable
class ReorderState internal constructor(
    val listState: LazyListState,
    private val scope: CoroutineScope,
    private val canMove: (Any) -> Boolean,
    private val onMove: (from: Any, to: Any) -> Unit,
) {
    var draggingKey by mutableStateOf<Any?>(null)
        private set
    internal var edgeInsets: Pair<Float, Float> = 0f to 0f
    private var initialOffset = 0
    private var expectedIndex = -1
    private val distance = mutableFloatStateOf(0f)
    private var scroller: Job? = null

    private fun dragged(): LazyListItemInfo? =
        listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == draggingKey }

    /** 只能在 draw / graphicsLayer 中调用。 */
    fun offsetOf(key: Any): Float {
        if (key != draggingKey) return 0f
        val item = dragged() ?: return 0f
        return initialOffset + distance.floatValue - item.offset
    }

    internal fun start(key: Any) {
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == key } ?: return
        draggingKey = key
        initialOffset = item.offset
        expectedIndex = item.index
        distance.floatValue = 0f
    }

    internal fun drag(dy: Float) {
        if (draggingKey == null) return
        distance.floatValue += dy
        swapIfNeeded()
        updateAutoScroll()
    }

    internal fun end() {
        draggingKey = null
        distance.floatValue = 0f
        scroller?.cancel()
        scroller = null
    }

    private fun swapIfNeeded() {
        val item = dragged() ?: return
        if (item.index != expectedIndex) return
        val center = initialOffset + distance.floatValue + item.size / 2f
        val target = listState.layoutInfo.visibleItemsInfo.firstOrNull { other ->
            other.key != item.key && canMove(other.key) &&
                center >= other.offset && center < other.offset + other.size
        } ?: return
        expectedIndex = target.index
        onMove(item.key, target.key)
    }

    private fun scrollSpeed(): Float {
        val item = dragged() ?: return 0f
        val info = listState.layoutInfo
        val top = initialOffset + distance.floatValue
        val bottom = top + item.size
        val zone = item.size * 0.75f
        val start = info.viewportStartOffset + edgeInsets.first
        val end = info.viewportEndOffset - edgeInsets.second
        // 越靠边越快，上限约每帧 1/4 行，长列表也能在几秒内拖到头。
        val max = item.size / 4f
        return when {
            top < start + zone -> -max * ((start + zone - top) / zone).coerceIn(0f, 1f)
            bottom > end - zone -> max * ((bottom - (end - zone)) / zone).coerceIn(0f, 1f)
            else -> 0f
        }
    }

    private fun updateAutoScroll() {
        if (scroller?.isActive == true || scrollSpeed() == 0f) return
        scroller = scope.launch {
            while (isActive && draggingKey != null) {
                val speed = scrollSpeed()
                if (speed == 0f) break
                withFrameNanos { }
                val consumed = listState.scrollBy(speed)
                if (consumed == 0f) break
                distance.floatValue += consumed
                swapIfNeeded()
            }
        }
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    canMove: (Any) -> Boolean,
    onMove: (from: Any, to: Any) -> Unit,
): ReorderState {
    val scope = rememberCoroutineScope()
    val latestMove by rememberUpdatedState(onMove)
    val latestCanMove by rememberUpdatedState(canMove)
    return remember(listState) {
        ReorderState(listState, scope, { latestCanMove(it) }, { from, to -> latestMove(from, to) })
    }
}

/** 拖拽手柄：越过 touch slop 才开始拖，轻点不被吞掉成拖动。 */
fun Modifier.reorderHandle(state: ReorderState, key: Any): Modifier = pointerInput(state, key) {
    detectDragGestures(
        onDragStart = { state.start(key) },
        onDragEnd = { state.end() },
        onDragCancel = { state.end() },
        onDrag = { change, amount -> change.consume(); state.drag(amount.y) }
    )
}
