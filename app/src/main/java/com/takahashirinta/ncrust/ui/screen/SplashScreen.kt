package com.takahashirinta.ncrust.ui.screen

// SplashScreen.kt
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SplashScreen(onFinished: () -> Unit) {
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            // 更密集的预热，覆盖更多编译路径
            val startTime = System.currentTimeMillis()
            var result = 0.0
            // 第一阶段：密集数学运算
            while (System.currentTimeMillis() - startTime < 1200) {
                result += kotlin.math.sqrt(kotlin.math.abs(kotlin.math.sin(result + 1.0)))
                result += kotlin.math.ln(kotlin.math.abs(result) + 2.0)
                result += kotlin.math.exp(kotlin.math.abs(result % 1.0))
            }
            // 第二阶段：字符串操作预热
            var str = ""
            repeat(5000) {
                str += "a"
                if (str.length > 100) str = str.takeLast(50)
            }
            // 第三阶段：集合操作预热
            val list = mutableListOf<Float>()
            repeat(1000) {
                list.add(kotlin.math.sqrt(it.toFloat()))
                if (list.size > 100) list.removeAt(0)
                list.sort()
            }
        }
        onFinished()
    }

    Box(
        modifier = Modifier.fillMaxSize().background(Color(0xFF121212)),
        contentAlignment = Alignment.Center
    ) {
        Text("Ncrust", color = Color(0xFF1DB954), fontSize = 48.sp)
    }
}