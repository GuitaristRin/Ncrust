package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.ui.anim.sokuou.MetroDefault
import com.takahashirinta.ncrust.ui.anim.sokuou.SokuouTweens
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

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
    // 如果调用方已经有缓存内容可以立即渲染，传 true，DetailScaffold 就不显示全屏 loader，
    // 直接展示 header/content；后台加载完成后 LazyColumn 自然 diff 更新，无跳变。
    hasCachedContent: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    header: @Composable () -> Unit,
    content: LazyListScope.() -> Unit
) {
    val strings = LocalStrings.current
    Scaffold(
        topBar = {
            Surface(color = MaterialTheme.colorScheme.surface) {
                Column(Modifier.fillMaxWidth()) {
                    Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = strings.back,
                                tint = Color.White
                            )
                        }
                        Text(
                            title,
                            color = Color.White,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        // 三态：loading（无缓存的冷启动）/ error / content。
        // hasCachedContent=true 时把 loading 视作 content，避免闪一下 spinner。
        val stateKey = when {
            error != null -> DetailScaffoldState.Error
            isLoading && !hasCachedContent -> DetailScaffoldState.Loading
            else -> DetailScaffoldState.Content
        }
        Crossfade(
            targetState = stateKey,
            animationSpec = SokuouTweens.CoverFade,
            modifier = Modifier.fillMaxSize().padding(innerPadding),
            label = "DetailScaffoldCrossfade"
        ) { state ->
            when (state) {
                DetailScaffoldState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                DetailScaffoldState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(error ?: "", color = Color.Red, fontSize = 16.sp)
                            if (onRetry != null) {
                                Spacer(Modifier.height(16.dp))
                                Button(onClick = onRetry) {
                                    Text(strings.retry)
                                }
                            }
                        }
                    }
                }
                DetailScaffoldState.Content -> {
                    // 入场级联：内容分支首次显示时，整块 LazyColumn 从下方 12dp 微滑上 + 淡入。
                    // 方向刻意与 NavGraph 的横向推入正交——纵向 12dp 不打架、不叠加视觉抖动。
                    // 首元素 0ms 延迟，保证不"不跟手"；220ms 落地，与 NavGraph 280ms 大致同步。
                    val density = LocalDensity.current
                    val slideOffsetPx = with(density) { 12.dp.roundToPx() }
                    val cascadeState = remember {
                        MutableTransitionState(false).apply { targetState = true }
                    }
                    AnimatedVisibility(
                        visibleState = cascadeState,
                        enter = fadeIn(animationSpec = tween(220, easing = MetroDefault)) +
                            slideInVertically(
                                animationSpec = tween(220, easing = MetroDefault),
                                initialOffsetY = { slideOffsetPx }
                            )
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp)
                        ) {
                            item { header() }
                            content()
                        }
                    }
                }
            }
        }
    }
}

private enum class DetailScaffoldState { Loading, Error, Content }

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
    val strings = LocalStrings.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Start,
        verticalAlignment = Alignment.Top
    ) {
        AsyncImage(
            model = coverUrl,
            contentDescription = strings.coverDesc,
            modifier = Modifier
                .weight(0.33f)
                .aspectRatio(1f),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.width(14.dp))
        Column(modifier = Modifier.weight(0.67f)) {
            Text(
                title,
                color = Color.White,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    subtitle,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 14.sp
                )
            }
            infoLines.forEach { line ->
                Spacer(Modifier.height(3.dp))
                Text(line, color = Color.Gray, fontSize = 13.sp)
            }
            headerActions()
            if (onPlayAll != null) {
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    PlayAllCircleButton(size = 36.dp, onClick = onPlayAll)
                }
            }
        }
    }

    Spacer(Modifier.height(16.dp))
    HorizontalDivider(color = Color(0xFF2A2A2A))
    Spacer(Modifier.height(10.dp))
}
