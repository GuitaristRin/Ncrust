package com.takahashirinta.ncrust.ui.screen

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.activity.compose.BackHandler

/**
 * 原生扫码登录(替代 WebView 登录)。
 * 流程: /eapi/login/qrcode/unikey 拿二维码 → 每 3s 轮询 client/unikey,
 * 803 成功时 cookie 在 Set-Cookie 头里, 回调给上层走既有保存路径。
 * 800 过期时点击二维码重新申请。
 */
@Composable
fun QrLoginScreen(
    onSuccess: (cookie: String) -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    var qrBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var hint by remember { mutableStateOf("") }
    var unikey by remember { mutableStateOf<String?>(null) }
    // 过期/失败次数过多时停止轮询, 让用户手动点二维码重试
    var pollingEnabled by remember { mutableStateOf(true) }

    // 请求成功后必须停止轮询再回调, 防止上层关闭页面后协程还打接口
    fun refresh() {
        pollingEnabled = true
        unikey = null
        qrBitmap = null
        hint = ""
        scope.launch {
            val key = PlaylistApi.getLoginQrKey()
            if (key == null) {
                hint = strings.qrExpiredHint
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
            launch {
                var ticks = 0
                while (pollingEnabled && ticks < 100) {
                    delay(3_000)
                    ticks++
                    val st = PlaylistApi.checkLoginQr(myKey)
                    when (st.code) {
                        800 -> { hint = strings.qrExpiredHint; pollingEnabled = false }
                        802 -> hint = strings.qrScannedHint
                        803 -> {
                            pollingEnabled = false
                            st.cookie?.let(onSuccess)
                            return@launch
                        }
                    }
                }
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    // 登录页拦截系统返回: 关闭登录页而不是退出应用
    BackHandler(enabled = true) { onDismiss() }

    Box(modifier = Modifier.fillMaxSize().background(Color(0xFF121212))) {
        Column(
            modifier = Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MetroText("Ncrust", color = Color(0xFF1DB954), style = LocalMetroTypography.current.pageHeading)
            Spacer(Modifier.height(6.dp))
            MetroText(strings.qrScanHint, color = Color.White, style = LocalMetroTypography.current.bodyMedium)

            Spacer(Modifier.height(28.dp))

            Box(
                modifier = Modifier
                    .size(232.dp)
                    .background(Color.White)
                    .clickable(enabled = !pollingEnabled) { refresh() },
                contentAlignment = Alignment.Center
            ) {
                val bmp = qrBitmap
                if (bmp != null) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(10.dp),
                        contentScale = ContentScale.Fit
                    )
                } else {
                    MetroText(
                        hint.ifEmpty { "..." },
                        color = Color(0xFF555555),
                        style = LocalMetroTypography.current.bodySmall.copy(textAlign = TextAlign.Center),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(16.dp))
            if (!pollingEnabled) {
                // 过期提示 + 点击刷新的引导文案
                MetroText(
                    strings.qrExpiredHint,
                    color = Color(0xFF1DB954),
                    style = LocalMetroTypography.current.bodySmall.copy(textAlign = TextAlign.Center)
                )
            } else if (hint.isNotEmpty()) {
                MetroText(hint, color = Color(0xFF1DB954), style = LocalMetroTypography.current.bodySmall)
            }
        }

        // 顶部右上角关闭
        MetroText(
            "✕",
            color = Color.White,
            style = LocalMetroTypography.current.titleMedium,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(16.dp)
                .clickable { onDismiss() }
        )
    }
}
