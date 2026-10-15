using System.Collections.Generic;

namespace Ncrust.Core.Account
{
    /// <summary>
    /// 播放权益提示的去重与合并规则（纯逻辑，单测见 <c>PlaybackNoticeTrackerTests</c>）。
    ///
    /// 规则来自 <c>spec/api/entitlement.md</c> §界面要求：
    /// - 试听：**每首首次**提示一次（无缝衔接的歌不重新取链，靠 <see cref="RecordTrial"/> 的记录还原）；
    /// - 音质封顶：**同一进程首次**提示一次（不是每首都提示）；
    /// - 跳过：单首带歌名，**连续多首**合并成计数；中间有歌真正开播就重新从 1 开始。
    ///
    /// 抽成独立类是因为这些规则原本散在引擎里、只有跑起来才验证得了。这里的方法都是纯的，
    /// 且内部状态自带上锁（取链在后台线程、切歌回调在 UI 线程，两处都要动它）。
    /// </summary>
    public sealed class PlaybackNoticeTracker
    {
        /// <summary>不想让「避免重复提示」的辅助表无限长：记满就整表清掉（不是播放状态，没有增量价值）。</summary>
        private const int MaxTrackedSongs = 64;

        private readonly Dictionary<long, TrialInfo> _trialBySong = new Dictionary<long, TrialInfo>();
        private readonly HashSet<long> _trialNoticedSongs = new HashSet<long>();
        private readonly Dictionary<long, CappedInfo> _cappedBySong = new Dictionary<long, CappedInfo>();
        private readonly object _lock = new object();

        private int _skipStreak;
        private bool _cappedNoticed;

        /// <summary>记录一首歌是试听片段。**不发提示**——取链可能比实际起播早很多。</summary>
        public void RecordTrial(long songId, int seconds, EntitlementReason reason)
        {
            lock (_lock)
            {
                if (_trialBySong.Count > MaxTrackedSongs)
                {
                    _trialBySong.Clear();
                    _trialNoticedSongs.Clear();
                }

                _trialBySong[songId] = new TrialInfo(seconds, reason);
            }
        }

        /// <summary>该歌是否已记录为试听片段（界面据此把音质徽标换成「试听」）。</summary>
        public bool IsTrial(long songId)
        {
            lock (_lock)
            {
                return _trialBySong.ContainsKey(songId);
            }
        }

        /// <summary>歌真正开始播：该歌是试听片段且**首次**时给出提示，否则 null。</summary>
        public PlaybackNotice? TrialNoticeOnStart(long songId)
        {
            lock (_lock)
            {
                if (!_trialBySong.TryGetValue(songId, out var info) || !_trialNoticedSongs.Add(songId))
                {
                    return null;
                }

                return PlaybackNotice.Trial(songId, info.Seconds, info.Reason);
            }
        }

        /// <summary>记录一首歌的音质被封顶。**不发提示**（同上，等它真正开始播）。</summary>
        public void RecordCapped(long songId, string level, EntitlementReason reason)
        {
            lock (_lock)
            {
                if (_cappedBySong.Count > MaxTrackedSongs)
                {
                    _cappedBySong.Clear();
                }

                _cappedBySong[songId] = new CappedInfo(level, reason);
            }
        }

        /// <summary>歌真正开始播：被封顶且**同一进程还没提示过**时给出提示，否则 null。</summary>
        public PlaybackNotice? CappedNoticeOnStart(long songId)
        {
            lock (_lock)
            {
                if (_cappedNoticed || !_cappedBySong.TryGetValue(songId, out var info))
                {
                    return null;
                }

                _cappedNoticed = true;
                return PlaybackNotice.Capped(songId, info.Level, info.Reason);
            }
        }

        /// <summary>一首歌成功开播（完整或试听）：连续跳过的计数归零。</summary>
        public void NotePlayableStarted()
        {
            lock (_lock)
            {
                _skipStreak = 0;
            }
        }

        /// <summary>跳过一首不可播的歌：首首带歌名，连续多首合并成一条计数。</summary>
        public PlaybackNotice SkipNotice(long songId, string songName, EntitlementReason reason)
        {
            lock (_lock)
            {
                _skipStreak++;
                return _skipStreak <= 1 && !string.IsNullOrWhiteSpace(songName)
                    ? PlaybackNotice.Skipped(songId, songName, reason)
                    : PlaybackNotice.SkippedMany(_skipStreak);
            }
        }

        private readonly struct TrialInfo
        {
            public TrialInfo(int seconds, EntitlementReason reason)
            {
                Seconds = seconds;
                Reason = reason;
            }

            public int Seconds { get; }

            public EntitlementReason Reason { get; }
        }

        private readonly struct CappedInfo
        {
            public CappedInfo(string level, EntitlementReason reason)
            {
                Level = level;
                Reason = reason;
            }

            public string Level { get; }

            public EntitlementReason Reason { get; }
        }
    }
}
