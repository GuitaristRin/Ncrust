package com.takahashirinta.ncrust.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import com.takahashirinta.ncrust.network.SongItem

@Composable
fun PlayerCardOverlay(
    song: SongItem?,
    isPlaying: Boolean,
    progress: Animatable<Float, AnimationVector1D>,
    collapsedOffsetY: Float,
    screenHeightPx: Float,
    totalDragDistancePx: Float = 0f,
    playbackQueue: List<SongItem> = emptyList(),
    currentQueueIndex: Int = -1,
    playMode: Int = 0,
    onPlayPause: () -> Unit,
    onDismiss: () -> Unit,
    onPlayPrevious: () -> Unit = {},
    onPlayNext: () -> Unit = {},
    onRemoveFromQueue: (Int) -> Unit = {},
    onPlayFromQueue: (Int) -> Unit = {},
    onTogglePlayMode: () -> Unit = {},
    onSavePlaylist: () -> Unit = {}
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer {
                translationY = collapsedOffsetY + (0f - collapsedOffsetY) * progress.value
            }
    ) {
        PlayerCard(
            song = song,
            isPlaying = isPlaying,
            screenHeightPx = screenHeightPx,
            progress = progress,
            totalDragDistancePx = totalDragDistancePx,
            playbackQueue = playbackQueue,
            currentQueueIndex = currentQueueIndex,
            playMode = playMode,
            onPlayPause = onPlayPause,
            onDismiss = onDismiss,
            onPlayPrevious = onPlayPrevious,
            onPlayNext = onPlayNext,
            onRemoveFromQueue = onRemoveFromQueue,
            onPlayFromQueue = onPlayFromQueue,
            onTogglePlayMode = onTogglePlayMode,
            onSavePlaylist = onSavePlaylist
        )
    }
}
