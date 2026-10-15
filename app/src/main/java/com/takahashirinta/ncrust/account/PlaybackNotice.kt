package com.takahashirinta.ncrust.account

/**
 * 需要提示一次播放权益事件（Toast 级，不弹模态框、不打断播放）。
 *
 * 只带枚举与数值，文案由界面层按当前语言渲染（`ui/i18n`）。
 *
 * 每个分支都带 `songId`：`MutableStateFlow` 对「值相等」的写入不发射，
 * 两首不同的歌判出同样的 `Trial(45, LOGIN)` 时提示会被吞掉——带上 id 就不会撞值。
 */
sealed interface PlaybackNotice {
    /** 试听片段开始播放。每首首次提示一次。 */
    data class Trial(
        val songId: Long,
        val seconds: Int,
        val reason: EntitlementReason
    ) : PlaybackNotice

    /** 音质被账号封顶（实际档位低于请求档位）。同一进程首次提示一次。 */
    data class Capped(
        val songId: Long,
        val level: String,
        val reason: EntitlementReason
    ) : PlaybackNotice

    /** 跳过一首不可播的歌。 */
    data class Skipped(
        val songId: Long,
        val songName: String,
        val reason: EntitlementReason
    ) : PlaybackNotice

    /** 连续多首不可播：合并成一条，避免刷屏。 */
    data class SkippedMany(val count: Int) : PlaybackNotice
}
