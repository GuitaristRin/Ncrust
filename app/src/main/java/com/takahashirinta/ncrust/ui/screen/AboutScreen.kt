package com.takahashirinta.ncrust.ui.screen

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.BuildConfig
import com.takahashirinta.ncrust.R
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.ui.anim.sokuou.MetroDefault
import com.takahashirinta.ncrust.ui.i18n.LocalStrings

// 版本号动态读取 BuildConfig.VERSION_NAME（对应 build.gradle.kts 的 versionName），
// 避免发版时忘记同步。
private val VERSION = "v${BuildConfig.VERSION_NAME}"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    val s = LocalStrings.current
    val accent = MaterialTheme.colorScheme.primary
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(s.aboutTitle, color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, s.back, tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
            )
        },
        containerColor = Color(0xFF121212)
    ) { innerPadding ->
        ResponsiveContent(modifier = Modifier.padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 5 段级联：0/40/80/120/160ms 起，每段 200ms 淡入 + 8dp 微滑上。
                // stagger 窗 160ms（不超 200ms 约束），首元素 0ms 保证不"不跟手"。
                CascadeBlock(delayMillis = 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Image(
                            painter = painterResource(id = R.drawable.ic_launcher),
                            contentDescription = "Ncrust",
                            modifier = Modifier.size(80.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(Modifier.height(16.dp))
                        Text("Ncrust", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = accent)
                        Text(VERSION, fontSize = 14.sp, color = accent.copy(alpha = 0.72f))
                        Spacer(Modifier.height(6.dp))
                        Text(s.aboutAppSubtitle, fontSize = 13.sp, color = Color(0xFF888888))
                        Spacer(Modifier.height(28.dp))
                        HorizontalDivider(color = Color(0xFF272727))
                        Spacer(Modifier.height(20.dp))
                    }
                }

                CascadeBlock(delayMillis = 40) {
                    Column {
                        AboutSection(s.aboutSectionProject.uppercase(), accent)
                        AboutRow(s.aboutVersion, VERSION)
                        AboutRow(s.aboutDeveloper, "Takahashi_Rinta")
                        AboutRow(s.aboutLicense, "MIT")
                        AboutRow(s.aboutRepository, "github.com/GuitaristRin/Ncrust")
                        Spacer(Modifier.height(20.dp))
                    }
                }

                CascadeBlock(delayMillis = 80) {
                    Column {
                        AboutSection(s.aboutSectionTechStack.uppercase(), accent)
                        AboutRow(s.aboutLangLabel, "Kotlin")
                        AboutRow(s.aboutUIFrameworkLabel, "Jetpack Compose")
                        AboutRow(s.aboutAudioEngineLabel, "Media3 ExoPlayer")
                        AboutRow(s.aboutNetworkLabel, "Retrofit + OkHttp")
                        AboutRow(s.aboutImageLabel, "Coil")
                        Spacer(Modifier.height(20.dp))
                    }
                }

                CascadeBlock(delayMillis = 120) {
                    Column {
                        AboutSection(s.aboutSectionTeam.uppercase(), accent)
                        AboutRow(s.aboutRoleDev, "Takahashi_Rinta")
                        AboutRow(s.aboutRoleTester, "白给小子")
                        Spacer(Modifier.height(20.dp))
                    }
                }

                CascadeBlock(delayMillis = 160) {
                    Column {
                        AboutSection(s.aboutSectionCredits.uppercase(), accent)
                        AboutRow(s.aboutCreditCli, "Suxiaoqinx/Netease_url (MIT)")
                        AboutRow(s.aboutCreditAnim, "SaltPlayerSource")
                        AboutRow(s.aboutCreditDesign, "Apple Music for Android")
                        Spacer(Modifier.height(32.dp))
                    }
                }
            }
        }
    }
}

// 关于页专用的级联入场包装：每块内容独立 MutableTransitionState + AnimatedVisibility，
// 通过 delayMillis 拉开时序。首块传 0 即立即出现，符合"首元素 0ms"约束。
@Composable
private fun CascadeBlock(delayMillis: Int, content: @Composable () -> Unit) {
    val density = LocalDensity.current
    val slideOffsetPx = with(density) { 8.dp.roundToPx() }
    val visibleState = remember {
        MutableTransitionState(false).apply { targetState = true }
    }
    AnimatedVisibility(
        visibleState = visibleState,
        enter = fadeIn(
            animationSpec = tween(200, delayMillis = delayMillis, easing = MetroDefault)
        ) + slideInVertically(
            animationSpec = tween(200, delayMillis = delayMillis, easing = MetroDefault),
            initialOffsetY = { slideOffsetPx }
        )
    ) { content() }
}

@Composable
private fun AboutSection(title: String, accent: Color) {
    Text(
        title,
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = accent,
        letterSpacing = 1.5.sp,
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 10.dp)
    )
}

@Composable
private fun AboutRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            label,
            fontSize = 13.sp,
            color = Color(0xFF888888),
            modifier = Modifier.weight(1f)
        )
        Text(
            value,
            fontSize = 13.sp,
            color = Color(0xFFDDDDDD),
            textAlign = TextAlign.End,
            modifier = Modifier.weight(1.5f)
        )
    }
}
