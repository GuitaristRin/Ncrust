package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.PlaylistRemove
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 歌单内编辑态的上下文工具条：固定一行 56dp，选中数变化、顺序变化都不改变它的高度。
 *
 * 旧版是「文字动作 ×4 + 提示行 + 有选中项时再长出一行按钮」，条高随状态跳；现在动作收成图标
 * （带 contentDescription），顺序被拖动过时整条切换为「顺序已调整 · 保存顺序」，此时选择动作
 * 不可用——整表顺序与逐首移除不能混在同一次提交里。
 *
 * [progress] 0→1 自上方滑入，只在 graphicsLayer 读取。
 */
@Composable
fun PlaylistEditBar(
    progress: () -> Float,
    selectionCount: Int,
    totalCount: Int,
    owned: Boolean,
    orderChanged: Boolean,
    busy: Boolean,
    onExit: () -> Unit,
    onToggleAll: () -> Unit,
    onRemove: () -> Unit,
    onAddTo: () -> Unit,
    onSaveOrder: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalStrings.current
    val p = s.playlists
    val colors = LocalMetroColors.current
    Column(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                val t = progress()
                translationY = -size.height * (1f - t)
                alpha = t
            }
            .background(colors.surface)
            .statusBarsPadding()
    ) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroIconButton(onClick = onExit) {
                MetroIcon(Icons.Default.Close, contentDescription = p.done, tint = colors.onBackground)
            }
            MetroText(
                when {
                    orderChanged -> p.orderChanged
                    selectionCount == 0 -> p.selectTracks
                    else -> p.selectionCount(selectionCount)
                },
                color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = LocalMetroTypography.current.title.copy(fontSize = 18.sp),
                modifier = Modifier.weight(1f).padding(start = 4.dp)
            )
            if (orderChanged) {
                BarTextAction(p.saveOrder, enabled = !busy, onClick = onSaveOrder, primary = true)
            } else {
                val all = totalCount > 0 && selectionCount == totalCount
                BarIconAction(if (all) Icons.Default.Deselect else Icons.Default.SelectAll, p.selectAll,
                    enabled = !busy && totalCount > 0, onClick = onToggleAll)
                BarIconAction(Icons.AutoMirrored.Filled.PlaylistAdd, p.add, enabled = !busy && selectionCount > 0, onClick = onAddTo)
                if (owned) BarIconAction(Icons.Default.PlaylistRemove, p.remove,
                    enabled = !busy && selectionCount > 0, onClick = onRemove)
            }
        }
    }
}

@Composable
internal fun BarIconAction(icon: ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val colors = LocalMetroColors.current
    MetroIconButton(onClick = onClick, enabled = enabled) {
        MetroIcon(icon, contentDescription = label,
            tint = if (enabled) colors.onBackground else colors.onSurfaceVariant.copy(alpha = 0.5f))
    }
}

/**
 * 文字型动作：直角、无背板。主操作铺强调色、前景按亮度取黑/白——强调色直接作文字色时，
 * 浅色底上的云杉绿 / 琥珀只有约 2:1 对比度，读不清。
 */
@Composable
internal fun BarTextAction(text: String, enabled: Boolean, onClick: () -> Unit, primary: Boolean = false) {
    val colors = LocalMetroColors.current
    if (primary) {
        Box(Modifier.padding(horizontal = 8.dp)) {
            Box(
                Modifier
                    .background(if (enabled) colors.primary else colors.surfaceVariant)
                    .clickable(enabled = enabled, onClick = onClick)
                    .semantics { role = Role.Button }
                    .padding(horizontal = 14.dp, vertical = 8.dp)
            ) {
                MetroText(text, color = if (enabled) playlistTextColor(colors.primary) else colors.onSurfaceVariant)
            }
        }
        return
    }
    Box(
        Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .semantics { role = Role.Button }
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        MetroText(text, color = if (enabled) colors.onBackground else colors.onSurfaceVariant)
    }
}
