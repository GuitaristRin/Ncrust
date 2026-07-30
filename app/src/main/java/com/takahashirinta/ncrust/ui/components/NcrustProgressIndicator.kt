package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors

/**
 * Metro 风加载指示器。替代 M3 CircularProgressIndicator。
 *
 * 细线圆环（3dp stroke），缺 90 度开口，线性旋转 1s/圈。
 * rotation 在 graphicsLayer lambda 内读取（deferred read），零重组。
 * 默认 36dp，调用方可通过 modifier.size() 覆盖。
 */
@Composable
fun NcrustProgressIndicator(
    modifier: Modifier = Modifier,
    color: Color = LocalNcrustColors.current.primary
) {
    val transition = rememberInfiniteTransition(label = "progress")
    val rotation = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "rotation"
    )
    val resolved = if (modifier == Modifier) Modifier.size(36.dp) else modifier
    Canvas(
        modifier = resolved.graphicsLayer { rotationZ = rotation.value }
    ) {
        drawArc(
            color = color,
            startAngle = -90f,
            sweepAngle = 270f,
            useCenter = false,
            style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
        )
    }
}
