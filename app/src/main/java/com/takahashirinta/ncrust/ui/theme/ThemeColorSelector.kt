package com.takahashirinta.ncrust.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题色选择器 —— Groove 风：一行 6 个方形色块 + 下方选中色名。
 *
 * 设计取舍：
 *  - 6 种预设正好一行装下，视觉上就是一条 Metro"色墙"；
 *  - 选中态用 √ 图标叠加，根据色块亮度自动切黑白对比色，不管选到哪个色都能看清；
 *  - 素白（#FFFFFF）单独加一圈灰色描边，否则在黑色背景上和相邻的色块之间没有边界。
 *  - 选中色名用 primary 色，随选择变化——即时视觉反馈。
 */
@Composable
fun ThemeColorSelector(
    selectedIndex: Int,
    presets: List<ThemeColorPreset>,
    onSelect: (Int) -> Unit
) {
    val colorNames = LocalStrings.current.themeColorNames
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            presets.forEachIndexed { index, preset ->
                val isSelected = index == selectedIndex
                val checkColor = if (isLightColor(preset.color)) Color.Black else Color.White
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .aspectRatio(1f)
                        .background(preset.color)
                        .then(
                            // 素白在黑背景上必须描边，否则看不出色块边界。
                            if (isLightColor(preset.color))
                                Modifier.border(1.dp, Color.Gray.copy(alpha = 0.5f))
                            else Modifier
                        )
                        .clickable { onSelect(index) },
                    contentAlignment = Alignment.Center
                ) {
                    if (isSelected) {
                        Icon(
                            Icons.Default.Check,
                            contentDescription = null,
                            tint = checkColor,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = colorNames.getOrElse(selectedIndex) {
                presets.getOrElse(selectedIndex) { presets[0] }.label
            },
            color = MaterialTheme.colorScheme.primary,
            fontSize = 14.sp
        )
    }
}

/** 感知亮度判断：> 0.7 视为浅色，用黑色图标；否则白色图标。 */
private fun isLightColor(color: Color): Boolean {
    val luminance = 0.299 * color.red + 0.587 * color.green + 0.114 * color.blue
    return luminance > 0.7
}
