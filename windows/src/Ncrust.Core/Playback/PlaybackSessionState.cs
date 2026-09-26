using System;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;

namespace Ncrust.Core.Playback
{
    /// <summary>
    /// 播放状态层：把队列状态机（<see cref="PlaybackQueue"/>）、设置（<see cref="PlaybackPreferences"/>）
    /// 与持久化（<see cref="PlaybackStateStore"/>）串起来，并把「进度 → 是否需要预载下一首」的
    /// 判定收在一处。UI / PlaybackEngine 只读它，不各自重复这套规则。
    /// </summary>
    public sealed class PlaybackSessionState
    {
        /// <summary>进入当前曲最后 60 秒即触发下一首预载（对齐 Android PRELOAD_THRESHOLD_MS）。</summary>
        public const long PreloadThresholdMs = 60_000;

        private const long MinPositionForPreloadMs = 1_000;

        public PlaybackSessionState(
            PlaybackQueue queue,
            PlaybackPreferences preferences,
            PlaybackStateStore store)
        {
            Queue = queue ?? throw new ArgumentNullException(nameof(queue));
            Preferences = preferences ?? throw new ArgumentNullException(nameof(preferences));
            Store = store ?? throw new ArgumentNullException(nameof(store));
        }

        public PlaybackQueue Queue { get; }

        public PlaybackPreferences Preferences { get; }

        public PlaybackStateStore Store { get; }

        public long PositionMs { get; private set; }

        public long DurationMs { get; private set; }

        public double Progress => DurationMs > 0 ? (double)PositionMs / DurationMs : 0.0;

        /// <summary>Engine 的 2Hz 进度回调写这里。</summary>
        public void UpdateProgress(long positionMs, long durationMs)
        {
            PositionMs = positionMs;
            DurationMs = durationMs;
        }

        /// <summary>
        /// 进入当前曲最后 <see cref="PreloadThresholdMs"/> 毫秒（且已播 >1s）时返回要预载的下一首，
        /// 否则 null。gapless 关闭时恒为 null。
        /// </summary>
        public SongItem? PreloadCandidate()
        {
            if (!Preferences.GaplessEnabled || DurationMs <= 0 || PositionMs <= MinPositionForPreloadMs)
            {
                return null;
            }

            var remaining = DurationMs - PositionMs;
            if (remaining < 1 || remaining > PreloadThresholdMs)
            {
                return null;
            }

            return Queue.PeekNext();
        }

        public Task SaveAsync(CancellationToken cancellationToken = default) =>
            Store.SaveAsync(Queue.Songs, Queue.CurrentIndex, cancellationToken);

        /// <summary>恢复上次的队列与当前索引（SHUFFLE 下重建打乱表）。</summary>
        public async Task RestoreAsync(CancellationToken cancellationToken = default)
        {
            var saved = await Store.LoadAsync(cancellationToken).ConfigureAwait(false);
            if (saved == null || saved.Queue.Count == 0)
            {
                return;
            }

            Queue.ReplaceAll(saved.Queue);
            var index = saved.Index;
            if (index < 0 || index >= Queue.Songs.Count)
            {
                index = 0;
            }

            Queue.SetCurrentIndex(index);
            if (Queue.Mode == PlaybackMode.Shuffle)
            {
                Queue.RegenerateShuffle();
            }
        }
    }
}
