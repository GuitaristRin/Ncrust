package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

/**
 * 统一详情页骨架
 *
 * @param title 页面标题（TopAppBar 显示）
 * @param onBack 返回回调
 * @param isLoading 是否加载中
 * @param error 错误信息
 * @param onRetry 重试回调
 * @param header 头部内容（封面、标题、副标题、操作按钮等）
 * @param content 下方列表内容（LazyListScope 接收者）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    isLoading: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    header: @Composable () -> Unit,
    content: LazyListScope.() -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title, color = Color.White) },
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
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        when {
            isLoading -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            }
            error != null -> {
                Box(
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(error, color = Color.Red, fontSize = 16.sp)
                        if (onRetry != null) {
                            Spacer(Modifier.height(16.dp))
                            Button(onClick = onRetry) {
                                Text("重试")
                            }
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    contentPadding = PaddingValues(16.dp)
                ) {
                    item { header() }
                    content()
                }
            }
        }
    }
}

/**
 * 详情页头部：封面 + 信息区域
 *
 * @param coverUrl 封面 URL
 * @param title 标题
 * @param subtitle 副标题（如艺术家名）
 * @param infoLines 信息行列表
 * @param onPlayAll 播放全部回调
 * @param headerActions 头部右侧操作区域
 */
@Composable
fun DetailHeader(
    coverUrl: String?,
    title: String,
    subtitle: String? = null,
    infoLines: List<String> = emptyList(),
    onPlayAll: (() -> Unit)? = null,
    headerActions: @Composable ColumnScope.() -> Unit = {}
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        AsyncImage(
            model = coverUrl,
            contentDescription = "封面",
            modifier = Modifier
                .weight(0.4f)
                .aspectRatio(1f),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(0.6f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 16.sp
                )
            }
            infoLines.forEach { line ->
                Spacer(Modifier.height(4.dp))
                Text(line, color = Color.Gray, fontSize = 14.sp)
            }
            headerActions()
            if (onPlayAll != null) {
                Spacer(Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    PlayAllCircleButton(size = 40.dp, onClick = onPlayAll)
                }
            }
        }
    }

    Spacer(Modifier.height(24.dp))
    HorizontalDivider(color = Color(0xFF2A2A2A))
    Spacer(Modifier.height(16.dp))
}
