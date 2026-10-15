package com.takahashirinta.ncrust.update

/**
 * 恢复落盘的「已发现更新」时的过滤规则（**纯函数**，便于单测）。
 *
 * 冷启动时会把上次检查到的结果从 prefs 读回来，好让圆点 / 横幅不凭空消失一天。
 * 但用户升级后重启时，落盘的版本可能已经**不高于**当前版本；照原样恢复就会显示
 * 「更新到当前版本」，下载到最后才报 `VERSION_NOT_HIGHER`。所以恢复阶段必须过滤。
 *
 * 当前版本解析失败时同样返回 null：宁可让提示晚一天出现，也不能提示一个假更新。
 */
object UpdateRecovery {

    fun restore(candidate: UpdateInfo?, current: AppVersion?): UpdateInfo? {
        if (candidate == null || current == null) return null
        return if (candidate.version > current) candidate else null
    }
}
