package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.ui.components.TopScrimIconButton
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.viewmodel.SongViewModel
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

@Composable
fun SongDetailScreen(songId: Long, onBack: () -> Unit) {
    val strings = LocalStrings.current
    val viewModel: SongViewModel = viewModel()
    val songDetail by viewModel.songDetail.collectAsState()
    val lyric by viewModel.lyric.collectAsState()
    val translatedLyric by viewModel.translatedLyric.collectAsState()

    LaunchedEffect(songId) {
        viewModel.loadSongDetail(songId)
    }

    // Groove 无边框：不再套 M3 TopAppBar Surface，返回箭头浮在内容上方共用 TopScrimIconButton。
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        ResponsiveContent {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    // 顶部预留 status bar + 72dp 让首行文字落在 scrim 之下、不被返回箭头挡住。
                    .statusBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, top = 72.dp, bottom = 16.dp)
            ) {
                songDetail?.let { song ->
                    MetroText(song.name, color = Color.White, style = LocalMetroTypography.current.headlineMedium)
                    Spacer(modifier = Modifier.height(8.dp))
                    MetroText(
                        song.artists.joinToString("/") { it.name },
                        color = Color(0xFF1DB954),
                        style = LocalMetroTypography.current.titleMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    MetroText(song.album.name ?: strings.unknownAlbum, color = Color.Gray)
                    if (song.duration > 0) {
                        MetroText(formatDuration(song.duration), color = Color.Gray)
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                MetroText(strings.lyricsLabel, color = Color.White, style = LocalMetroTypography.current.titleLarge)
                Spacer(modifier = Modifier.height(8.dp))

                lyric?.let { lrc ->
                    MetroText(lrc, color = Color.White, style = LocalMetroTypography.current.bodyMedium)
                } ?: MetroText(strings.noLyrics, color = Color.Gray)
            }
        }

        TopScrimIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = strings.back,
            onClick = onBack
        )
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
