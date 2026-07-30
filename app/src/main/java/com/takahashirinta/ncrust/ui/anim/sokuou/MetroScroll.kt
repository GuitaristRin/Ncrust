package com.takahashirinta.ncrust.ui.anim.sokuou

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateTo
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.ViewConfiguration
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sign

// Metro 风格 fling —— 参考 Lumia 950 / WP Metro 的滚动手感。
//
// 三处偏离 Compose 默认（Android spline）：
//   1. 速度→位移 用 v^1.5 超线性映射：轻拨走极短，重拨走远。
//      默认 spline 近线性，导致每次微小触碰都会飘一段（"神经质"感的来源）。
//   2. 减速曲线 用 UWP Quintic EaseOut：头段快、尾段极慢。
//      默认 spline 尾段刹车明显，Metro 要"漂到停"而非"刹停"。
//   3. 时长随速度线性放大 280~1100ms：默认 spline 300~500ms 结束太早。
//
// 不做过冲、不做橡皮筋——Metro Design 克制原则。iOS 方言不引入。

class MetroFlingBehavior(
    // 低于这个速度不 fling，直接返回原速度让上游处理（避免松手轻抖变成微飘）
    private val minFlingVelocity: Float = 40f,
    // 摩擦系数：值越大 → 同速度走越短。220 大致对应 Metro 典型 fling 尺度。
    private val friction: Float = 220f,
    // 速度→位移的幂指数：>1 使高速丢出走得比线性更远，低速走得更少。
    private val exponent: Float = 1.5f,
    private val baseDurationMs: Int = 280,
    // 每 px/s 速度增加的时长 (ms/(px/s))
    private val perVelocityMs: Float = 0.15f,
    private val minDurationMs: Int = 280,
    private val maxDurationMs: Int = 1100,
    private val easing: Easing = MetroQuintic,
) : FlingBehavior {

    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        if (abs(initialVelocity) < minFlingVelocity) return initialVelocity

        val absV = abs(initialVelocity)
        val target = sign(initialVelocity) * absV.pow(exponent) / friction
        val duration = (baseDurationMs + absV * perVelocityMs)
            .toInt()
            .coerceIn(minDurationMs, maxDurationMs)

        var lastValue = 0f
        var velocityLeft = initialVelocity

        try {
            AnimationState(
                initialValue = 0f,
                initialVelocity = initialVelocity,
            ).animateTo(
                targetValue = target,
                animationSpec = tween(durationMillis = duration, easing = easing),
            ) {
                val delta = value - lastValue
                val consumed = scrollBy(delta)
                lastValue = value
                velocityLeft = this.velocity
                // 触碰列表边界：请求 delta 但实际只吃到部分 → 归零速度并终止，
                // 避免继续 tween 空转（节省 8~16 帧）。
                if (abs(delta - consumed) > 0.5f) {
                    velocityLeft = 0f
                    this.cancelAnimation()
                }
            }
        } catch (_: CancellationException) {
            // 用户在 fling 中途重新按下 → 上游 scrollable 取消协程，让出控制权。
        }

        return velocityLeft
    }
}

// 全局单例。static composition local：不参与重组，无 Provider 时直接用默认实例。
// 需要临时替换（比如详情页想要不同摩擦）就在局部 CompositionLocalProvider 换。
val LocalMetroFling = staticCompositionLocalOf<FlingBehavior> { MetroFlingBehavior() }

@Composable
fun rememberMetroFlingBehavior(): FlingBehavior = LocalMetroFling.current

// 覆盖 Compose 默认 touchSlop（≈8dp）→ 1.5×（≈12dp）。
// "要不要滚"的犹豫感：按下时的手指微抖不会立刻变成滚动，去掉一部分"Sensitive"印象。
// 与 fling 曲线互补——前者管起始阈值，后者管松手后的物理感。
fun metroViewConfiguration(base: ViewConfiguration): ViewConfiguration =
    object : ViewConfiguration by base {
        override val touchSlop: Float = base.touchSlop * 1.5f
    }
