package com.takahashirinta.ncrust.ui

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * 内容区高度档。与既有的宽度判定（窄屏 / 宽屏）正交——宽度分支回答「一栏还是两栏」，
 * 高度档回答「纵向还剩多少空间」。
 *
 * 起因（docs/small-screen/research.md）：全屏播放器的响应式分支只看宽度，矮屏上满宽
 * 方形封面把进度条、歌名甚至播放键整片盖住，是功能性故障而非观感问题。
 */
enum class HeightClass {
    /** ≥ 720dp：普通手机，既有视觉一字不改。 */
    Regular,

    /** 560–719dp：荣悦 E5（427×640dp）一类。封面按高度适配，控件区用紧凑变体。 */
    Compact,

    /** < 560dp：Walkman NW-F885（320×533dp）与更矮的按键机。控件区进一步压行。 */
    Tiny
}

/**
 * 高度档判定的纯函数（无 Compose 依赖，便于单测）。
 *
 * 阈值取 [720, 560)：与调研文档的三档一致，边界归入较高一档，保证 720 / 560 这两个
 * 整数高度落进 Regular / Compact，不会因为浮点误差在档位间抖动。
 */
fun heightClassFor(contentHeightDp: Float): HeightClass = when {
    contentHeightDp >= 720f -> HeightClass.Regular
    contentHeightDp >= 560f -> HeightClass.Compact
    else -> HeightClass.Tiny
}

/**
 * 当前内容区高度档。[MainActivity] 用与 `collapsedOffsetY` **同一个高度来源**
 * （车机走根布局实测、其余设备走 `screenHeightDp`）算出并注入，各处只读档位，
 * 不各自拿数字比——否则车机上会出现「偏移量按实测高度算、档位按 screenHeightDp 算」
 * 的错位。
 *
 * 默认 [HeightClass.Regular]：预览 / 单测里没有 provider 时保持既有满宽行为。
 */
val LocalHeightClass = staticCompositionLocalOf { HeightClass.Regular }
