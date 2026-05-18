package com.takahashirinta.ncrust.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 主题色选择器 —— Metro 直角纯色块风格，与 QualitySelector 保持一致。
 *
 * @param selectedIndex 当前选中索引
 * @param presets       主题色预设列表
 * @param onSelect      选中回调
 */
@Composable
fun ThemeColorSelector(
    selectedIndex: Int,
    presets: List<ThemeColorPreset>,
    onSelect: (Int) -> Unit
) {
    val colorNames = LocalStrings.current.themeColorNames
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        presets.forEachIndexed { index, preset ->
            val isSelected = selectedIndex == index

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        if (isSelected) preset.color else Color.Transparent,
                        RoundedCornerShape(0.dp)
                    )
                    .border(
                        width = 1.dp,
                        color = if (isSelected) preset.color else Color.Gray.copy(alpha = 0.4f),
                        shape = RoundedCornerShape(0.dp)
                    )
                    .clickable { onSelect(index) }
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(16.dp)
                            .background(preset.color, RoundedCornerShape(0.dp))
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = colorNames.getOrElse(index) { preset.label },
                        color = if (isSelected) Color.Black else Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }
    }
}