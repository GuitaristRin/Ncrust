package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.graphicsLayer
import com.takahashirinta.ncrust.warmup.AppWarmup
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        // 双闸门：既要 warmup 完成（数据+封面就绪），又要满足最短驻留（避免网络太快 splash 一闪而过）。
        // MainScreen 已经在 splash 覆盖下并行组合 + shader 预热，两条工作线在此汇合。
        val minDwell = launch { delay(MIN_DWELL_MS) }
        val warmupDone = launch { AppWarmup.ready.first { it } }
        minDwell.join()
        warmupDone.join()
        alpha.animateTo(0f, animationSpec = tween(260, easing = FastOutSlowInEasing))
        onFinished()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
            .graphicsLayer { this.alpha = alpha.value }
    ) {
        Column(
            modifier = Modifier.align(Alignment.Center),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MetroText(
                "Ncrust",
                color = Color(0xFF1DB954),
                style = TextStyle(fontSize = 48.sp, fontWeight = FontWeight.ExtraBold)
            )
            Spacer(Modifier.height(8.dp))
            MetroText(
                "A Re-defined Music Player",
                color = Color.White,
                style = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.Normal)
            )
        }

        MetroText(
            "Artwork by Project Arcturius",
            color = Color.Gray.copy(alpha = 0.5f),
            style = TextStyle(fontSize = 12.sp, textAlign = TextAlign.Center),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        )
    }
}

// splash 最短驻留：warmup 早于此完成时仍要撑到这个时间，避免视觉一闪
private const val MIN_DWELL_MS = 350L