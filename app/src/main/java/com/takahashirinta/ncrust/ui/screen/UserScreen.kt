package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.ui.theme.ThemeColorSelector
import com.takahashirinta.ncrust.ui.theme.themeColorPresets
import kotlinx.coroutines.launch
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable
fun UserScreen(
    onOpenAbout: () -> Unit = {},
    themeIndex: Int = 0,
    onThemeChange: (Int) -> Unit = {},
    onShowWebLogin: () -> Unit = {},
    refreshTrigger: Int = 0
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var cookieText by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var cookieInfo by remember { mutableStateOf(CookieManager.getCookieInfo(context)) }

    var userProfile by remember { mutableStateOf<PlaylistApi.UserProfile?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("ncrust_settings", 0) }
    var wifiQuality by remember { mutableIntStateOf(prefs.getInt("wifi_quality", 3)) }
    var mobileQuality by remember { mutableIntStateOf(prefs.getInt("mobile_quality", 1)) }
    val qualityOptions = listOf("压缩", "较好", "更好", "无损", "高解析")

    fun loadProfile() {
        if (!CookieManager.hasCookie(context)) {
            userProfile = null
            return
        }
        coroutineScope.launch {
            isLoadingProfile = true
            try {
                userProfile = PlaylistApi.getUserProfile()
            } catch (_: Exception) {
                userProfile = null
            } finally {
                isLoadingProfile = false
            }
        }
    }

    LaunchedEffect(Unit) { loadProfile() }
    LaunchedEffect(refreshTrigger) {
        if (refreshTrigger > 0) {
            cookieInfo = CookieManager.getCookieInfo(context)
            loadProfile()
        }
    }

    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("登录网易云音乐", color = Color.White) },
            text = {
                Column {
                    Button(
                        onClick = {
                            showDialog = false
                            onShowWebLogin()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text("浏览器登录（推荐）")
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("或手动粘贴 Cookie", color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = cookieText,
                        onValueChange = { cookieText = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Cookie") },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.Gray,
                            focusedBorderColor = MaterialTheme.colorScheme.primary
                        ),
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                if (cookieText.isNotBlank()) {
                    TextButton(onClick = {
                        CookieManager.saveCookie(context, cookieText)
                        RetrofitClient.updateCookie(cookieText)
                        cookieInfo = CookieManager.getCookieInfo(context)
                        cookieText = ""
                        showDialog = false
                        loadProfile()
                    }) {
                        Text("保存 Cookie", color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    cookieText = ""
                    showDialog = false
                }) {
                    Text("取消", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF282828)
        )
    }

    if (showAccountDialog) {
        AlertDialog(
            onDismissRequest = { showAccountDialog = false },
            title = { Text("账户管理", color = Color.White) },
            text = {
                Column {
                    if (userProfile != null) {
                        Text("昵称: ${userProfile!!.nickname}", color = Color.White)
                        Spacer(Modifier.height(4.dp))
                        Text("UID: ${userProfile!!.userId}", color = Color.Gray)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    CookieManager.clearCookie(context)
                    RetrofitClient.updateCookie(null)
                    cookieInfo = CookieManager.getCookieInfo(context)
                    userProfile = null
                    showAccountDialog = false
                }) {
                    Text("退出登录", color = Color.Red)
                }
            },
            dismissButton = {
                TextButton(onClick = { showAccountDialog = false }) {
                    Text("关闭", color = Color.Gray)
                }
            },
            containerColor = Color(0xFF282828)
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable {
                    if (cookieInfo.hasCookie) showAccountDialog = true
                    else showDialog = true
                }
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(Color(0xFF404040)),
                contentAlignment = Alignment.Center
            ) {
                if (userProfile?.avatarUrl?.isNotEmpty() == true) {
                    AsyncImage(
                        model = userProfile!!.avatarUrl,
                        contentDescription = "头像",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Default.Person,
                        "用户",
                        tint = Color.Gray,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                if (isLoadingProfile) {
                    Text(
                        "加载中...",
                        color = Color.Gray,
                        style = MaterialTheme.typography.titleLarge
                    )
                } else if (userProfile != null) {
                    Text(
                        userProfile!!.nickname,
                        color = Color.White,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "UID: ${userProfile!!.userId}",
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        "未登录",
                        color = Color.Gray,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        "点击登录或设置 Cookie",
                        color = Color.Gray.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFF2A2A2A))
        Spacer(Modifier.height(16.dp))

        if (cookieInfo.hasCookie) {
            OutlinedButton(
                onClick = {
                    CookieManager.clearCookie(context)
                    RetrofitClient.updateCookie(null)
                    cookieInfo = CookieManager.getCookieInfo(context)
                    userProfile = null
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("退出登录", color = Color.Red)
            }
            Spacer(Modifier.height(8.dp))
            OutlinedButton(
                onClick = {
                    cookieText = CookieManager.getCookie(context) ?: ""
                    showDialog = true
                },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("更新 Cookie", color = Color.White)
            }
        }

        Spacer(Modifier.height(24.dp))

        Text(
            "音质偏好",
            color = Color.White,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(12.dp))

        QualitySelector(
            label = "WLAN 环境下",
            selected = wifiQuality,
            options = qualityOptions,
            onSelect = { wifiQuality = it.apply { prefs.edit().putInt("wifi_quality", it).apply() } }
        )
        Spacer(Modifier.height(8.dp))
        QualitySelector(
            label = "移动数据环境下",
            selected = mobileQuality,
            options = qualityOptions,
            onSelect = {
                mobileQuality = it.apply {
                    prefs.edit().putInt("mobile_quality", it).apply()
                }
            }
        )

        Spacer(Modifier.height(24.dp))
        Text(
            "主题色",
            color = MaterialTheme.colorScheme.primary,
            style = MaterialTheme.typography.titleLarge
        )
        Spacer(Modifier.height(12.dp))
        ThemeColorSelector(
            selectedIndex = themeIndex,
            presets = themeColorPresets,
            onSelect = onThemeChange
        )

        Spacer(Modifier.height(32.dp))

        TextButton(onClick = onOpenAbout) {
            Text("关于 Ncrust", color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(150.dp))
    }
}

@Composable
fun QualitySelector(
    label: String,
    selected: Int,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    Column {
        Text(label, color = Color.Gray, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            options.forEachIndexed { index, name ->
                val isSelected = selected == index
                Box(
                    modifier = Modifier
                        .background(
                            if (isSelected) MaterialTheme.colorScheme.primary else Color.Transparent,
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                        )
                        .border(
                            width = 1.dp,
                            color = if (isSelected) MaterialTheme.colorScheme.primary else Color.Gray.copy(
                                alpha = 0.4f
                            ),
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(0.dp)
                        )
                        .clickable { onSelect(index) }
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        name,
                        fontSize = 13.sp,
                        color = if (isSelected) Color.Black else Color.White
                    )
                }
            }
        }
    }
}
