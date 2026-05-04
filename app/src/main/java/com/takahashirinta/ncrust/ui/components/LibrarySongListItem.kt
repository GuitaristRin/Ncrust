package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.network.SongItem

@Composable
fun LibrarySongListItem(
    song: SongItem,
    onPlay: () -> Unit,
    onInsertNext: () -> Unit = {},
    onAppendToQueue: () -> Unit = {},
    onRemove: () -> Unit
) {
    val artistStr = song.artists?.joinToString("/") { it.name } ?: "未知歌手"

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onPlay() }
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = song.album?.picUrl,
            contentDescription = "封面",
            modifier = Modifier.size(48.dp),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                song.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "$artistStr · ${song.album?.name ?: ""}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        IconButton(onClick = onInsertNext) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistPlay,
                "插播",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        IconButton(onClick = onAppendToQueue) {
            Icon(
                Icons.AutoMirrored.Filled.PlaylistAdd,
                "加入播放列表",
                tint = Color.White,
                modifier = Modifier.size(24.dp)
            )
        }
        IconButton(onClick = onRemove) {
            Icon(
                Icons.Default.Delete,
                "移除",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
