package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import androidx.compose.ui.graphics.graphicsLayer

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    val alpha = remember { Animatable(1f) }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            val startTime = System.currentTimeMillis()
            var result = 0.0
            while (System.currentTimeMillis() - startTime < 1200) {
                result += kotlin.math.sqrt(kotlin.math.abs(kotlin.math.sin(result + 1.0)))
                result += kotlin.math.ln(kotlin.math.abs(result) + 2.0)
                result += kotlin.math.exp(kotlin.math.abs(result % 1.0))
            }
            var str = ""
            repeat(5000) {
                str += "a"
                if (str.length > 100) str = str.takeLast(50)
            }
            val list = mutableListOf<Float>()
            repeat(1000) {
                list.add(kotlin.math.sqrt(it.toFloat()))
                if (list.size > 100) list.removeAt(0)
                list.sort()
            }
        }
        delay(200)
        alpha.animateTo(0f, animationSpec = tween(400, easing = FastOutSlowInEasing))
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
            Text(
                "Ncrust",
                color = Color(0xFF1DB954),
                fontSize = 48.sp,
                fontWeight = FontWeight.ExtraBold
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "A Re-defined Music Player",
                color = Color.White,
                fontSize = 14.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Text(
            "Artwork by Project Arcturius",
            color = Color.Gray.copy(alpha = 0.5f),
            fontSize = 12.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 48.dp)
        )
    }
}