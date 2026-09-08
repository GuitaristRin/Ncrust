package com.takahashirinta.ncrust.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * 内容最大宽度按窗口宽度自适应（竖屏；Kanesumi 风格不变，只调整内容栏宽度）：
 * - < 600dp（手机）       → 360dp，保持原有窄屏比例
 * - 600..839dp（小平板/折叠屏展开）→ 480dp，多用一点横向空间
 * - >= 840dp（平板）      → 560dp，接近主流平板信息密度
 * 折叠屏展开时 [LocalConfiguration.screenWidthDp] 已是全展开宽度，自动命中宽档。
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    val maxWidthDp = when {
        windowWidthDp >= 840 -> 560.dp
        windowWidthDp >= 600 -> 480.dp
        else -> 360.dp
    }
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = maxWidthDp)
                .fillMaxHeight()
        ) {
            content()
        }
    }
}