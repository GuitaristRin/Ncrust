package com.takahashirinta.ncrust.ui

import androidx.compose.runtime.staticCompositionLocalOf

/** 内容高度档，与窄屏 / 宽屏判定正交。高度来源由 MainScreen 统一提供。 */
enum class HeightClass { Regular, Compact, Tiny }

fun heightClassFor(contentHeightDp: Float): HeightClass = when {
    contentHeightDp >= 720f -> HeightClass.Regular
    contentHeightDp >= 560f -> HeightClass.Compact
    else -> HeightClass.Tiny
}

/** 手机沿用 screenHeightDp；车机沿用 collapsedOffsetY 的根布局实测高度。 */
val LocalHeightClass = staticCompositionLocalOf { HeightClass.Regular }
