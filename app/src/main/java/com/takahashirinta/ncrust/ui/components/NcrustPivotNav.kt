package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors

data class PivotTabItem(
    val icon: ImageVector,
    val label: String
)

/**
 * Metro Pivot 风底部导航。替代 M3 NavigationBar。
 *
 * - 顶部 2dp 细线指示选中态（硬切换，无滑动--Metro 利落感）
 * - 图标 + 文字纯色变（选中 primary，未选中 onSurfaceVariant）
 * - 无 ripple、无背板、无圆角
 * - 高度由父容器决定（外层 Box 给 56dp 与迷你播放栏对齐）
 */
@Composable
fun NcrustPivotNav(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    items: List<PivotTabItem>,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth()) {
        items.forEachIndexed { index, item ->
            NcrustPivotTab(
                item = item,
                selected = selectedTab == index,
                onClick = { onTabSelected(index) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

@Composable
private fun NcrustPivotTab(
    item: PivotTabItem,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val colors = LocalNcrustColors.current
    val tint = if (selected) colors.primary else colors.onSurfaceVariant
    Box(
        modifier = modifier
            .fillMaxHeight()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(item.icon, item.label, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(2.dp))
            Text(
                item.label,
                color = tint,
                fontSize = 11.sp,
                lineHeight = 12.sp
            )
        }
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(24.dp)
                .height(2.dp)
                .background(if (selected) colors.primary else Color.Transparent)
        )
    }
}
