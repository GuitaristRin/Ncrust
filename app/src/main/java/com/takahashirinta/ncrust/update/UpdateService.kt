package com.takahashirinta.ncrust.update

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.takahashirinta.ncrust.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit

/** 更新检查的状态（全局单例持有，因此离开页面 / 重组都不会丢）。 */
data class UpdateState(
    val available: UpdateInfo? = null,
    val dismissedVersion: AppVersion? = null,
    val checking: Boolean = false,
    val autoCheckEnabled: Boolean = true,
    val lastCheckAt: Long = 0L
) {
    /** 该不该在底部导航画圆点 / 用户页显示横幅。 */
    val shouldNotify: Boolean get() = UpdateNotifier.shouldNotify(available, dismissedVersion)
}

/** 下载与校验的进度。 */
sealed interface DownloadState {
    data object Idle : DownloadState

    /** [total] 为 0 表示总长未知。 */
    data class Downloading(val downloaded: Long, val total: Long) : DownloadState

    data object Verifying : DownloadState

    data class Ready(val file: File, val info: UpdateInfo) : DownloadState

    data class Failed(val reason: FailureReason) : DownloadState
}

/** 下载 / 校验失败的原因，界面按它选文案与退路。 */
enum class FailureReason {
    /** 网络失败或过慢——给「在浏览器中打开」的退路。 */
    NETWORK,

    /** 包名不对：下载到的不是本应用的包。 */
    PACKAGE_MISMATCH,

    /** versionCode 不高于当前。 */
    VERSION_NOT_HIGHER,

    /** 签名与已安装应用不同，系统会拒装。 */
    SIGNATURE_MISMATCH,

    /** 文件读不出 APK 信息（不是合法 APK / 被截断）。 */
    ARCHIVE_UNREADABLE
}

/** 一次检查的结果，只有手动检查才需要区分（自动检查一律静默）。 */
sealed interface CheckOutcome {
    data class Found(val info: UpdateInfo) : CheckOutcome

    data object UpToDate : CheckOutcome

    data object Failed : CheckOutcome
}

/**
 * 应用内更新的唯一状态源（单例，与 `RetrofitClient` / `LibraryManager` 同一套服务定位器风格）。
 *
 * 职责与边界：
 *  - 查 GitHub Releases（匿名、只读、不带任何 token / cookie）；
 *  - 冷启动后由调用方延迟触发 [autoCheck]：24 小时最多一次、失败静默、可在设置里关掉；
 *  - 手动 [checkNow] 不受 24 小时限制，失败由界面提示；
 *  - 下载与校验跑在**应用级协程作用域**里，离开更新页不中断（进度是 StateFlow）。
 *
 * 「稍后」的语义：记住被推迟的版本，圆点与横幅消失，直到出现更高的版本
 * （[UpdateNotifier]）。
 */
object UpdateService {

    const val USER_AGENT = "Ncrust-Android"

    private const val RELEASES_API =
        "https://api.github.com/repos/GuitaristRin/Ncrust/releases?per_page=20"

    private const val PREFS_NAME = "ncrust_settings"
    private const val KEY_AUTO_CHECK = "update_auto_check"
    private const val KEY_LAST_CHECK_AT = "update_last_check_at"
    private const val KEY_DISMISSED_VERSION = "update_dismissed_version"

    // 上次发现的结果落盘用**显式键**（不用 Gson 反射整个 data class）：R8 会剪掉未 keep
    // 的类的字段注解，反射绑定在 release 包里会退化（见根 AGENTS.md「Gson 反射 vs R8」）。
    private const val KEY_FOUND_VERSION = "update_found_version"
    private const val KEY_FOUND_ASSET_NAME = "update_found_asset_name"
    private const val KEY_FOUND_ASSET_URL = "update_found_asset_url"
    private const val KEY_FOUND_ASSET_SIZE = "update_found_asset_size"
    private const val KEY_FOUND_TAG = "update_found_tag"
    private const val KEY_FOUND_PAGE_URL = "update_found_page_url"
    private const val KEY_FOUND_PUBLISHED_AT = "update_found_published_at"
    private const val KEY_FOUND_BODY = "update_found_body"

    /** 24 小时最多自动检查一次（未认证的 GitHub API 限额是 60 次 / 小时 / IP）。 */
    private const val CHECK_INTERVAL_MS = 24L * 60L * 60L * 1000L

    private const val DOWNLOAD_DIR = "updates"

    // 应用级作用域：下载期间用户离开更新页（甚至旋转 / 回到首页）都不能中断。
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // 独立 OkHttpClient：RetrofitClient 的 client 会往请求里塞网易云 cookie，
    // 把登录态发给 GitHub 是不可接受的。
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow(UpdateState())
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    private val _download = MutableStateFlow<DownloadState>(DownloadState.Idle)
    val download: StateFlow<DownloadState> = _download.asStateFlow()

    private var downloadJob: Job? = null
    private var downloader: UpdateDownloader? = null

    // 取消与失败要分开：cancel() 会让阻塞中的读抛 IOException，若不加这个标记，
    // 用户点「取消」后界面会先回到 Idle、紧接着被 catch 覆盖成「下载失败」。
    @Volatile
    private var cancelRequested = false

    /** 当前版本：唯一来源是 `build.gradle.kts` 的 `versionName`（经 BuildConfig 注入）。 */
    fun currentVersion(): AppVersion? = AppVersion.parseOrNull(BuildConfig.VERSION_NAME)

    /**
     * 从 SharedPreferences 恢复状态。每次冷启动在 `MainActivity.onCreate` 调一次：
     * 24 小时窗口内不会再联网，但「上次发现的新版本」必须立刻能显示，
     * 否则用户重启应用后圆点与横幅会凭空消失一天。
     */
    fun init(context: Context) {
        val prefs = prefs(context)
        _state.value = UpdateState(
            available = loadFound(prefs),
            dismissedVersion = AppVersion.parseOrNull(prefs.getString(KEY_DISMISSED_VERSION, null)),
            checking = false,
            autoCheckEnabled = prefs.getBoolean(KEY_AUTO_CHECK, true),
            lastCheckAt = prefs.getLong(KEY_LAST_CHECK_AT, 0L)
        )
    }

    fun setAutoCheckEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO_CHECK, enabled).apply()
        _state.update { it.copy(autoCheckEnabled = enabled) }
    }

    /** 冷启动后的自动检查：受开关与 24 小时窗口约束，失败静默。 */
    suspend fun autoCheck(context: Context) {
        val snapshot = _state.value
        if (!snapshot.autoCheckEnabled) return
        val now = System.currentTimeMillis()
        if (now - snapshot.lastCheckAt < CHECK_INTERVAL_MS) return
        _state.update { it.copy(checking = true) }
        // finally 收尾：调用方是界面作用域（冷启动那个 LaunchedEffect / 用户页），
        // 中途被取消时若不给 checking 收尾，界面会永远停在「正在检查…」且入口点不动。
        try {
            applyOutcome(context, fetch())
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    /** 手动检查：不受 24 小时限制，结果交给调用方提示。 */
    suspend fun checkNow(context: Context): CheckOutcome {
        _state.update { it.copy(checking = true) }
        try {
            val outcome = fetch()
            applyOutcome(context, outcome)
            return outcome
        } finally {
            _state.update { it.copy(checking = false) }
        }
    }

    /** 用户在更新页点「稍后」：该版本不再提示，直到出现更高的版本。 */
    fun dismiss(context: Context, version: AppVersion) {
        prefs(context).edit().putString(KEY_DISMISSED_VERSION, version.storageString()).apply()
        _state.update { it.copy(dismissedVersion = version) }
    }

    /**
     * 下载并校验。[info] 的资产先落到 `cacheDir/updates/`，校验通过才把
     * [DownloadState.Ready] 交给界面去调起安装器。
     *
     * 重复调用（页面重组 / 连点）时若已有下载在跑，直接忽略。
     */
    fun startDownload(context: Context, info: UpdateInfo) {
        if (downloadJob?.isActive == true) return
        val appContext = context.applicationContext
        val directory = File(appContext.cacheDir, DOWNLOAD_DIR)
        val active = UpdateDownloader(directory)
        downloader = active
        cancelRequested = false
        _download.value = DownloadState.Downloading(0L, info.assetSize)
        downloadJob = scope.launch {
            try {
                val file = active.download(
                    url = info.assetUrl,
                    fileName = fileNameOf(info)
                ) { downloaded, total ->
                    _download.value = DownloadState.Downloading(
                        downloaded = downloaded,
                        total = if (total > 0) total else info.assetSize
                    )
                }
                _download.value = DownloadState.Verifying
                _download.value = verify(appContext, file, info)
            } catch (_: DownloadCancelledException) {
                // 取消不是失败：半截文件留着，下次续传。
                _download.value = DownloadState.Idle
            } catch (_: Exception) {
                // 半截文件同样留着（断点续传），只是状态上如实报告失败。
                if (!cancelRequested) _download.value = DownloadState.Failed(FailureReason.NETWORK)
            }
        }
    }

    fun cancelDownload() {
        cancelRequested = true
        downloader?.cancel()
        downloadJob?.cancel()
        downloader = null
        _download.value = DownloadState.Idle
    }

    /** Android 8+ 需要用户为本应用打开「安装未知应用」。 */
    fun canInstallPackages(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { context.packageManager.canRequestPackageInstalls() }.getOrDefault(false)
        } else {
            true
        }

    /** 「安装未知应用」授权页；返回后由界面重新尝试安装。 */
    fun unknownSourcesIntent(context: Context): Intent =
        Intent(
            Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
            Uri.parse("package:${context.packageName}")
        )

    /** 交给系统安装器。签名 / 版本校验已由 [verify] 通过，这里只负责调起。 */
    fun installApk(context: Context, file: File): Boolean {
        val uri = runCatching {
            FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        }.getOrNull() ?: return false
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    private fun verify(context: Context, file: File, info: UpdateInfo): DownloadState {
        val installed = ApkArchive.readInstalled(context)
        val archive = ApkArchive.readArchive(context, file)
        if (installed == null || archive == null) {
            return DownloadState.Failed(FailureReason.ARCHIVE_UNREADABLE)
        }
        return when (ApkVerification.check(archive, installed)) {
            ApkCheck.OK -> DownloadState.Ready(file, info)
            ApkCheck.PACKAGE_MISMATCH -> DownloadState.Failed(FailureReason.PACKAGE_MISMATCH)
            ApkCheck.VERSION_NOT_HIGHER -> DownloadState.Failed(FailureReason.VERSION_NOT_HIGHER)
            ApkCheck.SIGNATURE_MISMATCH -> DownloadState.Failed(FailureReason.SIGNATURE_MISMATCH)
        }
    }

    private suspend fun fetch(): CheckOutcome = withContext(Dispatchers.IO) {
        val current = currentVersion() ?: return@withContext CheckOutcome.Failed
        try {
            val request = Request.Builder()
                .url(RELEASES_API)
                .header("User-Agent", USER_AGENT)
                .header("Accept", "application/vnd.github+json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@withContext CheckOutcome.Failed
                val body = response.body?.string() ?: return@withContext CheckOutcome.Failed
                val info = UpdateChecker.find(body, current)
                if (info != null) CheckOutcome.Found(info) else CheckOutcome.UpToDate
            }
        } catch (_: Exception) {
            CheckOutcome.Failed
        }
    }

    /** 把一次检查的结果落进状态与 prefs。`checking` 由调用方的 finally 负责收尾。 */
    private fun applyOutcome(context: Context, outcome: CheckOutcome) {
        // 失败也记时间：否则每次冷启动都重试一遍，白烧未认证的 60 次 / 小时限额
        // （Windows 的 UpdateService 同样在失败后写缓存时间）。
        val now = System.currentTimeMillis()
        when (outcome) {
            is CheckOutcome.Found -> {
                persistFound(context, outcome.info)
                _state.update { it.copy(available = outcome.info, lastCheckAt = now) }
            }
            CheckOutcome.UpToDate -> {
                // 已经是最新（例如刚升级完）→ 清掉上次的结果，圆点与横幅随之消失。
                clearFound(context)
                _state.update { it.copy(available = null, lastCheckAt = now) }
            }
            CheckOutcome.Failed -> {
                _state.update { it.copy(lastCheckAt = now) }
            }
        }
        prefs(context).edit().putLong(KEY_LAST_CHECK_AT, now).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun fileNameOf(info: UpdateInfo): String {
        // 资产名来自网络：只取最后一段，别让它带路径分隔符写出缓存目录之外。
        val safe = info.assetName.substringAfterLast('/').substringAfterLast('\\')
        return if (safe.isNotBlank() && safe.endsWith(".apk", ignoreCase = true)) safe else "ncrust-update.apk"
    }

    private fun persistFound(context: Context, info: UpdateInfo) {
        prefs(context).edit()
            .putString(KEY_FOUND_VERSION, info.version.storageString())
            .putString(KEY_FOUND_ASSET_NAME, info.assetName)
            .putString(KEY_FOUND_ASSET_URL, info.assetUrl)
            .putLong(KEY_FOUND_ASSET_SIZE, info.assetSize)
            .putString(KEY_FOUND_TAG, info.releaseTag)
            .putString(KEY_FOUND_PAGE_URL, info.releasePageUrl)
            .putString(KEY_FOUND_PUBLISHED_AT, info.publishedAt)
            .putString(KEY_FOUND_BODY, info.body)
            .apply()
    }

    private fun clearFound(context: Context) {
        prefs(context).edit()
            .remove(KEY_FOUND_VERSION)
            .remove(KEY_FOUND_ASSET_NAME)
            .remove(KEY_FOUND_ASSET_URL)
            .remove(KEY_FOUND_ASSET_SIZE)
            .remove(KEY_FOUND_TAG)
            .remove(KEY_FOUND_PAGE_URL)
            .remove(KEY_FOUND_PUBLISHED_AT)
            .remove(KEY_FOUND_BODY)
            .apply()
    }

    private fun loadFound(prefs: SharedPreferences): UpdateInfo? {
        val version = AppVersion.parseOrNull(prefs.getString(KEY_FOUND_VERSION, null)) ?: return null
        val url = prefs.getString(KEY_FOUND_ASSET_URL, null) ?: return null
        return UpdateInfo(
            version = version,
            assetName = prefs.getString(KEY_FOUND_ASSET_NAME, null) ?: "ncrust-update.apk",
            assetUrl = url,
            assetSize = prefs.getLong(KEY_FOUND_ASSET_SIZE, 0L),
            releaseTag = prefs.getString(KEY_FOUND_TAG, null),
            releasePageUrl = prefs.getString(KEY_FOUND_PAGE_URL, null),
            publishedAt = prefs.getString(KEY_FOUND_PUBLISHED_AT, null),
            body = prefs.getString(KEY_FOUND_BODY, null)
        )
    }
}
