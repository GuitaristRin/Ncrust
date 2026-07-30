package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors

/**
 * Metro 风 Tab 行。替代 M3 TabRow + Tab。
 *
 * - 顶部 2dp 细线指示选中态（满宽硬切换，与 Pivot 导航一致）
 * - 纯文字 14sp，选中态 primary Medium，未选中 onSurfaceVariant Regular
 * - 无 ripple、无下划线背板
 * - 高度 48dp（与 M3 Tab 默认一致，不破坏现有布局）
 */
@Composable
fun NcrustTabRow(
    selectedTabIndex: Int,
    titles: List<String>,
    onTabSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNcrustColors.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(colors.background)
    ) {
        titles.forEachIndexed { index, title ->
            val selected = index == selectedTabIndex
            val tint = if (selected) colors.primary else colors.onSurfaceVariant
            Column(
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = { onTabSelected(index) }
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(if (selected) colors.primary else Color.Transparent)
                )
                Spacer(Modifier.height(12.dp))
                Text(
                    title,
                    color = tint,
                    fontSize = 14.sp,
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                )
            }
        }
    }
}
