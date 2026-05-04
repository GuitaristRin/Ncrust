package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.formatDuration

@Composable
fun FullPlayerControls(
    isPlaying: Boolean,
    showLyrics: Boolean,
    showQueue: Boolean,
    playbackProgress: Float,
    currentPosition: Long,
    duration: Long,
    onPlayPause: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onAddToLibrary: () -> Unit = {},
    onSeek: (Float) -> Unit = {}
) {
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDuration(currentPosition), color = Color.Gray, fontSize = 12.sp)
            Text(formatDuration(duration), color = Color.Gray, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        SlimProgressBar(progress = playbackProgress, onSeek = onSeek)
        Spacer(Modifier.height(16.dp))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 16.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable { onPlayPrevious() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    "上一首",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
            Spacer(Modifier.width(24.dp))
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clickable { onPlayPause() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    if (isPlaying) "暂停" else "播放",
                    tint = Color.White,
                    modifier = Modifier.size(56.dp)
                )
            }
            Spacer(Modifier.width(24.dp))
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable { onPlayNext() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    "下一首",
                    tint = Color.White,
                    modifier = Modifier.size(36.dp)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable { onToggleLyrics() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Lyrics,
                    "歌词",
                    tint = if (showLyrics) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable { onToggleQueue() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.PlaylistPlay,
                    "队列",
                    tint = if (showQueue) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable { onAddToLibrary() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.Add,
                    "加入库",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
