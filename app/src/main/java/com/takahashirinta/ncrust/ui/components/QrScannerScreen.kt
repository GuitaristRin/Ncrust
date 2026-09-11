package com.takahashirinta.ncrust.ui.components

import android.Manifest
import android.content.pm.PackageManager
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.auth.QrPair
import com.takahashirinta.ncrust.auth.QrPairClient
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private sealed interface ScanStatus {
    data object Scanning : ScanStatus
    data object Connecting : ScanStatus
    data object Success : ScanStatus
    data class Failed(val message: String) : ScanStatus
}

/**
 * 手机端扫码授权页：相机识别平板上的登录二维码，解析 unikey 后经局域网把本机
 * cookie 回传给平板。全程留在本页给出明确状态（扫描中/连接中/成功/失败），
 * 不再扫到即关闭导致用户不知道结果。
 *
 * CameraX + zxing core 本地解码，不依赖 Google Play 服务。
 */
@Composable
fun QrAuthorizeScreen(
    onAuthorized: () -> Unit,
    onClose: () -> Unit
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val executor = remember { Executors.newSingleThreadExecutor() }
    // 扫到一次即停，失败后由「重试」按钮复位，避免对同一二维码反复触发。
    val handled = remember { AtomicBoolean(false) }

    var status by remember { mutableStateOf<ScanStatus>(ScanStatus.Scanning) }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { hasPermission = it }

    LaunchedEffect(Unit) {
        if (!hasPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    fun handleContent(content: String) {
        val unikey = QrPair.unikeyFromQrContent(content)
        val cookie = CookieManager.getCookie(context)
        when {
            unikey == null -> status = ScanStatus.Failed(strings.scanFailed)
            cookie.isNullOrBlank() -> status = ScanStatus.Failed(strings.scanNoCookie)
            else -> {
                status = ScanStatus.Connecting
                scope.launch {
                    val ok = QrPairClient.sendCookie(unikey, cookie)
                    if (ok) {
                        status = ScanStatus.Success
                        delay(900)
                        onAuthorized()
                    } else {
                        status = ScanStatus.Failed(strings.scanFailed)
                    }
                }
            }
        }
    }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }

    LaunchedEffect(hasPermission) {
        if (!hasPermission) return@LaunchedEffect
        val provider = suspendCancellableCoroutine<ProcessCameraProvider> { cont ->
            val future = ProcessCameraProvider.getInstance(context)
            future.addListener({
                runCatching { cont.resume(future.get()) }
                    .onFailure { cont.resumeWithException(it) }
            }, ContextCompat.getMainExecutor(context))
        }
        val preview = Preview.Builder().build().also {
            it.setSurfaceProvider(previewView.surfaceProvider)
        }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            // 默认 640x480 对焦远一点的二维码识别率低; 提到 720p 明显更灵敏。
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(1280, 720),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
        analysis.setAnalyzer(executor) { proxy ->
            val text = runCatching { decodeQr(proxy) }.getOrNull()
            proxy.close()
            if (text != null && handled.compareAndSet(false, true)) {
                previewView.post { handleContent(text) }
            }
        }
        try {
            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis
            )
            awaitCancellation()
        } finally {
            runCatching { provider.unbindAll() }
        }
    }

    DisposableEffect(Unit) { onDispose { executor.shutdown() } }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            if (hasPermission) {
                AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
            } else {
                MetroText(
                    strings.scanPermissionNeeded,
                    color = Color.White,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            if (status is ScanStatus.Scanning) {
                MetroText(
                    strings.scanPrompt,
                    color = Color.White,
                    style = TextStyle(fontSize = 15.sp),
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 32.dp, start = 48.dp, end = 48.dp)
                )
            }

            when (val s = status) {
                ScanStatus.Connecting -> MetroText(
                    strings.scanConnecting,
                    color = Color.White,
                    style = TextStyle(fontSize = 16.sp),
                    modifier = Modifier.align(Alignment.Center)
                )
                ScanStatus.Success -> MetroText(
                    strings.scanSuccess,
                    color = LocalMetroColors.current.primary,
                    style = TextStyle(fontSize = 16.sp),
                    modifier = Modifier.align(Alignment.Center)
                )
                is ScanStatus.Failed -> Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(horizontal = 40.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    MetroText(
                        s.message,
                        color = Color.White,
                        style = TextStyle(fontSize = 15.sp, textAlign = TextAlign.Center)
                    )
                    Spacer(Modifier.height(16.dp))
                    Box(
                        modifier = Modifier
                            .border(1.dp, LocalMetroColors.current.primary)
                            .clickable {
                                handled.set(false)
                                status = ScanStatus.Scanning
                            }
                            .padding(horizontal = 24.dp, vertical = 10.dp)
                    ) {
                        MetroText(
                            strings.retry,
                            color = LocalMetroColors.current.primary,
                            style = TextStyle(fontSize = 14.sp)
                        )
                    }
                }
                ScanStatus.Scanning -> Unit
            }

            MetroIcon(
                Icons.Default.Close,
                contentDescription = strings.close,
                tint = Color.White,
                sizeDp = 26.dp,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .statusBarsPadding()
                    .padding(16.dp)
                    .clickable(onClick = onClose)
            )
        }
    }
}

private val DECODE_HINTS = mapOf(
    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
    DecodeHintType.TRY_HARDER to true
)

/** 从 CameraX 的 YUV_420_888 帧中取 Y 平面，交给 zxing 解码。 */
private fun decodeQr(image: ImageProxy): String? {
    val plane = image.planes.firstOrNull() ?: return null
    val buffer = plane.buffer
    val width = image.width
    val height = image.height
    val rowStride = plane.rowStride
    val pixelStride = plane.pixelStride
    val data = ByteArray(width * height)
    if (pixelStride == 1 && rowStride == width) {
        buffer.rewind()
        buffer.get(data, 0, minOf(data.size, buffer.remaining()))
    } else {
        val row = ByteArray(rowStride)
        var offset = 0
        for (y in 0 until height) {
            val len = minOf(rowStride, buffer.remaining())
            if (len <= 0) break
            buffer.get(row, 0, len)
            for (x in 0 until width) data[offset + x] = row[x * pixelStride]
            offset += width
        }
    }
    val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
    return runCatching {
        MultiFormatReader().apply { setHints(DECODE_HINTS) }
            .decode(BinaryBitmap(HybridBinarizer(source))).text
    }.getOrNull()
}
