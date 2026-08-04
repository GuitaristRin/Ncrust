package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroLyricLine
import io.github.takahashirinta.kanesumi.controls.MetroLyricsPanel
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.flow.StateFlow

/**
 * 歌词面板 —— 渲染交给 Kanesumi 的 MetroLyricsPanel(左对齐大字,每行独立
 * 弹簧单位,静态效果 = 原版 LazyColumn 实现)。这里只做播放器侧对接:
 *
 *  - 位置外推:Service 侧刻意保持 2Hz 广播(见 PlaybackService.startProgressUpdates
 *    注释),若直接喂给面板,跨行检测会滞后最多 500ms。这里用 withFrameNanos
 *    在帧时钟上做线性外推(播放时 position ≈ 墙钟),让当前行切换精确落在
 *    时间戳上;暂停/隐藏时停掉帧循环,不空转。锚点在每次 2Hz 采样到达时
 *    重置,外推永远从最近真实值出发。
 *  - tap-to-seek:点击行 → 本地立即跳 position + 回调解绑回调,瞬时反馈。
 */
@Composable
fun LyricsView(
    lyrics: List<LrcLine>,
    positionFlow: StateFlow<Long>,
    isPlaying: Boolean,
    isVisible: Boolean,
    onSeekToMs: (Long) -> Unit,
    enabled: Boolean = true,
    onUserScrolled: () -> Unit = {},
) {
    val strings = LocalStrings.current
    if (lyrics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            MetroText(strings.noLyrics, color = Color.Gray, style = TextStyle(fontSize = 18.sp))
        }
        return
    }

    // list 引用保持稳定,让面板的测量缓存以它为 key 不被误清。
    val metroLines = remember(lyrics) { lyrics.map { MetroLyricLine(it.timeMs, it.text) } }

    // 订阅位置流:collectAsState 建 State;displayPosition 只在面板 draw/derived
    // 阶段被读,帧循环每帧写一次也只 invalidateDraw,不触发本 Composable 重组。
    val positionState = positionFlow.collectAsState()
    val displayPosition = remember { mutableLongStateOf(0L) }
    val anchor = remember { PositionAnchor().apply { anchorNanos = System.nanoTime() } }

    // 2Hz 采样到达时重置外推锚点(首帧前锚点已就位,避免一帧闪到末尾)。
    LaunchedEffect(Unit) {
        snapshotFlow { positionState.value }.collect { pos ->
            anchor.anchorPosMs = pos
            anchor.anchorNanos = System.nanoTime()
        }
    }

    // 帧时钟外推:只在播放且可见时跑。暂停/隐藏即停,不空转。
    LaunchedEffect(isPlaying, isVisible) {
        if (!isPlaying || !isVisible) {
            displayPosition.longValue = positionState.value
            return@LaunchedEffect
        }
        while (true) {
            withFrameNanos { now ->
                displayPosition.longValue =
                    anchor.anchorPosMs + (now - anchor.anchorNanos) / 1_000_000L
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        MetroLyricsPanel(
            lines = metroLines,
            currentPositionMillis = { displayPosition.longValue },
            isVisible = isVisible,
            enabled = enabled,
            onLineClick = if (enabled) { ms ->
                // 点击行:本地立即定位,不等 2Hz 采样回传,seek 手感即时。
                anchor.anchorPosMs = ms
                anchor.anchorNanos = System.nanoTime()
                displayPosition.longValue = ms
                onSeekToMs(ms)
            } else { _ -> },
            onUserScrolled = onUserScrolled,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        )

        // 上下边缘淡出,让歌词从黑里浮出来(沿用原实现)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(listOf(LocalMetroColors.current.background, Color.Transparent))
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(listOf(Color.Transparent, LocalMetroColors.current.background))
                )
        )
    }
}

private class PositionAnchor {
    var anchorPosMs: Long = 0L
    var anchorNanos: Long = 0L
}
