package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background

@Composable
fun SlimProgressBar(progress: Float, onSeek: (Float) -> Unit) {
    var barWidth by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(32.dp)
            .onSizeChanged { barWidth = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val newProgress = (change.position.x / barWidth).coerceIn(0f, 1f)
                    onSeek(newProgress)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    val newProgress = (it.x / barWidth).coerceIn(0f, 1f)
                    onSeek(newProgress)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0xFF404040))
        )
        Box(
            Modifier
                .fillMaxWidth(fraction = progress)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}
