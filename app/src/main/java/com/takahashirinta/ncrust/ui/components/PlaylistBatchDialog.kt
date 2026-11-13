package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.playlist.PlaylistBatch
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroDivider
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
            Box(Modifier.weight(1f).clickable(role = Role.Button) { onDismiss() }.padding(vertical = 16.dp),
                contentAlignment = Alignment.Center) {
                MetroText(s.cancel, color = colors.onBackground)
            }
            // 确认格铺强调色：与取消格在形状上就分得开，前景按亮度取黑/白保证对比度。
            Box(Modifier.weight(1f).background(colors.primary).clickable(role = Role.Button) { onConfirm() }.padding(vertical = 16.dp),
                contentAlignment = Alignment.Center) {
                MetroText(confirmLabel, color = playlistTextColor(colors.primary), maxLines = 1)
            }
        }
    }
}

/**
 * 批量删除的失败汇总。进度由轻提示条承接（[PlaylistNoticeHost]），全部确认删除也只给轻提示；
 * 只有存在未完成项时才弹这个对话框，如实给出已删除 / 未完成数量与第一个失败原因，
 * 不把「提交了 N 个」说成「删除了 N 个」。
 */
@Composable
fun PlaylistBatchDialog(batch: PlaylistBatch, onDismiss: () -> Unit) {
    if (!batch.visible || batch.running) return
    val s = LocalStrings.current
    val p = s.playlists
    val colors = LocalMetroColors.current
    MetroDialog(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MetroText(p.delete, style = LocalMetroTypography.current.title.copy(fontSize = 20.sp))
            MetroText(p.batchSummary(batch.deleted, batch.failed), color = colors.onBackground)
            batch.problem?.let { MetroText(playlistProblemText(it, p), color = colors.onSurfaceVariant) }
        }
        MetroDivider()
        Box(Modifier.fillMaxWidth().clickable { onDismiss() }.padding(vertical = 16.dp),
            contentAlignment = Alignment.Center) {
            MetroText(p.done, color = colors.primary)
        }
    }
}
