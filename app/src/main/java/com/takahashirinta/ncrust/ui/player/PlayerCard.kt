package com.takahashirinta.ncrust.ui.player

import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.*
import androidx.compose.foundation.MarqueeAnimationMode
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitVerticalTouchSlopOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.runtime.*
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.Coil
import coil.compose.AsyncImagePainter
import coil.compose.rememberAsyncImagePainter
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.CoverUrls
import com.takahashirinta.ncrust.QueueModes
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouTweens
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroIconButton
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import android.widget.Toast

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
    onMoveInQueue: (Int, Int) -> Unit = { _, _ -> },
    onTogglePlayMode: () -> Unit = {},
    onPlayNothing: () -> Unit = {},
    onSongInfoClick: () -> Unit = {},
    onClearQueue: () -> Unit = {},
    onSavePlaylist: () -> Unit = {},
    onNavigateToUser: () -> Unit = {}
) {
    val hasSong = song != null
    // 初始落大封面: 歌词未就绪时(加载中/确无), 全屏默认看封面而非空歌词面板;
    // lyricsReady 到位后由下方的 LaunchedEffect 自动切回歌词视图。
    var showLyrics by remember { mutableStateOf(false) }
    var showQueue by remember { mutableStateOf(false) }
    val density = LocalDensity.current
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    val strings = LocalStrings.current
    val playerViewModel: PlayerViewModel = viewModel()
    val lyrics by playerViewModel.lyrics.collectAsState()
    val translatedLyrics by playerViewModel.translatedLyrics.collectAsState()
    val lyricsLoading by playerViewModel.lyricsLoading.collectAsState()
    val lyricsSongId by playerViewModel.lyricsSongId.collectAsState()
    val showLyricsTranslation by playerViewModel.showLyricsTranslation.collectAsState()
    // 收藏库状态: 当前歌是否已收藏(右下角 加号/对号 切换用)。切歌或操作后刷新。
    var libraryTick by remember { mutableIntStateOf(0) }
    val isSongSaved = remember(song?.id, libraryTick) {
        song?.let { LibraryManager.isSongSaved(context, it.id) } ?: false
    }
    // currentPosition / progress 是 4Hz 更新的 StateFlow，直接传引用给需要的子组件，
    // 让它们在最小作用域（graphicsLayer / Canvas draw / derivedStateOf / 叶子 Text）内订阅，
    // 避免 PlayerCard 本身随位置更新 4Hz 重组
    // duration / qualityIndex / isBuffering downgraded to leaf collect inside FullPlayerControls;
    // subscribing at this scope would force the whole PlayerCard subtree to recompose on song
    // change / buffer flap, dragging in AsyncImage + Column layout for no reason.

    // 宽屏分栏进度：0 = 单栏（封面居中、控件铺满居中），1 = 两栏（左封面+控件 / 右歌词·队列）。
    // 由是否显示歌词/队列驱动；窄屏不读取该值（不触发额外重组）。
    val wideSplit by animateFloatAsState(
        targetValue = if (showLyrics || showQueue) 1f else 0f,
        animationSpec = tween(280, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)),
        label = "widePlayerSplit"
    )

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val screenWidthPx = with(density) { screenWidthDp.toPx() }
    val dp24px = with(density) { 24.dp.toPx() }
    // 宽屏播放器两栏（Apple Music 式）：左封面 / 右歌词·队列。
    val isWidePlayer = LocalConfiguration.current.screenWidthDp >= 600
    // 封面固有尺寸：窄屏=整屏宽（现有行为）；宽屏=左栏内的方图，受左栏宽与高度双重约束，
    // 留出底部控件/歌名的空间（否则封面 overlay 会盖住控件）。
    val coverSizePx = if (isWidePlayer)
        minOf(screenWidthPx * 0.34f, screenHeightPx * 0.46f)
    else screenWidthPx
    val coverSizeDp = with(density) { coverSizePx.toDp() }
    // 迷你条与顶栏按钮的触觉反馈
    val haptic = LocalHapticFeedback.current

    val miniCoverHalfPx = with(density) { 28.dp.toPx() }
    val miniScale = miniCoverHalfPx * 2f / coverSizePx
    val statusBarPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        .let { with(density) { it.toPx() } }
    val miniCoverCenterX = miniCoverHalfPx
    val miniCoverCenterY = statusBarPx + miniCoverHalfPx
    // 宽屏左栏宽度：随 wideSplit 在整宽(单栏)与 44%(两栏)之间过渡。窄屏恒为整宽。
    val wideLeftWidthPx = if (isWidePlayer) screenWidthPx * (1f - 0.56f * wideSplit) else screenWidthPx
    val wideLeftWidthDp = with(density) { wideLeftWidthPx.toDp() }
    val largeCoverCenterX = if (isWidePlayer) wideLeftWidthPx / 2f else screenWidthPx / 2f
    val largeCoverCenterY = if (isWidePlayer) screenHeightPx * 0.32f else screenHeightPx * 0.3f + dp24px
    val boundsCenter = coverSizePx / 2f

    // 完全收起时才激活迷你播放栏；derivedStateOf 将重组限制在阈值穿越处
    val miniBarEnabled by remember { derivedStateOf { progress.value < 0.01f } }
    val miniBarInteractionSource = remember { MutableInteractionSource() }
    // 完全展开时才激活收起按钮
    val dismissEnabled by remember { derivedStateOf { progress.value > 0.99f } }
    // 展开态子树常挂载（无 gate）：LyricsView / QueueView / FullPlayerControls / 大封面
    // 信息在 MainScreen 首次 composition 时即挂载，交互只切 graphicsLayer alpha，不再
    // 走 mount/dispose。这是"Apple Music 手感"的关键——原生 View 系统里 player subview
    // 是 app 启动就构建好、隐藏用 visibility=GONE 的；我们此前用 if (expandedEnough) 条件
    // 挂载来省折叠态 layout 成本，但把构造成本压到了首次跨阈值那一帧，压不进单帧就必然卡。
    // 常挂载的代价：折叠态 LazyColumn 仍走一次 layout（懒渲染，实际每帧 1-3ms），交换
    // 首次 expand 永远不卡。冷启动那一次成本由 Splash + AppWarmup 兜底吸收。

    // lyricAnimProgress：0 = 大封面，1 = 小封面；驱动封面缩放 + 内容淡入淡出
    // queueSlideProgress：0 = 歌词位置，1 = 列表位置；仅 b↔c 时动画，其他时 snap
    // 两个 Animatable 均只在 graphicsLayer { } draw 阶段读取，动画帧内零 recompose
    // 初始 0（大封面）：冷启动/splash 后若当前歌无歌词，首帧即大封面，不会"停"在歌词位
    val lyricAnimProgress = remember { Animatable(0f) }
    val queueSlideProgress = remember { Animatable(0f) }
    // 仅在歌词模式下歌词可交互；阈值穿越处各触发一次重组，其余帧零重组
    val lyricsEnabled by remember { derivedStateOf { lyricAnimProgress.value > 0.5f && queueSlideProgress.value < 0.5f } }
    // 卡片基本展开(>90%)时播放器内容区才可交互。面板常挂载、graphicsLayer 只调 alpha,
    // 折叠态下歌词行/队列行在屏幕底部(迷你条与导航栏之间的缝隙)依然命中测试——
    // 用户"在导航栏底部乱按"会点到不可见的歌词行/队列行, 触发 seek/切歌。
    // 展开阈值和 alpha 淡入阈值(0.7)错开, 保证交互只在内容真正可见后开启。
    val cardExpandedForInput by remember { derivedStateOf { progress.value > 0.9f } }

    // ---- 歌词可达性驱动的「大封面 ↔ 歌词视图」自动切换 ----
    // - 切歌瞬间: 旧歌词已被 ViewModel 清空, 直接落大封面, 绝不残留上一首歌词。
    // - 歌词就绪(lyricsReady): 自动切回歌词视图（Apple Music 语义）。
    // - 歌词未就绪(仍在加载/确无): 保持大封面 + 灰按钮。
    // - 用户在队列视图时不打扰。
    // lyricsReady：歌词**属于当前歌**且非加载中。旧歌词残留(切歌过渡)不算就绪——
    // 由 lyricsSongId == song?.id 保证, 否则无歌词的新歌会被误判就绪显示旧歌词。
    val lyricsReady = lyricsSongId == song?.id && !lyricsLoading && lyrics.isNotEmpty()

    LaunchedEffect(song?.id, lyricsReady, showQueue) {
        when {
            !lyricsReady && !showQueue && showLyrics -> {
                // 切歌后旧歌词已清空: 立即回大封面, 不保留 3s 窗口——
                // 挂着上一首歌词等加载, 用户看整张专辑连播时会一直觉得"还停在旧歌"。
                showLyrics = false
            }
            !lyricsReady && !showQueue -> {
                // 无歌词/加载中且不在歌词视图：大封面 + 灰按钮
                showLyrics = false
            }
            lyricsReady && !showQueue && !showLyrics -> {
                // 歌词就绪 + 用户停在大封面：自动切回歌词视图
                showLyrics = true
            }
            showQueue -> {
                // 用户在队列：不打扰, 放弃自动回切
            }
        }
    }

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

    // 展开动作触发一次歌词定位: 面板常挂载, isVisible 不会翻转, 若不做强制定位,
    // 从 mini bar 拉起后歌词停在旧位置(或用户上次手动滚动的位置)
    var lyricLocateTrigger by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        snapshotFlow { progress.value }
            .distinctUntilChanged { a, b -> (a > 0.9f) == (b > 0.9f) }
            .collect { p ->
                if (p > 0.9f) lyricLocateTrigger++
            }
    }

    // 歌词/队列面板可交互(展开 + 面板在前台)时,面板区域内的纵向手势归内部列表滚动。
    // 根节点的整卡拖拽与兜底消费器都不得抢手势——否则在面板上一滑,整卡被拖走、
    // 列表几乎滚不动(issue #23)。面板外的封面/顶栏/大封面模式仍驱动整卡。
    val topBarBottomPx = statusBarPx + with(density) { 56.dp.toPx() }
    val isPanelInteractive by remember {
        derivedStateOf {
            (lyricsEnabled || queueSlideProgress.value > 0.5f) && progress.value > 0.7f
        }
    }
    fun isOverPanel(y: Float, x: Float) = isPanelInteractive && y > topBarBottomPx &&
        (!isWidePlayer || x > screenWidthPx / 2f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            // Outer modifier → runs last within this node in Main pass (after drag detector below).
            // Consumes remaining events when fully expanded so Scaffold siblings never receive them.
            // 面板区域不吞事件:内部列表需要先拿到未消费的 MOVE 才能滚动。
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Main)
                        if (progress.value > 0.99f) {
                            val pos = event.changes.firstOrNull()?.position
                            if (pos == null || !isOverPanel(pos.y, pos.x)) {
                                event.changes.forEach { it.consume() }
                            }
                        }
                    }
                }
            }
            // Inner modifier → runs first within this node in Main pass.
            // Handles drag-to-collapse; runs before the outer consumer so it sees unconsumed MOVE.
            // 仅在有歌（!hasSong = 暂无播放）时可拖拽；用 hasSong 作 key，来了歌后手势重新激活。
            .pointerInput(hasSong) {
                if (!hasSong) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // 面板内纵向手势完全交给内部 LazyColumn/进度条,根节点不消费任何事件。
                    if (isOverPanel(down.position.y, down.position.x)) return@awaitEachGesture
                    var dragStartProgress = progress.value
                    val dragChange = awaitVerticalTouchSlopOrCancellation(down.id) { change, _ ->
                        dragStartProgress = progress.value
                        change.consume()
                    }
                    if (dragChange != null) {
                        val settled = drag(dragChange.id) { change ->
                            change.consume()
                            val dragAmount = change.positionChange().y
                            coroutineScope.launch {
                                progress.snapTo(
                                    (progress.value - dragAmount / totalDragDistancePx).coerceIn(0f, 1f)
                                )
                            }
                        }
                        if (settled) {
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
                        }
                    }
                }
            }
    ) {
        // 全屏纯黑背景
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 24.dp)
                .background(LocalMetroColors.current.background)
        )
        // 折叠态卡背：卡片整体下移后，miniBar 下方露出的是这张黑底（原底部导航/系统栏
        // 位置）。折叠时用 surface 盖住，与 miniBar 同色，避免底部黑块；展开时透明。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .offset(y = 24.dp)
                .graphicsLayer { alpha = (1f - progress.value * 5f).coerceIn(0f, 1f) }
                .background(LocalMetroColors.current.surface)
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding()
        ) {
            if (hasSong) {
                val s = song!!

                // 顶部标题栏：小封面模式下显示曲名（收起按钮在外层 Box 最高 z 序）。
                // 宽屏两栏时歌名移到左栏封面下方，故此处不渲染。
                if (!isWidePlayer) {
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
                            // 歌名区域可点: 上拉"转到歌手/转到专辑"菜单(类 Apple Music)
                            .clickable { onSongInfoClick() }
                    ) {
                        MetroText(
                            s.name,
                            color = Color.White,
                            style = LocalMetroTypography.current.titleMedium,
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
                        MetroText(
                            s.artists?.joinToString("/") { it.name } ?: "",
                            color = Color.Gray,
                            style = LocalMetroTypography.current.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
                }

                // 内容区外壳。子内容常挂载（无 gate）——见文件头对 expandedEnough 的注释。
                // alpha=0 时 draw 阶段短路，layout 仍走但 LazyColumn/Canvas 都是懒的，运行时开销可控。
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        // 宽屏：歌词/队列落在右栏（左栏宽度随分栏进度变化）。
                        .then(if (isWidePlayer) Modifier.padding(start = wideLeftWidthDp) else Modifier)
                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                ) {
                    // 歌词面板：translationX 从 0 滑至 -screenWidthPx，确保非歌词模式下完全移出屏幕，
                    // 彻底消除与列表面板的命中测试重叠（combinedClickable 忽略 isConsumed 标志）
                    // alpha 用阶梯而非交叉淡化: 切换时源面板瞬时隐藏、目标面板单层全宽滑入,
                    // 每帧只合成一个面板——原先 260ms 内两个全屏面板同时 alpha 混合是
                    // 低端机上左右切换动作的主要 GPU 成本。
                    Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val q = queueSlideProgress.value
                                    alpha = lyricAnimProgress.value * if (q < 0.01f) 1f else 0f
                                    translationX = -q * screenWidthPx
                                }
                        ) {
                            // 切歌时旧歌词渐隐、新歌词渐显（Sokuou UWP 缓动）。
                            // key = song?.id：同一首歌的歌词更新不触发 Crossfade, 只换内容。
                            Crossfade(
                                targetState = song?.id,
                                animationSpec = SokuouTweens.CoverFade,
                                label = "LyricsCrossfade"
                            ) { _ ->
                                LyricsView(
                                    lyrics = lyrics,
                                    translatedLyrics = translatedLyrics,
                                    showTranslation = showLyricsTranslation,
                                    positionFlow = playerViewModel.currentPosition,
                                    isPlaying = isPlaying,
                                    isVisible = showLyrics,
                                    forcedLocateTrigger = lyricLocateTrigger,
                                    onSeekToMs = { ms -> playerViewModel.seekTo(ms) },
                                    enabled = lyricsEnabled && cardExpandedForInput,
                                    onUserScrolled = {},
                                )
                            }
                        }

                        // 列表面板：translationX 从 +screenWidthPx 滑至 0，稳定态时完全在屏幕外
                        // alpha 阶梯同上: q < 0.01 时完全透明, 切换只合成单个面板
                        // 面板标题行高度: 队列自动定位要按"整个队列区域"(含标题行)居中
                        var queueHeaderHeightPx by remember { mutableFloatStateOf(0f) }
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .graphicsLayer {
                                    val q = queueSlideProgress.value
                                    alpha = lyricAnimProgress.value * if (q > 0.01f) 1f else 0f
                                    translationX = (1f - q) * screenWidthPx
                                }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                                    .onGloballyPositioned {
                                        queueHeaderHeightPx = it.size.height.toFloat()
                                    },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MetroText(
                                    strings.queueTitle,
                                    color = Color.White,
                                    style = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold),
                                    modifier = Modifier.weight(1f)
                                )
                                MetroIconButton(onClick = onTogglePlayMode) {
                                    MetroIcon(
                                        imageVector = when (playMode) {
                                            QueueModes.SINGLE -> Icons.Default.RepeatOne
                                            QueueModes.SHUFFLE -> Icons.Default.Shuffle
                                            QueueModes.LINE -> Icons.Default.PlaylistPlay
                                            QueueModes.INFINITY -> Icons.Default.AllInclusive
                                            else -> Icons.Default.Repeat
                                        },
                                        contentDescription = strings.playModeButton,
                                        tint = if (playMode != QueueModes.CYCLE) LocalMetroColors.current.primary else Color.White,
                                        sizeDp = 24.dp
                                    )
                                }
                                MetroIconButton(onClick = onSavePlaylist) {
                                    MetroIcon(
                                        imageVector = Icons.Default.Add,
                                        contentDescription = strings.saveAsPlaylist,
                                        tint = Color.White,
                                        sizeDp = 24.dp
                                    )
                                }
                                // 清空队列: 停播并回到暂无播放态
                                MetroIconButton(onClick = onClearQueue) {
                                    MetroIcon(
                                        imageVector = Icons.Default.DeleteSweep,
                                        contentDescription = strings.clearQueue,
                                        tint = Color.White,
                                        sizeDp = 24.dp
                                    )
                                }
                            }
                            MetroDivider(color = Color(0xFF2A2A2A))
                            QueueView(
                                queue = playbackQueue,
                                currentIndex = currentQueueIndex,
                                playMode = playMode,
                                isActive = showQueue,
                                interactive = cardExpandedForInput,
                                queueHeaderHeightPx = queueHeaderHeightPx,
                                onPlayIndex = onPlayFromQueue,
                                onRemoveIndex = onRemoveFromQueue,
                                onMove = onMoveInQueue
                            )
                        }

                        // 大封面模式下的曲名/歌手信息：overlay 在内容区底部，不占 Column 高度。
                        // 宽屏歌名移到左栏（随封面），此处不渲染。
                        if (!isWidePlayer) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.BottomStart)
                                .fillMaxWidth()
                                .padding(horizontal = 24.dp, vertical = 8.dp)
                                .graphicsLayer {
                                    alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) *
                                            (1f - lyricAnimProgress.value)
                                }
                                // 歌名区域可点: 上拉"转到歌手/转到专辑"菜单(类 Apple Music)
                                .clickable { onSongInfoClick() }
                        ) {
                            MetroText(
                                s.name,
                                color = Color.White,
                                style = LocalMetroTypography.current.titleLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            MetroText(
                                s.artists?.joinToString("/") { it.name } ?: "",
                                color = LocalMetroColors.current.primary,
                                style = LocalMetroTypography.current.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        }
                }

                Spacer(Modifier.height(16.dp))

                // 底部播放控件常挂载（与内容区同理）。FullPlayerControls 内部有 SlimProgressBar
                // Canvas + PositionText collectAsState + 多个 IconButton，成本一次性付在 MainScreen 首帧。
                Box(modifier = Modifier.fillMaxWidth().graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }) {
                    Column(
                        modifier = Modifier
                            .width(wideLeftWidthDp)
                            .align(Alignment.CenterStart)
                    ) {
                        // 宽屏：歌名/歌手置于左栏封面下方（窄屏由内容区底部 overlay 承担）。
                        if (isWidePlayer) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 24.dp)
                                    .clickable { onSongInfoClick() }
                            ) {
                                MetroText(
                                    s.name,
                                    color = Color.White,
                                    style = LocalMetroTypography.current.titleLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                MetroText(
                                    s.artists?.joinToString("/") { it.name } ?: "",
                                    color = LocalMetroColors.current.primary,
                                    style = LocalMetroTypography.current.bodyLarge,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                            Spacer(Modifier.height(12.dp))
                        }
                        FullPlayerControls(
                            isPlaying = isPlaying,
                            showLyrics = showLyrics,
                            showQueue = showQueue,
                            progressFlow = playerViewModel.progress,
                            positionFlow = playerViewModel.currentPosition,
                            durationFlow = playerViewModel.duration,
                            qualityIndexFlow = playerViewModel.currentQualityIndex,
                            qualityOptions = strings.qualityOptions,
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
                                val s = song
                                if (s != null) {
                                    // 已在库 → 移出; 不在库 → 收藏。本地即时生效, 云端异步同步。
                                    if (LibraryManager.isSongSaved(context, s.id)) {
                                        LibraryManager.removeSong(context, s.id)
                                        Toast.makeText(context, strings.removedFromLibrary, Toast.LENGTH_SHORT).show()
                                    } else {
                                        LibraryManager.saveSong(context, s)
                                        Toast.makeText(context, strings.addedToLibrary, Toast.LENGTH_SHORT).show()
                                    }
                                    libraryTick++
                                }
                            },
                            isInLibrary = isSongSaved,
                            isBufferingFlow = playerViewModel.isBuffering,
                            onSeek = { fraction ->
                                val dur = playerViewModel.duration.value
                                if (dur > 0) {
                                    playerViewModel.seekTo((fraction * dur).toLong())
                                }
                            },
                            onNavigateToUser = onNavigateToUser,
                            lyricsUnavailable = !lyricsReady,
                            previousEnabled = playMode != QueueModes.INFINITY
                        )
                    }
                }
            }
        }

        // 迷你播放栏叠加层：始终在 Composition 中，透明度仅在绘制阶段控制，避免动画期间触发重组
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
                .then(
                    // 暂无播放（hasSong=false）时不可点击展开，避免空白播放器被拉起。
                    if (miniBarEnabled && hasSong) Modifier.clickable(
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
                }
                .background(LocalMetroColors.current.surface)
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
                        MetroText(
                            s.name,
                            color = Color.White,
                            style = LocalMetroTypography.current.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        MetroText(
                            s.artists?.joinToString("/") { it.name } ?: "",
                            color = Color.Gray,
                            style = LocalMetroTypography.current.bodySmall,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    if (miniBarEnabled) {
                        MetroIconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayPause()
                        }) {
                            MetroIcon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = null,
                                tint = Color.White
                            )
                        }
                        MetroIconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayNext()
                        }) {
                            MetroIcon(Icons.Default.SkipNext, null, tint = Color.White)
                        }
                    } else {
                        Spacer(modifier = Modifier.width(96.dp))
                    }
                } else {
                    // 暂无播放: 卡片仍不可拉起(保持既有约束), 但给一个播放键
                    // 直接开始 Infinity——取每日推荐开播, 无需先有队列
                    MetroText(
                        strings.noSongPlaying,
                        color = Color(0xFF808080),
                        style = LocalMetroTypography.current.bodyMedium,
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 12.dp)
                    )
                    if (miniBarEnabled) {
                        MetroIconButton(onClick = {
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            onPlayNothing()
                        }) {
                            MetroIcon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = LocalStrings.current.playButton,
                                tint = Color.White
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                    }
                }
            }
        }

        // 封面图叠加层
        if (hasSong) {
            val s = song!!
            StableCover(
                model = CoverUrls.large(s.album?.picUrl),
                contentDescription = null,
                placeholderColor = LocalMetroColors.current.surfaceVariant,
                modifier = Modifier
                    .then(
                        if (isWidePlayer) Modifier.size(coverSizeDp)
                        else Modifier.fillMaxWidth().aspectRatio(1f)
                    )
                    .graphicsLayer {
                        val p = progress.value
                        val normalizedP = ((p - 0.2f) / 0.8f).coerceIn(0f, 1f)
                        // 宽屏：封面恒为大图（缩到 mini 是窄屏"大封面↔歌词"切换的语义），
                        // 歌词/队列改由右栏 alpha 淡入淡出。
                        val lyricAnimValue = if (isWidePlayer) 0f else lyricAnimProgress.value

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
                    MetroIconButton(onClick = onDismiss) {
                        MetroIcon(Icons.Default.KeyboardArrowDown, strings.collapsePlayer, tint = Color.White)
                    }
                }
            }
        }
    }
}

// 新封面超过该阈值仍未就绪，才退化为纯色占位（"实在不出来再禁用"）。
private const val COVER_HOLD_MS = 400L

/**
 * 切歌不闪的封面。Coil 的 [AsyncImagePainter] 在 model 变化时先进入 loading 态、
 * 画 placeholder（纯色），新图没秒出就会闪一下占位色。这里改为：
 *  - 记住最近一次成功加载的封面，切歌换图期间先沿用旧图（视觉无缝）；
 *  - 新图在 [COVER_HOLD_MS] 内就绪 → 直接换上新图；
 *  - 超过阈值仍未就绪 → 才退化为占位色。
 */
@Composable
private fun StableCover(
    model: Any?,
    contentDescription: String?,
    placeholderColor: Color,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    val painter = rememberAsyncImagePainter(
        model = model,
        imageLoader = Coil.imageLoader(context),
    )
    val state = painter.state
    // 最近一次成功加载的封面位图（跨切歌保留）。
    var lastBitmap by remember { mutableStateOf<Bitmap?>(null) }
    // 新图超过阈值仍未就绪 → 退化占位色。
    var timedOut by remember { mutableStateOf(false) }

    LaunchedEffect(state) {
        val s = state
        if (s is AsyncImagePainter.State.Success) {
            (s.result.drawable as? BitmapDrawable)?.bitmap?.let { lastBitmap = it }
            timedOut = false
        }
    }
    LaunchedEffect(model) {
        timedOut = false
        delay(COVER_HOLD_MS)
        if (painter.state !is AsyncImagePainter.State.Success) timedOut = true
    }

    Box(modifier = modifier) {
        // 底层：切歌后旧图垫底（超阈值退化占位色）。
        val bg = if (timedOut) null else lastBitmap
        if (bg != null) {
            Image(
                painter = remember(bg) { BitmapPainter(bg.asImageBitmap()) },
                contentDescription = null,
                modifier = Modifier.matchParentSize(),
                contentScale = contentScale,
            )
        } else {
            Box(Modifier.matchParentSize().background(placeholderColor))
        }
        // 上层：当前请求的 painter。必须真正绘制它，Coil 才会在 onRemembered 里发起
        // 请求；loading 时它不画东西，露出底层旧图/占位色。
        Image(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.matchParentSize(),
            contentScale = contentScale,
        )
    }
}
