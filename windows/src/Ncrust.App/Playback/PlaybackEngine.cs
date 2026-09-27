using System;
using System.Collections.Generic;
using System.Linq;
using Ncrust.Core.Api;
using Ncrust.Core.Audio;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;
using Ncrust.Core.Playback;
using Windows.ApplicationModel.Core;
using Windows.Foundation.Collections;
using Windows.Media;
using Windows.Media.Core;
using Windows.Media.Playback;
using Windows.Storage.Streams;
using Windows.UI.Core;
using Windows.UI.Xaml;

namespace Ncrust.Playback
{
    /// <summary>
    /// 把 <see cref="PlaybackSessionState"/> 的决定落到系统播放器上（对应 Android 的 PlaybackService）。
    ///
    /// - 线程：MediaPlayer / MediaPlaybackList 的事件在后台线程触发，这里统一封送到 UI 线程再处理，
    ///   所以本类的**所有公开事件都在 UI 线程上触发**，订阅方可以直接改界面；队列状态也只在 UI 线程上改。
    ///   唯一在后台线程运行的是 MediaBinder 的 Binding —— 它只读闭包里捕获的不可变参数，不碰共享状态。
    /// - 滑动窗口：<see cref="MediaPlaybackList"/> 只放「当前 + 下一首」，不用它自带的 Shuffle/AutoRepeat；
    ///   当前项变化时队列前进一步（<see cref="PlaybackQueue.MoveNext"/>）、补上新的下一首、裁掉已播项。
    /// - 延迟取链：每个条目用 <see cref="MediaBinder"/>，URL 在 Binding 事件里拿 deferral 异步解析；
    ///   元数据在创建时挂到 <see cref="MediaPlaybackItem"/> 上，换歌不会串元数据。
    /// - 质量降级：ItemFailed → <see cref="QualityLadder.NextRetryLevel"/> 在原位置重建条目
    ///   （同一 songId@level 3 秒内只重试一次）；失败的是当前曲则切回重建的条目继续播。
    /// - 进度：500ms ticker 读位置/时长（约 2Hz）；播放上报按 <see cref="PlayReportPolicy"/>。
    /// - SMTC：由 MediaPlayer + DisplayProperties 自动接管（媒体键、系统浮窗）。
    /// </summary>
    internal sealed class PlaybackEngine
    {
        private static readonly TimeSpan RetryDedupeWindow = TimeSpan.FromSeconds(3);

        private readonly MediaPlayer _player = new MediaPlayer();
        private readonly MediaPlaybackList _list = new MediaPlaybackList();
        private readonly DispatcherTimer _ticker = new DispatcherTimer { Interval = TimeSpan.FromMilliseconds(500) };

        private readonly SongUrlResolver _resolver;
        private readonly PlaybackSessionState _session;
        private readonly PlaybackPreferences _preferences;
        private readonly INetworkInfo _network;
        private readonly NcmHttp _http;
        private readonly PlayReportPolicy _reportPolicy = new PlayReportPolicy();

        // 只在 UI 线程上读写。
        private readonly Dictionary<MediaPlaybackItem, ItemInfo> _infoByItem = new Dictionary<MediaPlaybackItem, ItemInfo>();
        private readonly Dictionary<string, DateTime> _lastRetryAt = new Dictionary<string, DateTime>();

        // 均衡器参数：与音效组件按引用共享，改值即生效（见 EqualizerEffectKeys）。
        private readonly PropertySet _equalizer = new PropertySet();

        private readonly InfinityFeeder _infinity;

        private CoreDispatcher _dispatcher;
        private long _currentSongId = -1;
        private int _consecutiveFailures;

        // INFINITY 续播：同一时刻只取一批；取回时若已经停在队尾（播完 / 用户按了下一首），接着播。
        private bool _infinityInFlight;

        // 上次会话恢复完（或已经开始播放）之前不落盘：启动时恢复播放模式会调 SetMode，
        // 那时队列还是空的，存下去就把上次保存的队列覆盖成空（实测：重启后队列丢失）。
        private bool _stateReady;
        private bool _advanceWhenFed;

        public PlaybackEngine(
            SongUrlResolver resolver,
            PlaybackSessionState session,
            PlaybackPreferences preferences,
            INetworkInfo network,
            NcmHttp http,
            InfinityFeeder infinity)
        {
            _infinity = infinity;
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

        /// <summary>播放模式变化（按钮切换后）。</summary>
        public event Action<PlaybackMode> ModeChanged;

        /// <summary>队列内容或当前位置变化（队列视图据此刷新）。</summary>
        public event Action QueueChanged;

        /// <summary>
        /// 私人 FM 入口进来的 INFINITY：队尾续播拉 FM 流而不是相似歌曲（对应 Android fmMode）。
        /// 手动切换播放模式或整队替换时清掉。
        /// </summary>
        public bool FmMode { get; set; }

        public long CurrentSongId => _currentSongId;

        public bool IsPlaying => _player.PlaybackSession.PlaybackState == MediaPlaybackState.Playing;

        public PlaybackMode Mode => _session.Queue.Mode;

        public long PositionMs => (long)_player.PlaybackSession.Position.TotalMilliseconds;

        public long DurationMs => (long)_player.PlaybackSession.NaturalDuration.TotalMilliseconds;

        /// <summary>0..1。</summary>
        public double Volume
        {
            get => _player.Volume;
            set => _player.Volume = Math.Max(0, Math.Min(1, value));
        }

        public bool IsMuted
        {
            get => _player.IsMuted;
            set => _player.IsMuted = value;
        }

        /// <summary>音效组件是否成功挂到播放器上（失败时设置页提示均衡器不可用）。</summary>
        public bool EqualizerAvailable { get; private set; }

        /// <summary>
        /// 把 10 段均衡器挂到播放器上（启动时调用一次）。作为「可选」音效添加：万一管线插不进去，
        /// 照常播放、只是没有均衡器，而不是整首歌放不出来。
        /// </summary>
        public void AttachEqualizer(EqualizerState state)
        {
            ApplyEqualizer(state);
            try
            {
                _player.AddAudioEffect(EqualizerEffectKeys.ActivatableClassId, true, _equalizer);
                EqualizerAvailable = true;
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }
        }

        /// <summary>更新均衡器参数，下一帧生效。</summary>
        public void ApplyEqualizer(EqualizerState state)
        {
            _equalizer[EqualizerEffectKeys.GainsDb] = state.GainsDb.ToArray();
            _equalizer[EqualizerEffectKeys.PreampDb] = state.PreampDb;
            _equalizer[EqualizerEffectKeys.Enabled] = state.Enabled;
        }

        /// <summary>必须在 UI 线程上调用（DispatcherTimer 与 Dispatcher 都取自当前视图）。</summary>
        public void Initialize()
        {
            _dispatcher = CoreApplication.MainView.CoreWindow.Dispatcher;

            _player.AudioCategory = MediaPlayerAudioCategory.Media;
            _player.CommandManager.IsEnabled = true;
            _list.ShuffleEnabled = false;
            _list.AutoRepeatEnabled = false;
            _list.CurrentItemChanged += (sender, args) =>
            {
                var newItem = args.NewItem;
                OnUi(() => OnCurrentItemChanged(newItem));
            };
            _list.ItemFailed += OnItemFailedBackground;
            _player.MediaEnded += (sender, args) => OnUi(OnMediaEnded);
            _player.MediaFailed += (sender, args) => OnUi(() => PlaybackError?.Invoke(_currentSongId));
            _player.PlaybackSession.PlaybackStateChanged += (sender, args) => OnUi(OnPlaybackStateChanged);
            _player.PlaybackSession.BufferingStarted += (sender, args) => OnUi(() => BufferingChanged?.Invoke(true));
            _player.PlaybackSession.BufferingEnded += (sender, args) => OnUi(() => BufferingChanged?.Invoke(false));
            _player.Source = _list;
            _ticker.Tick += OnTick;
            _ticker.Start();
        }

        /// <summary>按当前队列与模式从当前曲开始播放（替换队列、跳转、后退后调用）。</summary>
        public void PlayCurrent()
        {
            _stateReady = true;
            ResetWindow();
            var current = _session.Queue.Current;
            if (current == null)
            {
                return;
            }

            var item = CreateItem(current, RequestedLevel());
            _list.Items.Add(item);
            _list.StartingItem = item;
            SetCurrentSong(current);
            AppendNextIfMissing();
            _player.Play();
            QueueChanged?.Invoke();
        }

        /// <summary>清空队列后调用：停止播放、清空窗口。</summary>
        public void Stop()
        {
            _player.Pause();
            ResetWindow();
            _advanceWhenFed = false;
            _currentSongId = -1;
            CurrentSongChanged?.Invoke(null);
            QueueChanged?.Invoke();
        }

        public void PlayPause()
        {
            if (IsPlaying)
            {
                _player.Pause();
            }
            else if (_list.Items.Count == 0)
            {
                // 刚从上次会话恢复、窗口还是空的：从队列的当前曲开始播。
                PlayCurrent();
            }
            else
            {
                _player.Play();
            }
        }

        /// <summary>启动时恢复了上次的队列：只把当前曲显示出来，不自动播放。</summary>
        public void ShowRestored()
        {
            _stateReady = true;
            var current = _session.Queue.Current;
            if (current != null)
            {
                SetCurrentSong(current);
            }
        }

        public void Pause() => _player.Pause();

        /// <summary>下一首（对应 Android playNext）。窗口里已有下一项就交给列表无缝切换。</summary>
        public void Next()
        {
            if (HasItemAfterCurrent())
            {
                _list.MoveNext();
                return;
            }

            // 窗口里还没有下一项（例如队列刚被编辑）：由队列决定，然后重建窗口。
            if (_session.Queue.MoveNext() != null)
            {
                PlayCurrent();
                return;
            }

            // INFINITY 停在队尾：等续播取回后接着播。
            if (_session.Queue.Mode == PlaybackMode.Infinity)
            {
                _advanceWhenFed = true;
                LaunchInfinity();
            }
        }

        /// <summary>上一首（对应 Android playPrevious）：SINGLE 与不可回退时回到开头。</summary>
        public void Previous()
        {
            if (_session.Queue.Mode == PlaybackMode.Single || _session.Queue.MovePrevious() == null)
            {
                Seek(0);
                return;
            }

            // 已播条目会被裁掉，列表本身退不回去；按队列的新当前曲重建窗口。
            PlayCurrent();
        }

        public void Seek(long positionMs) =>
            _player.PlaybackSession.Position = TimeSpan.FromMilliseconds(Math.Max(0, positionMs));

        /// <summary>切换播放模式，并按新模式重排窗口里的「下一首」。</summary>
        public void SetMode(PlaybackMode mode)
        {
            FmMode = false;
            _session.Queue.SetMode(mode);
            RefreshNext();
            ModeChanged?.Invoke(mode);
            SaveState();
        }

        /// <summary>队列被编辑后调用：丢掉窗口里过时的下一项，按队列重新补上。</summary>
        public void RefreshNext()
        {
            var index = CurrentItemIndex();
            if (index < 0)
            {
                // 窗口是空的（恢复了队列但还没开播）：没有要补的，只通知队列视图。
                QueueChanged?.Invoke();
                return;
            }

            for (var i = _list.Items.Count - 1; i > index; i--)
            {
                _infoByItem.Remove(_list.Items[i]);
                _list.Items.RemoveAt(i);
            }

            AppendNextIfMissing();
            QueueChanged?.Invoke();
        }

        /// <summary>队列在外部被改动（排序 / 删除 / 插入）后由调用方触发保存。</summary>
        public void SaveState()
        {
            if (_stateReady)
            {
                _ = SaveStateAsync();
            }
        }

        private string RequestedLevel() => _preferences.QualityForNetwork(_network.IsMetered);

        private void OnUi(Action action)
        {
            if (_dispatcher == null || _dispatcher.HasThreadAccess)
            {
                Guard(action);
                return;
            }

            _ = _dispatcher.RunAsync(CoreDispatcherPriority.Normal, () => Guard(action));
        }

        private static void Guard(Action action)
        {
            try
            {
                action();
            }
            catch (Exception ex)
            {
                // 播放回调里的异常不能带崩整个进程；记日志，播放器保持现状。
                App.WriteCrashLog(ex);
            }
        }

        private void ResetWindow()
        {
            _list.Items.Clear();
            _infoByItem.Clear();
        }

        private MediaPlaybackItem CreateItem(SongItem song, string level)
        {
            var binder = new MediaBinder { Token = song.Id + "@" + level };

            // Binding 在后台线程触发：只用闭包捕获的 song / level，不读共享字典。
            binder.Binding += async (sender, args) =>
            {
                var deferral = args.GetDeferral();
                try
                {
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
            };

            var item = new MediaPlaybackItem(MediaSource.CreateFromMediaBinder(binder));

            var props = item.GetDisplayProperties();
            props.Type = MediaPlaybackType.Music;
            props.MusicProperties.Title = song.Name;
            props.MusicProperties.Artist = song.ArtistText;
            props.MusicProperties.AlbumTitle = song.Album?.Name ?? string.Empty;
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
            _infoByItem[item] = new ItemInfo(song, level);
            return item;
        }

        private int CurrentItemIndex()
        {
            var current = _list.CurrentItem;
            return current == null ? (_list.Items.Count > 0 ? 0 : -1) : _list.Items.IndexOf(current);
        }

        private bool HasItemAfterCurrent()
        {
            var index = CurrentItemIndex();
            return index >= 0 && index < _list.Items.Count - 1;
        }

        /// <summary>
        /// 窗口里当前项之后没有条目时，按队列补上下一首。只看「当前项之后有没有」，
        /// 不按歌曲去重 —— 循环回绕、单曲循环都需要再次追加同一首歌。
        /// </summary>
        private void AppendNextIfMissing()
        {
            if (HasItemAfterCurrent())
            {
                return;
            }

            var next = _session.Queue.PeekNext();
            if (next != null)
            {
                // 关掉「无缝播放」时不预载下一首（同 Android：gapless_playback 为 false 时不进 ExoPlayer 队列），
                // 当前曲播完后由 OnMediaEnded 再起下一首。设置页的开关即时生效：下一次补窗口时按新值。
                if (_preferences.GaplessEnabled)
                {
                    _list.Items.Add(CreateItem(next, RequestedLevel()));
                }

                return;
            }

            // INFINITY 到了队尾：提前续播（对应 Android needsPreload 分支里的 launchInfinity），
            // 等到自然播完才请求的话，网络往返会让衔接出现空档。
            if (_session.Queue.Mode == PlaybackMode.Infinity && _session.Queue.Current != null)
            {
                LaunchInfinity();
            }
        }

        /// <summary>队尾续播：取一批 FM / 相似歌曲追加到队尾，并补进窗口。</summary>
        private async void LaunchInfinity()
        {
            var seed = _session.Queue.Current;
            if (_infinityInFlight || seed == null)
            {
                return;
            }

            _infinityInFlight = true;
            try
            {
                var existing = _session.Queue.Songs.Select(song => song.Id).ToList();
                var continuation = await _infinity.FetchAsync(FmMode, seed.Id, existing);

                // 取数期间用户可能切了模式或换了队列：只在仍是 INFINITY 时追加。
                if (continuation.Count == 0 || _session.Queue.Mode != PlaybackMode.Infinity)
                {
                    _advanceWhenFed = false;
                    return;
                }

                _session.Queue.AppendAll(continuation);
                SaveState();

                if (_advanceWhenFed)
                {
                    _advanceWhenFed = false;
                    if (_session.Queue.MoveNext() != null)
                    {
                        PlayCurrent();
                        return;
                    }
                }

                AppendNextIfMissing();
                QueueChanged?.Invoke();
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }
            finally
            {
                _infinityInFlight = false;
            }
        }

        private void OnCurrentItemChanged(MediaPlaybackItem newItem)
        {
            if (newItem == null || !_infoByItem.TryGetValue(newItem, out var info))
            {
                return;
            }

            // 事件是封送过来的：处理时列表可能已经又切走了（例如失败重试的 MoveTo）。
            // 过时的事件直接丢弃，否则 TrimBefore 会把正在播的条目裁掉。
            if (!ReferenceEquals(_list.CurrentItem, newItem))
            {
                return;
            }

            var queue = _session.Queue;
            if (queue.Current?.Id != info.Song.Id)
            {
                if (queue.PeekNext()?.Id == info.Song.Id)
                {
                    queue.MoveNext();
                }
                else
                {
                    // 非顺延的切换（例如失败重试切回、队列在外部被改过）：按身份定位。
                    var index = IndexOfSong(info.Song.Id);
                    if (index >= 0)
                    {
                        queue.SetCurrentIndex(index);
                    }
                }
            }

            if (_currentSongId != info.Song.Id)
            {
                SetCurrentSong(info.Song);
            }

            TrimBefore(newItem);
            AppendNextIfMissing();
            SaveState();
            QueueChanged?.Invoke();
        }

        private void OnPlaybackStateChanged()
        {
            var playing = IsPlaying;
            if (playing)
            {
                _consecutiveFailures = 0;
            }

            IsPlayingChanged?.Invoke(playing);
        }

        /// <summary>
        /// 一首歌所有音质都失败（对应 Android 的 onUnplayable → playNext）：是当前曲就跳到下一首。
        /// 连续失败达到队列长度就停下，避免整队都放不了时无限空转。
        /// </summary>
        private void SkipUnplayable(MediaPlaybackItem failed, long songId)
        {
            PlaybackError?.Invoke(songId);
            if (!ReferenceEquals(_list.CurrentItem, failed))
            {
                return; // 列表已经自己跳走了，或者失败的只是预载的下一首。
            }

            if (++_consecutiveFailures >= Math.Max(1, _session.Queue.Songs.Count))
            {
                _player.Pause();
                return;
            }

            Next();
        }

        private void SetCurrentSong(SongItem song)
        {
            _currentSongId = song.Id;
            CurrentSongChanged?.Invoke(song);
        }

        private void OnItemFailedBackground(MediaPlaybackList sender, MediaPlaybackItemFailedEventArgs args)
        {
            // 「失败的是不是当前项」要在事件现场判断：封送到 UI 线程时列表可能已经跳到下一项。
            var failed = args.Item;
            var wasCurrent = ReferenceEquals(sender.CurrentItem, failed);
            OnUi(() => OnItemFailed(failed, wasCurrent));
        }

        private void OnItemFailed(MediaPlaybackItem failed, bool wasCurrent)
        {
            if (!_infoByItem.TryGetValue(failed, out var info))
            {
                SkipUnplayable(failed, _currentSongId);
                return;
            }

            var nextLevel = QualityLadder.NextRetryLevel(info.Level);
            var key = info.Song.Id + "@" + info.Level;
            var now = DateTime.UtcNow;
            if (nextLevel == null ||
                (_lastRetryAt.TryGetValue(key, out var last) && now - last < RetryDedupeWindow))
            {
                SkipUnplayable(failed, info.Song.Id);
                return;
            }

            _lastRetryAt[key] = now;

            var index = _list.Items.IndexOf(failed);
            if (index >= 0)
            {
                _list.Items.RemoveAt(index);
            }

            _infoByItem.Remove(failed);

            // 失败项已被列表跳过时，重建的条目插在当前项之前再切回去。
            if (index < 0)
            {
                index = Math.Max(0, CurrentItemIndex());
            }

            var replacement = CreateItem(info.Song, nextLevel);
            _list.Items.Insert(Math.Min(index, _list.Items.Count), replacement);
            if (wasCurrent)
            {
                _list.MoveTo((uint)_list.Items.IndexOf(replacement));
            }
        }

        private void OnMediaEnded()
        {
            ReportCurrent(ended: true);

            if (!HasItemAfterCurrent())
            {
                if (_session.Queue.Mode == PlaybackMode.Infinity && _session.Queue.PeekNext() == null)
                {
                    // 整个窗口播完且队列到了尾：INFINITY 等续播取回后接着播。
                    _advanceWhenFed = true;
                    LaunchInfinity();
                }
                else if (!_preferences.GaplessEnabled && _session.Queue.MoveNext() != null)
                {
                    // 非无缝模式：窗口里本来就没预载下一首，播完由队列决定下一首再起播。
                    PlayCurrent();
                }
            }

            PlaybackEnded?.Invoke();
        }

        private void OnTick(object sender, object e)
        {
            var position = PositionMs;
            var duration = DurationMs;
            _session.UpdateProgress(position, duration);
            ProgressChanged?.Invoke(position, duration);

            if (_reportPolicy.TryAcquire(_currentSongId, position, duration, ended: false))
            {
                FireReport(_currentSongId, position);
            }
        }

        private void ReportCurrent(bool ended)
        {
            var duration = DurationMs;
            if (_reportPolicy.TryAcquire(_currentSongId, duration, duration, ended))
            {
                FireReport(_currentSongId, duration);
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

        private async System.Threading.Tasks.Task SaveStateAsync()
        {
            try
            {
                await _session.SaveAsync();
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }
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
                _infoByItem.Remove(_list.Items[i]);
                _list.Items.RemoveAt(i);
            }
        }

        private sealed class ItemInfo
        {
            public ItemInfo(SongItem song, string level)
            {
                Song = song;
                Level = level;
            }

            public SongItem Song { get; }

            public string Level { get; }
        }
    }
}
