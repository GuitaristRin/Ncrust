using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;
using Ncrust.Core.Playback;
using Windows.Media;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage.Streams;
using Windows.UI.Xaml;

namespace Ncrust.Playback
{
    /// <summary>
    /// 把 <see cref="PlaybackSessionState"/> 的决定落到系统播放器上（对应 Android 的 PlaybackService）。
    ///
    /// - 滑动窗口：<see cref="MediaPlaybackList"/> 只放「当前 + 下一首」两项，不用它自带的
    ///   Shuffle/AutoRepeat；当前项变化时状态机前进一步、追加新的下一首、移除已播项。
    /// - 延迟取链：每个条目用 <see cref="MediaBinder"/>，URL 在 Binding 事件里拿 deferral 异步解析；
    ///   元数据在创建时挂到 <see cref="MediaPlaybackItem"/> 上，换歌不会串元数据。
    /// - 质量降级：ItemFailed → <see cref="QualityLadder.NextRetryLevel"/> 在原位置重建条目。
    /// - 进度：500ms ticker 读位置/时长（约 2Hz）；播放上报按 <see cref="PlayReportPolicy"/>。
    /// - SMTC：由 MediaPlayer + DisplayProperties 自动接管（媒体键、系统浮窗）。
    /// </summary>
    internal sealed class PlaybackEngine
    {
        private readonly MediaPlayer _player = new MediaPlayer();
        private readonly MediaPlaybackList _list = new MediaPlaybackList();
        private readonly DispatcherTimer _ticker = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };

        private readonly SongUrlResolver _resolver;
        private readonly PlaybackSessionState _session;
        private readonly PlaybackPreferences _preferences;
        private readonly INetworkInfo _network;
        private readonly NcmHttp _http;
        private readonly PlayReportPolicy _reportPolicy = new PlayReportPolicy();

        private readonly Dictionary<string, SongItem> _songByToken = new Dictionary<string, SongItem>();
        private readonly Dictionary<string, string> _levelByToken = new Dictionary<string, string>();
        private readonly Dictionary<MediaPlaybackItem, string> _tokenByItem = new Dictionary<MediaPlaybackItem, string>();

        private long _currentSongId = -1;

        public PlaybackEngine(
            SongUrlResolver resolver,
            PlaybackSessionState session,
            PlaybackPreferences preferences,
            INetworkInfo network,
            NcmHttp http)
        {
            _resolver = resolver;
            _session = session;
            _preferences = preferences;
            _network = network;
            _http = http;
        }

        public event Action<SongItem> CurrentSongChanged;

        public event Action<bool> IsPlayingChanged;

        public event Action<bool> BufferingChanged;

        public event Action PlaybackEnded;

        public event Action<long> PlaybackError;

        /// <summary>(positionMs, durationMs)，约 2Hz。</summary>
        public event Action<long, long> ProgressChanged;

        public long CurrentSongId => _currentSongId;

        public bool IsPlaying => _player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing;

        public void Initialize()
        {
            _player.AudioCategory = MediaPlayerAudioCategory.Media;
            _player.CommandManager.IsEnabled = true;
            _list.ShuffleEnabled = false;
            _list.AutoRepeatEnabled = false;
            _list.CurrentItemChanged += OnCurrentItemChanged;
            _list.ItemFailed += OnItemFailed;
            _player.MediaEnded += OnMediaEnded;
            _player.MediaFailed += OnMediaFailed;
            _player.PlaybackSession.PlaybackStateChanged += (_, __) => IsPlayingChanged?.Invoke(IsPlaying);
            _player.PlaybackSession.BufferingStarted += (_, __) => BufferingChanged?.Invoke(true);
            _player.PlaybackSession.BufferingEnded += (_, __) => BufferingChanged?.Invoke(false);
            _player.Source = _list;
            _ticker.Tick += OnTick;
            _ticker.Start();
        }

        /// <summary>按当前队列与模式从头播放（替换队列后调用）。</summary>
        public void PlayCurrent()
        {
            ResetWindow();
            var current = _session.Queue.Current;
            if (current == null)
            {
                return;
            }

            var item = CreateItem(current, RequestedLevel());
            _list.Items.Add(item);
            _list.StartingItem = item;
            _currentSongId = current.Id;
            CurrentSongChanged?.Invoke(current);
            AppendNext();
            _player.Play();
        }

        public void PlayPause()
        {
            if (IsPlaying)
            {
                _player.Pause();
            }
            else
            {
                _player.Play();
            }
        }

        public void Pause() => _player.Pause();

        public void Next()
        {
            if (_list.Items.Count > 1)
            {
                _list.MoveNext();
            }
        }

        public void Previous()
        {
            _player.PlaybackSession.Position = TimeSpan.Zero;
            if (_list.Items.Count > 1)
            {
                _list.MovePrevious();
            }
        }

        public void Seek(long positionMs) =>
            _player.PlaybackSession.Position = TimeSpan.FromMilliseconds(positionMs);

        private string RequestedLevel() => _preferences.QualityForNetwork(_network.IsMetered);

        private void ResetWindow()
        {
            _list.Items.Clear();
            _songByToken.Clear();
            _levelByToken.Clear();
            _tokenByItem.Clear();
        }

        private MediaPlaybackItem CreateItem(SongItem song, string level)
        {
            var token = song.Id + "@" + level;
            var binder = new MediaBinder { Token = token };
            binder.Binding += OnBinderBinding;
            var source = MediaSource.CreateFromMediaBinder(binder);
            var item = new MediaPlaybackItem(source);

            var props = item.GetDisplayProperties();
            props.Type = MediaPlaybackType.Music;
            props.MusicProperties.Title = song.Name;
            props.MusicProperties.Artist = song.Artists.Count > 0 ? song.Artists[0].Name : string.Empty;
            if (!string.IsNullOrEmpty(song.Album?.PicUrl))
            {
                try
                {
                    props.Thumbnail = RandomAccessStreamReference.CreateFromUri(new Uri(song.Album.PicUrl));
                }
                catch
                {
                    // 封面 URL 非法时忽略缩略图。
                }
            }

            item.ApplyDisplayProperties(props);

            _songByToken[token] = song;
            _levelByToken[token] = level;
            _tokenByItem[item] = token;
            return item;
        }

        private void AppendNext()
        {
            var next = _session.Queue.PeekNext();
            if (next == null)
            {
                return;
            }

            var token = next.Id + "@" + RequestedLevel();
            if (_songByToken.ContainsKey(token))
            {
                return;
            }

            _list.Items.Add(CreateItem(next, RequestedLevel()));
        }

        private async void OnBinderBinding(MediaBinder sender, MediaBindingEventArgs args)
        {
            var deferral = args.GetDeferral();
            try
            {
                if (!_songByToken.TryGetValue(sender.Token, out var song) ||
                    !_levelByToken.TryGetValue(sender.Token, out var level))
                {
                    return;
                }

                var result = await _resolver.ResolveAsync(song.Id, level);
                if (result != null)
                {
                    args.SetUri(new Uri(result.Url));
                }
            }
            catch
            {
                // 取链失败：不 SetUri，条目会走 ItemFailed → 降级。
            }
            finally
            {
                deferral.Complete();
            }
        }

        private void OnCurrentItemChanged(MediaPlaybackList sender, CurrentMediaPlaybackItemChangedEventArgs args)
        {
            if (args.NewItem == null || !_tokenByItem.TryGetValue(args.NewItem, out var token))
            {
                return;
            }

            if (!_songByToken.TryGetValue(token, out var song))
            {
                return;
            }

            var index = IndexOfSong(song.Id);
            if (index >= 0)
            {
                _session.Queue.SetCurrentIndex(index);
            }

            _currentSongId = song.Id;
            CurrentSongChanged?.Invoke(song);
            AppendNext();
            TrimBefore(args.NewItem);
        }

        private void OnItemFailed(MediaPlaybackList sender, MediaPlaybackItemFailedEventArgs args)
        {
            if (!_tokenByItem.TryGetValue(args.Item, out var token) ||
                !_songByToken.TryGetValue(token, out var song) ||
                !_levelByToken.TryGetValue(token, out var level))
            {
                PlaybackError?.Invoke(_currentSongId);
                return;
            }

            var nextLevel = QualityLadder.NextRetryLevel(level);
            if (nextLevel == null)
            {
                PlaybackError?.Invoke(song.Id);
                return;
            }

            var index = sender.Items.IndexOf(args.Item);
            sender.Items.Remove(args.Item);
            _tokenByItem.Remove(args.Item);

            var replacement = CreateItem(song, nextLevel);
            if (index >= 0 && index <= sender.Items.Count)
            {
                sender.Items.Insert(index, replacement);
            }
            else
            {
                sender.Items.Add(replacement);
            }
        }

        private void OnMediaEnded(MediaPlayer sender, object args)
        {
            ReportCurrent(ended: true);
            PlaybackEnded?.Invoke();
        }

        private void OnMediaFailed(MediaPlayer sender, MediaPlayerFailedEventArgs args) =>
            PlaybackError?.Invoke(_currentSongId);

        private void OnTick(object sender, object e)
        {
            var position = (long)_player.PlaybackSession.Position.TotalMilliseconds;
            var duration = (long)_player.PlaybackSession.NaturalDuration.TotalMilliseconds;
            _session.UpdateProgress(position, duration);
            ProgressChanged?.Invoke(position, duration);

            if (_reportPolicy.TryAcquire(_currentSongId, position, duration, ended: false))
            {
                FireReport(_currentSongId, position);
            }
        }

        private void ReportCurrent(bool ended)
        {
            if (_reportPolicy.TryAcquire(_currentSongId, (long)_player.PlaybackSession.NaturalDuration.TotalMilliseconds, (long)_player.PlaybackSession.NaturalDuration.TotalMilliseconds, ended))
            {
                FireReport(_currentSongId, (long)_player.PlaybackSession.NaturalDuration.TotalMilliseconds);
            }
        }

        private void FireReport(long songId, long playedMs)
        {
            if (songId <= 0 || _http.Cookie == null)
            {
                return;
            }

            var csrf = _http.GetCsrfToken();
            if (string.IsNullOrEmpty(csrf))
            {
                return;
            }

            var url = PlayReport.WeblogUrl(csrf);
            var logs = PlayReport.BuildLogs(songId, playedMs, "playend", null, isWifi: !_network.IsMetered);
            _ = _http.PostWeblogAsync(url, logs);
        }

        private int IndexOfSong(long songId)
        {
            var songs = _session.Queue.Songs;
            for (var i = 0; i < songs.Count; i++)
            {
                if (songs[i].Id == songId)
                {
                    return i;
                }
            }

            return -1;
        }

        private void TrimBefore(MediaPlaybackItem current)
        {
            var index = _list.Items.IndexOf(current);
            for (var i = index - 1; i >= 0; i--)
            {
                var old = _list.Items[i];
                _tokenByItem.Remove(old);
                _list.Items.RemoveAt(i);
            }
        }
    }
}
