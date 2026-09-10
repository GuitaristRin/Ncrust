package com.takahashirinta.ncrust.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp

/**
 * 内容容器。手机与大屏走两套策略：
 *
 * - < 600dp（手机）→ 保持既有窄屏视觉：上限 360dp、居中。
 * - >= 600dp（平板 / 折叠展开 / 车机）→ **不再居中限宽**，内容铺满内容栏，
 *   仅留左右 24dp 内边距。配合自适应栅格，专辑/歌单随宽度多列铺开
 *   （Apple Music iPad 式），而不是把手机栏拉宽。
 *
 * 宽屏下 MainScreen 已把内容栏右移给左侧 sidebar 让位，这里只负责栏内布局。
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    if (windowWidthDp >= 600) {
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
        ) {
            content()
        }
    } else {
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
}