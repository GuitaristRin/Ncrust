package com.takahashirinta.ncrust.ui.screen

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
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

    // 登录 / 更新 Cookie 弹窗
    if (showDialog) {
        Dialog(onDismissRequest = { showDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF282828))
                    .padding(24.dp)
            ) {
                Text(
                    strings.loginDialogTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(20.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable {
                            showDialog = false
                            onShowWebLogin()
                        }
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
                    onValueChange = { cookieText = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text(strings.cookieFieldLabel) },
                    shape = RectangleShape,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.Gray,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = Color.Gray.copy(alpha = 0.4f),
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                        unfocusedLabelColor = Color.Gray,
                        cursorColor = MaterialTheme.colorScheme.primary
                    ),
                    maxLines = 3
                )

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .border(1.dp, Color.Gray.copy(alpha = 0.4f))
                            .clickable {
                                cookieText = ""
                                showDialog = false
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(strings.cancel, color = Color.Gray, fontSize = 14.sp)
                    }
                    if (cookieText.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable {
                                    CookieManager.saveCookie(context, cookieText)
                                    RetrofitClient.updateCookie(cookieText)
                                    cookieInfo = CookieManager.getCookieInfo(context)
                                    cookieText = ""
                                    showDialog = false
                                    loadProfile()
                                }
                                .padding(horizontal = 20.dp, vertical = 10.dp)
                        ) {
                            Text(strings.saveCookieButton, color = Color.Black, fontSize = 14.sp)
                        }
                    }
                }
            }
        }
    }

    // 账户管理弹窗
    if (showAccountDialog) {
        Dialog(onDismissRequest = { showAccountDialog = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF282828))
                    .padding(24.dp)
            ) {
                Text(
                    strings.accountDialogTitle,
                    color = Color.White,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(16.dp))

                if (userProfile != null) {
                    Text(strings.nicknameLabel(userProfile!!.nickname), color = Color.White)
                    Spacer(Modifier.height(4.dp))
                    Text(strings.uidLabel(userProfile!!.userId.toString()), color = Color.Gray, fontSize = 13.sp)
                    Spacer(Modifier.height(20.dp))
                }

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.Gray.copy(alpha = 0.4f))
                        .clickable {
                            cookieText = CookieManager.getCookie(context) ?: ""
                            showAccountDialog = false
                            showDialog = true
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(strings.updateCookieButton, color = Color.White, fontSize = 14.sp)
                }

                Spacer(Modifier.height(8.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .border(1.dp, Color.Red.copy(alpha = 0.5f))
                        .clickable {
                            CookieManager.clearCookie(context)
                            RetrofitClient.updateCookie(null)
                            cookieInfo = CookieManager.getCookieInfo(context)
                            userProfile = null
                            showAccountDialog = false
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(strings.logoutButton, color = Color.Red, fontSize = 14.sp)
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    Box(
                        modifier = Modifier
                            .border(1.dp, Color.Gray.copy(alpha = 0.4f))
                            .clickable { showAccountDialog = false }
                            .padding(horizontal = 20.dp, vertical = 10.dp)
                    ) {
                        Text(strings.close, color = Color.Gray, fontSize = 14.sp)
                    }
                }
            }
        }
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
                        strings.loading,
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
                        strings.uidLabel(userProfile!!.userId.toString()),
                        color = Color.Gray,
                        style = MaterialTheme.typography.bodySmall
                    )
                } else {
                    Text(
                        strings.notLoggedIn,
                        color = Color.Gray,
                        style = MaterialTheme.typography.titleLarge
                    )
                    Text(
                        strings.loginHint,
                        color = Color.Gray.copy(alpha = 0.6f),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        HorizontalDivider(color = Color(0xFF2A2A2A))
        Spacer(Modifier.height(24.dp))

        Text(
            strings.qualitySectionTitle,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))

        QualitySelector(
            label = strings.wifiQualityLabel,
            selected = wifiQuality,
            options = strings.qualityOptions,
            onSelect = { wifiQuality = it.apply { prefs.edit().putInt("wifi_quality", it).apply() } }
        )
        Spacer(Modifier.height(8.dp))
        QualitySelector(
            label = strings.mobileQualityLabel,
            selected = mobileQuality,
            options = strings.qualityOptions,
            onSelect = {
                mobileQuality = it.apply {
                    prefs.edit().putInt("mobile_quality", it).apply()
                }
            }
        )

        Spacer(Modifier.height(24.dp))
        Text(
            strings.gaplessSectionTitle,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                strings.gaplessDescription,
                color = Color.Gray,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f)
            )
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
        Text(
            strings.themeSectionTitle,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
        ThemeColorSelector(
            selectedIndex = themeIndex,
            presets = themeColorPresets,
            onSelect = onThemeChange
        )

        Spacer(Modifier.height(24.dp))
        Text(
            strings.languageSectionTitle,
            color = Color.White,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold
        )
        Spacer(Modifier.height(12.dp))
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

        Spacer(Modifier.height(32.dp))

        TextButton(onClick = onOpenAbout) {
            Text(strings.aboutButton, color = MaterialTheme.colorScheme.primary, fontSize = 16.sp)
        }

        Spacer(modifier = Modifier.height(150.dp))
    }
}

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
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(selected.displayName, color = Color.White, fontSize = 14.sp)
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
                                MaterialTheme.colorScheme.primary else Color.White,
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
    val accent = MaterialTheme.colorScheme.primary
    val trackColor by animateColorAsState(
        targetValue = if (checked) accent else Color(0xFF333333),
        animationSpec = tween(160),
        label = "switchTrack"
    )
    val thumbOffset by animateDpAsState(
        targetValue = if (checked) 24.dp else 0.dp,
        animationSpec = tween(160),
        label = "switchThumb"
    )
    Box(
        modifier = Modifier
            .width(52.dp)
            .height(28.dp)
            .background(trackColor)
            .border(1.dp, if (checked) accent else Color.Gray.copy(alpha = 0.35f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { onCheckedChange(!checked) }
    ) {
        Box(
            modifier = Modifier
                .padding(start = 3.dp + thumbOffset, top = 3.dp, bottom = 3.dp)
                .width(22.dp)
                .fillMaxHeight()
                .background(if (checked) Color.Black else Color.White)
        )
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
