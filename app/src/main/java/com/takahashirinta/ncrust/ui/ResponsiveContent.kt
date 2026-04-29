package com.takahashirinta.ncrust.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * 内容最大宽度限制为 360dp（21:9 基准）。
 * 在更宽屏幕上居中显示，保留窄屏视觉比例。
 * 在 ≤360dp 的屏幕上撑满，完全不影响原有体验。
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 360.dp)
                .fillMaxHeight()
        ) {
            content()
        }
    }
}