package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.lyric.LrcLine
import kotlinx.coroutines.delay

@Composable
fun LyricsView(lyrics: List<LrcLine>, currentPositionMs: Long, onUserScrolled: () -> Unit) {
    if (lyrics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = Color.Gray, fontSize = 18.sp)
        }
        return
    }

    var currentIndex = -1
    for (i in lyrics.indices) {
        if (lyrics[i].timeMs <= currentPositionMs) currentIndex = i else break
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var userScrolling by remember { mutableStateOf(false) }
    var lastAutoScrolledIndex by remember { mutableIntStateOf(-1) }

    val lyricsKey = remember(lyrics) { lyrics.hashCode() }
    LaunchedEffect(lyricsKey) {
        userScrolling = false
        lastAutoScrolledIndex = -1
        if (lyrics.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // 自动滚动至当前歌词行。
    LaunchedEffect(currentIndex) {
        if (!userScrolling && currentIndex >= 0 && currentIndex != lastAutoScrolledIndex) {
            lastAutoScrolledIndex = currentIndex
            val vh = listState.layoutInfo.viewportSize.height
            val targetPx = with(density) { (vh.toDp() * 0.45f).toPx() }.toInt()
            listState.animateScrollToItem(
                currentIndex.coerceIn(0, lyrics.size - 1),
                -targetPx + 28
            )
        }
    }

    // 检测用户手动滚动，停止自动滚动三秒后恢复。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            if (!userScrolling) {
                userScrolling = true
                onUserScrolled()
            }
        } else if (userScrolling) {
            delay(3000)
            userScrolling = false
            lastAutoScrolledIndex = -1
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            itemsIndexed(lyrics) { index, line ->
                Text(
                    text = line.text,
                    color = when {
                        index < currentIndex -> Color.White.copy(alpha = 0.6f)
                        index == currentIndex -> MaterialTheme.colorScheme.primary
                        else -> Color.Gray.copy(alpha = 0.4f)
                    },
                    fontSize = 32.sp,
                    fontWeight = if (index == currentIndex) FontWeight.ExtraBold else FontWeight.Bold,
                    softWrap = true,
                    lineHeight = 42.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }

        // 顶部渐变遮罩。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.background, Color.Transparent)
                    )
                )
        )

        // 底部渐变遮罩。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                    )
                )
        )
    }
}
