package com.takahashirinta.ncrust.account

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 提示的去重/合并规则（规格 `spec/api/entitlement.md` §界面要求）。
 *
 * 这些规则只靠真机很难回归：要先遇到 VIP 歌、再连续遇到两首无版权的歌。
 * 所以把它们抽成纯逻辑（[PlaybackNoticeTracker]）在这里锁住。
 */
class PlaybackNoticeTrackerTest {

    private val vip = EntitlementReason.VIP
    private val copyright = EntitlementReason.COPYRIGHT

    @Test
    fun `trial notice fires once per song`() {
        val tracker = PlaybackNoticeTracker()
        tracker.recordTrial(songId = 7, seconds = 45, reason = vip)

        val first = tracker.trialNoticeOnStart(7)
        assertEquals(PlaybackNotice.Trial(7, 45, vip), first)
        // 同一首再次起播（单曲循环 / 手动重播）不再提示。
        assertNull(tracker.trialNoticeOnStart(7))
    }

    @Test
    fun `trial notice fires for each different song`() {
        val tracker = PlaybackNoticeTracker()
        tracker.recordTrial(1, 45, vip)
        tracker.recordTrial(2, 30, EntitlementReason.ALBUM)

        assertEquals(PlaybackNotice.Trial(1, 45, vip), tracker.trialNoticeOnStart(1))
        assertEquals(PlaybackNotice.Trial(2, 30, EntitlementReason.ALBUM), tracker.trialNoticeOnStart(2))
    }

    @Test
    fun `unrecorded song is not trial and never notifies`() {
        val tracker = PlaybackNoticeTracker()
        assertFalse(tracker.isTrial(99))
        assertNull(tracker.trialNoticeOnStart(99))
        tracker.recordTrial(99, 45, vip)
        assertTrue(tracker.isTrial(99))
    }

    @Test
    fun `capped notice fires only once per tracker`() {
        val tracker = PlaybackNoticeTracker()
        assertEquals(
            PlaybackNotice.Capped(1, "exhigh", vip),
            tracker.cappedNotice(1, "exhigh", vip)
        )
        // 换一首歌、换一个档位都不再提示：规格是"同一会话/进程内首次"。
        assertNull(tracker.cappedNotice(2, "higher", EntitlementReason.LOGIN))
    }

    @Test
    fun `single skip carries song name and consecutive skips merge into a count`() {
        val tracker = PlaybackNoticeTracker()
        assertEquals(
            PlaybackNotice.Skipped(1, "歌一", copyright),
            tracker.skipNotice(1, "歌一", copyright)
        )
        assertEquals(PlaybackNotice.SkippedMany(2), tracker.skipNotice(2, "歌二", copyright))
        assertEquals(PlaybackNotice.SkippedMany(3), tracker.skipNotice(3, "歌三", copyright))
    }

    @Test
    fun `skip streak resets after a song actually starts`() {
        val tracker = PlaybackNoticeTracker()
        tracker.skipNotice(1, "歌一", copyright)
        tracker.skipNotice(2, "歌二", copyright)
        // 中间播成功了一首 → 下一条跳过重新从"带歌名"开始，而不是继续累加。
        tracker.notePlayableStarted()
        assertEquals(
            PlaybackNotice.Skipped(3, "歌三", copyright),
            tracker.skipNotice(3, "歌三", copyright)
        )
    }

    @Test
    fun `blank song name falls back to merged count`() {
        val tracker = PlaybackNoticeTracker()
        // 标题为空（例如车机侧塞进来的队列外歌曲）时不能提示「已跳过《》：…」。
        assertEquals(PlaybackNotice.SkippedMany(1), tracker.skipNotice(1, "", copyright))
    }
}
