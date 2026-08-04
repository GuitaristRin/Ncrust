package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.SongItem
import io.github.takahashirinta.kanesumi.controls.MetroBottomSheet
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.core.insets.metroNavigationBarsPadding
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

data class SongMenuAction(
    val icon: ImageVector,
    val label: String,
    val onClick: () -> Unit
)

@Composable
fun SongMenuSheet(
    song: SongItem,
    actions: List<SongMenuAction>,
    onDismiss: () -> Unit
) {
    MetroBottomSheet(
        onDismiss = onDismiss,
        // 信息区作为 dragHandle：MetroBottomSheet 只在 handle 区挂纵向拖拽手势，
        // 语义与旧实现（整块信息区可下滑收起）一致。
        dragHandle = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(112.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 直角封面，贴屏左边缘，112dp = 2x 迷你播放栏封面高
                AsyncImage(
                    model = song.album?.picUrl,
                    contentDescription = null,
                    modifier = Modifier.size(112.dp),
                    contentScale = ContentScale.Crop
                )
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .weight(1f)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    MetroText(
                        song.name,
                        color = Color.White,
                        style = LocalMetroTypography.current.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(4.dp))
                    MetroText(
                        song.artists?.joinToString("/") { it.name } ?: "",
                        color = LocalMetroColors.current.primary,
                        style = LocalMetroTypography.current.bodyMedium,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    val albumName = song.album?.name
                    if (!albumName.isNullOrEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        MetroText(
                            albumName,
                            color = Color.Gray,
                            style = LocalMetroTypography.current.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        },
    ) {
        MetroDivider()

        // 可扩展操作列表
        actions.forEach { action ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        action.onClick()
                        onDismiss()
                    }
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroIcon(
                    imageVector = action.icon,
                    contentDescription = null,
                    tint = Color.White,
                    sizeDp = 24.dp
                )
                Spacer(Modifier.width(16.dp))
                MetroText(
                    action.label,
                    color = Color.White,
                    style = LocalMetroTypography.current.bodyLarge
                )
            }
        }

        Spacer(Modifier.metroNavigationBarsPadding())
    }
}
