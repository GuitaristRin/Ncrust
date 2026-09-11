package com.takahashirinta.ncrust
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors

import android.Manifest
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.runtime.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.takahashirinta.ncrust.library.LibraryManager
import com.takahashirinta.ncrust.cache.ContentCache
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.network.model.AlbumItem
import com.takahashirinta.ncrust.network.model.ArtistItem
import com.takahashirinta.ncrust.player.PlaybackStateManager
import com.takahashirinta.ncrust.power.BackgroundActivity
import com.takahashirinta.ncrust.ui.components.BackgroundActivityDialog
import com.takahashirinta.ncrust.ui.components.PlayAllDialog
import io.github.takahashirinta.kanesumi.structure.bottomnav.MetroBottomNav
import io.github.takahashirinta.kanesumi.structure.bottomnav.MetroBottomNavItem
import io.github.takahashirinta.kanesumi.structure.sidebar.MetroSidebar
import io.github.takahashirinta.kanesumi.structure.sidebar.MetroSidebarItem
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.components.SongMenuSheet
import com.takahashirinta.ncrust.ui.components.TopScrimIconButton
import com.takahashirinta.ncrust.ui.navigation.MainNavGraph
import com.takahashirinta.ncrust.ui.navigation.NavRoutes
import com.takahashirinta.ncrust.ui.player.PlayerCardOverlay
import com.takahashirinta.ncrust.ui.screen.*
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.saveLanguageCode
import com.takahashirinta.ncrust.ui.i18n.stringsForCode
import com.takahashirinta.ncrust.ui.theme.NcrustTheme
import com.takahashirinta.ncrust.ui.theme.ThemeMode
import com.takahashirinta.ncrust.ui.theme.getSavedThemeIndex
import com.takahashirinta.ncrust.ui.theme.getSavedThemeMode
import com.takahashirinta.ncrust.ui.theme.saveThemeIndex
import com.takahashirinta.ncrust.ui.theme.saveThemeMode
import com.takahashirinta.ncrust.ui.theme.themeColorForIndex
import com.takahashirinta.ncrust.ui.theme.toMetroColors
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import io.github.takahashirinta.kanesumi.anim.sokuou.metroViewConfiguration
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroTheme
import com.takahashirinta.ncrust.warmup.AppWarmup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 手机锁竖屏、大屏(平板/折叠展开/车机)放开方向。见 applyOrientationPolicy。
        applyOrientationPolicy()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
        RetrofitClient.init(this)
        // 冷启动即刻并发启动预热：Home 三条网络 + 封面 Coil 预取。
        // splash 期间跑完，进入主页时 ContentCache 已就位，无 loader 闪烁。
        AppWarmup.start(this)
        enableEdgeToEdge()
        setContent {
            var themeIndex by remember {
                mutableIntStateOf(getSavedThemeIndex(this@MainActivity))
            }
            var themeMode by remember {
                mutableStateOf(getSavedThemeMode(this@MainActivity))
            }
            var languageCode by remember {
                mutableStateOf(getSavedLanguageCode(this@MainActivity))
            }
            val isDark = when (themeMode) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ThemeMode.DARK -> true
                ThemeMode.LIGHT -> false
            }

            val baseViewConfig = LocalViewConfiguration.current
            val metroConfig = remember(baseViewConfig) { metroViewConfiguration(baseViewConfig) }
            CompositionLocalProvider(
                LocalStrings provides stringsForCode(languageCode),
                // Metro 起始阈值：touchSlop ≈8dp → ≈12dp。按下时的手指微抖不会立即滚动，
                // 消除"神经质"输入印象。配合 MetroFlingBehavior 覆盖 fling 阶段。
                LocalViewConfiguration provides metroConfig,
            ) {
                NcrustTheme(primaryColor = themeColorForIndex(themeIndex), isDark = isDark) {
                    // Kanesumi Metro* 组件读 LocalMetroColors / LocalMetroTypography,
                    // 并通过 MetroTheme 注入的 LocalIndication -> MetroIndication 拿到直角
                    // 闪切反馈。这里从 NcrustColors 派生 MetroColors,让两套主题源共享同一
                    // 组配色 -- Ncrust* 与 Metro* 组件可以并存,视觉一致。
                    val ncrust = LocalNcrustColors.current
                    val metroColors = remember(ncrust, isDark) { ncrust.toMetroColors(isDark) }
                    MetroTheme(colors = metroColors) {
                    var showSplash by remember { mutableStateOf(true) }
                    Box(modifier = Modifier.fillMaxSize()) {
                        MainScreen(
                            themeIndex = themeIndex,
                            onThemeChange = { newIndex ->
                                themeIndex = newIndex
                                saveThemeIndex(this@MainActivity, newIndex)
                            },
                            themeMode = themeMode,
                            onThemeModeChange = { newMode ->
                                themeMode = newMode
                                saveThemeMode(this@MainActivity, newMode)
                            },
                            onLanguageChange = { newCode ->
                                saveLanguageCode(this@MainActivity, newCode)
                                languageCode = newCode
                                showSplash = true
                            }
                        )
                        if (showSplash) {
                            SplashScreen(onFinished = { showSplash = false })
                        }
                    }

                    // 首次启动（splash 结束后）申请后台活动白名单：ColorOS/MIUI 等
                    // 会在熄屏后清理后台，导致播放服务被杀、重进又走一遍 splash。
                    var showBatteryPrompt by remember { mutableStateOf(false) }
                    val batteryPrefs = remember {
                        getSharedPreferences("ncrust_settings", 0)
                    }
                    LaunchedEffect(showSplash) {
                        if (!showSplash &&
                            !batteryPrefs.getBoolean("battery_prompt_done", false) &&
                            !BackgroundActivity.isUnrestricted(this@MainActivity)
                        ) {
                            showBatteryPrompt = true
                        }
                    }
                    if (showBatteryPrompt) {
                        BackgroundActivityDialog(
                            onAllow = {
                                batteryPrefs.edit().putBoolean("battery_prompt_done", true).apply()
                                showBatteryPrompt = false
                                runCatching {
                                    startActivity(BackgroundActivity.requestIntent(this@MainActivity))
                                }.onFailure {
                                    runCatching {
                                        startActivity(BackgroundActivity.appDetailsIntent(this@MainActivity))
                                    }
                                }
                            },
                            onLater = {
                                batteryPrefs.edit().putBoolean("battery_prompt_done", true).apply()
                                showBatteryPrompt = false
                            }
                        )
                    }
                    }  // MetroTheme
                }
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        // 折叠展开/合拢会改变 smallestScreenWidthDp，需重新判定手机/大屏。
        applyOrientationPolicy()
    }

    /**
     * 方向策略：以与方向无关的 smallestScreenWidthDp 判定形态。
     *  - 手机（< 600dp）：锁竖屏，行为与旧版一致；
     *  - 大屏（平板 / 折叠展开 / 车机，>= 600dp）：不限制方向，避免大屏信箱模式黑边。
     *
     * 用 smallestScreenWidthDp 而非当前宽度：手机横屏时当前宽度可能 >= 600dp，
     * 会误判成大屏。该值随折叠形态变化，故在 onConfigurationChanged 里重跑。
     */
    private fun applyOrientationPolicy() {
        val smallestWidthDp = resources.configuration.smallestScreenWidthDp
        requestedOrientation = if (smallestWidthDp < 600) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

/** 播放模式：0=顺序循环, 1=单曲循环, 2=乱序, 3=顺序线性(播完停), 4=相似无限(FM 电台)。 */
object QueueModes {
    const val CYCLE = 0
    const val SINGLE = 1
    const val SHUFFLE = 2
    const val LINE = 3
    const val INFINITY = 4
}

@Composable
fun MainScreen(
    themeIndex: Int = 0,
    onThemeChange: (Int) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onLanguageChange: (String) -> Unit = {}
) {
    var selectedTab by remember { mutableIntStateOf(1) }
    val playerViewModel: PlayerViewModel = viewModel()
    val isPlaying by playerViewModel.isPlaying.collectAsState()
    var currentSong by remember { mutableStateOf<SongItem?>(null) }
    val vmCurrentSongId by playerViewModel.currentSongId.collectAsState()
    val context = LocalContext.current

    val density = LocalDensity.current
    val coroutineScope = rememberCoroutineScope()

    // ---------- 系统栏高度 ----------
    // 正常设备从 WindowInsets 取；车机（Android Automotive）的 CarSystemUI 顶/底栏是
    // 独立窗口、不下发 WindowInsets（实测全为 0），此时回退到系统资源
    // status_bar_height / navigation_bar_height，并在根节点用 padding 让出内容区。
    val sysStatusPx = WindowInsets.statusBars.getTop(density).toFloat()
    val sysNavPx = WindowInsets.navigationBars.getBottom(density).toFloat()
    val resStatusPx = context.resources.getIdentifier("status_bar_height", "dimen", "android")
        .let { if (it > 0) context.resources.getDimensionPixelSize(it).toFloat() else 0f }
    val resNavPx = context.resources.getIdentifier("navigation_bar_height", "dimen", "android")
        .let { if (it > 0) context.resources.getDimensionPixelSize(it).toFloat() else 0f }
    val fallbackStatusPx = if (sysStatusPx == 0f) resStatusPx else 0f
    val fallbackNavPx = if (sysNavPx == 0f) resNavPx else 0f

    // 宽屏（平板/折叠展开/车机）：左侧常驻 sidebar 取代底部导航，内容右移。
    // 与方向无关地按当前窗口宽度判定；窄屏(<600dp)完全走原底部导航路径。
    val windowWidthDp = LocalConfiguration.current.screenWidthDp
    val isWideLayout = windowWidthDp >= 600
    val sidebarWidthDp = 200.dp
    val sidebarWidthPx = with(density) { sidebarWidthDp.toPx() }

    // 卡片相关尺寸。宽屏无底部导航, navBar 高度记 0, miniBar 直接贴到系统栏之上。
    val navBarHeightPx = if (isWideLayout) 0f else with(density) { 56.dp.toPx() }
    val miniBarHeightPx = with(density) { 56.dp.toPx() }
    val screenHeightPx = with(density) { LocalConfiguration.current.screenHeightDp.dp.toPx() }
    // 内容区高度：扣掉系统栏（WindowInsets 或资源兜底）。
    val contentHeightPx =
        screenHeightPx - sysStatusPx - sysNavPx - fallbackStatusPx - fallbackNavPx

    // miniBar 底 = navBar 顶。系统栏已由根节点 padding（车机兜底）或 WindowInsets
    // （正常设备）让出，这里只需把 miniBar 顶到内容区底部之上。
    val collapsedOffsetY = contentHeightPx - navBarHeightPx - miniBarHeightPx

    val totalDragDistancePx = contentHeightPx * 0.85f

    val navBarHideOffset = if (isWideLayout) 0f else with(density) { 132.dp.toPx() }
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

    var menuSong by remember { mutableStateOf<SongItem?>(null) }
    var menuSongActions by remember { mutableStateOf<List<SongMenuAction>>(emptyList()) }

    var playbackQueue by remember { mutableStateOf<List<SongItem>>(emptyList()) }
    var currentQueueIndex by remember { mutableIntStateOf(-1) }
    var songEnded by remember { mutableStateOf(false) }
    var songTransitioned by remember { mutableStateOf(false) }
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
            if (playMode == QueueModes.SHUFFLE) {
                generateShuffledIndices()
            }
        }
    }

    // ViewModel 确认切歌后（URL fetch 完成或 gapless 快速路径），
    // 从队列中找到对应 SongItem 并更新 currentSong，保证 UI 与音频同步。
    LaunchedEffect(vmCurrentSongId) {
        val id = vmCurrentSongId ?: return@LaunchedEffect
        if (currentSong?.id == id) return@LaunchedEffect
        val found = playbackQueue.firstOrNull { it.id == id }
        if (found != null) currentSong = found
    }

    fun removeFromQueue(index: Int) {
        playbackQueue = playbackQueue.toMutableList().also { it.removeAt(index) }
        if (currentQueueIndex >= playbackQueue.size) currentQueueIndex = playbackQueue.size - 1
        if (playbackQueue.isEmpty()) currentQueueIndex = -1
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    // 队列拖拽排序：移动后修正 currentQueueIndex（当前播放项跟随其歌曲移动）。
    fun moveInQueue(from: Int, to: Int) {
        if (from !in playbackQueue.indices || to !in playbackQueue.indices || from == to) return
        val item = playbackQueue[from]
        val newQueue = playbackQueue.toMutableList().apply { removeAt(from); add(to, item) }
        // 当前播放项的索引修正：歌曲本身移动了，索引跟随歌曲。
        currentQueueIndex = when {
            from == currentQueueIndex -> to
            from < currentQueueIndex && to >= currentQueueIndex -> currentQueueIndex - 1
            from > currentQueueIndex && to <= currentQueueIndex -> currentQueueIndex + 1
            else -> currentQueueIndex
        }
        playbackQueue = newQueue
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    fun playFromQueue(index: Int) {
        if (index in playbackQueue.indices) {
            currentQueueIndex = index
            val song = playbackQueue[index]
            val (title, artist, artwork) = songParams(song)
            playerViewModel.playSong(song.id, title = title, artist = artist, artworkUrl = artwork)
            PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)

            // Immediately preload the next song so ExoPlayer has maximum time to buffer it.
            val nextIdx = when (playMode) {
                QueueModes.SINGLE -> index
                QueueModes.SHUFFLE -> shuffledIndices.getOrNull(
                    if (shuffledPosition < shuffledIndices.size - 1) shuffledPosition + 1 else 0
                ) ?: 0
                QueueModes.LINE ->
                    if (index < playbackQueue.size - 1) index + 1 else -1
                QueueModes.INFINITY ->
                    if (index < playbackQueue.size - 1) index + 1 else -1
                // CYCLE: 队尾循环到队首, 但单曲队列不预载自身(避免播完自动重播=假单曲循环)
                else ->
                    if (playbackQueue.size > 1) {
                        if (index < playbackQueue.size - 1) index + 1 else 0
                    } else -1
            }
            val nextSong = playbackQueue.getOrNull(nextIdx)
            if (nextSong != null) {
                val (nTitle, nArtist, nArtwork) = songParams(nextSong)
                playerViewModel.preloadNextSong(
                    nextSong.id, nTitle, nArtist, nArtwork,
                    allowCurrent = playMode == QueueModes.SINGLE
                )
            }
        }
    }

    // ---------- Infinity 无限播放（FM 电台, 作为播放模式之一 INFINITY） ----------
    val infinityJob = remember { mutableStateOf<Job?>(null) }
    // FM 电台入口(true) 与 相似无限播放模式(false) 共用 INFINITY 播放模式,
    // 但续播数据源不同: FM 继续拉私人 FM 流, 相似无限拉相似歌曲。
    var fmMode by remember { mutableStateOf(false) }

    /**
     * 队尾续播。数据源取决于入口:
     *  - FM 电台(fmMode): 继续调私人 FM 流 getPersonalFm, 保持"电台"体验;
     *  - 相似无限(Infinity 播放模式): 以当前歌为种子拉相似歌曲。
     * 已入队的歌会被过滤, 防止环绕重复; 数据源为空时兜底每日推荐, 避免断播。
     * in-flight 防重入:预载心跳与播完路径可能几乎同时触发。
     */
    fun launchInfinity() {
        if (infinityJob.value?.isActive == true) return
        val seed = playbackQueue.getOrNull(currentQueueIndex) ?: return
        val existingIds = playbackQueue.map { it.id }.toSet()
        val fromFm = fmMode
        infinityJob.value = coroutineScope.launch(Dispatchers.IO) {
            val primary = if (fromFm) {
                // 私人 FM 流: 一次只返回一小批, 队尾再拉一次即无限续播电台。
                runCatching { PlaylistApi.getPersonalFm() }.getOrDefault(emptyList())
            } else {
                // 相似歌曲（以当前歌为种子），听着听着往相似方向延伸。
                runCatching { PlaylistApi.getSimilarSongs(seed.id) }.getOrDefault(emptyList())
            }.filter { it.id !in existingIds }
            val continuation = if (primary.isNotEmpty()) primary else
                runCatching {
                    PlaylistApi.getDailyRecommendSongs().filter { it.id !in existingIds }
                }.getOrDefault(emptyList())
            if (continuation.isEmpty()) return@launch
            withContext(Dispatchers.Main) {
                playbackQueue = playbackQueue + continuation
                val startIdx = playbackQueue.size - continuation.size
                if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
                playFromQueue(startIdx)
                PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
                // 为无缝衔接立即预载 infinity 首曲之后的一首
                playbackQueue.getOrNull(startIdx + 1)?.let { next ->
                    val (t, a, w) = songParams(next)
                    playerViewModel.preloadNextSong(next.id, t, a, w)
                }
            }
        }
    }

    fun playNext() {
        if (playbackQueue.isEmpty()) return
        when (playMode) {
            QueueModes.SINGLE -> playerViewModel.seekTo(0)
            QueueModes.SHUFFLE -> {
                if (shuffledIndices.isEmpty() || shuffledPosition >= shuffledIndices.size - 1) {
                    // 乱序一轮播完后按"下一首": 生成**全新**乱序并播新序列首曲。
                    // 旧实现把当前曲固定为下一项(shuffledIndices[0]=current),
                    // 队列尾按下一首=重播当前曲, 观感就是"单曲循环/进度跳回开头"。
                    val fresh = playbackQueue.indices.shuffled()
                    shuffledIndices = fresh
                    shuffledPosition = 0
                    playFromQueue(fresh.getOrElse(0) { 0 })
                } else {
                    shuffledPosition++
                    playFromQueue(shuffledIndices[shuffledPosition])
                }
            }
            QueueModes.LINE -> {
                // 顺序线性：队列尾自然结束后不再循环, 定位到首曲(不自动续播)
                if (currentQueueIndex < playbackQueue.size - 1) {
                    playFromQueue(currentQueueIndex + 1)
                } else {
                    currentQueueIndex = 0
                    currentSong = playbackQueue.firstOrNull()
                    PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
                    playerViewModel.pausePlayback()
                }
            }
            QueueModes.INFINITY -> {
                // 队列尾 → FM 电台式相似歌曲续播
                if (currentQueueIndex < playbackQueue.size - 1) {
                    playFromQueue(currentQueueIndex + 1)
                } else {
                    launchInfinity()
                }
            }
            else -> { // CYCLE
                if (currentQueueIndex < playbackQueue.size - 1) {
                    playFromQueue(currentQueueIndex + 1)
                } else {
                    playFromQueue(0)
                }
            }
        }
    }

    fun playPrevious() {
        if (playbackQueue.isEmpty()) return
        // FM 电台(INFINITY)不可回退：上一首禁用
        if (playMode == QueueModes.INFINITY) return
        when (playMode) {
            QueueModes.SINGLE -> playerViewModel.seekTo(0)
            QueueModes.SHUFFLE -> {
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
        playerViewModel.setOnSongTransitionedCallback { songTransitioned = true }
        playerViewModel.setOnUnplayableCallback { playNext() }
    }

    // 无缝播放：当进入当前歌曲的最后 20 秒时，预取下一首的 URL 并加入 ExoPlayer 队列。
    // NOTE: resetPreloadFlag() is intentionally NOT called here. Calling it resets the StateFlow
    // to false, which lets the 250ms progress ticker re-set it to true immediately, causing
    // preloadNextSong to be called every 250ms (each call cancelling the previous). The flag is
    // cleared naturally by playSong() and onSongTransitioned.
    LaunchedEffect(Unit) {
        playerViewModel.needsPreload.collect { needs ->
            if (!needs) return@collect
            val nextSong: SongItem? = when {
                playbackQueue.isEmpty() -> null
                playMode == QueueModes.SINGLE -> playbackQueue.getOrNull(currentQueueIndex)
                playMode == QueueModes.SHUFFLE -> {
                    val nextPos = if (shuffledPosition < shuffledIndices.size - 1)
                        shuffledPosition + 1 else 0
                    playbackQueue.getOrNull(shuffledIndices.getOrNull(nextPos) ?: 0)
                }
                playMode == QueueModes.LINE ->
                    // 顺序线性: 队尾之后没有下一首, 不预载(否则队尾歌会 gapless 回绕队首,
                    // 单曲队列更是会 [A,A] 自动过渡 = 假单曲循环)。
                    playbackQueue.getOrNull(
                        if (currentQueueIndex < playbackQueue.size - 1) currentQueueIndex + 1 else -1
                    )
                playMode == QueueModes.INFINITY -> {
                    if (currentQueueIndex < playbackQueue.size - 1)
                        playbackQueue.getOrNull(currentQueueIndex + 1) else null
                }
                else -> {
                    // CYCLE: 队尾循环到队首；单曲队列不预载自身(避免播完自动重播=假单曲循环)
                    val nextIdx = when {
                        playbackQueue.size <= 1 -> -1
                        currentQueueIndex < playbackQueue.size - 1 -> currentQueueIndex + 1
                        else -> 0
                    }
                    playbackQueue.getOrNull(nextIdx)
                }
            }
            if (nextSong != null) {
                val (title, artist, artwork) = songParams(nextSong)
                // 单曲循环模式: 预载自身是实现无缝单曲循环的手段, 显式放行
                playerViewModel.preloadNextSong(
                    nextSong.id, title, artist, artwork,
                    allowCurrent = playMode == QueueModes.SINGLE
                )
            } else if (playMode == QueueModes.INFINITY &&
                currentQueueIndex >= playbackQueue.size - 1 && playbackQueue.isNotEmpty()
            ) {
                // 队列尾 + Infinity: 提前拉相似歌追加, 追加完成时才来得及无缝预载——
                // 等到自然播完才发起, 相似接口的网络往返会让衔接出现空档
                launchInfinity()
            }
        }
    }

    // 无缝播放：ExoPlayer 自动切换曲目后，同步队列索引和歌词。
    LaunchedEffect(songTransitioned) {
        if (!songTransitioned) return@LaunchedEffect
        songTransitioned = false
        if (playbackQueue.isEmpty()) return@LaunchedEffect
        val nextIndex = when (playMode) {
            QueueModes.SINGLE -> currentQueueIndex
            QueueModes.SHUFFLE -> {
                if (shuffledPosition < shuffledIndices.size - 1) {
                    shuffledPosition++
                    shuffledIndices.getOrElse(shuffledPosition) { 0 }
                } else {
                    generateShuffledIndices()
                    shuffledPosition = 0
                    shuffledIndices.getOrElse(0) { 0 }
                }
            }
            // 顺序线性: 队尾没有预载(见 needsPreload 分支), 正常不会走到这里;
            // 万一有竞态残留, 停在队尾而不是回绕队首。
            QueueModes.LINE ->
                if (currentQueueIndex < playbackQueue.size - 1) currentQueueIndex + 1
                else currentQueueIndex.coerceIn(0, playbackQueue.size - 1)
            QueueModes.INFINITY ->
                if (currentQueueIndex < playbackQueue.size - 1) currentQueueIndex + 1
                else if (playbackQueue.isNotEmpty()) 0 else currentQueueIndex
            else -> if (currentQueueIndex < playbackQueue.size - 1) currentQueueIndex + 1 else 0
        }
        currentQueueIndex = nextIndex
        currentSong = playbackQueue.getOrNull(nextIndex)
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
        currentSong?.id?.let { playerViewModel.fetchLyricsForSong(it) }

        // Immediately start preloading the song after this one.
        val nnIdx = when (playMode) {
            QueueModes.SINGLE -> nextIndex
            QueueModes.SHUFFLE -> shuffledIndices.getOrNull(
                if (shuffledPosition < shuffledIndices.size - 1) shuffledPosition + 1 else 0
            ) ?: 0
            QueueModes.LINE ->
                if (nextIndex < playbackQueue.size - 1) nextIndex + 1 else -1
            QueueModes.INFINITY -> {
                if (nextIndex < playbackQueue.size - 1) nextIndex + 1
                else { launchInfinity(); -1 }
            }
            // CYCLE: 单曲队列不预载自身(避免 ExoPlayer [A,A] 闭环=假单曲循环)
            else -> if (playbackQueue.size > 1) {
                if (nextIndex < playbackQueue.size - 1) nextIndex + 1 else 0
            } else -1
        }
        val songToPreload = playbackQueue.getOrNull(nnIdx)
        if (songToPreload != null) {
            val (pTitle, pArtist, pArtwork) = songParams(songToPreload)
            playerViewModel.preloadNextSong(
                songToPreload.id, pTitle, pArtist, pArtwork,
                allowCurrent = playMode == QueueModes.SINGLE
            )
        }
    }

    // 在 Splash 遮挡期间渲染一帧全展开状态，提前编译 PlayerCard 所有 graphicsLayer 的 GPU Shader。
    LaunchedEffect(Unit) {
        // Shader 预热：只跨过 expandedEnough 阈值(0.05f)让 graphicsLayer 编译 GPU shader，
        // 但不要 snapTo(1f)，那会让 LyricsView / QueueView / FullPlayerControls 全部 mount 再 unmount，
        // 在低端机上撞在 warmup 阶段是数十毫秒的 layout burst。0.06f 只让展开态视觉属性触发一次绘制。
        delay(50L)
        progress.snapTo(0.06f)
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
        coroutineScope.launch {
            progress.animateTo(1f, tween(400, easing = CubicBezierEasing(0.2f, 0f, 0f, 1f)))
        }
    }

    fun collapseCard() {
        coroutineScope.launch {
            progress.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
        }
    }

    fun playSongItem(song: SongItem) {
        // 单曲直接播放 → 插到当前播放的下一首并立即播放（不再是"排到队尾"）。
        // 队列未在播放（空/无当前曲）时, 该曲作为唯一一首。
        val isQueued = playbackQueue.isNotEmpty() && currentQueueIndex in playbackQueue.indices
        if (!isQueued) {
            playbackQueue = listOf(song)
            currentQueueIndex = 0
            if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
            PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
            playFromQueue(0)
            expandCard()
            return
        }
        // 内联 insertNext 语义：去重后把该曲插到当前歌的下一首，并让 currentQueueIndex 指向它
        val currentId = playbackQueue.getOrNull(currentQueueIndex)?.id
        if (song.id != currentId) {
            val filtered = playbackQueue.filter { it.id != song.id }.toMutableList()
            val newCurrentIndex = if (currentId != null)
                filtered.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
            else -1
            val insertPos = (newCurrentIndex + 1).coerceIn(0, filtered.size)
            filtered.add(insertPos, song)
            playbackQueue = filtered
            currentQueueIndex = if (newCurrentIndex < 0) 0 else newCurrentIndex
            if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
            PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
        }
        val idx = playbackQueue.indexOfFirst { it.id == song.id }
        if (idx >= 0) playFromQueue(idx)
        expandCard()
    }

    fun insertNext(song: SongItem) {
        // 关键不变量：playbackQueue[currentQueueIndex] 必须始终等于当前正在播的歌。
        // 直接 .filter 会把当前歌之前的重复项也删掉，让 currentQueueIndex 指错下一项——
        // 于是"下一首"变成当前歌的后一首之后的项。所以先记录当前歌 id，过滤后重新定位。
        val currentId = playbackQueue.getOrNull(currentQueueIndex)?.id
        if (song.id == currentId) return  // 已在播的曲无需"下一首"到自己
        val filtered = playbackQueue.filter { it.id != song.id }.toMutableList()
        val newCurrentIndex = if (currentId != null)
            filtered.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        else -1
        val insertPos = (newCurrentIndex + 1).coerceIn(0, filtered.size)
        filtered.add(insertPos, song)
        playbackQueue = filtered
        currentQueueIndex = if (newCurrentIndex < 0) 0 else newCurrentIndex
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    fun appendToQueue(song: SongItem) {
        val currentId = playbackQueue.getOrNull(currentQueueIndex)?.id
        if (song.id == currentId) return
        val filtered = playbackQueue.filter { it.id != song.id }
        playbackQueue = filtered + song
        currentQueueIndex = if (currentId != null)
            playbackQueue.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        else 0
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    fun replaceQueueAndPlay(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        fmMode = false
        playbackQueue = songs
        currentQueueIndex = 0
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        playFromQueue(0)
        expandCard()
    }

    /**
     * 主页「我的电台」入口: 进入真正的私人 FM(INFINITY 播放模式)。
     * 主数据源是网易私人 FM 流(getPersonalFm), 失败时兜底每日推荐,
     * 保证入口点下去一定有歌。之后靠 INFINITY 的队尾续播机制继续拉 FM 流无限延伸。
     */
    fun startFm() {
        playMode = QueueModes.INFINITY
        coroutineScope.launch(Dispatchers.IO) {
            val fm = runCatching { PlaylistApi.getPersonalFm() }.getOrDefault(emptyList())
            val songs = if (fm.isNotEmpty()) fm
                else runCatching { PlaylistApi.getDailyRecommendSongs() }.getOrDefault(emptyList())
            if (songs.isNotEmpty()) {
                withContext(Dispatchers.Main) {
                    replaceQueueAndPlay(songs)
                    // replaceQueueAndPlay 会清 fmMode, 电台标记须在其后置位。
                    fmMode = true
                    expandCard()
                }
            }
        }
    }

    fun insertAllNext(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        if (currentQueueIndex < 0) {
            replaceQueueAndPlay(songs)
            return
        }
        val currentId = playbackQueue.getOrNull(currentQueueIndex)?.id
        // 不允许把当前歌本身"塞到下一首"——那会让当前歌在队列里被 filter 掉、
        // currentQueueIndex 指向的东西完全变了。
        val toInsert = if (currentId != null) songs.filter { it.id != currentId } else songs
        if (toInsert.isEmpty()) return
        val ids = toInsert.map { it.id }.toSet()
        val filtered = playbackQueue.filter { it.id !in ids }.toMutableList()
        val newCurrentIndex = if (currentId != null)
            filtered.indexOfFirst { it.id == currentId }.coerceAtLeast(0)
        else 0
        val insertPos = (newCurrentIndex + 1).coerceIn(0, filtered.size)
        filtered.addAll(insertPos, toInsert)
        playbackQueue = filtered
        currentQueueIndex = newCurrentIndex
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    fun appendAllToQueue(songs: List<SongItem>) {
        if (songs.isEmpty()) return
        if (currentQueueIndex < 0) {
            replaceQueueAndPlay(songs)
            return
        }
        val existingIds = playbackQueue.map { it.id }.toSet()
        val newSongs = songs.filter { it.id !in existingIds }
        if (newSongs.isEmpty()) return
        // 尾追加不动 currentQueueIndex 前面的项，无需修正索引。
        playbackQueue = playbackQueue + newSongs
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
    }

    // 切换播放模式：顺序循环 → 单曲 → 乱序 → 顺序线性 → 相似无限(FM)，循环。
    val onTogglePlayMode: () -> Unit = {
        fmMode = false
        playMode = (playMode + 1) % 5
        if (playMode == QueueModes.SHUFFLE) generateShuffledIndices()
        else {
            shuffledIndices = emptyList()
            shuffledPosition = 0
        }
    }

    // ============ 导航控制器 ============
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val isInMain = navBackStackEntry?.destination?.route == NavRoutes.HOME

    /**
     * 从歌曲跳到歌手/专辑页(需求: 类 Apple Music 的来源回溯)。
     * 列表接口的解析点大多不带 artist/album id——缺失时先用 song/detail
     * 现拉全量再跳; 拉取失败静默放弃, 不给出死链接。
     */
    fun resolveAndNavigate(song: SongItem, toArtist: Boolean) {
        coroutineScope.launch(Dispatchers.IO) {
            var target = song
            val idMissing = if (toArtist)
                target.artists?.firstOrNull()?.id == null
            else
                target.album?.id == null
            if (idMissing) {
                target = runCatching { PlaylistApi.getSongsByIds(listOf(song.id)) }
                    .getOrDefault(emptyList()).firstOrNull() ?: song
            }
            val artistId = target.artists?.firstOrNull()?.id
            val albumId = target.album?.id
            withContext(Dispatchers.Main) {
                when {
                    toArtist && artistId != null -> {
                        if (progress.value > 0.01f) collapseCard()
                        navController.navigate(NavRoutes.artist(artistId))
                    }
                    !toArtist && albumId != null -> {
                        if (progress.value > 0.01f) collapseCard()
                        navController.navigate(NavRoutes.album(albumId))
                    }
                }
            }
        }
    }

    // ---------- 剪贴板分享链接识别 ----------
    // 别人发来的 music.163.com 链接(或 163cn.tv 短链)在回到 app 时自动打开;
    // 单曲则载入播放器但不播放, 由用户按播放键才开始。
    val lastHandledClip = remember { mutableStateOf("") }
    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current

    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event != androidx.lifecycle.Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            val clip = try {
                context.getSystemService(android.content.ClipboardManager::class.java)
                    ?.primaryClip?.getItemAt(0)?.text?.toString()
            } catch (_: Exception) { null } ?: return@LifecycleEventObserver
            if (clip == lastHandledClip.value || !clip.contains("163")) return@LifecycleEventObserver
            lastHandledClip.value = clip

            coroutineScope.launch(Dispatchers.IO) {
                var target = clip
                // 163cn.tv 短链: HEAD 跟随 302 拿真实地址
                if (target.contains("163cn.tv")) {
                    target = runCatching {
                        val client = okhttp3.OkHttpClient.Builder()
                            .followRedirects(false).build()
                        val resp = client.newCall(
                            okhttp3.Request.Builder().url(target).head().build()
                        ).execute()
                        resp.header("Location") ?: ""
                    }.getOrDefault("")
                }
                fun idOf(pattern: Regex): Long? =
                    pattern.find(target)?.groupValues?.get(1)?.toLongOrNull()

                val songId = idOf(Regex("music\\.163\\.com/song\\?id=(\\d+)"))
                val albumId = idOf(Regex("music\\.163\\.com/album\\?id=(\\d+)"))
                val playlistId = idOf(Regex("music\\.163\\.com/playlist\\?id=(\\d+)"))
                val artistId = idOf(Regex("music\\.163\\.com/artist\\?id=(\\d+)"))

                when {
                    albumId != null -> withContext(Dispatchers.Main) {
                        navController.navigate(NavRoutes.album(albumId))
                    }
                    playlistId != null -> withContext(Dispatchers.Main) {
                        navController.navigate(NavRoutes.playlist(playlistId))
                    }
                    artistId != null -> withContext(Dispatchers.Main) {
                        navController.navigate(NavRoutes.artist(artistId))
                    }
                    songId != null -> {
                        // 单曲: 载入播放器但不自动播放, 等用户按下播放键
                        val detail = runCatching {
                            PlaylistApi.getSongsByIds(listOf(songId))
                        }.getOrDefault(emptyList()).firstOrNull()
                            ?: return@launch
                        withContext(Dispatchers.Main) {
                            currentSong = detail
                            playbackQueue = listOf(detail)
                            currentQueueIndex = 0
                            shuffledIndices = emptyList()
                            PlaybackStateManager.saveQueue(context, playbackQueue, currentQueueIndex)
                            val (t, a, w) = songParams(detail)
                            playerViewModel.prepareSongWithoutPlay(detail.id, t, a, w)
                        }
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    var showAbout by remember { mutableStateOf(false) }
    if (showAbout) {
        AboutScreen(onBack = { showAbout = false })
        return
    }

    var showWebLogin by remember { mutableStateOf(false) }
    var cookieRefreshTrigger by remember { mutableIntStateOf(0) }

    // 登录成功后后台拉取一次云端收藏，供收藏页使用。
    LaunchedEffect(cookieRefreshTrigger) {
        if (cookieRefreshTrigger > 0) {
            LibraryManager.refreshFromCloud(context)
        }
    }
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
            // 顶部渐隐 scrim + 裸叉号：与二级页面返回箭头共用 TopScrimIconButton，
            // scrim 覆盖白色登录页顶部区域相当于"浏览器 chrome"，图标不再是孤立的黑方块。
            TopScrimIconButton(
                icon = Icons.Default.Close,
                contentDescription = LocalStrings.current.close,
                onClick = { showWebLogin = false },
                alignment = Alignment.TopEnd
            )
        }
        return
    }

    val navStrings = LocalStrings.current
    // 导航项在底部导航(窄屏)与左侧 sidebar(宽屏)之间共用。
    val navTabs = listOf(
        Icons.Default.Home to navStrings.tabHome,
        Icons.Default.LibraryMusic to navStrings.tabLibrary,
        Icons.Default.Search to navStrings.tabSearch,
        Icons.Default.Person to navStrings.tabUser,
    )
    val onNavSelected: (Int) -> Unit = { tab ->
        selectedTab = tab
        if (!isInMain) navController.popBackStack(NavRoutes.HOME, false)
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            // 背景先铺满整窗，再让出系统栏（车机 CarSystemUI 不下发 WindowInsets，
            // 用资源兜底）；正常设备 fallback 为 0，不受影响。
            .background(LocalMetroColors.current.background)
            .padding(
                top = with(density) { fallbackStatusPx.toDp() },
                bottom = with(density) { fallbackNavPx.toDp() }
            )
    ) {
        // PlayerCardOverlay is FIRST child: processes first in Compose Main pass (siblings are
        // dispatched in composition order). Its inner consumer modifier in PlayerCard prevents
        // Scaffold's SongCards from receiving events when the player is fully expanded.
        // zIndex(1f) ensures it renders above Scaffold (default zIndex=0) despite being listed first.
        Box(modifier = Modifier.fillMaxSize().zIndex(1f)) {
        PlayerCardOverlay(
            song = currentSong,
            isPlaying = isPlaying,
            progress = progress,
            collapsedOffsetY = collapsedOffsetY,
            screenHeightPx = contentHeightPx,
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
            onMoveInQueue = ::moveInQueue,
            onTogglePlayMode = onTogglePlayMode,
            onPlayNothing = {
                // 暂无播放 → 一键开始: 优先缓存里的每日推荐, 否则现拉
                coroutineScope.launch(Dispatchers.IO) {
                    val songs = ContentCache.homeDailySongs?.takeIf { it.isNotEmpty() }
                        ?: runCatching { PlaylistApi.getDailyRecommendSongs() }.getOrDefault(emptyList())
                    if (songs.isNotEmpty()) {
                        withContext(Dispatchers.Main) { replaceQueueAndPlay(songs) }
                    }
                }
            },
            onClearQueue = {
                // 显式清空: 停播 + 回到暂无播放态。进程重建保留队列是特性(接着听),
                // 想清空时用户有明确入口, 不做隐式自动清空(打断连续收听习惯)。
                playbackQueue = emptyList()
                currentQueueIndex = -1
                currentSong = null
                shuffledIndices = emptyList()
                shuffledPosition = 0
                playerViewModel.stopService()
                PlaybackStateManager.clearQueue(context)
                collapseCard()
            },
            onSongInfoClick = {
                // 全屏播放器点歌名: 上拉"转到歌手/转到专辑"菜单(复用长按菜单 sheet)
                currentSong?.let { menuSong = it; menuSongActions = emptyList() }
            },
            onSavePlaylist = { /* TODO: 保存歌单 */ },
            onNavigateToUser = {
                selectedTab = 3
                if (!isInMain) navController.popBackStack(NavRoutes.HOME, false)
                coroutineScope.launch {
                    progress.animateTo(0f, tween(260, easing = FastOutSlowInEasing))
                }
            }
        )
        } // end PlayerCardOverlay wrapper

        // 宽屏左侧常驻导航（Apple Music 式）：背景铺满整高(含状态栏后)，内容自行
        // 避让系统栏；底部 miniBar 仍整宽叠加，自然盖住侧栏空余的底部。
        if (isWideLayout) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .zIndex(0.5f)
                    .width(sidebarWidthDp)
                    .fillMaxHeight()
                    .background(LocalMetroColors.current.surface)
            ) {
                MetroSidebar(
                    items = navTabs.map { MetroSidebarItem(it.first, it.second) },
                    selectedIndex = selectedTab,
                    onSelected = onNavSelected,
                    width = sidebarWidthDp,
                    backgroundColor = Color.Transparent,
                    modifier = Modifier.windowInsetsPadding(WindowInsets.systemBars),
                )
            }
        }

        // 宽屏无底部导航，miniBar 下方的系统导航栏区域由 PlayerCard 折叠态卡背
        // 用 surface 填充（见 PlayerCard），这里不再重复铺。

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(WindowInsets.systemBars.asPaddingValues())
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    // 宽屏内容栏右移，给左侧 sidebar 让位（详情页与 tab 屏一起移）。
                    .padding(start = if (isWideLayout) sidebarWidthDp else 0.dp)
            ) {
                // 主 tab 屏一直挂载（下层）：以前用 if(isInMain) 条件挂载，导航返回时
                // tab 屏瞬间 mount + LaunchedEffect 立即触发，撞在 nav slide 动画的第一帧
                // → 主线程 gg，返回感觉卡。现在 tab 屏永远存在，nav 详情页通过 opaque bg
                // 从上层覆盖它，返回时 nav 只需 slide 详情页出去，tab 屏本来就在下面可见。
                // 副作用：NavGraph 空 HOME destination 内容为空且不占布局，pointer 天然穿透。
                Box(modifier = Modifier.fillMaxSize()) {
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
                                // 直接替换队列并从头播放。
                                // 旧实现循环 addToQueue → 每次都把 currentQueueIndex 推到队尾，
                                // 而 playSong 播的是 songs[0]，两者错位，导致下一首回绕 0 → 永远回放首曲。
                                replaceQueueAndPlay(songs)
                            },
                            onSongInsertNext = { insertNext(it) },
                            onSongAppendToQueue = { appendToQueue(it) },
                            onShowSongMenu = { song, actions -> menuSong = song; menuSongActions = actions },
                            // 主页「我的电台」入口: 之前一直没传, HomeScreen 的 FM 卡因此永不渲染
                            onPlayFm = { startFm() }
                        )

                        1 -> LibraryScreen(
                            onSongClick = { playSongItem(it) },
                            onAlbumClick = { albumId -> navController.navigate(NavRoutes.album(albumId)) },
                            onPlayAlbum = { albumId ->
                                val albumSongs = LibraryManager.getSongsByAlbumId(context, albumId)
                                if (albumSongs.isNotEmpty()) {
                                    pendingPlayAllSongs = albumSongs
                                } else {
                                    // 云端收藏的专辑可能不在收藏单曲缓存里，兜底拉专辑详情播放
                                    coroutineScope.launch {
                                        try {
                                            val detail = RetrofitClient.api.getAlbumDetail(albumId)
                                            val songs = detail.songs?.map {
                                                SongItem(id = it.id, name = it.name, artists = it.artists, album = it.album, duration = it.getDurationMs())
                                            }
                                            if (!songs.isNullOrEmpty()) pendingPlayAllSongs = songs
                                        } catch (_: Exception) {}
                                    }
                                }
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
                            onSongAppendToQueue = { appendToQueue(it) },
                            onShowSongMenu = { song, actions -> menuSong = song; menuSongActions = actions },
                            refreshTrigger = cookieRefreshTrigger
                        )

                        2 -> SearchScreen(
                            onSongClick = { playSongItem(it) },
                            onAlbumClick = { albumId -> navController.navigate(NavRoutes.album(albumId)) },
                            onArtistClick = { artistId -> navController.navigate(NavRoutes.artist(artistId)) },
                            onInsertNext = { insertNext(it) },
                            onAppendToQueue = { appendToQueue(it) },
                            onShowSongMenu = { song, actions -> menuSong = song; menuSongActions = actions },
                            onAlbumBatch = { albumId, action ->
                                coroutineScope.launch {
                                    try {
                                        val response = RetrofitClient.api.getAlbumDetail(albumId)
                                        val songs = (response.songs ?: emptyList()).map { s ->
                                            SongItem(
                                                id = s.id,
                                                name = s.name,
                                                artists = s.artists,
                                                album = s.album,
                                                duration = s.getDurationMs()
                                            )
                                        }
                                        if (songs.isNotEmpty()) {
                                            when (action) {
                                                BatchQueueAction.PLAY_NOW -> replaceQueueAndPlay(songs)
                                                BatchQueueAction.INSERT_NEXT -> insertAllNext(songs)
                                                BatchQueueAction.APPEND -> appendAllToQueue(songs)
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            onArtistBatch = { artistName, action ->
                                coroutineScope.launch {
                                    try {
                                        val searchResponse = RetrofitClient.api.search(keyword = artistName, type = 1, limit = 30)
                                        val songs = (searchResponse.result?.songs ?: emptyList())
                                            .filter { it.artists?.any { a -> a.name == artistName } == true }
                                        if (songs.isNotEmpty()) {
                                            when (action) {
                                                BatchQueueAction.PLAY_NOW -> replaceQueueAndPlay(songs)
                                                BatchQueueAction.INSERT_NEXT -> insertAllNext(songs)
                                                BatchQueueAction.APPEND -> appendAllToQueue(songs)
                                            }
                                        }
                                    } catch (_: Exception) {}
                                }
                            },
                            themeIndex = themeIndex
                        )

                        3 -> UserScreen(
                            onOpenAbout = { showAbout = true },
                            themeIndex = themeIndex,
                            onThemeChange = onThemeChange,
                            themeMode = themeMode,
                            onThemeModeChange = onThemeModeChange,
                            onShowWebLogin = { showWebLogin = true },
                            refreshTrigger = cookieRefreshTrigger,
                            onLanguageChange = onLanguageChange
                        )
                    }
                }

                // NavGraph 在 tab 屏之上（上层）：空 HOME destination 不遮挡 tab 屏，
                // 打开详情页时详情页 opaque bg 从上层盖住 tab 屏；返回时 slide 出去露出 tab 屏。
                MainNavGraph(
                    navController = navController,
                    onSongClick = { playSongItem(it) },
                    onReplaceAndPlay = { replaceQueueAndPlay(it) },
                    onInsertNext = { insertAllNext(it) },
                    onSongInsertNext = { insertNext(it) },
                    onSongAppendToQueue = { appendToQueue(it) },
                    onShowSongMenu = { song, actions -> menuSong = song; menuSongActions = actions },
                    startDestination = NavRoutes.HOME
                )
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

        // zIndex(1.5f) renders PivotNav above PlayerCardOverlay (zIndex=1f),
        // so it appears on top of the mini player bar when the player is collapsed.
        // background 放在 navigationBarsPadding 之外：surface 覆盖 56dp 视觉栏 + 系统栏预留
        // 一整段，一直涂到物理屏幕底；如果反过来，栏下方就是透明，露出后景空隙。
        // 宽屏改用左侧 sidebar，这里不再渲染底部导航。
        if (!isWideLayout) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(1.5f)
                    .graphicsLayer {
                        translationY = navBarHideOffset * progress.value
                    }
                    .fillMaxWidth()
                    .background(LocalMetroColors.current.surface)
                    .navigationBarsPadding()
                    .height(56.dp)
            ) {
                // autoReserveBottomStack=false: Ncrust 用自己的 BottomOverlayInsetDp 常量管
                // 底部预留,不接 MetroShell 的 MetroBottomStackScope,否则 rememberBottomStackReservation
                // 会因缺 CompositionLocal 而 crash。
                MetroBottomNav(
                    selectedIndex = selectedTab,
                    onSelected = onNavSelected,
                    items = navTabs.map { MetroBottomNavItem(it.first, it.second) },
                    autoReserveBottomStack = false,
                )
            }
        }

        menuSong?.let { song ->
            Box(Modifier.fillMaxSize().zIndex(2f)) {
                SongMenuSheet(
                    song = song,
                    // 统一在这里追加"转到歌手/转到专辑": 所有长按菜单(首页/歌单/专辑/
                    // 歌手/收藏/搜索)自动获得回调入口, 各 Screen 无需感知导航
                    actions = menuSongActions + listOf(
                        SongMenuAction(Icons.Default.Person, LocalStrings.current.actionGoToArtist) {
                            resolveAndNavigate(song, toArtist = true)
                        },
                        SongMenuAction(Icons.Default.LibraryMusic, LocalStrings.current.actionGoToAlbum) {
                            resolveAndNavigate(song, toArtist = false)
                        },
                    ),
                    onDismiss = { menuSong = null }
                )
            }
        }

        // 全屏播放器展开时拦截系统返回：先收起播放器而不是直接退出应用。
        // 播放器是独立 overlay 层不在 NavHost 里，必须自己处理 back（此前侧滑会穿透到
        // Activity 根部直接把 app finish 掉）。放在 NavHost 之后组合，OnBackPressedDispatcher
        // 按注册逆序回调，保证它比 Navigation 的 BackHandler 更优先命中。
        //
        // 关键: enabled 必须走 derivedStateOf。若直接写 `progress.value > 0.01f`,
        // 就是在组合阶段读 progress —— 展开/收起动画每一帧都会重组整个 MainScreen
        // (实测展开时 Compose:recompose 119 次/65ms)。derivedStateOf 只在布尔值
        // 真正翻转(阈值穿越)时才通知, 动画期间零重组。
        val backEnabled by remember { derivedStateOf { progress.value > 0.01f } }
        BackHandler(enabled = backEnabled) {
            collapseCard()
        }
    }
}