package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.takahashirinta.ncrust.ui.viewmodel.SongViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongDetailScreen(songId: Long, onBack: () -> Unit) {
    val viewModel: SongViewModel = viewModel()
    val songDetail by viewModel.songDetail.collectAsState()
    val lyric by viewModel.lyric.collectAsState()
    val translatedLyric by viewModel.translatedLyric.collectAsState()

    LaunchedEffect(songId) {
        viewModel.loadSongDetail(songId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("歌曲详情", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF121212)
                )
            )
        },
        containerColor = Color(0xFF121212)
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            songDetail?.let { song ->
                Text(song.name, color = Color.White, style = MaterialTheme.typography.headlineMedium)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    song.artists.joinToString("/") { it.name },
                    color = Color(0xFF1DB954),
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(song.album.name ?: "未知专辑", color = Color.Gray)
                if (song.duration > 0) {
                    Text(formatDuration(song.duration), color = Color.Gray)
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("歌词", color = Color.White, style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(8.dp))

            lyric?.let { lrc ->
                Text(lrc, color = Color.White, style = MaterialTheme.typography.bodyMedium)
            } ?: Text("暂无歌词", color = Color.Gray)
        }
    }
}

fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}