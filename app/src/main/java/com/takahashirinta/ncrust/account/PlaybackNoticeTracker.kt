package com.takahashirinta.ncrust.account

/**
 * 播放权益提示的去重与合并规则（纯逻辑，JVM 单测见 `PlaybackNoticeTrackerTest`）。
 *
 * 规则来自 `spec/api/entitlement.md` §界面要求：
 *  - 试听：**每首首次**提示一次（无缝衔接的歌不重新取链，靠 [recordTrial] 的记录还原）；
 *  - 音质封顶：**同一会话/进程首次**提示一次（不是每首歌都提示）；
 *  - 跳过：单首带歌名，**连续多首**合并成计数；中间有歌真正开播就重新从 1 开始。
 *
 * 抽出成独立类是因为这些规则原本散在 [com.takahashirinta.ncrust.ui.viewmodel.PlayerViewModel]
 * 里，只有 Android 运行起来才验证得了；这里的方法全部是纯的，且内部状态自带上锁
 * （取链在 IO 线程、切歌回调在主线程，两处都要动它）。
 */
class PlaybackNoticeTracker {

    /** 一首试听片段的时长与原因。 */
    data class TrialInfo(val seconds: Int, val reason: EntitlementReason)

    private val trialInfoBySong = mutableMapOf<Long, TrialInfo>()
    private val trialNoticedSongs = mutableSetOf<Long>()
    private var skipStreak = 0
    private var cappedNoticed = false

    /** 记录一首歌是试听片段。不发提示——预载可能比实际起播早一分钟。 */
    @Synchronized
    fun recordTrial(songId: Long, seconds: Int, reason: EntitlementReason) {
        // 记满就整表清掉：这是"避免重复提示"的辅助表，不是播放状态，
        // 长队列连播几十首试听也没有保留价值的增量信息。
        if (trialInfoBySong.size > MAX_TRIAL_SONGS) {
            trialInfoBySong.clear()
            trialNoticedSongs.clear()
        }
        trialInfoBySong[songId] = TrialInfo(seconds, reason)
    }

    /** 该歌是否已记录为试听片段（界面据此把音质徽标换成「试听」）。 */
    @Synchronized
    fun isTrial(songId: Long): Boolean = trialInfoBySong.containsKey(songId)

    /** 歌真正开始播：该歌是试听片段且**首次**时给出提示，否则 null。 */
    @Synchronized
    fun trialNoticeOnStart(songId: Long): PlaybackNotice.Trial? {
        val info = trialInfoBySong[songId] ?: return null
        if (!trialNoticedSongs.add(songId)) return null
        return PlaybackNotice.Trial(songId, info.seconds, info.reason)
    }

    /** 一首歌成功开播（完整或试听）：连续跳过的计数归零。 */
    @Synchronized
    fun notePlayableStarted() {
        skipStreak = 0
    }

    /** 音质被账号封顶：同一实例（≈同一进程）只提示一次。 */
    @Synchronized
    fun cappedNotice(songId: Long, level: String, reason: EntitlementReason): PlaybackNotice.Capped? {
        if (cappedNoticed) return null
        cappedNoticed = true
        return PlaybackNotice.Capped(songId, level, reason)
    }

    /** 跳过一首不可播的歌：首首带歌名，连续多首合并成一条计数。 */
    @Synchronized
    fun skipNotice(songId: Long, songName: String, reason: EntitlementReason): PlaybackNotice {
        skipStreak++
        return if (skipStreak <= 1 && songName.isNotBlank()) {
            PlaybackNotice.Skipped(songId, songName, reason)
        } else {
            PlaybackNotice.SkippedMany(skipStreak)
        }
    }

    private companion object {
        const val MAX_TRIAL_SONGS = 64
    }
}
