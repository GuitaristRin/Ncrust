using System;
using System.Diagnostics;
using System.Linq;
using System.Threading.Tasks;
using Kanesumi.Xaml.Controls;
using Ncrust.Core.Api;
using Ncrust.Core.Lyrics;
using Ncrust.Playback;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;

namespace Ncrust.Player
{
    /// <summary>
    /// 播放器侧的歌词对接（对应 Android LyricsView 的对接部分）：
    ///
    /// - 取词：先读 <see cref="LyricsCache"/>，没有再请求 /api/song/lyric 并写回；按设置合并译文。
    /// - 位置外推：引擎只有 2Hz 进度，直接喂面板跨行会晚最多 500ms。这里以最近一次采样为锚点线性外推，
    ///   但不逐帧轮询：只在「下一行时间戳」那一刻唤醒一次（最长 1s，seek 后最多 1s 重新对齐）。
    ///   暂停或面板不可见时停掉定时器。
    /// - 点击某行：本地立即定位，同时让引擎 seek。
    /// </summary>
    internal sealed class LyricsController
    {
        private readonly MetroLyricsPanel _panel;
        private readonly TextBlock _status;
        private readonly DispatcherTimer _boundaryTimer = new DispatcherTimer();
        private readonly Stopwatch _clock = Stopwatch.StartNew();

        private long[] _times = Array.Empty<long>();
        private long _songId = -1;
        private int _loadVersion;

        private long _anchorPositionMs;
        private long _anchorAtMs;
        private bool _playing;
        private bool _active;

        public LyricsController(MetroLyricsPanel panel, TextBlock status)
        {
            _panel = panel;
            _status = status;
            _panel.LineInvoked += OnLineInvoked;
            _boundaryTimer.Tick += (_, __) => OnBoundary();
        }

        /// <summary>面板是否可见（卡片展开且歌词页选中）。变为可见时立即对齐并强制定位到当前行。</summary>
        public bool Active
        {
            get => _active;
            set
            {
                if (_active == value)
                {
                    return;
                }

                _active = value;
                if (_active)
                {
                    _panel.UpdatePosition(Extrapolated());
                    _panel.ForceLocate();
                }

                Reschedule();
            }
        }

        public void OnSongChanged(SongItem song)
        {
            var id = song?.Id ?? -1;
            if (id == _songId)
            {
                return;
            }

            _songId = id;
            ResetAnchor(0);
            _ = LoadAsync(id);
        }

        /// <summary>引擎 2Hz 进度：重置外推锚点。</summary>
        public void OnProgress(long positionMs)
        {
            ResetAnchor(positionMs);
            if (_active)
            {
                _panel.UpdatePosition(positionMs);
            }

            Reschedule();
        }

        public void OnPlayingChanged(bool playing)
        {
            ResetAnchor(Extrapolated());
            _playing = playing;
            Reschedule();
        }

        /// <summary>设置页切换了「歌词翻译」：重新合并当前歌的歌词。</summary>
        public void Reload() => _ = LoadAsync(_songId);

        private async Task LoadAsync(long songId)
        {
            var version = ++_loadVersion;
            _times = Array.Empty<long>();
            _panel.SetLines(Array.Empty<MetroLyricLine>());

            // 加载中留空，避免切歌瞬间闪一下「暂无歌词」（同 Android isLoading）。
            _status.Visibility = Visibility.Collapsed;
            if (songId <= 0)
            {
                return;
            }

            string lrc;
            string translation;
            try
            {
                var cached = await AppServices.Lyrics.GetAsync(songId);
                if (cached != null)
                {
                    lrc = cached.Lrc;
                    translation = cached.Translation;
                }
                else
                {
                    var fetched = await AppServices.Songs.GetLyricAsync(songId);
                    lrc = fetched.Lrc;
                    translation = fetched.Translation;
                    _ = AppServices.Lyrics.PutAsync(songId, lrc, translation);
                }
            }
            catch
            {
                lrc = string.Empty;
                translation = string.Empty;
            }

            if (version != _loadVersion)
            {
                return; // 期间又切歌了。
            }

            var original = LrcParser.Parse(lrc);
            var merged = LyricMerger.Merge(
                original,
                AppServices.PlayPrefs.LyricsTranslation ? LrcParser.Parse(translation) : null);

            _times = merged.Select(line => line.TimeMs).ToArray();
            _panel.SetLines(merged.Select(line => new MetroLyricLine(line.TimeMs, line.Text, line.Translation)).ToList());
            _status.Visibility = merged.Count == 0 ? Visibility.Visible : Visibility.Collapsed;

            if (_active)
            {
                _panel.UpdatePosition(Extrapolated());
            }

            Reschedule();
        }

        private void OnLineInvoked(long timeMs)
        {
            // 点击行：本地立即定位，不等 2Hz 采样回传，seek 手感即时。
            ResetAnchor(timeMs);
            _panel.UpdatePosition(timeMs);
            _panel.ForceLocate();
            PlaybackHost.Engine.Seek(timeMs);
            Reschedule();
        }

        private void ResetAnchor(long positionMs)
        {
            _anchorPositionMs = positionMs;
            _anchorAtMs = _clock.ElapsedMilliseconds;
        }

        private long Extrapolated() =>
            _playing ? _anchorPositionMs + (_clock.ElapsedMilliseconds - _anchorAtMs) : _anchorPositionMs;

        /// <summary>睡到下一行的时间戳再唤醒一次；已越过最后一行时 1s 低频醒来等采样改变锚点。</summary>
        private void Reschedule()
        {
            _boundaryTimer.Stop();
            if (!_active || !_playing || _times.Length == 0)
            {
                return;
            }

            var now = Extrapolated();
            var wait = 1000L;
            var next = NextBoundaryAfter(now);
            if (next.HasValue)
            {
                wait = Math.Min(1000, Math.Max(16, next.Value - now));
            }

            _boundaryTimer.Interval = TimeSpan.FromMilliseconds(wait);
            _boundaryTimer.Start();
        }

        private void OnBoundary()
        {
            _panel.UpdatePosition(Extrapolated());
            Reschedule();
        }

        private long? NextBoundaryAfter(long positionMs)
        {
            if (_times.Length == 0 || positionMs >= _times[_times.Length - 1])
            {
                return null;
            }

            int lo = 0, hi = _times.Length - 1;
            while (lo < hi)
            {
                var mid = (lo + hi) / 2;
                if (_times[mid] <= positionMs)
                {
                    lo = mid + 1;
                }
                else
                {
                    hi = mid;
                }
            }

            return _times[lo];
        }
    }
}
