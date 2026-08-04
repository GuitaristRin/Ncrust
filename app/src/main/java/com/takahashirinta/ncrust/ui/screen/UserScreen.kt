package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenu
import io.github.takahashirinta.kanesumi.controls.MetroDropdownMenuItem
import io.github.takahashirinta.kanesumi.controls.MetroSwitch
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.LanguagePreset
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.languagePresets
import com.takahashirinta.ncrust.ui.theme.ThemeColorSelector
import com.takahashirinta.ncrust.ui.theme.themeColorPresets
import kotlinx.coroutines.launch

@Composable
fun UserScreen(
    onOpenAbout: () -> Unit = {},
    themeIndex: Int = 0,
    onThemeChange: (Int) -> Unit = {},
    onShowWebLogin: () -> Unit = {},
    refreshTrigger: Int = 0,
    onLanguageChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val coroutineScope = rememberCoroutineScope()
    var showAccountDialog by remember { mutableStateOf(false) }
    var hasCookie by remember { mutableStateOf(CookieManager.hasCookie(context)) }

    var userProfile by remember { mutableStateOf<PlaylistApi.UserProfile?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("ncrust_settings", 0) }
    var wifiQuality by remember { mutableIntStateOf(prefs.getInt("wifi_quality", 3)) }
    var mobileQuality by remember { mutableIntStateOf(prefs.getInt("mobile_quality", 1)) }
    var gaplessEnabled by remember { mutableStateOf(prefs.getBoolean("gapless_playback", false)) }

    var selectedLanguageCode by remember { mutableStateOf(getSavedLanguageCode(context)) }

    fun loadProfile() {
        if (!CookieManager.hasCookie(context)) {
            userProfile = null
            hasCookie = false
            return
        }
        coroutineScope.launch {
            isLoadingProfile = true
            try {
                val profile = PlaylistApi.getUserProfile()
                // 服务端对失效 cookie 会返回空 account/profile → userId=0。
                // 此时判定为已过期，主动清除本地 cookie，避免 UI 卡在 "UID: 0"。
                if (profile.userId == 0L) {
                    CookieManager.clearCookie(context)
                    RetrofitClient.updateCookie(null)
                    hasCookie = false
                    userProfile = null
                } else {
                    userProfile = profile
                    hasCookie = true
                }
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
            hasCookie = CookieManager.hasCookie(context)
            loadProfile()
        }
    }

    if (showAccountDialog) AccountDialog(
        userProfile = userProfile,
        onDismiss = { showAccountDialog = false },
        onLogout = {
            CookieManager.clearCookie(context)
            RetrofitClient.updateCookie(null)
            hasCookie = false
            userProfile = null
            showAccountDialog = false
        }
    )

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = BottomOverlayInsetDp),
        flingBehavior = rememberMetroFlingBehavior()
    ) {
        // Groove 大字页头。
        item {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(start = 16.dp, top = 20.dp, bottom = 8.dp)
            ) {
                MetroText(
                    strings.tabUser,
                    color = Color.White,
                    style = LocalMetroTypography.current.pageHeading,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Profile 块。整块可点：已登录 → 账户管理；未登录 → 直接进 WebView 登录。
        item {
            ProfileBlock(
                isLoading = isLoadingProfile,
                profile = userProfile,
                notLoggedInText = strings.notLoggedIn,
                loginHintText = strings.loginHint,
                uidLabel = strings.uidLabel(userProfile?.userId?.toString() ?: ""),
                onClick = {
                    if (hasCookie) showAccountDialog = true
                    else onShowWebLogin()
                }
            )
            Spacer(Modifier.height(24.dp))
        }

        // 音质
        item {
            SectionTitle(strings.qualitySectionTitle)
            MetroDropdownRow(
                label = strings.wifiQualityLabel,
                selectedIndex = wifiQuality,
                options = strings.qualityOptions,
                onSelect = {
                    wifiQuality = it
                    prefs.edit().putInt("wifi_quality", it).apply()
                }
            )
            MetroDropdownRow(
                label = strings.mobileQualityLabel,
                selectedIndex = mobileQuality,
                options = strings.qualityOptions,
                onSelect = {
                    mobileQuality = it
                    prefs.edit().putInt("mobile_quality", it).apply()
                }
            )
            Spacer(Modifier.height(24.dp))
        }

        // 播放
        item {
            SectionTitle(strings.gaplessSectionTitle)
            // 无缝播放：标题 + 描述在左（自动换行），Switch 固定 52dp 在右上角。
            // 描述 weight(1f)，任何语言的长文都能自然换行不撑破。
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    MetroText(
                        strings.gaplessDescription,
                        color = Color.Gray,
                        style = LocalMetroTypography.current.caption,
                    )
                }
                Spacer(Modifier.width(16.dp))
                MetroSwitch(
                    checked = gaplessEnabled,
                    onCheckedChange = {
                        gaplessEnabled = it
                        prefs.edit().putBoolean("gapless_playback", it).apply()
                    }
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        // 外观：主题色 + 语言
        item {
            SectionTitle(strings.themeSectionTitle)
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                ThemeColorSelector(
                    selectedIndex = themeIndex,
                    presets = themeColorPresets,
                    onSelect = onThemeChange
                )
            }
            Spacer(Modifier.height(24.dp))

            SectionTitle(strings.languageSectionTitle)
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                MetroLanguageDropdown(
                    selectedCode = selectedLanguageCode,
                    presets = languagePresets,
                    onSelect = { code ->
                        if (code != selectedLanguageCode) {
                            selectedLanguageCode = code
                            onLanguageChange(code)
                        }
                    }
                )
            }
            Spacer(Modifier.height(32.dp))
        }

        // 关于：单行条目，右侧带箭头。
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(onClick = onOpenAbout)
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.aboutButton,
                    color = Color.White,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroIcon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.Gray,
                    sizeDp = 20.dp,
                )
            }
        }
    }
}

/** Profile 块：96dp 方形头像 + 昵称 titleLarge + UID/登录状态 bodySmall。整块可点。 */
@Composable
private fun ProfileBlock(
    isLoading: Boolean,
    profile: PlaylistApi.UserProfile?,
    notLoggedInText: String,
    loginHintText: String,
    uidLabel: String,
    onClick: () -> Unit
) {
    val strings = LocalStrings.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .background(Color(0xFF404040)),
            contentAlignment = Alignment.Center
        ) {
            if (profile?.avatarUrl?.isNotEmpty() == true) {
                AsyncImage(
                    model = profile.avatarUrl,
                    contentDescription = strings.userAvatarDesc,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                MetroIcon(
                    Icons.Default.Person,
                    strings.userIconDesc,
                    tint = Color.Gray,
                    sizeDp = 52.dp,
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        // 文字列 weight(1f)：任何语言的昵称/提示都能换行不撑破。
        Column(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> MetroText(
                    strings.loading,
                    color = Color.Gray,
                    style = TextStyle(fontSize = 20.sp),
                )
                profile != null -> {
                    MetroText(
                        profile.nickname,
                        color = Color.White,
                        style = LocalMetroTypography.current.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    MetroText(
                        uidLabel,
                        color = Color.Gray,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> {
                    MetroText(
                        notLoggedInText,
                        color = Color.Gray,
                        style = LocalMetroTypography.current.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    MetroText(
                        loginHintText,
                        color = Color.Gray.copy(alpha = 0.6f),
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

/** Groove 风分区标题：16sp semi-bold、上留白 4dp、左 16dp。 */
@Composable
private fun SectionTitle(text: String) {
    MetroText(
        text,
        color = Color.White,
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
    )
}

/**
 * 单行下拉：左侧 label + 右侧「选中值 + ▼」，点击整行弹出垂直菜单。
 *
 * 多语言鲁棒：
 *  - label 用 weight(1f)，任何语言都能换行，不会挤到右侧值。
 *  - 选中值用 maxLines=1 + Ellipsis + widthIn(max=160dp)，极端长文会截断但不会撑破布局。
 *  - 下拉展开的菜单里每项独占一行，完整显示，用户始终能看到完整名字。
 */
@Composable
private fun MetroDropdownRow(
    label: String,
    selectedIndex: Int,
    options: List<String>,
    onSelect: (Int) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroText(
                label,
                color = Color.White,
                style = TextStyle(fontSize = 15.sp),
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            MetroText(
                options.getOrElse(selectedIndex) { "" },
                color = LocalMetroColors.current.primary,
                style = TextStyle(fontSize = 15.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp)
            )
            MetroIcon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = Color.Gray,
                sizeDp = 20.dp,
            )
        }
        // alignment = BottomEnd 让菜单右对齐箭头,而不是散在整宽 Row 的左侧。
        MetroDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            alignment = Alignment.BottomEnd,
        ) {
            options.forEachIndexed { index, name ->
                MetroDropdownMenuItem(
                    text = name,
                    textColor = if (index == selectedIndex) LocalMetroColors.current.primary else Color.White,
                    onClick = {
                        onSelect(index)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun AccountDialog(
    userProfile: PlaylistApi.UserProfile?,
    onDismiss: () -> Unit,
    onLogout: () -> Unit
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF282828))
                .padding(24.dp)
        ) {
            MetroText(
                strings.accountDialogTitle,
                color = Color.White,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(16.dp))

            if (userProfile != null) {
                MetroText(
                    strings.nicknameLabel(userProfile.nickname),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                MetroText(
                    strings.uidLabel(userProfile.userId.toString()),
                    color = Color.Gray,
                    style = TextStyle(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(20.dp))
            }

            // 全宽按钮：容器 fillMaxWidth，文字 Center + 换行——极长翻译最多多占一行，不会撑破对话框。
            FullWidthDialogButton(
                text = strings.logoutButton,
                accent = false,
                borderColor = Color.Red.copy(alpha = 0.5f),
                textColor = Color.Red,
                onClick = onLogout
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                DialogButton(
                    text = strings.close,
                    accent = false,
                    onClick = onDismiss
                )
            }
        }
    }
}

/** 短按钮：内容包裹式（wrap content）。用于对话框右下"取消/关闭/保存"。 */
@Composable
private fun DialogButton(
    text: String,
    accent: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .then(
                if (accent) Modifier.background(LocalMetroColors.current.primary)
                else Modifier.border(1.dp, Color.Gray.copy(alpha = 0.4f))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        MetroText(
            text,
            color = if (accent) Color.Black else Color.Gray,
            style = TextStyle(fontSize = 14.sp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** 全宽按钮：填满可用宽度，文字居中，多行安全。用于对话框内的"更新 Cookie/退出登录"。 */
@Composable
private fun FullWidthDialogButton(
    text: String,
    accent: Boolean,
    borderColor: Color = Color.Gray.copy(alpha = 0.4f),
    textColor: Color = Color.White,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (accent) Modifier.background(LocalMetroColors.current.primary)
                else Modifier.border(1.dp, borderColor)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        MetroText(
            text,
            color = if (accent) Color.Black else textColor,
            style = TextStyle(fontSize = 14.sp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 语言下拉。闭合态显示当前选中语言名 + 箭头；点击弹出所有语言。
 *
 * 多语言鲁棒：闭合态 Text 设 maxLines=1 + Ellipsis + weight(1f)。极长名如
 * "Советский русский"/"Middle English" 最多截断，绝不换行撑破箭头位置。
 */
@Composable
fun MetroLanguageDropdown(
    selectedCode: String,
    presets: List<LanguagePreset>,
    onSelect: (String) -> Unit
) {
    val selected = presets.find { it.code == selectedCode } ?: presets.first()
    var expanded by remember { mutableStateOf(false) }

    Box(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .border(1.dp, Color.Gray.copy(alpha = 0.4f))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroText(
                selected.displayName,
                color = Color.White,
                style = TextStyle(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            MetroIcon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = Color.Gray,
                sizeDp = 20.dp,
            )
        }

        // alignment = BottomEnd 让菜单右对齐箭头。maxWidthDp 放大以承载长语言名。
        MetroDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            alignment = Alignment.BottomEnd,
            maxWidthDp = 320.dp,
        ) {
            presets.forEach { preset ->
                MetroDropdownMenuItem(
                    text = preset.displayName,
                    textColor = if (preset.code == selectedCode)
                        LocalMetroColors.current.primary else Color.White,
                    onClick = {
                        onSelect(preset.code)
                        expanded = false
                    },
                )
            }
        }
    }
}

