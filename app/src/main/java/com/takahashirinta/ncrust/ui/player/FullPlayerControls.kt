package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.formatDuration
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

@Composable
fun FullPlayerControls(
    isPlaying: Boolean,
    showLyrics: Boolean,
    showQueue: Boolean,
    playbackProgress: Float,
    currentPosition: Long,
    duration: Long,
    qualityLabel: String = "无损",
    onPlayPause: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onAddToLibrary: () -> Unit = {},
    isBuffering: Boolean = false,
    onSeek: (Float) -> Unit = {},
    onNavigateToUser: () -> Unit = {}
) {
    val strings = LocalStrings.current
    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            Text(
                formatDuration(currentPosition),
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterStart)
            )
            Box(
                modifier = Modifier
                    .align(Alignment.Center)
                    .background(Color(0xFF2A2A2A))
                    .clickable(
                        indication = null,
                        interactionSource = remember { MutableInteractionSource() }
                    ) { onNavigateToUser() }
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Text(
                    qualityLabel,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold
                )
            }
            Text(
                formatDuration(duration),
                color = Color.Gray,
                fontSize = 12.sp,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        Spacer(Modifier.height(8.dp))
        SlimProgressBar(progress = playbackProgress, isBuffering = isBuffering, onSeek = onSeek)
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
                    strings.prevButton,
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
                    if (isPlaying) strings.pauseButton else strings.playButton,
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
                    strings.nextButton,
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
                    strings.lyricsButton,
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
                    strings.queueButton,
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
                    strings.addToLibraryButton,
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
    }
}
