package com.takahashirinta.ncrust.ui.screen

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.ResponsiveContent
import com.takahashirinta.ncrust.ui.components.TopScrimIconButton
import com.takahashirinta.ncrust.ui.components.openUpdateInBrowser
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.ui.i18n.formatCacheBytes
import com.takahashirinta.ncrust.ui.theme.MarkdownText
import com.takahashirinta.ncrust.update.DownloadState
import com.takahashirinta.ncrust.update.FailureReason
import com.takahashirinta.ncrust.update.UpdateInfo
import com.takahashirinta.ncrust.update.UpdateService
import com.takahashirinta.ncrust.update.forTarget
import com.takahashirinta.ncrust.update.targetKey
import io.github.takahashirinta.kanesumi.controls.MetroButton
import io.github.takahashirinta.kanesumi.controls.MetroDivider
import io.github.takahashirinta.kanesumi.controls.MetroProgressIndicator
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroTypography
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 更新页：版本号、发布日期、大小、release 正文，以及下载 / 安装。
 *
 * 与「关于」页同一套做法（全屏覆盖 + 悬浮返回箭头 + ResponsiveContent 限宽），
 * 差别是底部常驻一条操作栏——下载进度与「取消下载」必须在视线里，
 * 不能让用户滚到正文底部才找到。
 *
 * 下载本身跑在 [UpdateService] 的应用级作用域里：这一页只是观察者，
 * 退出页面不会中断下载（进度是 StateFlow，回来接着显示）。
 */
@Composable
fun UpdateScreen(
    info: UpdateInfo,
    onBack: () -> Unit,
    onLater: () -> Unit
) {
    val strings = LocalStrings.current
    val context = LocalContext.current
    val colors = LocalMetroColors.current
    val downloadState by UpdateService.download.collectAsState()
    // 只认本页目标的下载状态：别的目标（先下了 A 又进了 B 页）的 Ready 绝不能拿来安装。
    val pageState = downloadState.forTarget(info.targetKey)

    // 「下载并安装」是同一个动作的两段：下完就接着校验并调起安装器。
    // 用本地标记而不是「一进 Ready 就装」——否则从浏览器切回来 / 旋转重建都会再弹一次安装器。
    var installAfterDownload by remember { mutableStateOf(false) }

    fun toast(text: String) {
        Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
    }

    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        // 从「安装未知应用」授权页回来：授予了就继续安装，没授予就如实说明。
        val ready = UpdateService.readyFor(info) ?: return@rememberLauncherForActivityResult
        if (UpdateService.canInstallPackages(context)) {
            if (!UpdateService.installApk(context, ready.file)) {
                toast(strings.updateInstallLaunchFailed)
            }
        } else {
            toast(strings.updateInstallBlockedHint)
        }
    }

    fun requestInstall() {
        // 安装前再校验一次目标一致且文件仍在：页面停留期间状态可能已被别的版本改写。
        val ready = UpdateService.readyFor(info) ?: run {
            toast(strings.updateDownloadFailed)
            return
        }
        if (!UpdateService.canInstallPackages(context)) {
            // Android 8+ 的「安装未知应用」是逐应用的开关：先去授权，回来再装。
            toast(strings.updateInstallBlockedHint)
            unknownSourcesLauncher.launch(UpdateService.unknownSourcesIntent(context))
            return
        }
        if (!UpdateService.installApk(context, ready.file)) {
            toast(strings.updateInstallLaunchFailed)
        }
    }

    LaunchedEffect(pageState, installAfterDownload) {
        if (installAfterDownload && pageState is DownloadState.Ready) {
            installAfterDownload = false
            requestInstall()
        }
    }

    BackHandler { onBack() }

    // 顶部让开返回箭头；底部留白由操作栏负责（操作栏自己带系统导航栏 inset）。
    val textPadding = Modifier
        .padding(horizontal = 24.dp)
        .statusBarsPadding()
        .padding(top = 72.dp, bottom = 24.dp)
    val contentScrollState = rememberScrollState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(colors.background)
    ) {
        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
            // 矮屏 / 大字体：整页随滚动条滚，保证最底部按钮也能滚到、不被系统导航栏遮挡。
            // 注意此时正文本身不能再套一层纵向 scroll（同方向嵌套会崩）。
            val scrollWholePage = maxHeight < 360.dp
            if (scrollWholePage) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                    UpdateContent(info, modifier = textPadding)
                    UpdateActionBar(
                        state = pageState,
                        onDownload = {
                            installAfterDownload = true
                            UpdateService.startDownload(context, info)
                        },
                        onCancel = { UpdateService.cancelDownload() },
                        onRetry = {
                            installAfterDownload = true
                            UpdateService.startDownload(context, info)
                        },
                        onInstall = { requestInstall() },
                        onBrowser = { openUpdateInBrowser(context, info.releasePageUrl ?: info.assetUrl) },
                        onLater = onLater
                    )
                }
            } else {
                Column(modifier = Modifier.fillMaxSize()) {
                    ResponsiveContent(
                        maxWidth = 720.dp,
                        modifier = Modifier.weight(1f)
                    ) {
                        UpdateContent(
                            info,
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(contentScrollState)
                                .then(textPadding)
                        )
                    }
                    UpdateActionBar(
                        state = pageState,
                        onDownload = {
                            installAfterDownload = true
                            UpdateService.startDownload(context, info)
                        },
                        onCancel = { UpdateService.cancelDownload() },
                        onRetry = {
                            installAfterDownload = true
                            UpdateService.startDownload(context, info)
                        },
                        onInstall = { requestInstall() },
                        onBrowser = { openUpdateInBrowser(context, info.releasePageUrl ?: info.assetUrl) },
                        onLater = onLater
                    )
                }
            }
        }

        TopScrimIconButton(
            icon = Icons.AutoMirrored.Filled.ArrowBack,
            contentDescription = strings.back,
            onClick = onBack
        )
    }
}

/**
 * 更新页的正文（版本号 / 日期 / 大小 / release 正文）。
 *
 * 从 [UpdateScreen] 拆出来是为了在「矮屏整页滚动」与「常驻底栏」两套布局里复用同一份内容。
 */
@Composable
private fun UpdateContent(info: UpdateInfo, modifier: Modifier) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    Column(modifier = modifier) {
        MetroText(
            strings.updateTitle,
            color = colors.onSurfaceVariant,
            style = TextStyle(fontSize = 13.sp)
        )
        Spacer(Modifier.height(4.dp))
        MetroText(
            "v${info.version}",
            color = colors.primary,
            style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold)
        )
        Spacer(Modifier.height(16.dp))
        // 缺项（接口没给 size / published_at）就整行不显示，不留「0 B」「—」这种占位。
        val released = releaseDateOf(info)
        if (released.isNotEmpty()) {
            UpdateMetaRow(strings.updateReleasedLabel, released)
        }
        if (info.assetSize > 0) {
            UpdateMetaRow(strings.updateSizeLabel, formatCacheBytes(info.assetSize))
        }
        Spacer(Modifier.height(16.dp))
        MetroDivider(color = colors.divider)
        Spacer(Modifier.height(20.dp))
        MetroText(
            strings.updateNotesLabel.uppercase(),
            color = colors.primary,
            style = TextStyle(fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
        )
        Spacer(Modifier.height(8.dp))
        val body = info.body?.trim().orEmpty()
        if (body.isEmpty()) {
            MetroText(
                strings.updateNoNotes,
                color = colors.onSurfaceVariant,
                style = LocalMetroTypography.current.bodyMedium
            )
        } else {
            MarkdownText(body)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * 更新页的覆盖层入口。
 *
 * 单独包一层的原因与 [com.takahashirinta.ncrust.ui.components.UpdateNavDot] 相同：
 * 更新状态在这里订阅，MainScreen 的重组作用域就不会被它牵动。
 * 状态被后台检查清空（例如刚升完级）时自动关掉本页，避免停在空白页上。
 */
@Composable
fun UpdatePageOverlay(onClose: () -> Unit) {
    val context = LocalContext.current
    val info = UpdateService.state.collectAsState().value.available
    LaunchedEffect(info) {
        if (info == null) onClose()
    }
    if (info == null) return
    UpdateScreen(
        info = info,
        onBack = onClose,
        onLater = {
            // 「稍后」：记住这个版本，圆点与横幅消失，直到出现更高的版本。
            UpdateService.dismiss(context, info.version)
            onClose()
        }
    )
}

/** release 日期只取 `YYYY-MM-DD`：时分秒对用户没有意义，也不该按语言重新格式化。 */
private fun releaseDateOf(info: UpdateInfo): String {
    val raw = info.publishedAt?.trim().orEmpty()
    return if (raw.length >= 10) raw.substring(0, 10) else raw
}

@Composable
private fun UpdateMetaRow(label: String, value: String) {
    val colors = LocalMetroColors.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroText(
            label,
            color = colors.onSurfaceVariant,
            style = TextStyle(fontSize = 13.sp),
            modifier = Modifier.weight(1f)
        )
        MetroText(
            value,
            color = colors.onBackground,
            style = TextStyle(fontSize = 13.sp, textAlign = TextAlign.End),
            modifier = Modifier.weight(1.5f)
        )
    }
}

/**
 * 底部操作栏：按下载状态切换按钮组。
 *
 * 按钮顺序固定为「主操作 → 浏览器退路 → 稍后」：国内网络下 GitHub 资产经常下不动，
 * 「在浏览器中打开」是常驻退路而不是错误分支里的隐藏入口。
 */
@Composable
private fun UpdateActionBar(
    state: DownloadState,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onRetry: () -> Unit,
    onInstall: () -> Unit,
    onBrowser: () -> Unit,
    onLater: () -> Unit
) {
    val strings = LocalStrings.current
    val colors = LocalMetroColors.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(colors.surface)
            // edge-to-edge 下给系统导航栏留 inset，最底部按钮才不会被导航栏遮住。
            .navigationBarsPadding()
            .padding(horizontal = 24.dp, vertical = 12.dp)
    ) {
        when (state) {
            is DownloadState.Downloading -> {
                val total = state.total
                if (total > 0) {
                    val fraction = (state.downloaded.toFloat() / total).coerceIn(0f, 1f)
                    UpdateProgressBar(fraction = fraction, color = colors.primary, track = colors.surfaceVariant)
                    Spacer(Modifier.height(8.dp))
                    MetroText(
                        strings.updateDownloadingPercent((fraction * 100).toInt()),
                        color = colors.onBackground,
                        style = TextStyle(fontSize = 13.sp)
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MetroProgressIndicator(sizeDp = 18.dp, strokeDp = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        MetroText(
                            strings.updateDownloading,
                            color = colors.onBackground,
                            style = TextStyle(fontSize = 13.sp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
                ActionButton(strings.updateCancelDownload, secondary = true, onClick = onCancel)
            }

            is DownloadState.Verifying -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MetroProgressIndicator(sizeDp = 18.dp, strokeDp = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    MetroText(
                        strings.updateVerifying,
                        color = colors.onBackground,
                        style = TextStyle(fontSize = 13.sp)
                    )
                }
                Spacer(Modifier.height(12.dp))
            }

            is DownloadState.Failed -> {
                MetroText(
                    failureText(state.reason),
                    color = colors.primary,
                    style = TextStyle(fontSize = 13.sp),
                    maxLines = 4
                )
                Spacer(Modifier.height(12.dp))
                ActionButton(strings.retry, secondary = false, onClick = onRetry)
                Spacer(Modifier.height(8.dp))
                ActionButton(strings.updateOpenInBrowser, secondary = true, onClick = onBrowser)
                Spacer(Modifier.height(8.dp))
                ActionButton(strings.updateLater, secondary = true, onClick = onLater)
                return@Column
            }

            is DownloadState.Ready -> {
                // 校验已通过（包名 / versionCode / 签名）；按一下交给系统安装器。
                ActionButton(strings.updateDownloadAndInstall, secondary = false, onClick = onInstall)
                Spacer(Modifier.height(8.dp))
                ActionButton(strings.updateOpenInBrowser, secondary = true, onClick = onBrowser)
                Spacer(Modifier.height(8.dp))
                ActionButton(strings.updateLater, secondary = true, onClick = onLater)
                return@Column
            }

            DownloadState.Idle -> {
                ActionButton(strings.updateDownloadAndInstall, secondary = false, onClick = onDownload)
                Spacer(Modifier.height(8.dp))
                ActionButton(strings.updateOpenInBrowser, secondary = true, onClick = onBrowser)
                Spacer(Modifier.height(8.dp))
                ActionButton(strings.updateLater, secondary = true, onClick = onLater)
            }
        }
    }
}

@Composable
private fun failureText(reason: FailureReason): String {
    val strings = LocalStrings.current
    return when (reason) {
        FailureReason.NETWORK -> strings.updateDownloadFailed
        FailureReason.PACKAGE_MISMATCH -> strings.updatePackageMismatch
        FailureReason.VERSION_NOT_HIGHER -> strings.updateVersionNotHigher
        FailureReason.SIGNATURE_MISMATCH -> strings.updateSignatureMismatch
        FailureReason.ARCHIVE_UNREADABLE -> strings.updateArchiveUnreadable
    }
}

@Composable
private fun ActionButton(text: String, secondary: Boolean, onClick: () -> Unit) {
    val colors = LocalMetroColors.current
    MetroButton(
        text = text,
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        containerColor = if (secondary) colors.surfaceVariant else colors.primary,
        contentColor = if (secondary) colors.onBackground else colors.onPrimary,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 12.dp)
    )
}

/**
 * 直角进度条：4dp 高、无圆角、底色用 surfaceVariant。
 *
 * 不复用 M3 的 LinearProgressIndicator（本仓库没有 material3），也不用圆角指示器
 * ——Kanesumi 的进度反馈就是一条直边。
 */
@Composable
private fun UpdateProgressBar(fraction: Float, color: Color, track: Color) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(track)
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(fraction)
                .background(color)
        )
    }
}
