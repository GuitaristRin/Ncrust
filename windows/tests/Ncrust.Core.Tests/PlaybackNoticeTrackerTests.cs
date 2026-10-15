using Ncrust.Core.Account;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>
/// 播放权益提示的去重与合并规则（spec/api/entitlement.md §界面要求）：
/// 试听每首一次、封顶每进程一次、连续跳过合并计数。
/// </summary>
public class PlaybackNoticeTrackerTests
{
    [Fact]
    public void Trial_NoticesOncePerSong()
    {
        var tracker = new PlaybackNoticeTracker();
        tracker.RecordTrial(1, 45, EntitlementReason.Vip);

        Assert.True(tracker.IsTrial(1)); // 界面据此把音质徽标换成「试听」。

        var notice = tracker.TrialNoticeOnStart(1);
        Assert.NotNull(notice);
        Assert.Equal(PlaybackNoticeKind.Trial, notice!.Kind);
        Assert.Equal(1, notice.SongId);
        Assert.Equal(45, notice.TrialSeconds);
        Assert.Equal(EntitlementReason.Vip, notice.Reason);

        Assert.Null(tracker.TrialNoticeOnStart(1)); // 同一首不重复提示
    }

    [Fact]
    public void Trial_UnknownSong_IsNotTrial()
    {
        var tracker = new PlaybackNoticeTracker();
        Assert.False(tracker.IsTrial(9));
        Assert.Null(tracker.TrialNoticeOnStart(9));
    }

    [Fact]
    public void Trial_RecordIsSelfBounded()
    {
        var tracker = new PlaybackNoticeTracker();
        for (var id = 1L; id <= 100; id++)
        {
            tracker.RecordTrial(id, 45, EntitlementReason.Login);
        }

        // 辅助表不能无限长：满了整表清掉，最后一条记录仍在。
        Assert.True(tracker.IsTrial(100));
        Assert.False(tracker.IsTrial(1));
    }

    [Fact]
    public void Capped_NoticesOncePerProcess()
    {
        var tracker = new PlaybackNoticeTracker();
        tracker.RecordCapped(1, "exhigh", EntitlementReason.Vip);
        tracker.RecordCapped(2, "exhigh", EntitlementReason.Vip);

        var notice = tracker.CappedNoticeOnStart(1);
        Assert.NotNull(notice);
        Assert.Equal(PlaybackNoticeKind.Capped, notice!.Kind);
        Assert.Equal("exhigh", notice.Level);

        Assert.Null(tracker.CappedNoticeOnStart(2)); // 同一进程只提示一次
        Assert.Null(tracker.CappedNoticeOnStart(1));
    }

    [Fact]
    public void Capped_NotRecorded_IsNull()
    {
        var tracker = new PlaybackNoticeTracker();
        Assert.Null(tracker.CappedNoticeOnStart(3));
    }

    [Fact]
    public void Skip_FirstCarriesName_ThenMergesIntoCount()
    {
        var tracker = new PlaybackNoticeTracker();

        var first = tracker.SkipNotice(1, "甲", EntitlementReason.Copyright);
        Assert.Equal(PlaybackNoticeKind.Skipped, first.Kind);
        Assert.Equal("甲", first.SongName);
        Assert.Equal(EntitlementReason.Copyright, first.Reason);

        var second = tracker.SkipNotice(2, "乙", EntitlementReason.Vip);
        Assert.Equal(PlaybackNoticeKind.SkippedMany, second.Kind);
        Assert.Equal(2, second.Count);
    }

    [Fact]
    public void Skip_PlayableStartResetsStreak()
    {
        var tracker = new PlaybackNoticeTracker();
        tracker.SkipNotice(1, "甲", EntitlementReason.Unknown);
        tracker.NotePlayableStarted();

        var afterPlay = tracker.SkipNotice(2, "乙", EntitlementReason.Unknown);
        Assert.Equal(PlaybackNoticeKind.Skipped, afterPlay.Kind);
        Assert.Equal("乙", afterPlay.SongName);
    }

    [Fact]
    public void Skip_BlankNameMerges()
    {
        var tracker = new PlaybackNoticeTracker();
        var notice = tracker.SkipNotice(1, "  ", EntitlementReason.Unknown);

        // 没有歌名就不编「已跳过《》：…」这种话，直接走合并计数。
        Assert.Equal(PlaybackNoticeKind.SkippedMany, notice.Kind);
        Assert.Equal(1, notice.Count);
    }

    /// <summary>两首不同的歌判出相同的试听时长 / 原因时，两条提示都要发出去（带上 songId 才不撞值）。</summary>
    [Fact]
    public void Trial_DifferentSongsWithSameNumbers_BothNotice()
    {
        var tracker = new PlaybackNoticeTracker();
        tracker.RecordTrial(1, 45, EntitlementReason.Login);
        tracker.RecordTrial(2, 45, EntitlementReason.Login);

        Assert.NotNull(tracker.TrialNoticeOnStart(1));
        Assert.NotNull(tracker.TrialNoticeOnStart(2));
    }
}
