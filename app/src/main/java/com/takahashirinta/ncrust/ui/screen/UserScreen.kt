package com.takahashirinta.ncrust.ui.screen
import com.takahashirinta.ncrust.ui.theme.LocalNcrustTypography
import com.takahashirinta.ncrust.ui.theme.LocalNcrustColors

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.takahashirinta.kanesumi.anim.sokuou.SokuouPresets
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
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
    var cookieText by remember { mutableStateOf("") }
    var showDialog by remember { mutableStateOf(false) }
    var showAccountDialog by remember { mutableStateOf(false) }
    var cookieInfo by remember { mutableStateOf(CookieManager.getCookieInfo(context)) }

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

    if (showDialog) LoginDialog(
        cookieText = cookieText,
        onCookieTextChange = { cookieText = it },
        onDismiss = { showDialog = false },
        onWebLogin = { showDialog = false; onShowWebLogin() },
        onSave = {
            CookieManager.saveCookie(context, cookieText)
            RetrofitClient.updateCookie(cookieText)
            cookieInfo = CookieManager.getCookieInfo(context)
            cookieText = ""
            showDialog = false
            loadProfile()
        }
    )

    if (showAccountDialog) AccountDialog(
        userProfile = userProfile,
        onDismiss = { showAccountDialog = false },
        onUpdateCookie = {
            cookieText = CookieManager.getCookie(context) ?: ""
            showAccountDialog = false
            showDialog = true
        },
        onLogout = {
            CookieManager.clearCookie(context)
            RetrofitClient.updateCookie(null)
            cookieInfo = CookieManager.getCookieInfo(context)
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
                Text(
                    strings.tabUser,
                    color = Color.White,
                    fontSize = 34.sp,
                    fontWeight = FontWeight.Normal
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // Profile 块。整块可点：已登录 → 账户管理；未登录 → 登录弹窗。
        item {
            ProfileBlock(
                isLoading = isLoadingProfile,
                profile = userProfile,
                notLoggedInText = strings.notLoggedIn,
                loginHintText = strings.loginHint,
                uidLabel = strings.uidLabel(userProfile?.userId?.toString() ?: ""),
                onClick = {
                    if (cookieInfo.hasCookie) showAccountDialog = true
                    else showDialog = true
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
                    Text(
                        strings.gaplessDescription,
                        color = Color.Gray,
                        fontSize = 13.sp
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
                Text(
                    strings.aboutButton,
                    color = Color.White,
                    fontSize = 15.sp,
                    modifier = Modifier.weight(1f)
                )
                Icon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = Color.Gray,
                    modifier = Modifier.size(20.dp)
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
                Icon(
                    Icons.Default.Person,
                    strings.userIconDesc,
                    tint = Color.Gray,
                    modifier = Modifier.size(52.dp)
                )
            }
        }
        Spacer(Modifier.width(16.dp))
        // 文字列 weight(1f)：任何语言的昵称/提示都能换行不撑破。
        Column(modifier = Modifier.weight(1f)) {
            when {
                isLoading -> Text(
                    strings.loading,
                    color = Color.Gray,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Normal
                )
                profile != null -> {
                    Text(
                        profile.nickname,
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        uidLabel,
                        color = Color.Gray,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> {
                    Text(
                        notLoggedInText,
                        color = Color.Gray,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Normal,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        loginHintText,
                        color = Color.Gray.copy(alpha = 0.6f),
                        fontSize = 12.sp,
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
    Text(
        text,
        color = Color.White,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
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
            Text(
                label,
                color = Color.White,
                fontSize = 15.sp,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                options.getOrElse(selectedIndex) { "" },
                color = LocalNcrustColors.current.primary,
                fontSize = 15.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = 160.dp)
            )
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            shape = RectangleShape,
            containerColor = Color(0xFF282828)
        ) {
            options.forEachIndexed { index, name ->
                DropdownMenuItem(
                    text = {
                        Text(
                            name,
                            color = if (index == selectedIndex) LocalNcrustColors.current.primary else Color.White,
                            fontSize = 14.sp
                        )
                    },
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
private fun LoginDialog(
    cookieText: String,
    onCookieTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onWebLogin: () -> Unit,
    onSave: () -> Unit
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF282828))
                .padding(24.dp)
        ) {
            Text(
                strings.loginDialogTitle,
                color = Color.White,
                style = LocalNcrustTypography.current.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(20.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(LocalNcrustColors.current.primary)
                    .clickable(onClick = onWebLogin)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(strings.webLoginButton, color = Color.Black, fontWeight = FontWeight.Medium)
            }

            Spacer(Modifier.height(20.dp))
            Text(strings.manualCookieHint, color = Color.Gray, fontSize = 13.sp)
            Spacer(Modifier.height(8.dp))

            OutlinedTextField(
                value = cookieText,
                onValueChange = onCookieTextChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text(strings.cookieFieldLabel) },
                shape = RectangleShape,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.Gray,
                    focusedBorderColor = LocalNcrustColors.current.primary,
                    unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
                    focusedLabelColor = LocalNcrustColors.current.primary,
                    unfocusedLabelColor = Color.Gray,
                    cursorColor = LocalNcrustColors.current.primary
                ),
                maxLines = 3
            )

            Spacer(Modifier.height(20.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                DialogButton(
                    text = strings.cancel,
                    accent = false,
                    onClick = { onCookieTextChange(""); onDismiss() }
                )
                if (cookieText.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    DialogButton(
                        text = strings.saveCookieButton,
                        accent = true,
                        onClick = onSave
                    )
                }
            }
        }
    }
}

@Composable
private fun AccountDialog(
    userProfile: PlaylistApi.UserProfile?,
    onDismiss: () -> Unit,
    onUpdateCookie: () -> Unit,
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
            Text(
                strings.accountDialogTitle,
                color = Color.White,
                style = LocalNcrustTypography.current.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(16.dp))

            if (userProfile != null) {
                Text(
                    strings.nicknameLabel(userProfile.nickname),
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    strings.uidLabel(userProfile.userId.toString()),
                    color = Color.Gray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(20.dp))
            }

            // 全宽按钮：容器 fillMaxWidth，文字 Center + 换行——极长翻译最多多占一行，不会撑破对话框。
            FullWidthDialogButton(
                text = strings.updateCookieButton,
                accent = false,
                onClick = onUpdateCookie
            )

            Spacer(Modifier.height(8.dp))

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
                if (accent) Modifier.background(LocalNcrustColors.current.primary)
                else Modifier.border(1.dp, Color.Gray.copy(alpha = 0.4f))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        Text(
            text,
            color = if (accent) Color.Black else Color.Gray,
            fontSize = 14.sp,
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
                if (accent) Modifier.background(LocalNcrustColors.current.primary)
                else Modifier.border(1.dp, borderColor)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text,
            color = if (accent) Color.Black else textColor,
            fontSize = 14.sp,
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
            Text(
                selected.displayName,
                color = Color.White,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            Icon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier.fillMaxWidth(),
            shape = RectangleShape,
            containerColor = Color(0xFF282828)
        ) {
            presets.forEach { preset ->
                DropdownMenuItem(
                    text = {
                        Text(
                            preset.displayName,
                            color = if (preset.code == selectedCode)
                                LocalNcrustColors.current.primary else Color.White,
                            fontSize = 14.sp
                        )
                    },
                    onClick = {
                        onSelect(preset.code)
                        expanded = false
                    },
                    colors = MenuItemColors(
                        textColor = Color.White,
                        leadingIconColor = Color.White,
                        trailingIconColor = Color.White,
                        disabledTextColor = Color.Gray,
                        disabledLeadingIconColor = Color.Gray,
                        disabledTrailingIconColor = Color.Gray
                    )
                )
            }
        }
    }
}

@Composable
fun MetroSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val accent = LocalNcrustColors.current.primary
    val trackOff = Color(0xFF333333)
    val borderOff = Color.Gray.copy(alpha = 0.35f)
    // 单一 progress 驱动 track 颜色、border 颜色、thumb 位移。旧实现两个 animateXxxAsState
    // 每次翻转触发一次 MetroSwitch recomposition；这里 drawBehind 只在 draw 阶段读取 .value。
    val progress = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) {
        progress.animateTo(if (checked) 1f else 0f, SokuouPresets.ToggleFlip)
    }
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(28.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
            .drawBehind {
                val p = progress.value
                val track = lerp(trackOff, accent, p)
                val border = lerp(borderOff, accent, p)
                drawRect(track)
                val strokeWidth = 1.dp.toPx()
                drawRect(color = border, style = Stroke(width = strokeWidth))
                val padPx = 3.dp.toPx()
                val thumbW = 22.dp.toPx()
                val travel = size.width - padPx * 2f - thumbW
                val thumbX = padPx + travel * p
                val thumbColor = if (p > 0.5f) Color.Black else Color.White
                drawRect(
                    color = thumbColor,
                    topLeft = Offset(thumbX, padPx),
                    size = Size(thumbW, size.height - padPx * 2f)
                )
            }
    )
}
