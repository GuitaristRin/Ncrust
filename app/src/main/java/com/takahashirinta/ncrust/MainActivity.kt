package com.takahashirinta.ncrust

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.automirrored.filled.PlaylistPlay
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.lyric.LrcLine
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.screen.SongDetailScreen
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import com.takahashirinta.ncrust.ui.viewmodel.SearchViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.takahashirinta.ncrust.network.AlbumSearchItem
import com.takahashirinta.ncrust.network.ArtistSearchItem
import com.takahashirinta.ncrust.ui.screen.AlbumDetailScreen
import com.takahashirinta.ncrust.ui.screen.ArtistDetailScreen
import androidx.compose.foundation.shape.CircleShape
import androidx.activity.compose.BackHandler
import com.takahashirinta.ncrust.library.AlbumInfo
import com.takahashirinta.ncrust.ui.screen.LibraryScreen
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import android.Manifest
import android.os.Build
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.ui.screen.PlaylistDetailScreen
import com.takahashirinta.ncrust.network.PlaylistApi
import androidx.compose.foundation.border
import com.takahashirinta.ncrust.ui.screen.HomeScreen
import androidx.compose.ui.viewinterop.AndroidView
import android.content.Context
import com.takahashirinta.ncrust.player.PlaybackStateManager
import com.takahashirinta.ncrust.ui.screen.AboutScreen
import com.takahashirinta.ncrust.ui.screen.SplashScreen
import com.takahashirinta.ncrust.ui.theme.getSavedThemeIndex
import com.takahashirinta.ncrust.ui.theme.saveThemeIndex
import com.takahashirinta.ncrust.ui.theme.themeColorForIndex
import com.takahashirinta.ncrust.ui.theme.NcrustTheme
import com.takahashirinta.ncrust.ui.theme.ThemeColorSelector
import com.takahashirinta.ncrust.ui.theme.themeColorPresets
import com.takahashirinta.ncrust.ui.components.SongCard
import com.takahashirinta.ncrust.ui.components.SongCardStyle
import com.takahashirinta.ncrust.ui.components.PlayAllCircleButton
import androidx.navigation.compose.rememberNavController
import androidx.navigation.compose.currentBackStackEntryAsState
import com.takahashirinta.ncrust.ui.navigation.MainNavGraph
import com.takahashirinta.ncrust.ui.navigation.NavRoutes

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        RetrofitClient.init(this)
        enableEdgeToEdge()
        setContent {
            var themeIndex by remember {
                mutableIntStateOf(getSavedThemeIndex(this@MainActivity))
            }

            NcrustTheme(primaryColor = themeColorForIndex(themeIndex)) {
                var showSplash by remember { mutableStateOf(true) }
                Box(modifier = Modifier.fillMaxSize()) {
                    MainScreen(
                        themeIndex = themeIndex,
                        onThemeChange = { newIndex ->
                            themeIndex = newIndex
                            saveThemeIndex(this@MainActivity, newIndex)
                        }
                    )
                    if (showSplash) {
                        SplashScreen(onFinished = { showSplash = false })
                    }
                }
            }
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    themeIndex: Int = 0,
    onThemeChange: (Int) -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(1) }
    val playerViewModel: PlayerViewModel = viewModel()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    var currentSong by remember { mutableStateOf<SongItem?>(null) }
    val context = LocalContext.current

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // ---------- 系统栏高度 ----------
    val systemNavBarHeightDp = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val systemNavBarHeightPx = with(density) { systemNavBarHeightDp.toPx() }

    // 卡片相关尺寸
    val navBarHeightPx = with(density) { 56.dp.toPx() }
    val miniBarHeightPx = with(density) { 56.dp.toPx() }
    val statusBarHeightDp = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    // M3 NavigationBar 实际高度为 80dp，navBarHeightPx 使用的是 56dp，差值 24dp 需要一并补偿
    val fullCardExtraOffsetPx = with(density) { (statusBarHeightDp + 24.dp).toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }

    val collapsedOffsetY = screenHeightPx - systemNavBarHeightPx - navBarHeightPx - miniBarHeightPx - fullCardExtraOffsetPx

    val totalDragDistancePx = screenHeightPx * 0.85f

    val navBarHideOffset = with(density) { 132.dp.toPx() }
    // ------------------------------------

    // 自 ViewModel 恢复 currentSong 之状态。
    LaunchedEffect(Unit) {
        val name = playerViewModel.currentSongName.value
        val artist = playerViewModel.currentSongArtist.value
        val artwork = playerViewModel.currentSongArtwork.value
        val songId = playerViewModel.currentSongId.value
        if (name != null && songId != null && songId > 0 && currentSong == null) {
            currentSong = SongItem(
                id = songId,
                name = name,
                artists = if (artist != null) listOf(ArtistItem(name = artist)) else null,
                album = AlbumItem(id = null, name = "", picUrl = artwork),
                duration = null
            )
        }
    }

    val progress = remember { Animatable(0f) }

    var playbackQueue by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var currentQueueIndex by remember { mutableIntStateOf(-1) }
    var songEnded by remember { mutableStateOf(false) }
    var playMode by remember { mutableIntStateOf(0) }
    var shuffledIndices by remember { mutableStateOf<List<Int>>(emptyList()) }
    var shuffledPosition by remember { mutableIntStateOf(0) }

    fun songParams(s: SongItem) = Triple(
        s.name,
        s.artists?.joinToString("/") { it.name } ?: "",
        s.album?.picUrl ?: ""
    )

    fun generateShuffledIndices() {
        if (playbackQueue.isEmpty()) return
        val current = currentQueueIndex.coerceIn(0, playbackQueue.size - 1)
        val allIndices = playbackQueue.indices.filter { it != current }.shuffled()
        shuffledIndices = listOf(current) + allIndices
        shuffledPosition = 0
    }

    // 恢复播放队列。
    LaunchedEffect(Unit) {
        val savedQueue = PlaybackStateManager.getQueue(context)
        if (savedQueue != null && savedQueue.first.isNotEmpty()) {
            playbackQueue = savedQueue.first.toMutableList()
            currentQueueIndex = savedQueue.second.coerceIn(0, playbackQueue.size - 1)
            if (playMode == 2) {
                generateShuffledIndices()
            }
        }
    }

    fun addToQueue(song: SongItem) {
        playbackQueue = playbackQueue.filter { it.id != song.id }
        playbackQueue = playbackQueue + song
        currentQueueIndex = playbackQueue.size - 1
        if (playMode == 2) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    fun removeFromQueue(index: Int) {
        playbackQueue = playbackQueue.toMutableList().also { it.removeAt(index) }
        if (currentQueueIndex >= playbackQueue.size) currentQueueIndex = playbackQueue.size - 1
        if (playbackQueue.isEmpty()) currentQueueIndex = -1
        if (playMode == 2) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    fun playFromQueue(index: Int) {
        if (index in playbackQueue.indices) {
            currentQueueIndex = index
            val song = playbackQueue[index]
            val (title, artist, artwork) = songParams(song)
            playerViewModel.playSong(song.id, title = title, artist = artist, artworkUrl = artwork)
            currentSong = song
            PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
        }
    }

    fun playNext() {
        if (playbackQueue.isEmpty()) return
        when (playMode) {
            1 -> playerViewModel.seekTo(0)
            2 -> {
                if (shuffledIndices.isEmpty() || shuffledPosition >= shuffledIndices.size - 1) {
                    generateShuffledIndices()
                    playFromQueue(shuffledIndices[0])
                    shuffledPosition = 0
                } else {
                    shuffledPosition++
                    playFromQueue(shuffledIndices[shuffledPosition])
                }
            }
            else -> playFromQueue(
                if (currentQueueIndex < playbackQueue.size - 1) currentQueueIndex + 1 else 0
            )
        }
    }

    fun playPrevious() {
        if (playbackQueue.isEmpty()) return
        when (playMode) {
            1 -> playerViewModel.seekTo(0)
            2 -> {
                if (shuffledPosition > 0) {
                    shuffledPosition--
                    playFromQueue(shuffledIndices[shuffledPosition])
                }
            }
            else -> playFromQueue(
                if (currentQueueIndex > 0) currentQueueIndex - 1 else playbackQueue.size - 1
            )
        }
    }

    // 设置播放器回调。
    LaunchedEffect(Unit) {
        playerViewModel.setOnSongPreviousCallback { playPrevious() }
        playerViewModel.setOnSongEndedCallback { songEnded = true }
    }

    // 在 Splash 遮挡期间渲染一帧全展开状态，提前编译 PlayerCard 所有 graphicsLayer 的 GPU Shader。
    // 对应 Apple Music 的 pre-render 策略：展开动画发生前所有图层已被 GPU 处理过至少一次。
    LaunchedEffect(Unit) {
        delay(50L)
        progress.snapTo(1f)
        delay(32L)
        progress.snapTo(0f)
    }

    // 歌曲结束后自动播放下一首。
    LaunchedEffect(songEnded) {
        if (songEnded) {
            songEnded = false
            if (playbackQueue.isNotEmpty()) {
                playNext()
            }
        }
    }

    fun expandCard() {
        coroutineScope.launch { progress.animateTo(1f, tween(250)) }
    }

    fun collapseCard() {
        coroutineScope.launch { progress.animateTo(0f, tween(250)) }
    }

    fun playSongItem(song: SongItem) {
        val (title, artist, artwork) = songParams(song)
        currentSong = song
        addToQueue(song)
        playerViewModel.playSong(song.id, title = title, artist = artist, artworkUrl = artwork)
        expandCard()
    }

    fun insertNext(song: SongItem) {
        playbackQueue = playbackQueue.filter { it.id != song.id }
        val mutable = playbackQueue.toMutableList()
        val insertPos = (currentQueueIndex + 1).coerceAtMost(mutable.size)
        mutable.add(insertPos, song)
        playbackQueue = mutable
        if (currentQueueIndex < 0) currentQueueIndex = 0
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    fun appendToQueue(song: SongItem) {
        playbackQueue = playbackQueue.filter { it.id != song.id }
        playbackQueue = playbackQueue + song
        if (currentQueueIndex < 0) currentQueueIndex = 0
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    // 切换播放模式并初始化随机索引。
    val onTogglePlayMode: () -> Unit = {
        playMode = (playMode + 1) % 3
        if (playMode == 2) generateShuffledIndices()
        else {
            shuffledIndices = emptyList()
            shuffledPosition = 0
        }
    }

    // ============ 导航控制器 ============
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val isInMain = navBackStackEntry?.destination?.route == NavRoutes.HOME

    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(containerColor = Color.Transparent) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                MainNavGraph(
                    navController = navController,
                    onSongClick = { playSongItem(it) },
                    startDestination = NavRoutes.HOME
                )

                // 仅在主页时显示四个标签页内容
                if (isInMain) {
                    when (selectedTab) {
                        0 -> HomeScreen(
                            onSongClick = { playSongItem(it) },
                            onPlaylistClick = { playlistId ->
                                navController.navigate(NavRoutes.playlist(playlistId))
                            },
                            onPlayPlaylist = { playlistId ->
                                coroutineScope.launch {
                                    try {
                                        val songs = PlaylistApi.getPlaylistDetail(playlistId)
                                        for (song in songs) addToQueue(song)
                                        if (songs.isNotEmpty()) {
                                            currentSong = songs.first()
                                            val (title, artist, artwork) = songParams(songs.first())
                                            playerViewModel.playSong(songs.first().id, title = title, artist = artist, artworkUrl = artwork)
                                            expandCard()
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            onPlayDailyAll = { songs ->
                                for (song in songs) addToQueue(song)
                                if (songs.isNotEmpty()) {
                                    currentSong = songs.first()
                                    val (title, artist, artwork) = songParams(songs.first())
                                    playerViewModel.playSong(songs.first().id, title = title, artist = artist, artworkUrl = artwork)
                                    expandCard()
                                }
                            }
                        )

                        1 -> LibraryScreen(
                            onSongClick = { playSongItem(it) },
                            onAlbumClick = { albumId -> navController.navigate(NavRoutes.album(albumId)) },
                            onPlayAlbum = { albumId ->
                                val albumSongs = LibraryManager.getSongsByAlbumId(context, albumId)
                                for (song in albumSongs) addToQueue(song)
                                if (albumSongs.isNotEmpty()) {
                                    currentSong = albumSongs.first()
                                    val (title, artist, artwork) = songParams(albumSongs.first())
                                    playerViewModel.playSong(albumSongs.first().id, title = title, artist = artist, artworkUrl = artwork)
                                    expandCard()
                                }
                            },
                            onPlaylistClick = { pl ->
                                navController.navigate(NavRoutes.playlist(pl.id, pl.name, pl.coverImgUrl))
                            },
                            onPlayPlaylist = { playlistId ->
                                coroutineScope.launch {
                                    try {
                                        val songs = PlaylistApi.getPlaylistDetail(playlistId)
                                        for (song in songs) addToQueue(song)
                                        if (songs.isNotEmpty()) {
                                            currentSong = songs.first()
                                            val (title, artist, artwork) = songParams(songs.first())
                                            playerViewModel.playSong(songs.first().id, title = title, artist = artist, artworkUrl = artwork)
                                            expandCard()
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            onSongInsertNext = { insertNext(it) },
                            onSongAppendToQueue = { appendToQueue(it) }
                        )

                        2 -> SearchScreen(
                            onSongClick = { playSongItem(it) },
                            onAlbumClick = { albumId -> navController.navigate(NavRoutes.album(albumId)) },
                            onArtistClick = { artistId -> navController.navigate(NavRoutes.artist(artistId)) },
                            onInsertNext = { insertNext(it) },
                            onAppendToQueue = { appendToQueue(it) }
                        )

                        3 -> UserScreen(
                            onOpenAbout = { showAbout = true },
                            themeIndex = themeIndex,
                            onThemeChange = onThemeChange
                        )
                    }
                }
            }
        }

        PlayerCardOverlay(
            song = currentSong,
            isPlaying = isPlaying,
            progress = progress,
            collapsedOffsetY = collapsedOffsetY,
            screenHeightPx = screenHeightPx,
            totalDragDistancePx = totalDragDistancePx,
            playbackQueue = playbackQueue,
            currentQueueIndex = currentQueueIndex,
            playMode = playMode,
            onPlayPause = { playerViewModel.togglePlayPause() },
            onDismiss = { collapseCard() },
            onPlayPrevious = { playPrevious() },
            onPlayNext = { playNext() },
            onRemoveFromQueue = { removeFromQueue(it) },
            onPlayFromQueue = { playFromQueue(it) },
            onTogglePlayMode = onTogglePlayMode,
            onSavePlaylist = { /* TODO: 保存歌单 */ }
        )

        NavigationBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .graphicsLayer {
                    translationY = navBarHideOffset * progress.value
                },
            containerColor = MaterialTheme.colorScheme.surface
        ) {
            NavigationBarItem(
                selected = selectedTab == 0,
                onClick = {
                    selectedTab = 0
                    if (!isInMain) navController.popBackStack(NavRoutes.HOME, false)
                },
                icon = { Icon(Icons.Default.Home, "首页") },
                label = { Text("首页") }
            )
            NavigationBarItem(
                selected = selectedTab == 1,
                onClick = {
                    selectedTab = 1
                    if (!isInMain) navController.popBackStack(NavRoutes.HOME, false)
                },
                icon = { Icon(Icons.Default.LibraryMusic, "库") },
                label = { Text("库") }
            )
            NavigationBarItem(
                selected = selectedTab == 2,
                onClick = {
                    selectedTab = 2
                    if (!isInMain) navController.popBackStack(NavRoutes.HOME, false)
                },
                icon = { Icon(Icons.Default.Search, "搜索") },
                label = { Text("搜索") }
            )
            NavigationBarItem(
                selected = selectedTab == 3,
                onClick = {
                    selectedTab = 3
                    if (!isInMain) navController.popBackStack(NavRoutes.HOME, false)
                },
                icon = { Icon(Icons.Default.Person, "用户") },
                label = { Text("用户") }
            )
        }
    }
}

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
    onSavePlaylist: () -> Unit = {}
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

    val screenWidthDp = LocalConfiguration.current.screenWidthDp.dp
    val screenWidthPx = with(density) { screenWidthDp.toPx() }
    val dp24px = with(density) { 24.dp.toPx() }

    val miniCoverHalfPx = with(density) { 28.dp.toPx() }
    val miniScale = miniCoverHalfPx * 2f / screenWidthPx
    val statusBarPx = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        .let { with(density) { it.toPx() } }
    // 封面各状态的中心点坐标（相对于播放器卡片顶部）
    val miniCoverCenterX = miniCoverHalfPx
    val miniCoverCenterY = statusBarPx + miniCoverHalfPx
    val largeCoverCenterX = screenWidthPx / 2f
    val largeCoverCenterY = screenHeightPx * 0.3f + dp24px
    val boundsCenter = screenWidthPx / 2f

    val lyricAnimProgress = remember { Animatable(1f) }
    LaunchedEffect(showLyrics, showQueue) {
        val target = if (showLyrics || showQueue) 1f else 0f
        // 收起到小封面：快出慢进，短促有力；展开到大封面：强减速收尾，扎实落定
        lyricAnimProgress.animateTo(
            targetValue = target,
            animationSpec = if (target == 1f)
                tween(durationMillis = 190, easing = FastOutSlowInEasing)
            else
                tween(durationMillis = 300, easing = LinearOutSlowInEasing)
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectVerticalDragGestures(
                    onDragEnd = {
                        coroutineScope.launch {
                            progress.animateTo(
                                if (progress.value > 0.25f) 1f else 0f,
                                tween(durationMillis = 260, easing = FastOutSlowInEasing)
                            )
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        coroutineScope.launch {
                            progress.snapTo(
                                (progress.value - dragAmount / totalDragDistancePx)
                                    .coerceIn(0f, 1f)
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

                // 顶部标题栏
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                        .padding(start = 68.dp, end = 8.dp),
                    contentAlignment = Alignment.CenterStart
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .graphicsLayer { alpha = lyricAnimProgress.value }
                        ) {
                            Text(
                                s.name,
                                color = Color.White,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                s.artists?.joinToString("/") { it.name } ?: "",
                                color = Color.Gray,
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(
                                Icons.Default.KeyboardArrowDown,
                                "收起",
                                tint = Color.White
                            )
                        }
                    }
                }

                // 歌词或队列区域
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                ) {
                    if (showLyrics) {
                        LyricsView(
                            lyrics = lyrics,
                            currentPositionMs = currentPosition,
                            onUserScrolled = {}
                        )
                    } else if (showQueue) {
                        Column(modifier = Modifier.fillMaxSize()) {
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
                    }
                }

                // 大封面模式下的歌曲信息
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .graphicsLayer { alpha = ((progress.value - 0.7f) / 0.3f).coerceIn(0f, 1f) }
                ) {
                    if (!showLyrics && !showQueue) {
                        Column {
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
                        }
                    )
                }
            }
        }

        // 迷你播放栏叠加层：始终保留在 Composition 中，透明度仅在绘制阶段控制，避免动画期间触发重组
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .height(56.dp)
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
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(1f)
                            .background(Color(0xFF404040)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.MusicNote,
                            null,
                            tint = Color(0xFF808080),
                            modifier = Modifier.size(24.dp)
                        )
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

                        // 根据歌词状态插值出全屏后封面的目标中心
                        val targetCenterX = largeCoverCenterX + lyricAnimValue * (miniCoverCenterX - largeCoverCenterX)
                        val targetCenterY = largeCoverCenterY + lyricAnimValue * (miniCoverCenterY - largeCoverCenterY)
                        val targetScale = miniScale + (1f - lyricAnimValue) * (1f - miniScale)

                        // 当前帧：沿拉起手势从迷你封面中心插值到目标中心
                        val currentCenterX = miniCoverCenterX + normalizedP * (targetCenterX - miniCoverCenterX)
                        val currentCenterY = miniCoverCenterY + normalizedP * (targetCenterY - miniCoverCenterY)
                        val currentBaseScale = miniScale + normalizedP * (targetScale - miniScale)

                        scaleX = currentBaseScale
                        scaleY = currentBaseScale
                        // 以封面中心（boundsCenter）为变换原点，平移到目标中心
                        translationX = currentCenterX - boundsCenter
                        translationY = currentCenterY - boundsCenter
                        transformOrigin = TransformOrigin(0.5f, 0.5f)
                    },
                contentScale = ContentScale.Crop
            )
        }
    }
}

@Composable
private fun FullPlayerControls(
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

@Composable
fun SlimProgressBar(progress: Float, onSeek: (Float) -> Unit) {
    var barWidth by remember { mutableFloatStateOf(0f) }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .height(32.dp)
            .onSizeChanged { barWidth = it.width.toFloat() }
            .pointerInput(Unit) {
                detectHorizontalDragGestures { change, _ ->
                    change.consume()
                    val newProgress = (change.position.x / barWidth).coerceIn(0f, 1f)
                    onSeek(newProgress)
                }
            }
            .pointerInput(Unit) {
                detectTapGestures {
                    val newProgress = (it.x / barWidth).coerceIn(0f, 1f)
                    onSeek(newProgress)
                }
            },
        contentAlignment = Alignment.CenterStart
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0xFF404040))
        )
        Box(
            Modifier
                .fillMaxWidth(fraction = progress)
                .height(2.dp)
                .background(MaterialTheme.colorScheme.primary)
        )
    }
}

@Composable
fun LyricsView(lyrics: List<LrcLine>, currentPositionMs: Long, onUserScrolled: () -> Unit) {
    if (lyrics.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("暂无歌词", color = Color.Gray, fontSize = 18.sp)
        }
        return
    }

    var currentIndex = -1
    for (i in lyrics.indices) {
        if (lyrics[i].timeMs <= currentPositionMs) currentIndex = i else break
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var userScrolling by remember { mutableStateOf(false) }
    var lastAutoScrolledIndex by remember { mutableIntStateOf(-1) }

    val lyricsKey = remember(lyrics) { lyrics.hashCode() }
    LaunchedEffect(lyricsKey) {
        userScrolling = false
        lastAutoScrolledIndex = -1
        if (lyrics.isNotEmpty()) {
            listState.scrollToItem(0)
        }
    }

    // 自动滚动至当前歌词行。
    LaunchedEffect(currentIndex) {
        if (!userScrolling && currentIndex >= 0 && currentIndex != lastAutoScrolledIndex) {
            lastAutoScrolledIndex = currentIndex
            val vh = listState.layoutInfo.viewportSize.height
            val targetPx = with(density) { (vh.toDp() * 0.45f).toPx() }.toInt()
            listState.animateScrollToItem(
                currentIndex.coerceIn(0, lyrics.size - 1),
                -targetPx + 28
            )
        }
    }

    // 检测用户手动滚动，停止自动滚动三秒后恢复。
    LaunchedEffect(listState.isScrollInProgress) {
        if (listState.isScrollInProgress) {
            if (!userScrolling) {
                userScrolling = true
                onUserScrolled()
            }
        } else if (userScrolling) {
            delay(3000)
            userScrolling = false
            lastAutoScrolledIndex = -1
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.Center
        ) {
            itemsIndexed(lyrics) { index, line ->
                Text(
                    text = line.text,
                    color = when {
                        index < currentIndex -> Color.White.copy(alpha = 0.6f)
                        index == currentIndex -> MaterialTheme.colorScheme.primary
                        else -> Color.Gray.copy(alpha = 0.4f)
                    },
                    fontSize = 32.sp,
                    fontWeight = if (index == currentIndex) FontWeight.ExtraBold else FontWeight.Bold,
                    softWrap = true,
                    lineHeight = 42.sp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                )
            }
        }

        // 顶部渐变遮罩。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.background, Color.Transparent)
                    )
                )
        )

        // 底部渐变遮罩。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                    )
                )
        )
    }
}

@Composable
fun QueueView(
    queue: List<SongItem>,
    currentIndex: Int,
    onPlayIndex: (Int) -> Unit,
    onRemoveIndex: (Int) -> Unit
) {
    if (queue.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("播放队列为空", color = Color.Gray, fontSize = 16.sp)
        }
        return
    }
    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
            itemsIndexed(queue) { index, song ->
                SongCard(
                    song = song,
                    style = SongCardStyle.COMPACT,
                    onClick = { onPlayIndex(index) },
                    isCurrentPlaying = index == currentIndex,
                    actions = {
                        IconButton(onClick = { onRemoveIndex(index) }) {
                            Icon(
                                Icons.Default.Close,
                                "移除",
                                tint = Color.Gray,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }
                )
            }
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(MaterialTheme.colorScheme.background, Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(60.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, MaterialTheme.colorScheme.background)
                    )
                )
        )
    }
}




@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onSongClick: (SongItem) -> Unit,
    onAlbumClick: (Long) -> Unit,
    onArtistClick: (Long) -> Unit,
    onInsertNext: (SongItem) -> Unit = {},
    onAppendToQueue: (SongItem) -> Unit = {}
) {
    val viewModel: SearchViewModel = viewModel()
    val query by viewModel.query.collectAsState()
    val songs by viewModel.songs.collectAsState()
    val albums by viewModel.albums.collectAsState()
    val artists by viewModel.artists.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()
    val currentType by viewModel.currentType.collectAsState()
    val context = LocalContext.current
    val categories = listOf("单曲", "专辑", "艺人")

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        OutlinedTextField(
            value = query,
            onValueChange = { viewModel.onQueryChanged(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            label = { Text("搜索歌曲、专辑、艺人") },
            singleLine = true,
            keyboardActions = KeyboardActions(onDone = {}),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            trailingIcon = {
                Row {
                    if (isLoading) CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.primary
                    )
                    if (query.isNotEmpty()) IconButton(onClick = { viewModel.clearQuery() }) {
                        Icon(Icons.Default.Clear, "清空", tint = Color.Gray)
                    }
                }
            },
            colors = OutlinedTextFieldDefaults.colors(
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.Gray,
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        TabRow(
            selectedTabIndex = when (currentType) {
                1 -> 0
                10 -> 1
                100 -> 2
                else -> 0
            },
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.primary
        ) {
            categories.forEachIndexed { index, title ->
                Tab(
                    selected = when {
                        index == 0 -> currentType == 1
                        index == 1 -> currentType == 10
                        index == 2 -> currentType == 100
                        else -> false
                    },
                    onClick = {
                        viewModel.onTypeChanged(
                            when (index) {
                                0 -> 1
                                1 -> 10
                                2 -> 100
                                else -> 1
                            }
                        )
                    },
                    text = {
                        Text(
                            title,
                            color = when {
                                index == 0 && currentType == 1 -> MaterialTheme.colorScheme.primary
                                index == 1 && currentType == 10 -> MaterialTheme.colorScheme.primary
                                index == 2 && currentType == 100 -> MaterialTheme.colorScheme.primary
                                else -> Color.Gray
                            },
                            fontSize = 14.sp
                        )
                    }
                )
            }
        }

        error?.let {
            Text(
                it,
                color = Color.Red,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        when (currentType) {
            1 -> {
                if (songs.isEmpty() && !isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("搜索歌曲", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(songs, key = { it.id }) { item ->
                            SongSearchItem(
                                song = item,
                                onPlay = { onSongClick(item) },
                                onAddToLibrary = { LibraryManager.saveSong(context, item) },
                                onInsertNext = { onInsertNext(item) },
                                onAppendToQueue = { onAppendToQueue(item) }
                            )
                        }
                    }
                }
            }

            10 -> {
                if (albums.isEmpty() && !isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("搜索专辑", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(albums, key = { it.id }) { album ->
                            AlbumSearchItem(
                                album = album,
                                onClick = { onAlbumClick(album.id) }
                            )
                        }
                    }
                }
            }

            100 -> {
                if (artists.isEmpty() && !isLoading) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("搜索艺人", color = Color.Gray, fontSize = 16.sp)
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(artists, key = { it.id }) { artist ->
                            ArtistSearchItem(
                                artist = artist,
                                onClick = { onArtistClick(artist.id) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SongSearchItem(
    song: SongItem,
    onPlay: () -> Unit,
    onAddToLibrary: () -> Unit,
    onInsertNext: () -> Unit = {},
    onAppendToQueue: () -> Unit = {}
) {
    SongCard(
        song = song,
        style = SongCardStyle.LIST,
        coverSize = 56.dp,
        onClick = onPlay,
        actions = {
            IconButton(onClick = onAddToLibrary) {
                Icon(
                    Icons.Default.Add,
                    "加入库",
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
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
        }
    )
}

@Composable
fun UserScreen(
    onOpenAbout: () -> Unit = {},
    themeIndex: Int = 0,
    onThemeChange: (Int) -> Unit = {}
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var cookieText by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var showWebLogin by remember { mutableStateOf(false) }
    var cookieInfo by remember { mutableStateOf(CookieManager.getCookieInfo(context)) }

    var userProfile by remember { mutableStateOf<PlaylistApi.UserProfile?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("ncrust_settings", 0) }
    var wifiQuality by remember { mutableIntStateOf(prefs.getInt("wifi_quality", 2)) }
    var mobileQuality by remember { mutableIntStateOf(prefs.getInt("mobile_quality", 1)) }
    val qualityOptions = listOf("压缩", "无损", "高解析")

    fun loadProfile() {
        if (!CookieManager.hasCookie(context)) {
            userProfile = null
            return
        }
        coroutineScope.launch {
            isLoadingProfile = true
            try {
                userProfile = PlaylistApi.getUserProfile()
            } catch (_: Exception) {
                userProfile = null
            } finally {
                isLoadingProfile = false
            }
        }
    }

    // 尝试自 WebView 中提取并保存 Cookie。
    fun tryExtractCookie(cookieStr: String?, ctx: Context) {
        if (cookieStr != null && cookieStr.contains("MUSIC_U=")) {
            CookieManager.saveCookie(ctx, cookieStr)
            RetrofitClient.updateCookie(cookieStr)
            cookieInfo = CookieManager.getCookieInfo(ctx)
            showWebLogin = false
            loadProfile()
        }
    }

    LaunchedEffect(Unit) { loadProfile() }

    if (showWebLogin) {
        Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        android.webkit.CookieManager.getInstance()
                            .setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : android.webkit.WebViewClient() {
                            // 页面加载完成时尝试提取 Cookie。
                            override fun onPageFinished(
                                view: android.webkit.WebView,
                                url: String
                            ) {
                                val cookie = android.webkit.CookieManager.getInstance()
                                    .getCookie(url)
                                tryExtractCookie(cookie, ctx)
                            }
                        }

                        android.webkit.CookieManager.getInstance()
                            .removeAllCookies(null)
                        loadUrl("https://music.163.com/#/login")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            IconButton(
                onClick = { showWebLogin = false },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp)
                    .statusBarsPadding()
            ) {
                Icon(
                    Icons.Default.Close,
                    "关闭",
                    tint = Color.White,
                    modifier = Modifier.size(28.dp)
                )
            }
        }
        return
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("登录网易云音乐", color = Color.White) },
            text = {
                Column {
                    Button(
                        onClick = {
                            showDialog = false
                            showWebLogin = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("浏览器登录（推荐）")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("或手动粘贴 Cookie", color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cookieText,
                        onValueChange = { cookieText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Cookie") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.Gray,
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        ),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                if (cookieText.isNotBlank()) {
                    TextButton(onClick = {
                        CookieManager.saveCookie(context, cookieText)
                        RetrofitClient.updateCookie(cookieText)
                        cookieInfo = CookieManager.getCookieInfo(context)
                        cookieText = ""
                        showDialog = false
                        loadProfile()
                    }) {
                        Text("保存 Cookie", color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    cookieText = ""
                    showDialog = false
                }) {
                    Text("取消", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF282828)
        )
    }

    if (showAccountDialog) {
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text("账户管理", color = Color.White) },
            text = {
                Column {
                    if (userProfile != null) {
                        Text("昵称: ${userProfile!!.nickname}", color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        Text("UID: ${userProfile!!.userId}", color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CookieManager.clearCookie(context)
                    RetrofitClient.updateCookie(null)
                    cookieInfo = CookieManager.getCookieInfo(context)
                    userProfile = null
                    showAccountDialog = false
                }) {
                    Text("退出登录", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccountDialog = false }) {
                    Text("关闭", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF282828)
        )
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        // [用户信息行] 保持原有逻辑不动
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (cookieInfo.hasCookie) showAccountDialog = true
                    else showDialog = true
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFF404040)),
                contentAlignment = Alignment.Center
            ) {
                if (userProfile?.avatarUrl?.isNotEmpty() == true) {
                    AsyncImage(
                        model = userProfile!!.avatarUrl,
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.Person,
                        "用户",
                        tint = Color.Gray,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (isLoadingProfile) {
                    Text(
                        "加载中...",
                        color = Color.Gray,
                        style = MaterialTheme.typography.titleLarge
                    )
                } else if (userProfile != null) {
                    Text(
                        userProfile!!.nickname,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "UID: ${userProfile!!.userId}",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "未登录",
                        color = Color.Gray,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "点击登录或设置 Cookie",
                        color = Color.Gray.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFF2A2A2A))
        Spacer(Modifier.height(16.dp))

        if (cookieInfo.hasCookie) {
            OutlinedButton(
                onClick = {
                    CookieManager.clearCookie(context)
                    RetrofitClient.updateCookie(null)
                    cookieInfo = CookieManager.getCookieInfo(context)
                    userProfile = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("退出登录", color = Color.Red)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    cookieText = CookieManager.getCookie(context) ?: ""
                    showDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("更新 Cookie", color = Color.White)
            }
        }

        Spacer(Modifier.height(24.dp))

        // ========== 音质偏好（原有逻辑不变） ==========
        Text(
            "音质偏好",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(12.dp))

        QualitySelector(
            label = "WLAN 环境下",
            selected = wifiQuality,
            options = qualityOptions,
            onSelect = { wifiQuality = it.apply { prefs.edit().putInt("wifi_quality", it).apply() } }
        )
        Spacer(Modifier.height(8.dp))
        QualitySelector(
            label = "移动数据环境下",
            selected = mobileQuality,
            options = qualityOptions,
            onSelect = {
                mobileQuality = it.apply {
                    prefs.edit().putInt("mobile_quality", it).apply()
                }
            }
        )

        // ========== 主题色选择器（新增） ==========
        Spacer(Modifier.height(24.dp))
        Text(
            "主题色",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(12.dp))
        ThemeColorSelector(
            selectedIndex = themeIndex,
            presets = themeColorPresets,
            onSelect = onThemeChange
        )

        Spacer(Modifier.height(32.dp))

        TextButton(onClick = onOpenAbout) {
            Text("关于 Ncrust", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
        }
    }
}

@Composable
fun QualitySelector(
    label: String,
    selected: Int,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    Column {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            options.forEachIndexed { index, name ->
                val isSelected = selected == index
                Box(
                    modifier = Modifier
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(
                                alpha = 0.4f
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                        )
                        .clickable { onSelect(index) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        name,
                        fontSize = 13.sp,
                        color = if (isSelected) Color.Black else Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun AlbumSearchItem(album: AlbumSearchItem, onClick: () -> Unit) {
    val publishYear = album.publishTime?.let {
        java.text.SimpleDateFormat("yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(it))
    } ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 8.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AsyncImage(
            model = album.picUrl,
            contentDescription = "专辑封面",
            modifier = Modifier.size(56.dp),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                album.name,
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                "${album.artist?.name ?: "未知歌手"}${
                    if (publishYear.isNotEmpty()) " · $publishYear" else ""
                }${album.company?.let { " · $it" } ?: ""}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        album.size?.let {
            Text(
                "${it}首",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
fun ArtistSearchItem(artist: ArtistSearchItem, onClick: () -> Unit) {
    val aliasStr = artist.alias?.joinToString(" / ") ?: ""
    val transStr = artist.trans ?: ""

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(56.dp)) {
            AsyncImage(
                model = artist.picUrl,
                contentDescription = "艺人头像",
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF2A2A2A), CircleShape),
                contentScale = ContentScale.Crop
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    artist.name,
                    color = Color.White,
                    style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (transStr.isNotEmpty()) {
                    Text(
                        " · $transStr",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (aliasStr.isNotEmpty()) {
                Text(
                    aliasStr,
                    color = Color.Gray,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Text(
                "专辑: ${artist.albumSize ?: 0} · 单曲: ${artist.musicSize ?: 0}",
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}