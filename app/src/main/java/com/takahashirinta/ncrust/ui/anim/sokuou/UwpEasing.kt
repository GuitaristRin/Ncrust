package com.takahashirinta.ncrust.ui.anim.sokuou

import androidx.compose.animation.core.Easing
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

// UWP Metro 缓动函数体系。对应 Windows.UI.Xaml.Media.Animation 下的 EasingFunctionBase 派生。
// 从 PezMax-One/src/sokuou/uwp.rs 移植。
//
// 设计目标：轻盈、短时、60Hz 友好。
// 默认变体 Quadratic / EaseOut，与 MetroAnim::default_metro() 对齐。
// 这些是 Compose 标准库没有的缓动族——独立带 amplitude / bounces / oscillations 参数。

sealed class UwpEasing {
    data object Quadratic : UwpEasing()
    data object Cubic : UwpEasing()
    data object Quartic : UwpEasing()
    data object Quintic : UwpEasing()
    data object Sine : UwpEasing()
    data object Circle : UwpEasing()
    data class Power(val exponent: Float) : UwpEasing()
    data class Exponential(val exponent: Float) : UwpEasing()
    data class Back(val amplitude: Float = 1f) : UwpEasing()
    data class Bounce(val bounces: Int = 3, val bounciness: Float = 2f) : UwpEasing()
    data class Elastic(val oscillations: Int = 3, val springiness: Float = 3f) : UwpEasing()
}

enum class EasingMode { EaseIn, EaseOut, EaseInOut }

private fun easeInRaw(tRaw: Float, variant: UwpEasing): Float {
    val t = tRaw.coerceIn(0f, 1f)
    return when (variant) {
        UwpEasing.Quadratic -> t * t
        UwpEasing.Cubic -> t * t * t
        UwpEasing.Quartic -> t * t * t * t
        UwpEasing.Quintic -> t * t * t * t * t
        UwpEasing.Sine -> 1f - sin((PI / 2.0).toFloat() * (1f - t))
        UwpEasing.Circle -> 1f - sqrt(1f - t * t)
        is UwpEasing.Power -> t.pow(variant.exponent)
        is UwpEasing.Exponential -> {
            if (t <= 0f) 0f else 2f.pow(10f * variant.exponent * (t - 1f))
        }
        is UwpEasing.Back -> {
            val c = 1.70158f * variant.amplitude
            val c1 = c + 1f
            c1 * t * t * t - c * t * t
        }
        is UwpEasing.Bounce -> bounceRaw(t, variant.bounces, variant.bounciness)
        is UwpEasing.Elastic -> {
            if (t <= 0f) return 0f
            if (t >= 1f) return 1f
            val osc = variant.oscillations.coerceAtLeast(1).toFloat()
            val spring = variant.springiness.coerceAtLeast(0.001f)
            val phase = osc * PI.toFloat() * t
            val decay = exp(-spring * t)
            1f - decay * cos(phase)
        }
    }
}

private fun bounceRaw(t: Float, bounces: Int, bounciness: Float): Float {
    if (t <= 0f || t >= 1f) return t
    val b = bounces.coerceAtLeast(1)
    val c = bounciness.coerceAtLeast(0.001f)
    var total = 0f
    var p = 1f
    for (i in 0 until b) {
        total += p
        p *= c
    }
    val tScaled = t * total
    p = 1f
    var acc = 0f
    for (i in 0 until b) {
        val dur = p
        if (tScaled <= acc + dur) {
            val local = (tScaled - acc) / dur
            return if (i == 0) {
                1f - (1f - local).pow(2)
            } else {
                val height = 1f / c.pow(i)
                1f - 4f * local * (1f - local) * height
            }
        }
        acc += dur
        p *= c
    }
    return t
}

private fun applyUwp(t: Float, variant: UwpEasing, mode: EasingMode): Float = when (mode) {
    EasingMode.EaseIn -> easeInRaw(t, variant)
    EasingMode.EaseOut -> {
        if (t <= 0f) 0f
        else if (t >= 1f) 1f
        else 1f - easeInRaw(1f - t, variant)
    }
    EasingMode.EaseInOut -> {
        if (t <= 0f) 0f
        else if (t >= 1f) 1f
        else if (t < 0.5f) 0.5f * easeInRaw(2f * t, variant)
        else 1f - 0.5f * easeInRaw(2f - 2f * t, variant)
    }
}

// 将 UWP 缓动包装成 Compose `Easing`，直接可传给 tween(...) / keyframes(...)。
fun uwpEasing(variant: UwpEasing, mode: EasingMode = EasingMode.EaseOut): Easing =
    Easing { fraction -> applyUwp(fraction, variant, mode) }

// 常用命名 easing——Metro 风格短时、克制的过渡。
val MetroDefault: Easing = uwpEasing(UwpEasing.Quadratic, EasingMode.EaseOut)
val MetroCubic: Easing = uwpEasing(UwpEasing.Cubic, EasingMode.EaseOut)
val MetroQuartic: Easing = uwpEasing(UwpEasing.Quartic, EasingMode.EaseOut)
val MetroQuintic: Easing = uwpEasing(UwpEasing.Quintic, EasingMode.EaseOut)
val MetroSine: Easing = uwpEasing(UwpEasing.Sine, EasingMode.EaseOut)
val MetroBackOut: Easing = uwpEasing(UwpEasing.Back(amplitude = 1f), EasingMode.EaseOut)
val MetroBounceOut: Easing = uwpEasing(UwpEasing.Bounce(), EasingMode.EaseOut)
val MetroElasticOut: Easing = uwpEasing(UwpEasing.Elastic(), EasingMode.EaseOut)
