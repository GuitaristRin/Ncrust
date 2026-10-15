package com.takahashirinta.ncrust.account

import com.google.gson.annotations.SerializedName

/**
 * VIP 状态判定（纯函数）。
 *
 * 规格：`spec/api/entitlement.md` §VIP；答案：`spec/fixtures/entitlement/vip.json`。
 * 要点：接口失败（网络异常、非 200、301 未登录）一律 [VipState.Unknown]，
 * **绝不能**显示成「未开通」——那会把「没查到」说成「你没买」。
 */
sealed interface VipState {
    /** 已开通。[kind] 取 SVIP > 黑胶 VIP > 音乐包。 */
    data class Active(val kind: VipKind, val expireAt: Long) : VipState

    /** 开通过但已过期。[expireAt] 取最近一项的到期时间。 */
    data class Lapsed(val kind: VipKind, val expireAt: Long) : VipState

    /** 从未开通（三项都无效且无到期记录）。 */
    data object None : VipState

    /** 查不到（网络失败 / 非 200 / 未登录）。 */
    data object Unknown : VipState
}

enum class VipKind(val wire: String) {
    SVIP("svip"),
    VIP("vip"),
    PACKAGE("package")
}

/** `weapi /api/music-vip-membership/client/vip/info` 的响应。 */
data class VipInfoResponse(
    @SerializedName("code") val code: Int? = null,
    @SerializedName("data") val data: VipInfoData? = null
)

data class VipInfoData(
    /** 服务端时间（毫秒）。缺省时用本机时间，见 [deriveVip]。 */
    @SerializedName("now") val now: Long? = null,
    /** 黑胶 VIP。 */
    @SerializedName("associator") val associator: VipEntry? = null,
    /** 音乐包。 */
    @SerializedName("musicPackage") val musicPackage: VipEntry? = null,
    /** 黑胶 SVIP，可能缺省。 */
    @SerializedName("redplus") val redplus: VipEntry? = null
)

data class VipEntry(
    @SerializedName("vipCode") val vipCode: Int? = null,
    @SerializedName("expireTime") val expireTime: Long? = null
)

/**
 * 派生 VIP 状态。
 *
 * 有效 ⇔ `expireTime > now`。[now] 优先用服务端给的 `data.now`：手机时间被改过时，
 * 用本机时间会把已过期的会员显示成有效（或反之）。服务端没给才退回本机时间。
 */
fun deriveVip(
    response: VipInfoResponse?,
    localNow: Long = System.currentTimeMillis()
): VipState {
    if (response == null || response.code != 200) return VipState.Unknown
    val data = response.data ?: return VipState.Unknown
    val now = data.now ?: localNow

    // 顺序即优先级：SVIP > 黑胶 VIP > 音乐包。
    val candidates = listOf(
        Triple(VipKind.SVIP, data.redplus, "redplus"),
        Triple(VipKind.VIP, data.associator, "associator"),
        Triple(VipKind.PACKAGE, data.musicPackage, "musicPackage")
    )

    for ((kind, entry, _) in candidates) {
        val expire = entry?.expireTime ?: 0L
        if (expire > now) return VipState.Active(kind, expire)
    }

    // 都无效：只要有一项留下过到期时间，就是「开通过、已过期」，取最近的一项。
    val lapsed = candidates.mapNotNull { it.second?.expireTime?.takeIf { e -> e > 0L } }
    if (lapsed.isEmpty()) return VipState.None
    val latest = lapsed.max()
    val kind = candidates.first { (it.second?.expireTime ?: 0L) == latest }.first
    return VipState.Lapsed(kind, latest)
}
