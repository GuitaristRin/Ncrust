package com.takahashirinta.ncrust.ui.components

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import com.takahashirinta.ncrust.update.CheckOutcome
import com.takahashirinta.ncrust.update.UpdateService
import kotlinx.coroutines.launch

/**
 * 「检查更新」手动入口的共用实现（用户页与关于页各有一个入口，逻辑只有这一份）。
 *
 * 手动检查不受 24 小时窗口限制，所以它**必须**给反馈：有新版就打开更新页，
 * 没有就说「已是最新版本」，网络失败说「检查更新失败」。只有自动检查才静默。
 */
@Composable
fun rememberManualUpdateCheck(onFound: () -> Unit): () -> Unit {
    val context = LocalContext.current
    val strings = LocalStrings.current
    val scope = rememberCoroutineScope()
    return {
        scope.launch {
            when (UpdateService.checkNow(context)) {
                is CheckOutcome.Found -> onFound()
                CheckOutcome.UpToDate ->
                    Toast.makeText(context, strings.updateUpToDate, Toast.LENGTH_SHORT).show()
                CheckOutcome.Failed ->
                    Toast.makeText(context, strings.updateCheckFailed, Toast.LENGTH_SHORT).show()
            }
        }
    }
}

/**
 * 在系统浏览器里打开 release 页（国内网络下载失败 / 过慢时的退路）。
 *
 * 只打开 GitHub 的 https 地址，不引入任何第三方镜像——是否用镜像由负责人决定。
 */
fun openUpdateInBrowser(context: Context, url: String) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
