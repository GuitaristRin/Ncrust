package com.takahashirinta.ncrust.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler

/**
 * 原生登录页(替代 WebView), 双通道:
 *  1. 手机号+验证码 —— legacy 网页协议两段式(/api/sms/captcha/sent + /verify),
 *     同机收码, 验证通过即登录
 *  2. 手机号+密码   —— eapi login/cellphone, 无验证码环节
 */
@Composable
fun QrLoginScreen(
    onSuccess: (cookie: String) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableIntStateOf(0) }  // 0 验证码, 1 密码
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var info by remember { mutableStateOf<String?>(null) }

    // 验证码模式
    var smsPhone by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(0) }

    // 密码模式
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    BackHandler(enabled = true) { onDismiss() }

    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            countdown--
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MetroText("Ncrust", color = Color(0xFF1DB954), style = LocalMetroTypography.current.pageHeading)
            Spacer(Modifier.height(6.dp))

            // ---------- 模式切换 ----------
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                listOf(strings.loginTabSms, strings.loginTabPassword).forEachIndexed { i, label ->
                    MetroText(
                        label,
                        color = if (mode == i) Color(0xFF1DB954) else Color.Gray,
                        style = LocalMetroTypography.current.bodyMedium,
                        modifier = Modifier
                            .clickable { mode = i; error = null; info = null }
                            .padding(vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            when (mode) {
                // ================= 验证码登录(主通道, legacy 网页两段式) =================
                0 -> {
                    LoginField(
                        value = smsPhone, onValue = { smsPhone = it }, placeholder = strings.loginPhone,
                        keyboardType = KeyboardType.Phone
                    )
                    Spacer(Modifier.height(10.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        LoginField(
                            value = smsCode, onValue = { smsCode = it }, placeholder = strings.loginSmsCode,
                            keyboardType = KeyboardType.Number,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(12.dp))
                        MetroText(
                            if (countdown > 0) "$countdown s" else strings.loginSendCode,
                            color = if (countdown > 0) Color.Gray else Color(0xFF1DB954),
                            style = LocalMetroTypography.current.bodySmall,
                            modifier = Modifier
                                .clickable(enabled = countdown == 0 && smsPhone.length >= 6 && !busy) {
                                    busy = true; error = null; info = null
                                    scope.launch {
                                        val (ok, serverMsg) = PlaylistApi.sendSmsCaptcha(smsPhone)
                                        busy = false
                                        if (ok) {
                                            countdown = 60
                                            info = strings.loginCodeSent
                                        } else {
                                            // 服务端原文(如"发送验证码间隔过短")优先于静态文案
                                            error = serverMsg.ifEmpty { strings.loginSendFailed }
                                        }
                                    }
                                }
                                .padding(8.dp)
                        )
                    }
                    Spacer(Modifier.height(18.dp))
                    LoginButton(
                        enabled = smsCode.length >= 4 && smsPhone.length >= 6 && !busy,
                        label = if (busy) "..." else strings.loginSubmit
                    ) {
                        busy = true; error = null; info = null
                        scope.launch {
                            val st = PlaylistApi.loginBySms(smsPhone, smsCode)
                            busy = false
                            when {
                                st.cookie != null -> st.cookie?.let(onSuccess)
                                else -> error = st.message.ifEmpty {
                                    if (st.code == 400) strings.loginWrongCreds
                                    else strings.loginFailed(st.code.toString())
                                }
                            }
                        }
                    }
                }
                // ================= 密码登录 =================
                else -> {
                    LoginField(
                        value = phone, onValue = { phone = it }, placeholder = strings.loginPhone,
                        keyboardType = KeyboardType.Phone
                    )
                    Spacer(Modifier.height(10.dp))
                    LoginField(
                        value = password, onValue = { password = it }, placeholder = strings.loginPassword,
                        keyboardType = KeyboardType.Password, isPassword = true
                    )
                    Spacer(Modifier.height(18.dp))
                    LoginButton(
                        enabled = phone.length >= 6 && password.isNotEmpty() && !busy,
                        label = if (busy) "..." else strings.loginSubmit
                    ) {
                        busy = true; error = null; info = null
                        scope.launch {
                            val st = PlaylistApi.loginByPassword(phone, password)
                            busy = false
                            when {
                                st.cookie != null -> st.cookie?.let(onSuccess)
                                st.code == 400 || st.code == 501 || st.code == 502 ->
                                    error = strings.loginWrongCreds
                                else -> error = strings.loginFailed(st.code.toString())
                            }
                        }
                    }
                }
            }

            info?.let {
                Spacer(Modifier.height(12.dp))
                MetroText(
                    it, color = Color(0xFF1DB954),
                    style = LocalMetroTypography.current.bodySmall.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
            error?.let {
                Spacer(Modifier.height(12.dp))
                MetroText(
                    it, color = Color(0xFFFF6B6B),
                    style = LocalMetroTypography.current.bodySmall.copy(textAlign = TextAlign.Center),
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }

        // 右上角关闭
        MetroText(
            "✕", color = Color.White, style = LocalMetroTypography.current.titleMedium,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .clickable { onDismiss() }
        )
    }
}

@Composable
private fun LoginField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    keyboardType: KeyboardType,
    isPassword: Boolean = false,
    modifier: Modifier = Modifier
) {
    BasicTextField(
        value = value,
        onValueChange = onValue,
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1F1F1F))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = ImeAction.Done
        ),
        textStyle = TextStyle(color = Color.White, fontSize = 16.sp),
        cursorBrush = SolidColor(Color(0xFF1DB954)),
        visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
        decorationBox = { inner ->
            Box {
                if (value.isEmpty()) {
                    MetroText(placeholder, color = Color.Gray, style = TextStyle(fontSize = 16.sp))
                }
                inner()
            }
        }
    )
}

@Composable
private fun LoginButton(
    enabled: Boolean,
    label: String,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (enabled) Color(0xFF1DB954) else Color(0xFF2A2A2A))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        MetroText(
            label,
            color = if (enabled) Color.Black else Color.Gray,
            style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        )
    }
}
