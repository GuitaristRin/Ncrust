package com.takahashirinta.ncrust.ui

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp

/**
 * 屏幕底部被"迷你播放器 + M3 NavigationBar"两层浮层遮挡的总高度。
 *
 * 组成：M3 NavigationBar 内容区实际高度 80dp + miniBar 56dp + 视觉缓冲 8dp = 144dp。
 *
 * 系统导航栏（手势条 / 三按钮）不算在内——它由 Scaffold 的 `innerPadding.bottom` 自动补偿，
 * 屏内容已经被上一级 `Modifier.padding(innerPadding)` 顶掉了。
 *
 * 用法：所有可滚动内容（LazyColumn / LazyRow / verticalScroll Column）在其 contentPadding 或
 * 末尾 Spacer 中加上这个值，保证滚动到底时最后一项不会被 miniBar 或 NavigationBar 盖住。
 *
 * ```
 * LazyColumn(
 *     contentPadding = PaddingValues(bottom = BottomOverlayInsetDp)
 * ) { ... }
 * ```
 */
val BottomOverlayInsetDp = 144.dp

/** contentPadding 便捷版本，等价于 PaddingValues(bottom = BottomOverlayInsetDp)。 */
val BottomOverlayContentPadding = PaddingValues(bottom = BottomOverlayInsetDp)
