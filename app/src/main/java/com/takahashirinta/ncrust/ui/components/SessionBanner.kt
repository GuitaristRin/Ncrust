package com.takahashirinta.ncrust.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.takahashirinta.ncrust.ui.i18n.LocalStrings
import io.github.takahashirinta.kanesumi.core.theme.LocalMetroColors
import io.github.takahashirinta.kanesumi.core.theme.MetroIcon
import io.github.takahashirinta.kanesumi.core.theme.MetroText

/**
 * 登录失效横幅（首页 / 收藏页顶部）。
 *
 * 存在的原因：登录失效时网易大多不报错，表现是「每日推荐变了 / 收藏刷不出来 / 有些歌只能试听」
 * （`spec/api/entitlement.md`）。不告知用户，这些现象全都会被算成 Ncrust 的 bug。
 *
 * Kanesumi 风格：直角、无圆角、左右贴边；整条可点，直接进现有登录流程。
 */
@Composable
fun SessionExpiredBanner(
    onRelogin: () -> Unit,
    modifier: Modifier = Modifier
) {
    val strings = LocalStrings.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(LocalMetroColors.current.primary.copy(alpha = 0.16f))
            .clickable(onClick = onRelogin)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MetroIcon(
            Icons.Default.Warning,
            contentDescription = null,
            tint = LocalMetroColors.current.primary,
            sizeDp = 18.dp
        )
        Spacer(Modifier.width(10.dp))
        // maxLines 不设 1：8 种语言的这句话长度差很大，宁可折成两行也不能被截成
        // 「登录已失效，部分歌…」——截断后用户读不到「重新登录」这个动作。
        MetroText(
            strings.sessionExpiredBanner,
            color = LocalMetroColors.current.primary,
            style = TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium),
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}
