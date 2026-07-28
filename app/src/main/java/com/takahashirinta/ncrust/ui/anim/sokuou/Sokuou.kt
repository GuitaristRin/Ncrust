package com.takahashirinta.ncrust.ui.anim.sokuou

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.TweenSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import kotlin.math.PI

// Sokuou Engine — 即応エンジン。
// 空间状态解析器：progress ∈ [0.0, 1.0] 驱动所有视觉属性。
//
// 与 PezMax-One/src/sokuou 保持同名 API 与参数惯例，方便跨项目共享动画词汇。
// Compose 已提供 Animatable + SpringSpec / TweenSpec 作为解析解与时长驱动缓动的底层，
// 这里只做参数化桥接与预设命名，让新代码停止散写 tween(300, CubicBezierEasing(...))。

// Apple 风格弹簧参数 → Compose stiffness 换算。
//   omega0 = 2π / response
//   stiffness = omega0² = (2π / response)²
// response 越短越"急"；damping_ratio 决定过冲量（<1 有过冲，=1 临界，>1 无过冲）。
fun sokuouSpring(response: Float, dampingRatio: Float): SpringSpec<Float> {
    val omega0 = (2.0 * PI / response).toFloat()
    return spring(dampingRatio = dampingRatio, stiffness = omega0 * omega0)
}

// 将 progress [0.0, 1.0] 映射到任意值域 [from, to]。
fun mapRange(progress: Float, from: Float, to: Float): Float =
    from + (to - from) * progress

// 同 mapRange，但先将 progress clamp 到 [0.0, 1.0]。
fun mapRangeClamped(progress: Float, from: Float, to: Float): Float =
    mapRange(progress.coerceIn(0f, 1f), from, to)

// 命名预设。
// 弹簧预设参数来源：SOKUOU_ENGINE.md §6.3 Apple 风格典型值表。
// tween 预设来源：Ncrust 现有播放器 / 抽屉动画的实际参数聚类。
object SokuouPresets {
    // ── 弹簧（主运动：位置、大小、卡片入场） ───────────────────────────

    // 通用交互（点击、面板切换）。response=0.5s, damping=0.825
    val StandardInteraction: AnimationSpec<Float> = sokuouSpring(0.5f, 0.825f)

    // 快速交互（按钮按下、图标反馈）。response=0.3s, damping=0.6
    val QuickInteraction: AnimationSpec<Float> = sokuouSpring(0.3f, 0.6f)

    // 慢速展示（通知横幅、引导提示）。response=0.65s, damping=0.85
    val SlowReveal: AnimationSpec<Float> = sokuouSpring(0.65f, 0.85f)

    // 弹窗强调弹入（对话框、菜单）。response=0.45s, damping=0.7
    val DialogEnter: AnimationSpec<Float> = sokuouSpring(0.45f, 0.7f)

    // 页面转场。response=0.4s, damping=0.8
    val PageTransition: AnimationSpec<Float> = sokuouSpring(0.4f, 0.8f)

    // ── tween（次要属性：透明度、颜色、非物理位移） ───────────────────

    // 抽屉/弹窗入场：慢起快收。300ms + (0.2, 0, 0, 1) 是 Ncrust 现有 SongMenuSheet / PlayerCard 的固定组合。
    val SheetAppear: AnimationSpec<Float> = tween(
        durationMillis = 300,
        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    )

    // 抽屉/弹窗收起：Material FastOutSlowIn。260ms 是 SongMenuSheet.dismiss 的常量。
    val SheetDismiss: AnimationSpec<Float> = tween(
        durationMillis = 260,
        easing = FastOutSlowInEasing
    )

    // 快速切换（歌词行、播放/暂停）。
    val QuickSwitch: AnimationSpec<Float> = tween(
        durationMillis = 180,
        easing = FastOutSlowInEasing
    )

    // 封面 / 大图淡入。
    val CoverFade: AnimationSpec<Float> = tween(
        durationMillis = 400,
        easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)
    )

    // 颜色 / 主题过渡（accent transition）。
    val ColorTransition: AnimationSpec<Float> = tween(
        durationMillis = 300,
        easing = MetroDefault
    )

    // 开关滑块 / 短反馈（对齐 UWP toggle 的 220ms）。
    val ToggleFlip: AnimationSpec<Float> = tween(
        durationMillis = 220,
        easing = MetroCubic
    )

    // 线性（缓冲进度条等无限循环）。
    val Linear: AnimationSpec<Float> = tween(
        durationMillis = 300,
        easing = LinearEasing
    )
}

// tween 专用类型，方便新代码显式表达"这是时长驱动"。
object SokuouTweens {
    val SheetAppear: TweenSpec<Float> = tween(300, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    val SheetDismiss: TweenSpec<Float> = tween(260, easing = FastOutSlowInEasing)
    val QuickSwitch: TweenSpec<Float> = tween(180, easing = FastOutSlowInEasing)
    val CoverFade: TweenSpec<Float> = tween(400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
    val ToggleFlip: TweenSpec<Float> = tween(220, easing = MetroCubic)
}
