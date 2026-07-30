package com.takahashirinta.ncrust.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.anim.sokuou.MetroDefault
import com.takahashirinta.ncrust.ui.anim.sokuou.SokuouTweens
import com.takahashirinta.ncrust.ui.anim.sokuou.rememberMetroFlingBehavior
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

/**
 * 无边框详情页骨架。
 *
 * 与旧版差异：
 *  - 没有 M3 TopAppBar 的实体 Surface。顶部使用向下渐隐的深色 scrim 提供图标可读性，
 *    避免以往"半透明黑色方块"堆在角落的突兀感——scrim 与内容边缘自然融合，仍无任何 border。
 *  - 返回箭头是裸图标，无背板，Ripple 以图标中心为原点，触控区扩大到 44dp。
 *  - 内容 LazyColumn 从屏幕顶部开始（延伸到 status bar 下），第一项 header() 会填满整个可视区宽度。
 *  - title 参数已弃用（Groove 风：页面标题由 header 本身承担），保留以兼容签名。
 */
@Composable
fun DetailScaffold(
    title: String,
    onBack: () -> Unit,
    isLoading: Boolean = false,
    // 缓存兜底：调用方已有可立即渲染的内容时传 true，跳过全屏 loader。
    hasCachedContent: Boolean = false,
    error: String? = null,
    onRetry: (() -> Unit)? = null,
    header: @Composable () -> Unit,
    content: LazyListScope.() -> Unit
) {
    val strings = LocalStrings.current
    Box(
        // 必须 opaque：nav popExit 动画期间详情页会 slide + 部分透明，
        // 若无 bg 会透视到下方主 tab 屏（返回时 mount/绘制未完成 → 用户看到"低一层残影"）。
        // 主 tab 屏一直在下层挂载，DetailScaffold 显示期间就是它遮住 tab 屏。
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val stateKey = when {
            error != null -> DetailScaffoldState.Error
            isLoading && !hasCachedContent -> DetailScaffoldState.Loading
            else -> DetailScaffoldState.Content
        }
        Crossfade(
            targetState = stateKey,
            animationSpec = SokuouTweens.CoverFade,
            modifier = Modifier.fillMaxSize(),
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
                    // 入场级联：内容分支从下方 12dp 微滑上 + 淡入。方向与 NavGraph 横向推入正交。
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
                            contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
                            flingBehavior = rememberMetroFlingBehavior()
                        ) {
                            item { header() }
                            content()
                        }
                    }
                }
            }
        }

        TopScrimIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = strings.back,
            onClick = onBack
        )
    }
}

/**
 * 顶部渐隐 scrim + 裸图标。scrim 承担"让白色图标在任意封面上可读"的职责，
 * 图标本身无背板，M3 IconButton 提供 48dp 触控区 + 默认 ripple。
 * 所有二级页面（详情、关于、WebView 登录）共用此组件以保证一致性。
 */
@Composable
fun TopScrimIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    alignment: Alignment = Alignment.TopStart
) {
    Box(modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.55f),
                        1f to Color.Transparent
                    )
                )
        )
        Box(
            modifier = Modifier
                .align(alignment)
                .statusBarsPadding()
                .padding(horizontal = 4.dp, vertical = 4.dp)
        ) {
            IconButton(onClick = onClick) {
                Icon(
                    icon,
                    contentDescription = contentDescription,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

private enum class DetailScaffoldState { Loading, Error, Content }

/**
 * 无边框详情页头部。
 *
 * 封面全宽通铺（fillMaxWidth + aspectRatio 1:1），下方是"标题 / 副标题 / info / 右侧大圆播放按钮"。
 * 标题与信息区左右各有 16dp 内边距；封面本身没有任何 padding，贴屏幕边缘。
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
    Column(modifier = Modifier.fillMaxWidth()) {
        AsyncImage(
            model = coverUrl,
            contentDescription = strings.coverDesc,
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentScale = ContentScale.Crop
        )
        Spacer(Modifier.height(20.dp))
        // 信息区 + 右侧大播放按钮
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalAlignment = Alignment.Top
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    color = Color.White,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 3,
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
            }
            if (onPlayAll != null) {
                Spacer(Modifier.width(12.dp))
                PlayAllCircleButton(size = 48.dp, onClick = onPlayAll)
            }
        }
        // 右侧附加操作（如"收藏专辑"按钮），左对齐、无 divider。
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            content = headerActions
        )
        Spacer(Modifier.height(16.dp))
    }
}
