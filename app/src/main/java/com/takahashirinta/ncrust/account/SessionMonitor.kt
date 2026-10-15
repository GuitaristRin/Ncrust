package com.takahashirinta.ncrust.account

import android.content.Context
import android.util.Log
import com.takahashirinta.ncrust.auth.CookieManager
import com.takahashirinta.ncrust.network.PlaylistApi
import com.takahashirinta.ncrust.network.RetrofitClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 会话状态单例（界面订阅 [state]）。
 *
 * 规格：`spec/api/entitlement.md` §状态模型。存在的原因：登录失效时网易**不报错**——
 * `account/get` 照样 200（只是 `account: null`），只有收藏等写/私有接口返回 301。
 * 客户端若只看得见「请求成功」，用户看到的就是「每日推荐变了 / 收藏刷不出来」。
 *
 * 判定时机：
 *  - 启动时一次（[init]）；
 *  - 回前台且距上次判定 ≥ 30 分钟一次（[onResume]）；
 *  - 任一需登录接口返回 301 时立即转 `Expired`（[reportApiCode]）。
 *
 * 转入 `Expired` 会清掉请求层的失效 cookie，但**保留**「曾登录」标记与
 * `LibraryManager` 的本地收藏缓存：会话失效不是用户数据失效。
 */
object SessionMonitor {
    private const val TAG = "SessionMonitor"
    private const val PREFS_NAME = "ncrust_settings"
    // 「曾登录」标记：cookie 被清掉后，界面靠它区分「登录已失效」与「从未登录」。
    private const val KEY_WAS_LOGGED_IN = "session_was_logged_in"
    // 回前台复核的最小间隔，规格写死 30 分钟。
    private const val RECHECK_INTERVAL_MS = 30 * 60 * 1000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<SessionState>(SessionState.Unknown)
    val state: StateFlow<SessionState> = _state.asStateFlow()

    @Volatile private var appContext: Context? = null
    @Volatile private var lastCheckAt = 0L
    @Volatile private var checking = false
    // 会话失效的代号：复核期间（account/get → vip/info 两跳）若中途被判失效，
    // 复核结果不得把 Expired 覆盖回 Valid。
    @Volatile private var expiryGeneration = 0

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun wasLoggedIn(context: Context): Boolean =
        prefs(context).getBoolean(KEY_WAS_LOGGED_IN, false)

    /**
     * 启动时判定一次。调用方需在 [RetrofitClient.init] 之后调用（本对象会读 cookie）。
     */
    fun init(context: Context) {
        val app = context.applicationContext
        appContext = app
        // 升级上来的老用户只有 cookie 没有标记：先补上标记，否则首次判失效后
        // 下次启动会退化成「从未登录」，横幅文案就错了。
        if (CookieManager.hasCookie(app) && !wasLoggedIn(app)) {
            prefs(app).edit().putBoolean(KEY_WAS_LOGGED_IN, true).apply()
        }
        if (!hasCredential(app)) {
            _state.value = SessionState.LoggedOut
            return
        }
        // 进程内只强制判一次：Activity 旋转重建也会走 onCreate，不该每次都打一次网络。
        refresh(app, force = lastCheckAt == 0L)
    }

    /** 回到前台：距上次判定 ≥ 30 分钟才复核，避免每次切前台都打一次网络。 */
    fun onResume(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (!hasCredential(app)) {
            lastCheckAt = System.currentTimeMillis()
            _state.value = SessionState.LoggedOut
            return
        }
        refresh(app, force = false)
    }

    /** 登录成功（WebView / 扫码）：置「曾登录」标记并立刻复核，别被 30 分钟窗口挡住。 */
    fun onLoginSucceeded(context: Context) {
        val app = context.applicationContext
        appContext = app
        prefs(app).edit().putBoolean(KEY_WAS_LOGGED_IN, true).apply()
        lastCheckAt = 0L
        refresh(app, force = true)
    }

    /**
     * 用户主动退出登录：清掉「曾登录」标记，回到 `LoggedOut`。
     *
     * 必须与「失效」区分：退出是用户的选择，再显示「登录已失效 · 重新登录」就成了骚扰。
     */
    fun onLoggedOut(context: Context) {
        val app = context.applicationContext
        appContext = app
        prefs(app).edit().remove(KEY_WAS_LOGGED_IN).apply()
        _state.value = SessionState.LoggedOut
    }

    /**
     * 网络层上报业务码：301 = 未登录 → 立即转 `Expired`。
     *
     * 去抖靠 [refresh] 自身：同一时刻只允许一次 `account/get` 在途，所以并发返回的几个
     * 301 不会各打一次复核网络请求。
     */
    fun reportApiCode(code: Int?) {
        if (code != 301) return
        val app = appContext ?: return
        markExpired(app)
    }

    /** 立即转 `Expired`：清请求层 cookie，保留「曾登录」标记与本地收藏缓存。 */
    fun markExpired(context: Context) {
        val app = context.applicationContext
        appContext = app
        if (_state.value is SessionState.Expired) return
        expiryGeneration++
        CookieManager.clearCookie(app)
        RetrofitClient.updateCookie(null)
        _state.value = SessionState.Expired
        Log.w(TAG, "会话已失效：已清 cookie，本地收藏缓存保留")
    }

    /**
     * `account/get` 复核（去抖：同时在途只发一次）。
     *
     * @param force true = 忽略 30 分钟窗口（启动、登录成功后必须立刻判一次）。
     */
    fun refresh(context: Context, force: Boolean = false) {
        val app = context.applicationContext
        appContext = app
        val now = System.currentTimeMillis()
        if (!force && now - lastCheckAt < RECHECK_INTERVAL_MS) return
        synchronized(this) {
            if (checking) return
            checking = true
        }
        lastCheckAt = now
        scope.launch {
            try {
                checkBlocking(app)
            } catch (e: Throwable) {
                // 判定本身出错绝不改状态：维持上一个已知状态（可能是 Valid）。
                Log.w(TAG, "会话复核异常，维持原状态: ${e.message}")
            } finally {
                checking = false
            }
        }
    }

    private suspend fun checkBlocking(app: Context) {
        if (!hasCredential(app)) {
            _state.value = SessionState.LoggedOut
            return
        }
        val response: AccountGetResponse? =
            runCatching { PlaylistApi.getAccount() }.getOrNull()
        when (val derived = deriveSession(hasCookie = true, accountGet = response)) {
            is SessionState.Valid -> {
                val generation = expiryGeneration
                // VIP 只在 Valid 时拉；失败（含 301）显示为未知，绝不显示「未开通」。
                val vipResponse = runCatching { PlaylistApi.getVipInfo(derived.userId) }.getOrNull()
                if (expiryGeneration != generation) return
                val vip = deriveVip(vipResponse)
                _state.value = derived.copy(vip = vip)
                // 只记 userId 与 VIP 派生结果：昵称/头像属于用户资料，不进 logcat。
                Log.i(TAG, "会话有效 userId=${derived.userId} vip=$vip")
            }
            SessionState.Unknown -> {
                // 网络失败 / 非 200：维持上一个已知状态，绝不因抖动判失效。
                // 只记状态类型：Valid 里带昵称等用户资料，不进 logcat。
                Log.w(TAG, "account/get 不可用，维持会话状态: ${_state.value::class.simpleName}")
            }
            SessionState.Expired -> markExpired(app)
            SessionState.LoggedOut -> _state.value = SessionState.LoggedOut
        }
    }

    /** 本地是否有凭证：cookie（或已清 cookie 但曾登录过）。 */
    private fun hasCredential(context: Context): Boolean =
        CookieManager.hasCookie(context) || wasLoggedIn(context)
}
