package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.takahashirinta.kanesumi.anim.sokuou.MetroDefault
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.launch

/**
 * 左滑露出一个动作按钮的行容器（iOS 风格）。
 *
 * 位移只写进 `graphicsLayer.translationX`，所以滑动过程中不触发重组；纵向滚动不受影响
 * （横向手势要越过横向 touchSlop 才被认领，竖直方向仍然交给 LazyColumn）。
 * [open] 由调用方托管，这样同一列表里能保证只有一行处于露出状态，并且点别处可以收起。
 *
 * 破坏性动作在这里不用红色常量：交付给调用方决定配色，组件只负责几何与手势。
 */
@Composable
fun SwipeRevealRow(
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    actionLabel: String,
    onAction: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    actionWidth: Dp = 96.dp,
    content: @Composable () -> Unit
) {
    val colors = LocalMetroColors.current
    val actionPx = with(LocalDensity.current) { actionWidth.toPx() }
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val settle = tween<Float>(180, easing = MetroDefault)
    val target = if (open && enabled) -actionPx else 0f

    LaunchedEffect(target) { offset.animateTo(target, settle) }

    Box(modifier) {
        Row(Modifier.matchParentSize(), horizontalArrangement = Arrangement.End) {
            Box(
                Modifier
                    .width(actionWidth)
                    .fillMaxHeight()
                    .background(colors.surfaceVariant)
                    .clickable(enabled = enabled) { onAction(); onOpenChange(false) },
                contentAlignment = Alignment.Center
            ) {
                MetroText(actionLabel, color = colors.primary, style = LocalMetroTypography.current.bodyMedium)
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset.value }
                // 内容必须不透明：否则底下的动作面会透过行体一直露出来。
                .background(colors.background)
                .pointerInput(enabled, actionPx) {
                    if (!enabled) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            val revealed = offset.value <= -actionPx * 0.45f
                            scope.launch { offset.animateTo(if (revealed) -actionPx else 0f, settle) }
                            onOpenChange(revealed)
                        },
                        onDragCancel = {
                            scope.launch { offset.animateTo(0f, settle) }
                            onOpenChange(false)
                        },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            val next = (offset.value + dragAmount).coerceIn(-actionPx, 0f)
                            scope.launch { offset.snapTo(next) }
                        }
                    )
                }
        ) { content() }
    }
}
