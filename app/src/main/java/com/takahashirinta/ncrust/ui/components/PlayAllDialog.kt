package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.controls.MetroDialog
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 整列表操作对话框：替换队列并播放 / 插到下一首 / 追加到队尾。
 *
 * [onAppendToQueue] 默认 null = 不显示第三项——老调用点（专辑、歌单详情）没有「追加整列表」
 * 的入口，传 null 才能保证它们的行为一字不变。
 */
@Composable
fun PlayAllDialog(
    songCount: Int,
    onDismiss: () -> Unit,
    onReplaceAndPlay: () -> Unit,
    onInsertNext: () -> Unit,
    onAppendToQueue: (() -> Unit)? = null
) {
    val strings = LocalStrings.current
    MetroDialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 14.dp)
        ) {
            MetroText(
                text = strings.songCountFormat(songCount),
                color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 13.sp)
            )
        }
        MetroDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onReplaceAndPlay(); onDismiss() }
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroIcon(
                imageVector = Icons.Default.PlayArrow,
                contentDescription = null,
                tint = LocalMetroColors.current.primary,
                sizeDp = 26.dp
            )
            Spacer(Modifier.width(16.dp))
            Column {
                MetroText(
                    text = strings.playNowTitle,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                MetroText(
                    text = strings.playNowDesc,
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 12.sp)
                )
            }
        }
        MetroDivider()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onInsertNext(); onDismiss() }
                .padding(horizontal = 20.dp, vertical = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroIcon(
                imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                contentDescription = null,
                tint = LocalMetroColors.current.onBackground,
                sizeDp = 26.dp
            )
            Spacer(Modifier.width(16.dp))
            Column {
                MetroText(
                    text = strings.insertNextTitle,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                )
                MetroText(
                    text = strings.insertNextDesc,
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 12.sp)
                )
            }
        }
        MetroDivider()

        if (onAppendToQueue != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onAppendToQueue(); onDismiss() }
                    .padding(horizontal = 20.dp, vertical = 18.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroIcon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistAdd,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onBackground,
                    sizeDp = 26.dp
                )
                Spacer(Modifier.width(16.dp))
                Column {
                    MetroText(
                        text = strings.appendQueueTitle,
                        color = LocalMetroColors.current.onBackground,
                        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    )
                    MetroText(
                        text = strings.appendQueueDesc,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = TextStyle(fontSize = 12.sp)
                    )
                }
            }
            MetroDivider()
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onDismiss() }
                .padding(vertical = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            MetroText(
                text = strings.cancel,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = TextStyle(fontSize = 15.sp)
            )
        }
    }
}
