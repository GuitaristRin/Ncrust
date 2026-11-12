package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.playlist.PlaylistBatch
import com.takahashirinta.ncrust.playlist.PlaylistProblem
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 破坏性动作的极简确认：一句话 + 取消/确认。不用再来一整个全屏面板 —— 写操作本身
 * 仍然走「备份 → 提交 → 读回」，结果由 [PlaylistBatchDialog] 或面板结果页承接。
 */
@Composable
fun PlaylistConfirmDialog(text: String, confirmLabel: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val s = LocalStrings.current
    val colors = LocalMetroColors.current
    MetroDialog(onDismissRequest = onDismiss) {
        MetroText(text, modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp))
        MetroDivider()
        Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f).clickable { onDismiss() }.padding(vertical = 16.dp),
                contentAlignment = Alignment.Center) {
                MetroText(s.cancel, color = colors.onSurfaceVariant)
            }
            Box(Modifier.weight(1f).clickable { onConfirm() }.padding(vertical = 16.dp),
                contentAlignment = Alignment.Center) {
                MetroText(confirmLabel, color = colors.primary)
            }
        }
    }
}

/**
 * 批量删除的进度/汇总。逐个歌单提交，所以这里既显示进度也如实汇总失败数量，
 * 不把「提交了 N 个」说成「删除了 N 个」。
 */
@Composable
fun PlaylistBatchDialog(batch: PlaylistBatch, onDismiss: () -> Unit) {
    if (!batch.visible) return
    val s = LocalStrings.current
    val p = s.playlists
    val colors = LocalMetroColors.current
    MetroDialog(onDismissRequest = { if (!batch.running) onDismiss() }) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MetroText(p.delete, style = LocalMetroTypography.current.title.copy(fontSize = 20.sp))
            if (batch.running) {
                Row(verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetroProgressIndicator(sizeDp = 18.dp)
                    MetroText(p.batchProgress(batch.done, batch.total), color = colors.onSurfaceVariant)
                }
            } else {
                MetroText(p.batchSummary(batch.deleted, batch.failed),
                    color = if (batch.failed > 0) colors.primary else colors.onSurfaceVariant)
                batch.problem?.let {
                    MetroText(playlistProblemText(it, p), color = colors.onSurfaceVariant)
                }
            }
        }
        if (!batch.running) {
            MetroDivider()
            Box(Modifier.fillMaxWidth().clickable { onDismiss() }.padding(vertical = 16.dp),
                contentAlignment = Alignment.Center) {
                MetroText(p.done, color = colors.primary)
            }
        }
    }
}
