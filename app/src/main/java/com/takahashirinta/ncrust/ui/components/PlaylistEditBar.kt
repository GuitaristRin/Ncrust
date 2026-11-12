package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 歌单内的编辑态工具条。**不用长按进入**：长按仍是歌曲菜单，编辑由歌单页头的显式入口进入，
 * 进入后点按选中、按住上下滑动连选。
 *
 * 排序是独立子态：它要提交整表顺序（见 `spec/api/playlist-edit.md`），与选择互斥，
 * 所以两者不共用一套手势。
 */
@Composable
fun PlaylistEditBar(
    modifier: Modifier = Modifier,
    selectionCount: Int,
    owned: Boolean,
    sorting: Boolean,
    canSort: Boolean,
    busy: Boolean,
    onExit: () -> Unit,
    onSelectAll: () -> Unit,
    onRemove: () -> Unit,
    onAddTo: () -> Unit,
    onStartSorting: () -> Unit,
    onSaveOrder: () -> Unit,
    onCancelSorting: () -> Unit
) {
    val s = LocalStrings.current
    val p = s.playlists
    val colors = LocalMetroColors.current
    val caption = LocalMetroTypography.current.caption
    Column(modifier.background(colors.surface).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroIconButton(
                onClick = { if (sorting) onCancelSorting() else onExit() },
                enabled = !busy
            ) {
                MetroIcon(Icons.Default.Close, contentDescription = if (sorting) s.cancel else s.playlists.done,
                    tint = colors.onBackground)
            }
            MetroText(
                if (sorting) p.sortTracks else p.selectionCount(selectionCount),
                color = colors.onBackground, maxLines = 1,
                style = LocalMetroTypography.current.title.copy(fontSize = 18.sp),
                modifier = Modifier.weight(1f)
            )
            if (sorting) {
                BarTextAction(p.saveOrder, enabled = !busy, onClick = onSaveOrder, primary = true)
            } else {
                BarTextAction(p.selectAll, enabled = !busy, onClick = onSelectAll)
                if (canSort) BarTextAction(p.sortTracks, enabled = !busy, onClick = onStartSorting)
                BarTextAction(s.playlists.done, enabled = !busy, onClick = onExit, primary = true)
            }
        }
        // 首次进入编辑态且没有选中项时提示手势，避免用户以为只能一首一首点。
        if (!sorting && selectionCount == 0) {
            MetroText(p.dragSelectHint, color = colors.onSurfaceVariant, maxLines = 2, style = caption,
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 10.dp))
        }
        if (!sorting && selectionCount > 0) {
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (owned) PlaylistButton(p.remove, onRemove, Modifier.weight(1f),
                    enabled = !busy, containerColor = colors.surfaceVariant, contentColor = colors.onBackground)
                PlaylistButton(p.add, onAddTo, Modifier.weight(1f),
                    enabled = !busy, containerColor = colors.surfaceVariant, contentColor = colors.onBackground)
            }
        }
    }
}

/** 文字型动作：直角、无背板，主操作用强调色。 */
@Composable
private fun BarTextAction(text: String, enabled: Boolean, onClick: () -> Unit, primary: Boolean = false) {
    val colors = LocalMetroColors.current
    Box(
        Modifier
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        MetroText(text, color = when {
            !enabled -> colors.onSurfaceVariant
            primary -> colors.primary
            else -> colors.onBackground
        })
    }
}

/**
 * 按住上下滑动连选。拖动发生在行内，指针捕获属于按下的那一行，所以纵向位移除以行高就是
 * 目标行偏移；消费掉 move 事件可阻止 LazyColumn 同时滚动，让待选范围保持稳定。
 *
 * 第 0 个参数是「是否处于编辑态」——非编辑态直接不挂手势，长按与点击完全保留原行为。
 */
internal fun Modifier.playlistDragSelect(
    enabled: Boolean,
    index: Int,
    rowHeightPx: () -> Float,
    onStart: (Int) -> Unit,
    onExtend: (Int) -> Unit,
    onEnd: () -> Unit
): Modifier {
    if (!enabled) return this
    return pointerInput(index, enabled) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            var active = false
            while (true) {
                val event = awaitPointerEvent()
                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                if (!change.pressed) break
                val dy = change.position.y - down.position.y
                if (!active && abs(dy) > viewConfiguration.touchSlop) {
                    active = true
                    onStart(index)
                }
                if (active) {
                    val height = rowHeightPx()
                    if (height > 0f) onExtend(index + (dy / height).roundToInt())
                    change.consume()
                }
            }
            if (active) onEnd()
        }
    }
}
