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
import java.util.concurrent.atomic.AtomicLong

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

/**
 * 下载与校验的进度。
 *
 * 每个非 Idle 状态都带 [target]（稳定资产标识），页面据此**只认自己目标的进度与结果**：
 * 下载过 A 没安装、随后发现 B 进入 B 页时，A 的 [Ready] 绝不能被当成 B 的安装包。
 */
sealed interface DownloadState {
    data object Idle : DownloadState

    /** [total] 为 0 表示总长未知。 */
    data class Downloading(val downloaded: Long, val total: Long, val target: String) : DownloadState

    data class Verifying(val target: String) : DownloadState

    data class Ready(val file: File, val info: UpdateInfo) : DownloadState

    data class Failed(val reason: FailureReason, val target: String) : DownloadState
}

/**
 * 把全局下载状态收窄到某个页面目标：别的目标的进度 / 结果一律当 Idle，
 * 这样 B 页不会显示 A 的进度，更不会拿 A 的 [DownloadState.Ready] 去安装。
 */
internal fun DownloadState.forTarget(target: String): DownloadState = when (this) {
    is DownloadState.Ready ->
        if (info.targetKey == target && file.isFile) this else DownloadState.Idle
    is DownloadState.Downloading -> if (this.target == target) this else DownloadState.Idle
    is DownloadState.Verifying -> if (this.target == target) this else DownloadState.Idle
    is DownloadState.Failed -> if (this.target == target) this else DownloadState.Idle
    DownloadState.Idle -> DownloadState.Idle
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

    // per_page 取 GitHub 上限 100；再据响应 Link 头翻页（见 ReleaseFeed），
    // 否则落在第 21 个及以后的资产会被漏掉并误判「已最新」。
    private const val RELEASES_API =
        "https://api.github.com/repos/GuitaristRin/Ncrust/releases?per_page=100"

    /** 翻页上限，防病态的分页链把 60 次 / 小时限额烧光。 */
    private const val MAX_RELEASES_PAGES = 10

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
    private const val KEY_FOUND_ASSET_ID = "update_found_asset_id"
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

    /**
     * 下载任务代次。每次 [startDownload] / [cancelDownload] 都自增，
     * 旧任务的进度回调与收尾写状态前先比对代次——取消后旧任务必须彻底哑火，
     * 否则它捕获 `DownloadCancelledException` 时会把紧接着新开的下载覆盖成 Idle。
     */
    private val downloadGeneration = AtomicLong(0L)

    /** 当前版本：唯一来源是 `build.gradle.kts` 的 `versionName`（经 BuildConfig 注入）。 */
    fun currentVersion(): AppVersion? = AppVersion.parseOrNull(BuildConfig.VERSION_NAME)

    /**
     * 从 SharedPreferences 恢复状态。每次冷启动在 `MainActivity.onCreate` 调一次：
     * 24 小时窗口内不会再联网，但「上次发现的新版本」必须立刻能显示，
     * 否则用户重启应用后圆点与横幅会凭空消失一天。
     *
     * 恢复时按 [UpdateRecovery] 过滤：用户升级后重启，落盘的版本可能已不再更高，
     * 必须丢弃并清掉 prefs，否则会显示「更新到当前版本」。
     */
    fun init(context: Context) {
        val prefs = prefs(context)
        val stored = loadFound(prefs)
        val available = UpdateRecovery.restore(stored, currentVersion())
        if (stored != null && available == null) {
            clearFound(context)
        }
        _state.value = UpdateState(
            available = available,
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
        val target = info.targetKey
        val directory = File(appContext.cacheDir, DOWNLOAD_DIR)
        val active = UpdateDownloader(directory)
        downloader = active
        val generation = downloadGeneration.incrementAndGet()
        publish(generation, DownloadState.Downloading(0L, info.assetSize, target))
        downloadJob = scope.launch {
            try {
                val file = active.download(
                    url = info.assetUrl,
                    fileName = fileNameOf(info),
                    identity = target,
                    expectedSize = info.assetSize
                ) { downloaded, total ->
                    publish(
                        generation,
                        DownloadState.Downloading(
                            downloaded = downloaded,
                            total = if (total > 0) total else info.assetSize,
                            target = target
                        )
                    )
                }
                publish(generation, DownloadState.Verifying(target))
                publish(generation, verify(appContext, file, info))
            } catch (_: DownloadCancelledException) {
                // 取消不是失败：半截文件留着，下次续传。代次已被 cancel 推高，这里多半是哑火。
                publish(generation, DownloadState.Idle)
            } catch (_: Exception) {
                // 半截文件同样留着（断点续传），只是状态上如实报告失败。
                publish(generation, DownloadState.Failed(FailureReason.NETWORK, target))
            }
        }
    }

    fun cancelDownload() {
        // 推高代次：旧任务之后的所有写状态（含 catch / finally）都被 publish 挡掉，
        // 不会覆盖紧接着新开的下载。
        downloadGeneration.incrementAndGet()
        downloader?.cancel()
        downloadJob?.cancel()
        downloadJob = null
        downloader = null
        _download.value = DownloadState.Idle
    }

    /** 只有代次仍是当前任务的回调才允许写状态。 */
    private fun publish(generation: Long, state: DownloadState) {
        if (downloadGeneration.get() == generation) _download.value = state
    }

    /**
     * 当前 [DownloadState.Ready]（若有）是否正好指向 [info] 这份资产，且文件还在。
     *
     * 界面用它做显示与安装的双重门控：目标不一致或文件已不在就返回 null，
     * 绝不能拿别的版本的已下载包去安装。
     */
    fun readyFor(info: UpdateInfo): DownloadState.Ready? =
        (_download.value as? DownloadState.Ready)
            ?.takeIf { it.info.targetKey == info.targetKey && it.file.isFile }

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
            return DownloadState.Failed(FailureReason.ARCHIVE_UNREADABLE, info.targetKey)
        }
        return when (ApkVerification.check(archive, installed)) {
            ApkCheck.OK -> DownloadState.Ready(file, info)
            ApkCheck.PACKAGE_MISMATCH -> DownloadState.Failed(FailureReason.PACKAGE_MISMATCH, info.targetKey)
            ApkCheck.VERSION_NOT_HIGHER -> DownloadState.Failed(FailureReason.VERSION_NOT_HIGHER, info.targetKey)
            ApkCheck.SIGNATURE_MISMATCH -> DownloadState.Failed(FailureReason.SIGNATURE_MISMATCH, info.targetKey)
        }
    }

    private suspend fun fetch(): CheckOutcome = withContext(Dispatchers.IO) {
        val current = currentVersion() ?: return@withContext CheckOutcome.Failed
        try {
            val pages = mutableListOf<String?>()
            var url: String? = RELEASES_API
            var page = 0
            while (url != null && page < MAX_RELEASES_PAGES) {
                page++
                val request = Request.Builder()
                    .url(url)
                    .header("User-Agent", USER_AGENT)
                    .header("Accept", "application/vnd.github+json")
                    .build()
                val (body, next) = client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) return@withContext CheckOutcome.Failed
                    val text = response.body?.string() ?: return@withContext CheckOutcome.Failed
                    text to nextPageUrl(response.header("Link"))
                }
                pages += body
                url = next
            }
            // 解析失败（含非 JSON / 非数组）由 evaluateReleases 返回 null → Failed，
            // 绝不能退化成 UpToDate 去清掉上次真实发现的结果。
            evaluateReleases(pages, current) ?: CheckOutcome.Failed
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
            .putString(KEY_FOUND_ASSET_ID, info.assetId)
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
            .remove(KEY_FOUND_ASSET_ID)
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
            body = prefs.getString(KEY_FOUND_BODY, null),
            assetId = prefs.getString(KEY_FOUND_ASSET_ID, null)
        )
    }
}
