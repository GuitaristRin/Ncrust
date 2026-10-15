package com.takahashirinta.ncrust.account

import com.google.gson.annotations.SerializedName

/**
 * 会话状态判定（纯函数）。
 *
 * 规格：`spec/api/entitlement.md` §状态模型；答案：`spec/fixtures/entitlement/session.json`。
 * 关键区分：登录失效时 `account/get` **仍然 200**，只是 `account`/`profile` 为 null——
 * 只看 HTTP 状态码是查不出来的；而网络失败 / 非 200 必须维持上一个已知状态，
 * 一次抖动就把用户判成失效是最糟的误报。
 */
sealed interface SessionState {
    /** 客户端尚未判定过（首次判定在途）。界面按「非 Valid」处理，但不显示失效横幅。 */
    data object Unknown : SessionState

    /** 本地既没有 cookie 也没有「曾登录」标记。 */
    data object LoggedOut : SessionState

    /** 有凭证但服务端不认（`account` 为空，或任一需登录接口返回 301）。 */
    data object Expired : SessionState

    /**
     * 正常登录。[vip] 由 [SessionMonitor] 另行拉取，判定函数只负责填 [VipState.Unknown]。
     */
    data class Valid(
        val userId: Long,
        val nickname: String,
        val avatarUrl: String,
        val vip: VipState = VipState.Unknown
    ) : SessionState
}

/** `eapi /w/nuser/account/get` 的响应（只取判定与展示需要的字段）。 */
data class AccountGetResponse(
    @SerializedName("code") val code: Int? = null,
    @SerializedName("account") val account: AccountInfo? = null,
    @SerializedName("profile") val profile: ProfileInfo? = null
)

data class AccountInfo(
    @SerializedName("id") val id: Long? = null,
    @SerializedName("userName") val userName: String? = null,
    @SerializedName("avatarUrl") val avatarUrl: String? = null
)

data class ProfileInfo(
    @SerializedName("userId") val userId: Long? = null,
    @SerializedName("nickname") val nickname: String? = null,
    @SerializedName("avatarUrl") val avatarUrl: String? = null
)

/**
 * 判定会话状态。
 *
 * @param hasCookie 本地是否持有凭证——**包含「曾登录」标记**：转入 `Expired` 时会清掉
 *   cookie 但保留该标记，之后必须一直判成 `Expired`（横幅据此说「登录已失效」而不是
 *   「未登录」），而 `LoggedOut` 只留给从没登录过的设备。
 * @param accountGet `account/get` 的响应；null = 网络失败。
 * @param otherApiCode 本次判定期间任一需登录接口返回的业务码（301 即未登录）。
 */
fun deriveSession(
    hasCookie: Boolean,
    accountGet: AccountGetResponse?,
    otherApiCode: Int? = null
): SessionState {
    // 301 是服务端的明确答复，优先于任何其他信号。
    if (otherApiCode == 301) return SessionState.Expired
    if (accountGet == null || accountGet.code != 200) return SessionState.Unknown
    val account = accountGet.account
    val profile = accountGet.profile
    if (account != null && profile != null) {
        return SessionState.Valid(
            userId = profile.userId ?: account.id ?: 0L,
            nickname = profile.nickname ?: account.userName ?: "",
            avatarUrl = profile.avatarUrl ?: account.avatarUrl ?: ""
        )
    }
    return if (hasCookie) SessionState.Expired else SessionState.LoggedOut
}
