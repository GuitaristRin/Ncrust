using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Core.Playback;

namespace Ncrust.Playback
{
    /// <summary>
    /// 全局播放宿主：进程内单例 <see cref="PlaybackEngine"/>，页面通过它点播与编辑队列
    /// （对应 Android MainScreen 里的 playSongItem / insertNext / appendToQueue / replaceQueueAndPlay /
    /// insertAllNext / appendAllToQueue / startFm）。队列规则本身在 Core 的 <see cref="PlaybackQueue"/> 里，
    /// 这里只负责「改完队列之后让播放器跟上」。只在 UI 线程上使用。
    /// </summary>
    internal static class PlaybackHost
    {
        /// <summary>播放模式的持久化键（播放栏的模式按钮与 FM 入口共用）。</summary>
        public const string PlayModeKey = "play_mode";

        private static PlaybackEngine _engine;
        private static bool _restored;

        public static PlaybackEngine Engine => _engine ?? (_engine = Create());

        private static PlaybackQueue Queue => AppServices.Queue;

        /// <summary>替换队列并播放（「全部播放」；对应 replaceQueueAndPlay）。</summary>
        public static void PlayAll(IReadOnlyList<SongItem> songs, long? startSongId = null)
        {
            if (songs == null || songs.Count == 0)
            {
                return;
            }

            Engine.FmMode = false;
            Queue.ReplaceAll(songs);
            if (startSongId.HasValue)
            {
                for (var i = 0; i < songs.Count; i++)
                {
                    if (songs[i].Id == startSongId.Value)
                    {
                        Queue.SetCurrentIndex(i);
                        break;
                    }
                }
            }

            // ReplaceAll 之后 SHUFFLE 的打乱表以当前曲为首项重建（与 Android 一致）。
            RegenerateShuffleIfNeeded();
            Engine.PlayCurrent();
        }

        /// <summary>
        /// 播放单曲但保留队列（对应 Android playSongItem）：已在队列里就跳过去，否则插到当前曲之后再播。
        /// 首页、搜索、音乐库、详情页点歌都走这里。
        /// </summary>
        public static void PlaySong(SongItem song)
        {
            if (song == null)
            {
                return;
            }

            Queue.PlaySong(song);

            // PlaySong 内部在改当前索引之前重建了打乱表：按新的当前曲再重建一次，保证它是本轮首项。
            RegenerateShuffleIfNeeded();
            Engine.PlayCurrent();
        }

        /// <summary>「插播」：放到当前曲之后，不打断当前播放。</summary>
        public static void InsertNext(SongItem song) => Edit(() => Queue.InsertNext(song));

        /// <summary>「最后播放」：加到队尾。</summary>
        public static void Append(SongItem song) => Edit(() => Queue.Append(song));

        /// <summary>整批插播（详情页「插播」）。</summary>
        public static void InsertAllNext(IReadOnlyList<SongItem> songs) => Edit(() => Queue.InsertAllNext(songs));

        /// <summary>整批加到队尾（去掉已在队列里的）。</summary>
        public static void AppendAll(IReadOnlyList<SongItem> songs) => Edit(() => Queue.AppendAll(songs));

        /// <summary>队列视图里点一首：切过去播放（对应 playFromQueue）。</summary>
        public static void PlayAt(int index)
        {
            if (index < 0 || index >= Queue.Songs.Count)
            {
                return;
            }

            Queue.SetCurrentIndex(index);
            RegenerateShuffleIfNeeded();
            Engine.PlayCurrent();
        }

        /// <summary>从队列移除一首。移除的是正在播的歌时，接着播新的当前曲（暂停中则只切过去）。</summary>
        public static void RemoveAt(int index)
        {
            if (index < 0 || index >= Queue.Songs.Count)
            {
                return;
            }

            var removingCurrent = index == Queue.CurrentIndex;
            var wasPlaying = Engine.IsPlaying;
            Queue.RemoveAt(index);

            if (Queue.Current == null)
            {
                Engine.Stop();
            }
            else if (removingCurrent)
            {
                if (wasPlaying)
                {
                    Engine.PlayCurrent();
                }
                else
                {
                    Engine.Stop();
                    Engine.ShowRestored();
                }
            }
            else
            {
                Engine.RefreshNext();
            }

            Engine.SaveState();
        }

        /// <summary>拖动排序（只允许在「将要播放」区里拖，由视图保证）。</summary>
        public static void Move(int from, int to) => Edit(() => Queue.Move(from, to));

        /// <summary>清空播放内容：停止播放，队列置空。</summary>
        public static void Clear()
        {
            Engine.FmMode = false;
            Queue.ReplaceAll(Array.Empty<SongItem>());
            Engine.Stop();
            Engine.SaveState();
        }

        /// <summary>
        /// 私人 FM（对应 Android startFm）：切到 INFINITY，拉 FM 流替换队列播放；FM 取不到时兜底每日推荐。
        /// 之后靠 INFINITY 的队尾续播继续拉 FM。返回是否开播。
        /// </summary>
        public static async Task<bool> StartFmAsync()
        {
            IReadOnlyList<SongItem> songs;
            try
            {
                songs = await AppServices.Discovery.GetPersonalFmAsync();
                if (songs.Count == 0)
                {
                    songs = await AppServices.Discovery.GetDailyRecommendSongsAsync();
                }
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
                return false;
            }

            if (songs.Count == 0)
            {
                return false;
            }

            Engine.SetMode(PlaybackMode.Infinity);
            AppServices.Settings.SetInt(PlayModeKey, (int)PlaybackMode.Infinity);
            PlayAll(songs);

            // PlayAll / SetMode 都会清 FmMode：电台标记必须在它们之后置位。
            Engine.FmMode = true;
            return true;
        }

        /// <summary>
        /// 启动时恢复上次的队列与当前曲（对应 Android PlaybackStateManager）。只显示、不自动播放；
        /// 按播放键时从当前曲开始。只执行一次。
        /// </summary>
        public static async Task RestoreAsync()
        {
            if (_restored)
            {
                return;
            }

            _restored = true;
            try
            {
                await AppServices.Session.RestoreAsync();
                Engine.ShowRestored();
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }
        }

        private static void Edit(Action mutate)
        {
            var wasEmpty = Queue.Current == null;
            mutate();

            // 队列原本是空的（刚启动或清空过）：与 Android 一致，整批操作等价于「替换并播放」。
            if (wasEmpty && Queue.Current != null)
            {
                RegenerateShuffleIfNeeded();
                Engine.PlayCurrent();
                return;
            }

            // 窗口里预载的「下一首」可能已经不是队列的下一首了：丢掉重补。
            Engine.RefreshNext();
            Engine.SaveState();
        }

        private static void RegenerateShuffleIfNeeded()
        {
            if (Queue.Mode == PlaybackMode.Shuffle)
            {
                Queue.RegenerateShuffle();
            }
        }

        private static PlaybackEngine Create()
        {
            var engine = new PlaybackEngine(
                AppServices.UrlResolver,
                AppServices.Session,
                AppServices.PlayPrefs,
                AppServices.Network,
                AppServices.Http,
                InfinityFeeder.FromApi(AppServices.Discovery));
            engine.Initialize();
            engine.AttachEqualizer(AppServices.Equalizer.LoadState());
            return engine;
        }
    }
}
