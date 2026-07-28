package com.takahashirinta.ncrust.ui.navigation

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.takahashirinta.ncrust.network.SongItem
import com.takahashirinta.ncrust.ui.anim.sokuou.MetroDefault
import com.takahashirinta.ncrust.ui.components.SongMenuAction
import com.takahashirinta.ncrust.ui.screen.*
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object NavRoutes {
    const val HOME = "home"
    const val ALBUM = "album/{albumId}"
    const val ARTIST = "artist/{artistId}"
    const val PLAYLIST = "playlist/{playlistId}/{playlistName}/{playlistCoverUrl}"
    const val SONG_DETAIL = "song/{songId}"

    fun album(albumId: Long) = "album/$albumId"
    fun artist(artistId: Long) = "artist/$artistId"
    fun playlist(id: Long, name: String = "", coverUrl: String = "") =
        "playlist/$id/${URLEncoder.encode(name, StandardCharsets.UTF_8.toString())}/${URLEncoder.encode(coverUrl, StandardCharsets.UTF_8.toString())}"
    fun song(songId: Long) = "song/$songId"
}

@Composable
fun MainNavGraph(
    navController: NavHostController,
    onSongClick: (SongItem) -> Unit,
    onReplaceAndPlay: (List<SongItem>) -> Unit = {},
    onInsertNext: (List<SongItem>) -> Unit = {},
    onSongInsertNext: (SongItem) -> Unit = {},
    onSongAppendToQueue: (SongItem) -> Unit = {},
    onShowSongMenu: (SongItem, List<SongMenuAction>) -> Unit = { _, _ -> },
    startDestination: String = NavRoutes.HOME
) {
    // Metro 页面推入：进比退更"重"，滑距 1/8 屏宽，MetroDefault 曲线克制无过冲。
    //
    // 关键前提——HOME 路由的 composable 内容故意为空（MainScreen 单独负责），
    // 这意味着 exit（前进时旧页退场）与 popEnter（返回时目的页入场）作用于一个
    // 没有像素的空 composable，配置动画只是让 Compose 走完动画机器空转，
    // 在低端机上还会额外拉长合成窗口。显式改为 None 完全跳过。
    // 如果未来 HOME 加入内容，需要在这里补回过渡。
    //
    // popExit（返回时详情页退场）延迟启动 fadeOut：前 120ms 是纯不透明滑动
    // （GPU 最便宜的操作），最后 80ms 才做淡出。alpha 合成窗口从 200ms 缩到
    // 80ms，配合详情页 dispose 时的 LazyColumn/Coil 拆解，掉帧窗口大幅收窄。
    NavHost(
        navController = navController,
        startDestination = startDestination,
        enterTransition = {
            slideInHorizontally(
                initialOffsetX = { fullWidth -> fullWidth / 8 },
                animationSpec = tween(durationMillis = 280, easing = MetroDefault)
            ) + fadeIn(animationSpec = tween(durationMillis = 220, easing = MetroDefault))
        },
        exitTransition = { ExitTransition.None },
        popEnterTransition = { EnterTransition.None },
        popExitTransition = {
            slideOutHorizontally(
                targetOffsetX = { fullWidth -> fullWidth / 8 },
                animationSpec = tween(durationMillis = 200, easing = MetroDefault)
            ) + fadeOut(
                animationSpec = tween(durationMillis = 80, delayMillis = 120, easing = MetroDefault)
            )
        }
    ) {
        composable(NavRoutes.HOME) {
            // 不渲染任何内容，由 MainScreen 的 Scaffold 内容填充
        }

        composable(
            route = NavRoutes.ALBUM,
            arguments = listOf(navArgument("albumId") { type = NavType.LongType })
        ) { backStackEntry ->
            val albumId = backStackEntry.arguments?.getLong("albumId") ?: return@composable
            AlbumDetailScreen(
                albumId = albumId,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onReplaceAndPlay = onReplaceAndPlay,
                onInsertNext = onInsertNext,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.ARTIST,
            arguments = listOf(navArgument("artistId") { type = NavType.LongType })
        ) { backStackEntry ->
            val artistId = backStackEntry.arguments?.getLong("artistId") ?: return@composable
            ArtistDetailScreen(
                artistId = artistId,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onAlbumClick = { id -> navController.navigate(NavRoutes.album(id)) },
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.PLAYLIST,
            arguments = listOf(
                navArgument("playlistId") { type = NavType.LongType },
                navArgument("playlistName") { type = NavType.StringType; defaultValue = "" },
                navArgument("playlistCoverUrl") { type = NavType.StringType; defaultValue = "" }
            )
        ) { backStackEntry ->
            val playlistId = backStackEntry.arguments?.getLong("playlistId") ?: return@composable
            // ← 解码
            val name = URLDecoder.decode(
                backStackEntry.arguments?.getString("playlistName") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            val cover = URLDecoder.decode(
                backStackEntry.arguments?.getString("playlistCoverUrl") ?: "",
                StandardCharsets.UTF_8.toString()
            )
            PlaylistDetailScreen(
                playlistId = playlistId,
                playlistName = name,
                playlistCoverUrl = cover,
                onBack = { navController.popBackStack() },
                onSongClick = onSongClick,
                onReplaceAndPlay = onReplaceAndPlay,
                onInsertNext = onInsertNext,
                onSongInsertNext = onSongInsertNext,
                onSongAppendToQueue = onSongAppendToQueue,
                onShowSongMenu = onShowSongMenu
            )
        }

        composable(
            route = NavRoutes.SONG_DETAIL,
            arguments = listOf(navArgument("songId") { type = NavType.LongType })
        ) { backStackEntry ->
            val songId = backStackEntry.arguments?.getLong("songId") ?: return@composable
            SongDetailScreen(
                songId = songId,
                onBack = { navController.popBackStack() }
            )
        }
    }
}