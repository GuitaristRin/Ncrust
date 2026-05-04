package com.takahashirinta.ncrust

import android.Manifest
import android.content.Context
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.player.PlaybackStateManager
import com.takahashirinta.ncrust.ui.components.PlayAllDialog
import com.takahashirinta.ncrust.ui.navigation.MainNavGraph
import com.takahashirinta.ncrust.ui.navigation.NavRoutes
import com.takahashirinta.ncrust.ui.player.PlayerCardOverlay
import com.takahashirinta.ncrust.ui.screen.*
import com.takahashirinta.ncrust.ui.theme.NcrustTheme
import com.takahashirinta.ncrust.ui.theme.getSavedThemeIndex
import com.takahashirinta.ncrust.ui.theme.saveThemeIndex
import com.takahashirinta.ncrust.ui.theme.themeColorForIndex
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

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
    var pendingPlayAllSongs by remember { mutableStateOf<List<SongItem>?>(null) }
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

    fun replaceQueueAndPlay(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        playbackQueue = songs
        currentQueueIndex = 0
        if (playMode == 2) generateShuffledIndices()
        playFromQueue(0)
        expandCard()
    }

    fun insertAllNext(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        if (currentQueueIndex < 0) {
            replaceQueueAndPlay(songs)
            return
        }
        val ids = songs.map { it.id }.toSet()
        val filtered = playbackQueue.filter { it.id !in ids }.toMutableList()
        val insertPos = (currentQueueIndex + 1).coerceAtMost(filtered.size)
        filtered.addAll(insertPos, songs)
        playbackQueue = filtered
        if (playMode == 2) generateShuffledIndices()
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

    var showWebLogin by remember { mutableStateOf(false) }
    var cookieRefreshTrigger by remember { mutableIntStateOf(0) }
    if (showWebLogin) {
        Box(modifier = Modifier.fillMaxSize().background(Color.White)) {
            AndroidView(
                factory = { ctx ->
                    android.webkit.WebView(ctx).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.useWideViewPort = true
                        settings.loadWithOverviewMode = true
                        android.webkit.CookieManager.getInstance()
                            .setAcceptThirdPartyCookies(this, true)
                        webViewClient = object : android.webkit.WebViewClient() {
                            override fun onPageFinished(
                                view: android.webkit.WebView, url: String
                            ) {
                                val cookie = android.webkit.CookieManager.getInstance()
                                    .getCookie(url)
                                if (cookie != null && cookie.contains("MUSIC_U=")) {
                                    com.takahashirinta.ncrust.auth.CookieManager
                                        .saveCookie(ctx, cookie)
                                    RetrofitClient.updateCookie(cookie)
                                    showWebLogin = false
                                    cookieRefreshTrigger++
                                }
                            }
                        }
                        android.webkit.CookieManager.getInstance().removeAllCookies(null)
                        loadUrl("https://music.163.com/#/login")
                    }
                },
                modifier = Modifier.fillMaxSize()
            )
            // 关闭按钮：深色半透明底色，确保在白色登录页上可见
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(12.dp)
                    .size(40.dp)
                    .background(Color(0x80000000)),
                contentAlignment = Alignment.Center
            ) {
                IconButton(onClick = { showWebLogin = false }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = "关闭",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
        return
    }

    Box(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Scaffold(containerColor = Color.Transparent) { innerPadding ->
            Box(modifier = Modifier.padding(innerPadding)) {
                MainNavGraph(
                    navController = navController,
                    onSongClick = { playSongItem(it) },
                    onReplaceAndPlay = { replaceQueueAndPlay(it) },
                    onInsertNext = { insertAllNext(it) },
                    startDestination = NavRoutes.HOME
                )

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
                                        if (songs.isNotEmpty()) pendingPlayAllSongs = songs
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
                                if (albumSongs.isNotEmpty()) pendingPlayAllSongs = albumSongs
                            },
                            onPlaylistClick = { pl ->
                                navController.navigate(NavRoutes.playlist(pl.id, pl.name, pl.coverImgUrl))
                            },
                            onPlayPlaylist = { playlistId ->
                                coroutineScope.launch {
                                    try {
                                        val songs = PlaylistApi.getPlaylistDetail(playlistId)
                                        if (songs.isNotEmpty()) pendingPlayAllSongs = songs
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
                            onThemeChange = onThemeChange,
                            onShowWebLogin = { showWebLogin = true },
                            refreshTrigger = cookieRefreshTrigger
                        )
                    }
                }
            }
        }

        pendingPlayAllSongs?.let { songs ->
            PlayAllDialog(
                songCount = songs.size,
                onDismiss = { pendingPlayAllSongs = null },
                onReplaceAndPlay = { replaceQueueAndPlay(songs); pendingPlayAllSongs = null },
                onInsertNext = { insertAllNext(songs); pendingPlayAllSongs = null }
            )
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