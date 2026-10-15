package com.takahashirinta.ncrust.ui.components

import com.takahashirinta.ncrust.account.SessionState
import com.takahashirinta.ncrust.account.VipKind
import com.takahashirinta.ncrust.account.VipState
import com.takahashirinta.ncrust.ui.i18n.Strings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 会话状态 → 会员状态文案（`spec/api/entitlement.md` §VIP）。
 *
 * 三种文案由 VIP 状态派生；会话不是 `Valid` 时返回 null——那时"会员状态"没有意义，
 * 界面已经在讲「登录已失效」。
 * 接口失败 → [VipState.Unknown] → 「会员状态未知」，**绝不**显示成「未开通」：
 * 把"没查到"说成"你没买"是这套判定里最容易犯也最伤人的错误。
 */
fun vipStatusText(strings: Strings, state: SessionState): String? {
    if (state !is SessionState.Valid) return null
    return when (val vip = state.vip) {
        is VipState.Active -> strings.vipActiveLabel(vipKindName(strings, vip.kind), vipDate(vip.expireAt))
        is VipState.Lapsed -> strings.vipLapsedLabel(vipKindName(strings, vip.kind), vipDate(vip.expireAt))
        VipState.None -> strings.vipNoneLabel
        VipState.Unknown -> strings.vipUnknownLabel
    }
}

private fun vipKindName(strings: Strings, kind: VipKind): String = when (kind) {
    VipKind.SVIP -> strings.vipKindSvip
    VipKind.VIP -> strings.vipKindVip
    VipKind.PACKAGE -> strings.vipKindPackage
}

/** 到期时间按 yyyy-MM-dd 显示（规格里的文案就是「有效期至 2026-12-01」）。 */
private fun vipDate(ms: Long): String =
    SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(ms))
