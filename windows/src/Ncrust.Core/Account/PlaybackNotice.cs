namespace Ncrust.Core.Account
{
    public enum PlaybackNoticeKind
    {
        /// <summary>试听片段开始播放（每首首次）。</summary>
        Trial,

        /// <summary>音质被账号封顶（同一进程首次）。</summary>
        Capped,

        /// <summary>跳过一首不可播的歌。</summary>
        Skipped,

        /// <summary>连续多首不可播：合并成一条计数，避免刷屏。</summary>
        SkippedMany,
    }

    /// <summary>
    /// 需要提示一次播放权益事件（Toast 级：不弹模态框、不打断播放）。
    ///
    /// 只带枚举与数值，文案由界面层渲染（Windows 端集中在 <c>Resources.DisplayFormat</c>）。
    /// 每个分支都带 <paramref name="SongId"/>：两首不同的歌判出同样的 <c>Trial(45, Login)</c> 时，
    /// 按值比较的去重实现会把第二条提示吞掉，带上 id 就不会撞值。
    /// </summary>
    public sealed class PlaybackNotice
    {
        private PlaybackNotice(PlaybackNoticeKind kind)
        {
            Kind = kind;
        }

        public PlaybackNoticeKind Kind { get; }

        public long SongId { get; private set; }

        /// <summary>试听秒数（仅 <see cref="PlaybackNoticeKind.Trial"/>）。</summary>
        public int TrialSeconds { get; private set; }

        /// <summary>实际拿到的档位（仅 <see cref="PlaybackNoticeKind.Capped"/>）。</summary>
        public string Level { get; private set; } = string.Empty;

        /// <summary>歌名（仅 <see cref="PlaybackNoticeKind.Skipped"/>）。</summary>
        public string SongName { get; private set; } = string.Empty;

        /// <summary>合并后的连续跳过数量（仅 <see cref="PlaybackNoticeKind.SkippedMany"/>）。</summary>
        public int Count { get; private set; }

        public EntitlementReason Reason { get; private set; }

        public static PlaybackNotice Trial(long songId, int seconds, EntitlementReason reason) =>
            new PlaybackNotice(PlaybackNoticeKind.Trial)
            {
                SongId = songId,
                TrialSeconds = seconds,
                Reason = reason,
            };

        public static PlaybackNotice Capped(long songId, string level, EntitlementReason reason) =>
            new PlaybackNotice(PlaybackNoticeKind.Capped)
            {
                SongId = songId,
                Level = level,
                Reason = reason,
            };

        public static PlaybackNotice Skipped(long songId, string songName, EntitlementReason reason) =>
            new PlaybackNotice(PlaybackNoticeKind.Skipped)
            {
                SongId = songId,
                SongName = songName,
                Reason = reason,
            };

        public static PlaybackNotice SkippedMany(int count) =>
            new PlaybackNotice(PlaybackNoticeKind.SkippedMany) { Count = count };
    }
}
