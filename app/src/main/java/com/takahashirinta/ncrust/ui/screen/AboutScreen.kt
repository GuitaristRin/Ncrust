package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.ui.theme.MarkdownText
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.layout.ContentScale
import com.takahashirinta.ncrust.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(onBack: () -> Unit) {
    BackHandler { onBack() }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于 Ncrust", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1A1A1A)
                )
            )
        },
        containerColor = Color(0xFF121212)
    ) { innerPadding ->
        ResponsiveContent(modifier = Modifier.padding(innerPadding)) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 16.dp)
            ) {
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher),
                    contentDescription = "Ncrust 图标",
                    modifier = Modifier
                        .size(96.dp)
                        .align(Alignment.CenterHorizontally),
                    contentScale = ContentScale.Fit
                )

                Spacer(Modifier.height(24.dp))

                MarkdownText(
                    markdown = aboutMarkdown
                )
            }
        }
    }
}

/**
 * 关于页的 Markdown 内容。
 * 替换此字符串即可更新页面内容。
 * 支持：标题、粗体、斜体、代码、图片（![描述](URL)）。
 */
val aboutMarkdown = """
# Ncrust

网易云音乐第三方客户端

---

## 项目信息

- **版本**：v1.0.0
- **开发者**：Takahashi_Rinta
- **许可证**：MIT
- **项目地址**：github.com/GuitaristRin/Ncrust

---

## 技术栈

- **语言**：Kotlin
- **UI 框架**：Jetpack Compose
- **音频引擎**：Media3 ExoPlayer
- **网络库**：Retrofit + OkHttp
- **图片库**：Coil

---
## 开发人员名单
- **全栈工程师 & 设计主理**：Takahashi_Rinta
- **UI适配性测试**：白给小子
---

## 致谢

- CLI逻辑实现：Suxiaoqinx/Netease_url (MIT)
- 动画参考：SaltPlayerSource
- 设计参考：Apple Music for Android

---

""".trimIndent()
