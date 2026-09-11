package com.takahashirinta.ncrust.power

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台活动授权（电池优化白名单）。
 *
 * Ncrust 依赖前台播放服务在熄屏后继续跑，但 ColorOS / MIUI 等 ROM 会主动清理
 * 后台应用，表现为"后台被杀、重进又走一遍 splash"。把本应用加入系统电池优化
 * 白名单（设置里的"允许后台活动 / 不受电池优化限制"）能显著改善存活率。
 *
 * 注意：`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 需要 Manifest 里的
 * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限；部分 ROM 会忽略直达弹窗，退化成
 * 打开电池设置页，这属于预期内的降级。
 */
object BackgroundActivity {

    /** 是否已在电池优化白名单内（无法读取 PowerManager 时按已授权处理，避免误弹）。 */
    fun isUnrestricted(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /** 跳转"允许后台活动 / 忽略电池优化"的系统弹窗。 */
    @SuppressLint("BatteryLife")
    fun requestIntent(context: Context): Intent =
        Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))

    /** 部分 ROM 不认直达弹窗时，退回到应用详情页。 */
    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
}
