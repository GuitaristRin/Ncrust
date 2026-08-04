package com.takahashirinta.ncrust.ui.player
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.runtime.State
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * 进度条，含三种状态：
 *  - 普通：强调色填充 + 手指离开时宽2dp
 *  - 拖动：手指按下即响应，进度跟随手指；轨道加厚至4dp，显示直角滑块指示器
 *  - 缓冲：强调色短段在轨道上从左至右周期滑动，段长随时间脉动，直到 isBuffering=false
 *
 * progressFlow 在 Canvas draw scope 内读取，非拖动/seek 期间仅触发重绘，不触发 Composable 重组
 */
@Composable
fun SlimProgressBar(
    progressFlow: StateFlow<Float>,
    isBufferingFlow: StateFlow<Boolean>,
    onSeek: (Float) -> Unit
) {
    // isBufferingFlow 由内部叶子 collect：SlimProgressBar 常驻在 FullPlayerControls，
    // isBuffering 每次抖动都会走到这里，避免让父层因布尔值变化而重组
    val isBuffering by isBufferingFlow.collectAsState()
    // collectAsState 创建 State 引用，只有 .value 被读的作用域才订阅
    val progressState = progressFlow.collectAsState()
    var barWidth by remember { mutableFloatStateOf(1f) }
    var isDragging by remember { mutableStateOf(false) }
    var dragProgress by remember { mutableFloatStateOf(0f) }

    // seekTargetProgress >= 0 → 用户刚拖拽完，等待 ExoPlayer 缓冲确认
    var seekTargetProgress by remember { mutableFloatStateOf(-1f) }
    val isSeeking = seekTargetProgress >= 0f

    // 缓冲完成（isBuffering 变 false）后清除 seek 悬挂态
    LaunchedEffect(isBuffering) {
        if (!isBuffering) seekTargetProgress = -1f
    }
    // 安全超时：若 ExoPlayer 未触发 BUFFERING（已缓存该位置），5秒后自动退出
    LaunchedEffect(isSeeking) {
        if (isSeeking) {
            delay(5_000L)
            seekTargetProgress = -1f
        }
    }

    val showBuffering = (isBuffering || isSeeking) && !isDragging

    // 缓冲动画懒启动：只有当 showBuffering 为 true 时才创建 rememberInfiniteTransition。
    // 旧实现无论是否缓冲都常驻两条无限循环，Compose 每帧都会 invalidate 本 composable，
    // 让 Canvas 每帧重跑一次 draw block（即便分支不进入）。低端机上纯浪费。
    val bufferingPhases: BufferingPhases? = if (showBuffering) rememberBufferingPhases() else null

    val accent = LocalMetroColors.current.primary
    val trackColor = Color(0xFF3A3A3A)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(40.dp)                        // 触摸区域保持 40dp
            .onSizeChanged { barWidth = it.width.toFloat().coerceAtLeast(1f) }
            .pointerInput(Unit) {
                awaitEachGesture {
                    // 手指落下即刻响应，无 touchSlop 延迟
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    isDragging = true
                    dragProgress = (down.position.x / barWidth).coerceIn(0f, 1f)

                    // 追踪拖动直到手指抬起
                    drag(down.id) { change ->
                        change.consume()
                        dragProgress = (change.position.x / barWidth).coerceIn(0f, 1f)
                    }

                    // 手指抬起：进入 seeking 悬挂态，等待缓冲动画接管
                    isDragging = false
                    seekTargetProgress = dragProgress
                    onSeek(dragProgress)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // displayProgress 移入 draw scope：非拖动/seek 时读 progressState.value，只 invalidate draw
            val displayProgress = when {
                isDragging -> dragProgress
                isSeeking  -> seekTargetProgress
                else       -> progressState.value
            }
            val trackH = if (isDragging) 4.dp.toPx() else 2.dp.toPx()
            val topY = (size.height - trackH) / 2f

            // 轨道底色
            drawRect(trackColor, topLeft = Offset(0f, topY), size = Size(size.width, trackH))

            if (showBuffering && bufferingPhases != null) {
                // 脉动段：halfLen 在 0.10~0.16 之间震荡，center 从 -0.20 线性移动到 1.20
                val halfLen = 0.10f + 0.06f * abs(sin(bufferingPhases.pulse.value * PI).toFloat())
                val center = bufferingPhases.slide.value * 1.40f - 0.20f
                val segStart = (center - halfLen).coerceAtLeast(0f)
                val segEnd   = (center + halfLen).coerceAtMost(1f)
                if (segEnd > segStart) {
                    drawRect(
                        color = accent,
                        topLeft = Offset(segStart * size.width, topY),
                        size = Size((segEnd - segStart) * size.width, trackH)
                    )
                }
            } else {
                // 普通播放进度填充
                if (displayProgress > 0f) {
                    drawRect(
                        color = accent,
                        topLeft = Offset(0f, topY),
                        size = Size(displayProgress * size.width, trackH)
                    )
                }
            }

            // 拖动时显示直角滑块指示器（3×14dp 白色矩形），符合 Metro 直角风格
            if (isDragging) {
                val thumbW = 3.dp.toPx()
                val thumbH = 14.dp.toPx()
                val thumbX = (displayProgress * size.width - thumbW / 2f)
                    .coerceIn(0f, size.width - thumbW)
                val thumbY = (size.height - thumbH) / 2f
                drawRect(
                    color = Color.White,
                    topLeft = Offset(thumbX, thumbY),
                    size = Size(thumbW, thumbH)
                )
            }
        }
    }
}

private data class BufferingPhases(
    val slide: State<Float>,
    val pulse: State<Float>,
)

@Composable
private fun rememberBufferingPhases(): BufferingPhases {
    val transition = rememberInfiniteTransition(label = "bufAnim")
    val slide = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1400, easing = LinearEasing)),
        label = "bufSlide"
    )
    val pulse = transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700, easing = LinearEasing)),
        label = "bufPulse"
    )
    return BufferingPhases(slide, pulse)
}
