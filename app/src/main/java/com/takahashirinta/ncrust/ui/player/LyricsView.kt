package com.takahashirinta.ncrust.ui.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun LyricsView(
    lyrics: List<LrcLine>,
    currentPositionMs: Long,
    isVisible: Boolean,
    onSeekToMs: (Long) -> Unit,
    onUserScrolled: () -> Unit,
    enabled: Boolean = true
) {
    val strings = LocalStrings.current
    if (lyrics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(strings.noLyrics, color = Color.Gray, fontSize = 18.sp)
        }
        return
    }

    var currentIndex = -1
    for (i in lyrics.indices) {
        if (lyrics[i].timeMs <= currentPositionMs) currentIndex = i else break
    }

    val listState = rememberLazyListState()
    var userScrolling by remember { mutableStateOf(false) }
    var programmaticScrolling by remember { mutableStateOf(false) }
    var lastAutoScrolledIndex by remember { mutableIntStateOf(-1) }

    // Drives per-line alpha/scale in graphicsLayer — only invalidates draw phase, never recomposition
    val smoothCurrentIndex = remember { Animatable(currentIndex.coerceAtLeast(0).toFloat()) }

    LaunchedEffect(currentIndex) {
        if (currentIndex >= 0) {
            smoothCurrentIndex.animateTo(
                currentIndex.toFloat(),
                tween(180, easing = FastOutSlowInEasing)
            )
        }
    }

    val lyricsKey = remember(lyrics) { lyrics.hashCode() }
    LaunchedEffect(lyricsKey) {
        userScrolling = false
        lastAutoScrolledIndex = -1
        smoothCurrentIndex.snapTo(0f)
        listState.scrollToItem(0)
    }

    fun scrollOffset(): Int {
        val vh = listState.layoutInfo.viewportSize.height
        return if (vh > 0) -(vh * 0.36f).toInt() else 0
    }

    // Instant jump when lyrics panel opens; waits one frame for layout if needed
    LaunchedEffect(isVisible) {
        if (!isVisible || lyrics.isEmpty()) return@LaunchedEffect
        var vh = listState.layoutInfo.viewportSize.height
        if (vh == 0) { delay(16); vh = listState.layoutInfo.viewportSize.height }
        val idx = currentIndex.coerceAtLeast(0)
        val offset = if (vh > 0) -(vh * 0.36f).toInt() else 0
        smoothCurrentIndex.snapTo(idx.toFloat())
        // index +1 accounts for the leading top-spacer item
        listState.scrollToItem((idx + 1).coerceIn(1, lyrics.size), offset)
        lastAutoScrolledIndex = idx
    }

    // Auto-scroll on lyric line change
    LaunchedEffect(currentIndex) {
        if (!isVisible || userScrolling || currentIndex < 0 || currentIndex == lastAutoScrolledIndex) return@LaunchedEffect
        lastAutoScrolledIndex = currentIndex
        val offset = scrollOffset()
        try {
            programmaticScrolling = true
            listState.animateScrollToItem((currentIndex + 1).coerceIn(1, lyrics.size), offset)
        } finally {
            programmaticScrolling = false
        }
    }

    // Detect manual scroll; resume auto-scroll after 5s
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress && !programmaticScrolling) {
            if (!userScrolling) {
                userScrolling = true
                onUserScrolled()
            }
        } else if (!listState.isScrollInProgress && userScrolling) {
            delay(5000)
            userScrolling = false
            lastAutoScrolledIndex = -1
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            userScrollEnabled = enabled,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 20.dp),
        ) {
            item(key = "top_spacer") { Spacer(Modifier.height(200.dp)) }

            itemsIndexed(lyrics, key = { index, _ -> index }) { index, line ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(enabled, line.timeMs) {
                            if (enabled) detectTapGestures {
                                userScrolling = false
                                lastAutoScrolledIndex = -1
                                onSeekToMs(line.timeMs)
                            }
                        }
                        .padding(vertical = 10.dp)
                ) {
                    Text(
                        text = line.text,
                        fontSize = 32.sp,                          // ← 恢复原来字号
                        fontWeight = FontWeight.Bold,
                        softWrap = true,
                        lineHeight = 42.sp,                        // ← 恢复原来行高
                        color = when {
                            index < currentIndex -> Color.White.copy(alpha = 0.6f)
                            index == currentIndex -> MaterialTheme.colorScheme.primary
                            else -> Color.Gray.copy(alpha = 0.4f)
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer {
                                val dist = abs(index.toFloat() - smoothCurrentIndex.value)
                                // 激活行保持全尺寸，非激活行缩小；营造 Apple Music 的层次感
                                val scale = lerp(1.0f, 0.82f, (dist / 1.8f).coerceIn(0f, 1f))
                                scaleX = scale
                                scaleY = scale
                                transformOrigin = TransformOrigin(0f, 0.5f)
                            }
                    )
                }
            }

            item(key = "bottom_spacer") { Spacer(Modifier.height(200.dp)) }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.background, Color.Transparent)
                    )
                )
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(100.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                    )
                )
        )
    }
}
