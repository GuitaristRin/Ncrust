package com.takahashirinta.ncrust.ui.player

import androidx.compose.animation.core.*
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

@Composable
fun PlayerCard(
    song: SongItem?,
    isPlaying: Boolean,
    screenHeightPx: Float,
    progress: Animatable<Float, AnimationVector1D>,
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
    onSavePlaylist: () -> Unit = {},
    onNavigateToUser: () -> Unit = {}
) {
    val hasSong = song != null
    var showLyrics by remember { mutableStateOf(true) }
    var showQueue by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val playerViewModel: PlayerViewModel = viewModel()
    val lyrics by playerViewModel.lyrics.collectAsState()
    val currentPosition by playerViewModel.currentPosition.collectAsState()
    val playbackProgress by playerViewModel.progress.collectAsState()
    val qualityLabel by playerViewModel.currentQualityLabel.collectAsState()

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val screenWidthPx = with(density) { screenWidthDp.toPx() }
    val dp24px = with(density) { 24.dp.toPx() }
    // 横滑时内容偏移量 = 1/3 屏宽，保持视觉层次感
    val contentSlideWidthPx = screenWidthPx / 3f

    val miniCoverHalfPx = with(density) { 28.dp.toPx() }
    val miniScale = miniCoverHalfPx * 2f / screenWidthPx
    val statusBarPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        .let { with(density) { it.toPx() } }
    val miniCoverCenterX = miniCoverHalfPx
    val miniCoverCenterY = statusBarPx + miniCoverHalfPx
    val largeCoverCenterX = screenWidthPx / 2f
    val largeCoverCenterY = screenHeightPx * 0.3f + dp24px
    val boundsCenter = screenWidthPx / 2f

    // 完全收起时才激活迷你播放栏；derivedStateOf 将重组限制在阈值穿越处
    val miniBarEnabled by remember { derivedStateOf { progress.value < 0.01f } }
    val miniBarInteractionSource = remember { MutableInteractionSource() }
    // 完全展开时才激活收起按钮
    val dismissEnabled by remember { derivedStateOf { progress.value > 0.99f } }

    // lyricAnimProgress：0 = 大封面，1 = 小封面；驱动封面缩放 + 内容淡入淡出
    // queueSlideProgress：0 = 歌词位置，1 = 列表位置；仅 b↔c 时动画，其他时 snap
    // 两个 Animatable 均只在 graphicsLayer { } draw 阶段读取，动画帧内零 recompose
    val lyricAnimProgress = remember { Animatable(1f) }
    val queueSlideProgress = remember { Animatable(0f) }
    // 仅在歌词模式下歌词可交互；阈值穿越处各触发一次重组，其余帧零重组
    val lyricsEnabled by remember { derivedStateOf { lyricAnimProgress.value > 0.5f && queueSlideProgress.value < 0.5f } }

    LaunchedEffect(showLyrics, showQueue) {
        when {
            showLyrics -> {
                // 与封面缩小并行
                launch { lyricAnimProgress.animateTo(1f, tween(190, easing = FastOutSlowInEasing)) }
                // 若当前 queueSlideProgress > 0（来自列表模式），横滑回歌词位置
                if (queueSlideProgress.value > 0.01f) {
                    queueSlideProgress.animateTo(
                        0f, tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                    )
                }
            }
            showQueue -> {
                launch { lyricAnimProgress.animateTo(1f, tween(190, easing = FastOutSlowInEasing)) }
                when {
                    // 已在列表位置（或正在返回），无需再动
                    queueSlideProgress.value > 0.99f -> {}
                    // 来自稳定歌词模式（lyricAnimProgress 已是 1）→ 横滑
                    lyricAnimProgress.value > 0.95f -> {
                        queueSlideProgress.animateTo(
                            1f, tween(260, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                        )
                    }
                    // 来自大封面模式 → 不横滑，仅随封面缩小淡入
                    else -> queueSlideProgress.snapTo(1f)
                }
            }
            else -> {
                // 切回大封面：等封面展开完成，内容随 lyricAnimProgress 自然淡出
                lyricAnimProgress.animateTo(0f, tween(300, easing = LinearOutSlowInEasing))
                // 封面展开后重置滑动位置，为下次 b→c 准备
                queueSlideProgress.snapTo(0f)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Outer modifier → runs last within this node in Main pass (after drag detector below).
            // Consumes remaining events when fully expanded so Scaffold siblings never receive them.
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (progress.value > 0.99f) {
                            event.changes.forEach { it.consume() }
                        }
                    }
                }
            }
            // Inner modifier → runs first within this node in Main pass.
            // Handles drag-to-collapse; runs before the outer consumer so it sees unconsumed MOVE.
            .pointerInput(Unit) {
                var dragStartProgress = 0f
                detectVerticalDragGestures(
                    onDragStart = { dragStartProgress = progress.value },
                    onDragEnd = {
                        coroutineScope.launch {
                            val target = if (dragStartProgress < 0.5f) {
                                if (progress.value >= 0.5f) 1f else 0f
                            } else {
                                if (progress.value >= 0.75f) 1f else 0f
                            }
                            progress.animateTo(
                                target,
                                if (target == 1f)
                                    tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                                else
                                    tween(durationMillis = 260, easing = FastOutSlowInEasing)
                            )
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            progress.snapTo(
                                (progress.value - dragAmount / totalDragDistancePx).coerceIn(0f, 1f)
                            )
                        }
                    }
                )
            }
    ) {
        // 全屏纯黑背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 24.dp)
                .background(MaterialTheme.colorScheme.background)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            if (hasSong) {
                val s = song!!

                // 顶部标题栏：小封面模式下显示曲名（收起按钮在外层 Box 最高 z 序）
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                        .padding(start = 68.dp, end = 56.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .graphicsLayer { alpha = lyricAnimProgress.value }
                    ) {
                        Text(
                            s.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Clip,
                            modifier = Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                animationMode = MarqueeAnimationMode.Immediately,
                                initialDelayMillis = 2000,
                                repeatDelayMillis = 2500,
                                velocity = 48.dp
                            )
                        )
                        Text(
                            s.artists?.joinToString("/") { it.name } ?: "",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                // 内容区：LyricsView、QueueView、大封面信息全部始终在 Composition 中。
                // 可见性完全由 graphicsLayer { alpha / translationX } 控制，动画帧零 recompose。
                // 大封面信息以 Alignment.BottomStart overlay 在同一 Box 内，不占 Column 高度。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                ) {
                    // 歌词面板：translationX 从 0 滑至 -screenWidthPx，确保非歌词模式下完全移出屏幕，
                    // 彻底消除与列表面板的命中测试重叠（combinedClickable 忽略 isConsumed 标志）
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val q = queueSlideProgress.value
                                alpha = lyricAnimProgress.value * (1f - q)
                                translationX = -q * screenWidthPx
                            }
                    ) {
                        LyricsView(
                            lyrics = lyrics,
                            currentPositionMs = currentPosition,
                            isVisible = showLyrics,
                            onSeekToMs = { ms -> playerViewModel.seekTo(ms) },
                            onUserScrolled = {},
                            enabled = lyricsEnabled
                        )
                    }

                    // 列表面板：translationX 从 +screenWidthPx 滑至 0，稳定态时完全在屏幕外
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .graphicsLayer {
                                val q = queueSlideProgress.value
                                alpha = lyricAnimProgress.value * q
                                translationX = (1f - q) * screenWidthPx
                            }
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                "播放列表",
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.weight(1f)
                            )
                            IconButton(onClick = onTogglePlayMode) {
                                Icon(
                                    when (playMode) {
                                        0 -> Icons.Default.Repeat
                                        1 -> Icons.Default.RepeatOne
                                        2 -> Icons.Default.Shuffle
                                        else -> Icons.Default.Repeat
                                    },
                                    "播放模式",
                                    tint = if (playMode != 0) MaterialTheme.colorScheme.primary else Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                            IconButton(onClick = onSavePlaylist) {
                                Icon(
                                    Icons.Default.Add,
                                    "保存为歌单",
                                    tint = Color.White,
                                    modifier = Modifier.size(24.dp)
                                )
                            }
                        }
                        HorizontalDivider(color = Color(0xFF2A2A2A))
                        QueueView(
                            queue = playbackQueue,
                            currentIndex = currentQueueIndex,
                            onPlayIndex = onPlayFromQueue,
                            onRemoveIndex = onRemoveFromQueue
                        )
                    }

                    // 大封面模式下的曲名/歌手信息：overlay 在内容区底部，不占 Column 高度。
                    // alpha 随 lyricAnimProgress 淡入淡出，无需 if 控制 Composition 成员资格。
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 8.dp)
                            .graphicsLayer {
                                alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) *
                                        (1f - lyricAnimProgress.value)
                            }
                    ) {
                        Text(
                            s.name,
                            color = Color.White,
                            style = MaterialTheme.typography.titleLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            s.artists?.joinToString("/") { it.name } ?: "",
                            color = MaterialTheme.colorScheme.primary,
                            style = MaterialTheme.typography.bodyLarge,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                // 底部播放控件
                Box(modifier = Modifier.graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }) {
                    FullPlayerControls(
                        isPlaying = isPlaying,
                        showLyrics = showLyrics,
                        showQueue = showQueue,
                        playbackProgress = playbackProgress,
                        currentPosition = currentPosition,
                        duration = playerViewModel.duration.collectAsState().value,
                        qualityLabel = qualityLabel,
                        onPlayPause = onPlayPause,
                        onPlayPrevious = onPlayPrevious,
                        onPlayNext = onPlayNext,
                        onToggleLyrics = {
                            showLyrics = !showLyrics
                            showQueue = false
                        },
                        onToggleQueue = {
                            showQueue = !showQueue
                            showLyrics = false
                        },
                        onAddToLibrary = {
                            LibraryManager.saveSong(context, song!!)
                        },
                        onSeek = { fraction ->
                            val dur = playerViewModel.duration.value
                            if (dur > 0) {
                                playerViewModel.seekTo((fraction * dur).toLong())
                            }
                        },
                        onNavigateToUser = onNavigateToUser
                    )
                }
            }
        }

        // 迷你播放栏叠加层：始终在 Composition 中，透明度仅在绘制阶段控制，避免动画期间触发重组
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .then(
                    if (miniBarEnabled) Modifier.clickable(
                        interactionSource = miniBarInteractionSource,
                        indication = null,
                        onClick = {
                            coroutineScope.launch {
                                progress.animateTo(
                                    1f,
                                    tween(durationMillis = 400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f))
                                )
                            }
                        }
                    ) else Modifier
                )
                .graphicsLayer {
                    alpha = (1f - progress.value * 5f).coerceIn(0f, 1f)
                },
            color = MaterialTheme.colorScheme.surface
        ) {
            Row(
                modifier = Modifier.fillMaxSize(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (hasSong) {
                    val s = song!!
                    Spacer(modifier = Modifier.fillMaxHeight().aspectRatio(1f))
                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    ) {
                        Text(
                            s.name,
                            color = Color.White,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            s.artists?.joinToString("/") { it.name } ?: "",
                            color = Color.Gray,
                            style = MaterialTheme.typography.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (miniBarEnabled) {
                        IconButton(onClick = onPlayPause) {
                            Icon(
                                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                null,
                                tint = Color.White
                            )
                        }
                        IconButton(onClick = onPlayNext) {
                            Icon(Icons.Default.SkipNext, null, tint = Color.White)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(96.dp))
                    }
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .background(Color(0xFF404040)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.MusicNote, null, tint = Color(0xFF808080), modifier = Modifier.size(24.dp))
                    }
                    Text(
                        "暂无播放",
                        color = Color(0xFF808080),
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 12.dp)
                    )
                }
            }
        }

        // 封面图叠加层
        if (hasSong) {
            val s = song!!
            AsyncImage(
                model = s.album?.picUrl,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        val p = progress.value
                        val normalizedP = ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)
                        val lyricAnimValue = lyricAnimProgress.value

                        val targetCenterX = largeCoverCenterX + lyricAnimValue * (miniCoverCenterX - largeCoverCenterX)
                        val targetCenterY = largeCoverCenterY + lyricAnimValue * (miniCoverCenterY - largeCoverCenterY)
                        val targetScale = miniScale + (1f - lyricAnimValue) * (1f - miniScale)

                        val currentCenterX = miniCoverCenterX + normalizedP * (targetCenterX - miniCoverCenterX)
                        val currentCenterY = miniCoverCenterY + normalizedP * (targetCenterY - miniCoverCenterY)
                        val currentBaseScale = miniScale + normalizedP * (targetScale - miniScale)

                        scaleX = currentBaseScale
                        scaleY = currentBaseScale
                        translationX = currentCenterX - boundsCenter
                        translationY = currentCenterY - boundsCenter
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
                contentScale = ContentScale.Crop
            )
        }

        // 收起按钮叠加层：z 序最高，保证触摸事件不被任何下层元素拦截
        if (hasSong) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .height(56.dp)
                    .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                    .padding(end = 8.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                if (dismissEnabled) {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.KeyboardArrowDown, "收起", tint = Color.White)
                    }
                }
            }
        }
    }
}
