package com.takahashirinta.ncrust.ui.components

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.takahashirinta.ncrust.auth.QrPairServer
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 扫码状态机（UI 侧）。 */
private enum class QrState { Loading, Waiting, Scanned, Expired, Failed }

/**
 * 平板 / 大屏独占的扫码登录弹窗。
 *
 * 流程：`/eapi/login/qrcode/unikey` 拿到官方返回的二维码图（base64 PNG，无需本地
 * 生成），每 3 秒轮询 `client/unikey`：801 待扫 / 802 已扫待确认 / 803 成功。
 * 成功时 cookie 在响应的 Set-Cookie 头里，回调给上层走既有保存路径。
 * 800 过期或请求失败时点击二维码可重新申请。
 *
 * 「通用登录」退回到与手机一致的 WebView 官方登录页。
 */
@Composable
fun QrLoginDialog(
    onLoginSuccess: (cookie: String) -> Unit,
    onGenericLogin: () -> Unit,
    onDismiss: () -> Unit
) {
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var state by remember { mutableStateOf(QrState.Loading) }
    var pollJob by remember { mutableStateOf<Job?>(null) }
    // 平板侧局域网配对服务：手机 Ncrust 扫同一个二维码时把 cookie 回传过来。
    var pairServer by remember { mutableStateOf<QrPairServer?>(null) }

    fun refresh() {
        pollJob?.cancel()
        pairServer?.stop()
        pairServer = null
        qrBitmap = null
        state = QrState.Loading
        pollJob = scope.launch {
            val key = PlaylistApi.getLoginQrKey()
            if (key == null || key.unikey.isEmpty()) {
                state = QrState.Failed
                return@launch
            }
            // 官方 App 扫码走轮询; Ncrust 手机扫码走局域网回传, 两条路谁先到用谁。
            pairServer = QrPairServer(key.unikey) { cookie -> onLoginSuccess(cookie) }.also { it.start() }
            // 二维码内容优先用服务端给的 qrurl; 否则由 unikey 拼官方登录链接。
            // 服务端一般不给图, 用 zxing 本地生成(官方客户端同款做法)。
            val qrContent = key.qrurl
                ?: if (key.unikey.startsWith("http")) key.unikey
                else "https://music.163.com/login?codekey=${key.unikey}"
            qrBitmap = key.qrimg?.let { decodeQrImage(it) } ?: generateQrBitmap(qrContent)
            if (qrBitmap == null) {
                state = QrState.Failed
                return@launch
            }
            state = QrState.Waiting
            var ticks = 0
            while (ticks < 100) {
                delay(3_000)
                ticks++
                val st = PlaylistApi.checkLoginQr(key.unikey)
                when (st.code) {
                    800 -> { state = QrState.Expired; return@launch }
                    802 -> state = QrState.Scanned
                    803 -> {
                        val cookie = st.cookie
                        if (cookie != null) onLoginSuccess(cookie) else state = QrState.Failed
                        return@launch
                    }
                }
            }
            // 长时间无人扫码，按过期处理
            state = QrState.Expired
        }
    }

    LaunchedEffect(Unit) { refresh() }
    DisposableEffect(Unit) {
        onDispose {
            pollJob?.cancel()
            pairServer?.stop()
        }
    }

    val refreshable = state == QrState.Expired || state == QrState.Failed

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF282828))
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            MetroText(
                strings.qrLoginTitle,
                color = Color.White,
                style = LocalMetroTypography.current.titleLarge.copy(fontWeight = FontWeight.Bold),
            )
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .size(240.dp)
                    .background(Color.White)
                    .clickable(enabled = refreshable) { refresh() },
                contentAlignment = Alignment.Center
            ) {
                val bmp = qrBitmap
                when {
                    bmp != null -> Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                        contentScale = ContentScale.Fit
                    )
                    state == QrState.Loading -> MetroText(
                        strings.loading,
                        color = Color(0xFF555555),
                        style = LocalMetroTypography.current.bodyMedium,
                    )
                    else -> MetroText(
                        strings.qrExpiredHint,
                        color = Color(0xFF555555),
                        style = LocalMetroTypography.current.bodySmall.copy(textAlign = TextAlign.Center),
                        modifier = Modifier.padding(16.dp)
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            MetroText(
                when (state) {
                    QrState.Scanned -> strings.qrScannedHint
                    QrState.Expired, QrState.Failed -> strings.qrExpiredHint
                    else -> strings.qrScanHint
                },
                color = if (state == QrState.Scanned) LocalMetroColors.current.primary else Color.Gray,
                style = LocalMetroTypography.current.bodySmall.copy(textAlign = TextAlign.Center),
            )

            Spacer(Modifier.height(24.dp))
            // 通用登录：与手机默认的 WebView 官方登录页一致
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, LocalMetroColors.current.primary)
                    .clickable(onClick = onGenericLogin)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                MetroText(
                    strings.qrGenericLogin,
                    color = LocalMetroColors.current.primary,
                    style = TextStyle(fontSize = 14.sp),
                )
            }
            Spacer(Modifier.height(10.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .border(1.dp, Color.Gray.copy(alpha = 0.4f))
                    .clickable(onClick = onDismiss)
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                MetroText(
                    strings.close,
                    color = Color.Gray,
                    style = TextStyle(fontSize = 14.sp),
                )
            }
        }
    }
}

/** 解析 `data:image/png;base64,...` 为 Bitmap；失败返回 null。 */
private fun decodeQrImage(dataUrl: String): Bitmap? = runCatching {
    val b64 = dataUrl.substringAfter("base64,", dataUrl)
    val bytes = Base64.decode(b64, Base64.DEFAULT)
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
}.getOrNull()

/** zxing 本地生成二维码位图（纯黑白，白底）。失败返回 null。 */
private fun generateQrBitmap(content: String, sizePx: Int = 512): Bitmap? = runCatching {
    val matrix = com.google.zxing.qrcode.QRCodeWriter()
        .encode(content, com.google.zxing.BarcodeFormat.QR_CODE, sizePx, sizePx)
    val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.RGB_565)
    for (x in 0 until sizePx) {
        for (y in 0 until sizePx) {
            bmp.setPixel(x, y, if (matrix.get(x, y)) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
        }
    }
    bmp
}.getOrNull()
