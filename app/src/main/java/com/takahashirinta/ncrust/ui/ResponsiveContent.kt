package com.takahashirinta.ncrust.ui

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 内容容器。手机与大屏走两套策略：
 *
 * - < 600dp（手机）→ 保持既有窄屏视觉：上限 360dp、居中。
 * - >= 600dp（平板 / 折叠展开 / 车机）→ 默认**不再居中限宽**，内容铺满内容栏，
 *   仅留左右 24dp 内边距（配合自适应栅格，专辑/歌单随宽度多列铺开）。
 *   若传入 [maxWidth]（文本为主的页面，如关于/单曲详情），则宽屏也居中限宽，
 *   避免整行文字横跨平板、行长不可读。
 *
 * 宽屏下 MainScreen 已把内容栏右移给左侧 sidebar 让位，这里只负责栏内布局。
 */
@Composable
fun ResponsiveContent(
    modifier: Modifier = Modifier,
    maxWidth: Dp? = null,
    content: @Composable () -> Unit
) {
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    if (windowWidthDp >= 600) {
        if (maxWidth != null) {
            Box(
                modifier = modifier.fillMaxSize(),
                contentAlignment = Alignment.TopCenter
            ) {
                Box(
                    modifier = Modifier
                        .widthIn(max = maxWidth)
                        .fillMaxHeight()
                ) {
                    content()
                }
            }
        } else {
            Box(
                modifier = modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp)
            ) {
                content()
            }
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