package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 后台活动授权弹窗（首次启动、且尚未加入电池优化白名单时弹出）。
 * Kanesumi 风格：直角、信息优先、无圆角。
 */
@Composable
fun BackgroundActivityDialog(
    onAllow: () -> Unit,
    onLater: () -> Unit
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onLater) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF282828))
                .padding(24.dp)
        ) {
            MetroText(
                strings.batteryTitle,
                color = Color.White,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(12.dp))
            MetroText(
                strings.batteryMessage,
                color = Color.Gray,
                style = LocalMetroTypography.current.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalMetroColors.current.primary)
                    .clickable(onClick = onAllow)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                MetroText(
                    strings.batteryAllow,
                    color = Color.Black,
                    style = TextStyle(fontSize = 14.sp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.Gray.copy(alpha = 0.4f))
                    .clickable(onClick = onLater)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                MetroText(
                    strings.batteryLater,
                    color = Color.Gray,
                    style = TextStyle(fontSize = 14.sp),
                )
            }
        }
    }
}
