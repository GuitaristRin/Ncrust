package com.takahashirinta.ncrust.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.formatDuration
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.flow.StateFlow

@Composable
fun FullPlayerControls(
    isPlaying: Boolean,
    showLyrics: Boolean,
    showQueue: Boolean,
    progressFlow: StateFlow<Float>,
    positionFlow: StateFlow<Long>,
    durationFlow: StateFlow<Long>,
    qualityIndexFlow: StateFlow<Int>,
    qualityOptions: List<String>,
    onPlayPause: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onToggleLyrics: () -> Unit,
    onToggleQueue: () -> Unit,
    onAddToLibrary: () -> Unit = {},
    // 当前歌是否已在收藏库: true 显示对号, 按下走"移出库"分支
    isInLibrary: Boolean = false,
    isBufferingFlow: StateFlow<Boolean>,
    onSeek: (Float) -> Unit = {},
    onNavigateToUser: () -> Unit = {},
    lyricsUnavailable: Boolean = false,
    previousEnabled: Boolean = true
) {
    val strings = LocalStrings.current
    // 触觉反馈:播放/暂停/切歌/开关面板给一个轻振,补足无 ripple 时代的确认感
    val haptic = LocalHapticFeedback.current
    fun tick() = haptic.performHapticFeedback(HapticFeedbackType.LongPress)

    Column(modifier = Modifier.fillMaxWidth().padding(bottom = 32.dp)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
        ) {
            // 位置文本抽出为叶子 Composable：仅它随 250ms 位置更新重组，
            // 不牵连按钮/进度条区域
            PositionText(
                positionFlow = positionFlow,
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
                QualityLabel(qualityIndexFlow = qualityIndexFlow, options = qualityOptions)
            }
            DurationText(
                durationFlow = durationFlow,
                modifier = Modifier.align(Alignment.CenterEnd)
            )
        }
        Spacer(Modifier.height(8.dp))
        SlimProgressBar(
            progressFlow = progressFlow,
            durationFlow = durationFlow,
            isBufferingFlow = isBufferingFlow,
            onSeek = onSeek
        )
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
                    .size(60.dp)
                    .clickable(enabled = previousEnabled) {
                        tick()
                        onPlayPrevious()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.SkipPrevious,
                    contentDescription = strings.prevButton,
                    tint = if (previousEnabled) Color.White else Color(0xFF505050),
                    sizeDp = 40.dp
                )
            }
            Spacer(Modifier.width(32.dp))
            Box(
                modifier = Modifier
                    .size(84.dp)
                    .clickable {
                        tick()
                        onPlayPause()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) strings.pauseButton else strings.playButton,
                    tint = Color.White,
                    sizeDp = 56.dp
                )
            }
            Spacer(Modifier.width(32.dp))
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clickable {
                        tick()
                        onPlayNext()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.SkipNext,
                    contentDescription = strings.nextButton,
                    tint = Color.White,
                    sizeDp = 40.dp
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
                    .clickable(enabled = !lyricsUnavailable) {
                        tick()
                        onToggleLyrics()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.Default.Lyrics,
                    contentDescription = strings.lyricsButton,
                    // 无歌词/未加载时置灰, 语义是该歌不可展开歌词
                    tint = if (lyricsUnavailable) Color(0xFF505050)
                    else if (showLyrics) LocalMetroColors.current.primary else Color.White,
                    sizeDp = 32.dp
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable {
                        tick()
                        onToggleQueue()
                    },
                contentAlignment = Alignment.Center
            ) {
                MetroIcon(
                    imageVector = Icons.AutoMirrored.Filled.PlaylistPlay,
                    contentDescription = strings.queueButton,
                    tint = if (showQueue) LocalMetroColors.current.primary else Color.White,
                    sizeDp = 32.dp
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clickable {
                        tick()
                        onAddToLibrary()
                    },
                contentAlignment = Alignment.Center
            ) {
                // 已在库 → 对号(按动移出); 不在库 → 加号(按动收藏)
                MetroIcon(
                    imageVector = if (isInLibrary) Icons.Default.Check else Icons.Default.Add,
                    contentDescription = strings.addToLibraryButton,
                    tint = if (isInLibrary) LocalMetroColors.current.primary else Color.White,
                    sizeDp = 32.dp
                )
            }
        }
    }
}

@Composable
private fun PositionText(positionFlow: StateFlow<Long>, modifier: Modifier) {
    val position by positionFlow.collectAsState()
    MetroText(
        formatDuration(position),
        color = Color.Gray,
        style = TextStyle(fontSize = 12.sp),
        modifier = modifier
    )
}

@Composable
private fun DurationText(durationFlow: StateFlow<Long>, modifier: Modifier) {
    val duration by durationFlow.collectAsState()
    MetroText(
        formatDuration(duration),
        color = Color.Gray,
        style = TextStyle(fontSize = 12.sp),
        modifier = modifier
    )
}

@Composable
private fun QualityLabel(qualityIndexFlow: StateFlow<Int>, options: List<String>) {
    val qualityIndex by qualityIndexFlow.collectAsState()
    val label = options.getOrElse(qualityIndex) { options.getOrElse(3) { "" } }
    MetroText(
        label,
        color = LocalMetroColors.current.primary,
        style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Ellipsis
    )
}
