package com.takahashirinta.ncrust.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 屏幕底部被浮层遮挡的总高度（滚动内容需预留，避免最后一项被盖住）。
 *
 * - 窄屏（<600dp）：底部导航 80dp + miniBar 56dp + 视觉缓冲 8dp = 144dp。
 * - 宽屏（>=600dp）：无底部导航（改用左侧 sidebar），只剩 miniBar 56dp + 缓冲 8dp = 64dp。
 *
 * 系统导航栏（手势条 / 三按钮）不算在内——由外层 systemBars padding 补偿。
 *
 * 用法（须在 composable 内）：
 * ```
 * LazyColumn(contentPadding = PaddingValues(bottom = BottomOverlayInsetDp)) { ... }
 * ```
 */
val BottomOverlayInsetDp: Dp
    @Composable get() = if (LocalConfiguration.current.screenWidthDp >= 600) 64.dp else 144.dp
