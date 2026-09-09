package com.takahashirinta.ncrust.ui.screen

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
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
 * 原生登录页(替代 WebView), 三通道:
 *  1. 手机号+密码   —— 主通道, 无验证码环节, 不依赖短信网关/反机器人
 *  2. 手机号+验证码 —— 发送可能被网易风控拦(无 reCAPTCHA 能力), 失败如实提示
 *  3. 扫码          —— 网易云客户端扫一下即可(需另一台设备/客户端)
 */
@Composable
fun QrLoginScreen(
    onSuccess: (cookie: String) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    var mode by remember { mutableIntStateOf(0) }  // 0 密码, 1 验证码, 2 扫码
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    // 密码模式
    var phone by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // 验证码模式
    var smsPhone by remember { mutableStateOf("") }
    var smsCode by remember { mutableStateOf("") }
    var countdown by remember { mutableIntStateOf(0) }

    // 扫码模式
    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var qrHint by remember { mutableStateOf("") }
    var unikey by remember { mutableStateOf<String?>(null) }
    var qrPolling by remember { mutableStateOf(false) }

    BackHandler(enabled = true) { onDismiss() }

    // 验证码发送倒计时
    LaunchedEffect(countdown) {
        if (countdown > 0) {
            delay(1_000)
            countdown--
        }
    }

    fun startQrPolling() {
        qrPolling = true
        qrHint = ""
        unikey = null
        qrBitmap = null
        scope.launch {
            val key = PlaylistApi.getLoginQrKey()
            if (key == null) {
                qrHint = strings.qrExpiredHint
                qrPolling = false
                return@launch
            }
            unikey = key.unikey
            key.qrimg?.let { dataUrl ->
                runCatching {
                    val b64 = dataUrl.substringAfter("base64,")
                    val bytes = Base64.decode(b64, Base64.DEFAULT)
                    qrBitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
            }
            val myKey = key.unikey
            var ticks = 0
            while (qrPolling && ticks < 120) {
                delay(3_000)
                ticks++
                val st = PlaylistApi.checkLoginQr(myKey)
                when (st.code) {
                    800 -> { qrHint = strings.qrExpiredHint; qrPolling = false }
                    802 -> qrHint = strings.qrScannedHint
                    803 -> {
                        qrPolling = false
                        st.cookie?.let(onSuccess)
                        return@launch
                    }
                }
            }
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
                listOf(strings.loginTabPassword, strings.loginTabSms, strings.loginTabQr).forEachIndexed { i, label ->
                    MetroText(
                        label,
                        color = if (mode == i) Color(0xFF1DB954) else Color.Gray,
                        style = LocalMetroTypography.current.bodyMedium,
                        modifier = Modifier
                            .clickable { mode = i; error = null }
                            .padding(vertical = 8.dp)
                    )
                }
            }
            Spacer(Modifier.height(16.dp))

            when (mode) {
                // ================= 密码登录 =================
                0 -> {
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
                        busy = true; error = null
                        scope.launch {
                            val st = PlaylistApi.loginByPassword(phone, password)
                            busy = false
                            when {
                                st.cookie != null -> st.cookie?.let(onSuccess)
                                st.code == 502 || st.code == 501 -> error = strings.loginWrongCreds
                                else -> error = strings.loginFailed(st.code.toString())
                            }
                        }
                    }
                }
                // ================= 验证码登录 =================
                1 -> {
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
                                    busy = true; error = null
                                    scope.launch {
                                        val ok = PlaylistApi.sendSmsCaptcha(smsPhone)
                                        busy = false
                                        if (ok) countdown = 60
                                        else error = strings.loginCaptchaBlocked
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
                        busy = true; error = null
                        scope.launch {
                            val st = PlaylistApi.loginBySms(smsPhone, smsCode)
                            busy = false
                            when {
                                st.cookie != null -> st.cookie?.let(onSuccess)
                                st.code == 400 -> error = strings.loginWrongCreds
                                else -> error = strings.loginFailed(st.code.toString())
                            }
                        }
                    }
                }
                // ================= 扫码登录 =================
                else -> {
                    androidx.compose.runtime.LaunchedEffect(mode) { if (mode == 2 && !qrPolling) startQrPolling() }
                    Box(
                        modifier = Modifier
                            .size(210.dp)
                            .background(Color.White)
                            .clickable(enabled = !qrPolling) { startQrPolling() },
                        contentAlignment = Alignment.Center
                    ) {
                        val bmp = qrBitmap
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize().padding(8.dp),
                                contentScale = ContentScale.Fit
                            )
                        } else {
                            MetroText(
                                qrHint.ifEmpty { "…" },
                                color = Color(0xFF555555),
                                style = LocalMetroTypography.current.bodySmall.copy(textAlign = TextAlign.Center),
                                modifier = Modifier.padding(14.dp)
                            )
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    MetroText(
                        strings.loginQrTip,
                        color = if (qrHint.isNotEmpty()) Color(0xFF1DB954) else Color.Gray,
                        style = LocalMetroTypography.current.bodySmall.copy(textAlign = TextAlign.Center),
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
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
        visualTransformation = if (isPassword) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
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
            style = TextStyle(fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        )
    }
}