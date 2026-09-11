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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.takahashirinta.kanesumi.anim.sokuou.rememberMetroFlingBehavior
import io.github.takahashirinta.kanesumi.controls.MetroSelectorFlyout
import io.github.takahashirinta.kanesumi.controls.MetroSwitch
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import coil.compose.AsyncImage
import androidx.lifecycle.viewmodel.compose.viewModel
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.cache.ContentCache
import android.content.Context
import android.widget.Toast
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import com.takahashirinta.ncrust.power.BackgroundActivity
import com.takahashirinta.ncrust.ui.BottomOverlayInsetDp
import com.takahashirinta.ncrust.ui.components.QrAuthorizeScreen
import com.takahashirinta.ncrust.ui.components.QrLoginDialog
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.LanguagePreset
import com.takahashirinta.ncrust.ui.i18n.getSavedLanguageCode
import com.takahashirinta.ncrust.ui.i18n.languagePresets
import com.takahashirinta.ncrust.ui.theme.ThemeColorSelector
import com.takahashirinta.ncrust.ui.theme.ThemeMode
import com.takahashirinta.ncrust.ui.theme.themeColorPresets
import com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel
import kotlinx.coroutines.launch

@Composable
fun UserScreen(
    onOpenAbout: () -> Unit = {},
    themeIndex: Int = 0,
    onThemeChange: (Int) -> Unit = {},
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    onThemeModeChange: (ThemeMode) -> Unit = {},
    onShowWebLogin: () -> Unit = {},
    refreshTrigger: Int = 0,
    onLanguageChange: (String) -> Unit = {}
) {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val coroutineScope = rememberCoroutineScope()
    var showAccountDialog by remember { mutableStateOf(false) }
    var showQrLogin by remember { mutableStateOf(false) }
    var showScanner by remember { mutableStateOf(false) }
    var showClearCacheConfirm by remember { mutableStateOf(false) }
    var cacheSize by remember { mutableStateOf(currentCacheSize(context)) }
    var hasCookie by remember { mutableStateOf(CookieManager.hasCookie(context)) }
    // 扫码登录为平板 / 大屏独占：手机端未登录点头像仍直接进 WebView 官方登录页。
    val isWideLayout = LocalConfiguration.current.screenWidthDp >= 600

    var userProfile by remember { mutableStateOf<PlaylistApi.UserProfile?>(null) }
    var isLoadingProfile by remember { mutableStateOf(false) }

    val prefs = remember { context.getSharedPreferences("ncrust_settings", 0) }
    val playerViewModel: PlayerViewModel = viewModel()
    var wifiQuality by remember { mutableIntStateOf(prefs.getInt("wifi_quality", 3)) }
    var mobileQuality by remember { mutableIntStateOf(prefs.getInt("mobile_quality", 1)) }
    var gaplessEnabled by remember { mutableStateOf(prefs.getBoolean("gapless_playback", true)) }
    var lyricsTranslation by remember { mutableStateOf(prefs.getBoolean("lyrics_translation", true)) }

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
        onScanAuthorize = {
            showAccountDialog = false
            showScanner = true
        },
        onLogout = {
            CookieManager.clearCookie(context)
            RetrofitClient.updateCookie(null)
            hasCookie = false
            userProfile = null
            showAccountDialog = false
        }
    )

    // 手机端扫码授权：扫平板登录二维码 → 解析 unikey → 局域网回传本机 cookie。
    // 连接/成功/失败状态都在扫码页内呈现，不再扫到即关闭。
    if (showScanner) QrAuthorizeScreen(
        onAuthorized = { showScanner = false },
        onClose = { showScanner = false }
    )

    if (showQrLogin) QrLoginDialog(
        onLoginSuccess = { cookie ->
            CookieManager.saveCookie(context, cookie)
            RetrofitClient.updateCookie(cookie)
            hasCookie = true
            showQrLogin = false
            loadProfile()
        },
        onGenericLogin = {
            showQrLogin = false
            onShowWebLogin()
        },
        onDismiss = { showQrLogin = false }
    )

    if (showClearCacheConfirm) ClearCacheConfirmDialog(
        onConfirm = {
            ContentCache.clearAll()
            runCatching { coil.Coil.imageLoader(context).memoryCache?.clear() }
            runCatching { coil.Coil.imageLoader(context).diskCache?.clear() }
            runCatching {
                context.cacheDir?.let { dir ->
                    dir.listFiles()
                        ?.filter { it.name == "WebView" || it.name == "http" }
                        ?.forEach { it.deleteRecursively() }
                }
            }
            cacheSize = currentCacheSize(context)
            Toast.makeText(context, strings.cacheCleared, Toast.LENGTH_SHORT).show()
            showClearCacheConfirm = false
        },
        onDismiss = { showClearCacheConfirm = false }
    )

    // 宽屏设置/资料内容居中限宽（上限 720dp），避免设置行横跨平板。
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
    LazyColumn(
        modifier = Modifier.widthIn(max = 720.dp).fillMaxHeight(),
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
                    color = LocalMetroColors.current.onBackground,
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
                    when {
                        hasCookie -> showAccountDialog = true
                        // 平板/大屏：原生扫码（官方 App 扫），下面再给「通用登录」退回 WebView
                        isWideLayout -> showQrLogin = true
                        else -> onShowWebLogin()
                    }
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
            SectionTitle(strings.playbackSectionTitle)
            // 标题与 Switch 同行居中，描述另起一行——描述不参与对齐，否则 Switch
            // 会被顶到与描述顶部对齐，看起来像挂在描述上。
            SettingSwitchRow(
                title = strings.gaplessSectionTitle,
                description = strings.gaplessDescription,
                checked = gaplessEnabled,
                onCheckedChange = {
                    gaplessEnabled = it
                    prefs.edit().putBoolean("gapless_playback", it).apply()
                    // 即时生效: VM 缓存的 gaplessEnabled 不刷新的话,
                    // 本首歌的预载状态与开关不一致, 要等下一首歌才对上
                    playerViewModel.refreshGaplessSetting()
                }
            )
            // 歌词翻译开关(Spotify 式双语:原句下方小号译文)。切了立即生效,播放器常挂载无需重进。
            SettingSwitchRow(
                title = strings.lyricsTranslationLabel,
                checked = lyricsTranslation,
                onCheckedChange = {
                    lyricsTranslation = it
                    prefs.edit().putBoolean("lyrics_translation", it).apply()
                    playerViewModel.setLyricsTranslation(it)
                }
            )
            Spacer(Modifier.height(24.dp))
        }

        // 外观：主题模式 + 主题色 + 语言
        item {
            SectionTitle(strings.themeModeSectionTitle)
            ThemeModeSelector(
                selected = themeMode,
                labels = Triple(
                    strings.themeModeSystem,
                    strings.themeModeDark,
                    strings.themeModeLight
                ),
                onSelect = onThemeModeChange
            )
            Spacer(Modifier.height(24.dp))

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

        // 后台运行：跳转系统"允许后台活动 / 忽略电池优化"设置（与首次启动弹窗同一入口）。
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        runCatching {
                            context.startActivity(BackgroundActivity.requestIntent(context))
                        }.onFailure {
                            runCatching {
                                context.startActivity(BackgroundActivity.appDetailsIntent(context))
                            }
                        }
                    }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.batteryTitle,
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroIcon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 20.dp,
                )
            }
            Spacer(Modifier.height(24.dp))
        }

        // 存储与缓存：显示当前占用，点击后弹窗确认再清除。
        item {
            SectionTitle(strings.storageSectionTitle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showClearCacheConfirm = true }
                    .padding(horizontal = 16.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                MetroText(
                    strings.cacheSizeLabel(cacheSize),
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroText(
                    strings.clearCache,
                    color = LocalMetroColors.current.primary,
                    style = TextStyle(fontSize = 15.sp)
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
                    color = LocalMetroColors.current.onBackground,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier.weight(1f)
                )
                MetroIcon(
                    Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = LocalMetroColors.current.onSurfaceVariant,
                    sizeDp = 20.dp,
                )
            }
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
                .background(LocalMetroColors.current.surfaceVariant),
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
                    tint = LocalMetroColors.current.onSurfaceVariant,
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
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 20.sp),
                )
                profile != null -> {
                    MetroText(
                        profile.nickname,
                        color = LocalMetroColors.current.onBackground,
                        style = LocalMetroTypography.current.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    MetroText(
                        uidLabel,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                else -> {
                    MetroText(
                        notLoggedInText,
                        color = LocalMetroColors.current.onSurfaceVariant,
                        style = LocalMetroTypography.current.title,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    MetroText(
                        loginHintText,
                        color = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.6f),
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
        color = LocalMetroColors.current.onBackground,
        style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
        modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 8.dp)
    )
}

/**
 * 开关设置行：标题与 Switch 同一行垂直居中，描述（可选）另起一行。
 * 描述不参与对齐，避免 Switch 被顶到与描述顶部对齐。
 */
@Composable
private fun SettingSwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    description: String? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            title,
            color = LocalMetroColors.current.onBackground,
            style = LocalMetroTypography.current.bodyMedium,
            modifier = Modifier.weight(1f)
        )
        Spacer(Modifier.width(16.dp))
        MetroSwitch(checked = checked, onCheckedChange = onCheckedChange)
    }
    if (description != null) {
        MetroText(
            description,
            color = LocalMetroColors.current.onSurfaceVariant,
            style = LocalMetroTypography.current.caption,
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 4.dp)
        )
    }
}

/** 主题模式三选一：跟随系统 / 深色 / 浅色。 */
@Composable
private fun ThemeModeSelector(
    selected: ThemeMode,
    labels: Triple<String, String, String>,
    onSelect: (ThemeMode) -> Unit
) {
    val options = listOf(
        ThemeMode.SYSTEM to labels.first,
        ThemeMode.DARK to labels.second,
        ThemeMode.LIGHT to labels.third
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        options.forEach { (mode, label) ->
            val active = mode == selected
            Box(
                modifier = Modifier
                    .weight(1f)
                    .border(
                        1.dp,
                        if (active) LocalMetroColors.current.primary
                        else LocalMetroColors.current.divider
                    )
                    .background(
                        if (active) LocalMetroColors.current.primary.copy(alpha = 0.14f)
                        else Color.Transparent
                    )
                    .clickable { onSelect(mode) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                MetroText(
                    label,
                    color = if (active) LocalMetroColors.current.primary
                    else LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 14.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/** 清除缓存确认弹窗。 */
@Composable
private fun ClearCacheConfirmDialog(
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalMetroColors.current.surface)
                .padding(24.dp)
        ) {
            MetroText(
                strings.clearCache,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(12.dp))
            MetroText(
                strings.clearCacheConfirm,
                color = LocalMetroColors.current.onSurfaceVariant,
                style = LocalMetroTypography.current.bodyMedium,
            )
            Spacer(Modifier.height(24.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                DialogButton(text = strings.cancel, accent = false, onClick = onDismiss)
                Spacer(Modifier.width(12.dp))
                DialogButton(text = strings.clearCache, accent = true, onClick = onConfirm)
            }
        }
    }
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
                color = LocalMetroColors.current.onBackground,
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
                tint = LocalMetroColors.current.onSurfaceVariant,
                sizeDp = 20.dp,
            )
        }
        // UWP ComboBox 移植:选中项落回锚点原位,菜单从锚点双向展开。
        MetroSelectorFlyout(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
        )
    }
}

@Composable
private fun AccountDialog(
    userProfile: PlaylistApi.UserProfile?,
    onDismiss: () -> Unit,
    onScanAuthorize: () -> Unit,
    onLogout: () -> Unit
) {
    val strings = LocalStrings.current
    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(LocalMetroColors.current.surface)
                .padding(24.dp)
        ) {
            MetroText(
                strings.accountDialogTitle,
                color = LocalMetroColors.current.onBackground,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(16.dp))

            if (userProfile != null) {
                MetroText(
                    strings.nicknameLabel(userProfile.nickname),
                    color = LocalMetroColors.current.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                MetroText(
                    strings.uidLabel(userProfile.userId.toString()),
                    color = LocalMetroColors.current.onSurfaceVariant,
                    style = TextStyle(fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(20.dp))
            }

            // 扫码授权其他设备：扫平板的登录二维码，经局域网把本机账号授权过去。
            FullWidthDialogButton(
                text = strings.scanEntryTitle,
                accent = false,
                borderColor = LocalMetroColors.current.primary,
                textColor = LocalMetroColors.current.primary,
                onClick = onScanAuthorize
            )
            Spacer(Modifier.height(12.dp))

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
                else Modifier.border(1.dp, LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f))
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        MetroText(
            text,
            color = if (accent) Color.Black else LocalMetroColors.current.onSurfaceVariant,
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
    borderColor: Color = LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f),
    textColor: Color = LocalMetroColors.current.onBackground,
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
                .border(1.dp, LocalMetroColors.current.onSurfaceVariant.copy(alpha = 0.4f))
                .clickable { expanded = true }
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MetroText(
                selected.displayName,
                color = LocalMetroColors.current.onBackground,
                style = TextStyle(fontSize = 14.sp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            Spacer(Modifier.width(8.dp))
            MetroIcon(
                Icons.Default.ArrowDropDown,
                contentDescription = null,
                tint = LocalMetroColors.current.onSurfaceVariant,
                sizeDp = 20.dp,
            )
        }

        // UWP ComboBox 移植:选中项落回锚点原位,内部滚动到选中语言。
        val selectedIndex = presets.indexOfFirst { it.code == selectedCode }.coerceAtLeast(0)
        MetroSelectorFlyout(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            options = presets.map { it.displayName },
            selectedIndex = selectedIndex,
            onSelect = { index ->
                onSelect(presets[index].code)
                expanded = false
            },
        )
    }
}


/** 统计应用缓存占用：图片磁盘缓存 + 缓存目录（含 WebView 缓存）。 */
private fun currentCacheSize(context: Context): Long {
    var total = 0L
    runCatching { coil.Coil.imageLoader(context).diskCache?.size?.let { total += it } }
    runCatching { context.cacheDir?.let { total += folderSize(it) } }
    return total
}

private fun folderSize(dir: java.io.File): Long {
    var size = 0L
    dir.listFiles()?.forEach { f ->
        size += if (f.isDirectory) folderSize(f) else f.length()
    }
    return size
}
