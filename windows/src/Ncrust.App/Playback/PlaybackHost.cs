using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using Ncrust.Core.Api;

namespace Ncrust.Playback
{
    /// <summary>全局播放宿主：进程内单例 <see cref="PlaybackEngine"/>，页面通过它点播。只在 UI 线程上使用。</summary>
    internal static class PlaybackHost
    {
        private static PlaybackEngine _engine;
        private static bool _restored;

        public static PlaybackEngine Engine => _engine ?? (_engine = Create());

        public static void PlayAll(IReadOnlyList<SongItem> songs, long? startSongId = null)
        {
            if (songs == null || songs.Count == 0)
            {
                return;
            }

            AppServices.Queue.ReplaceAll(songs);
            if (startSongId.HasValue)
            {
                for (var i = 0; i < songs.Count; i++)
                {
                    if (songs[i].Id == startSongId.Value)
                    {
                        AppServices.Queue.SetCurrentIndex(i);
                        break;
                    }
                }
            }

            // ReplaceAll 之后 SHUFFLE 的打乱表以当前曲为首项重建（与 Android 一致）。
            if (AppServices.Queue.Mode == Core.Playback.PlaybackMode.Shuffle)
            {
                AppServices.Queue.RegenerateShuffle();
            }

            Engine.PlayCurrent();
        }

        /// <summary>
        /// 播放单曲但保留队列（对应 Android playSongItem）：已在队列里就跳过去，否则插到当前曲之后再播。
        /// 搜索结果、搜索建议点歌走这里。
        /// </summary>
        public static void PlaySong(SongItem song)
        {
            if (song == null)
            {
                return;
            }

            var queue = AppServices.Queue;
            queue.PlaySong(song);

            // PlaySong 内部在改当前索引之前重建了打乱表：按新的当前曲再重建一次，保证它是本轮首项。
            if (queue.Mode == Core.Playback.PlaybackMode.Shuffle)
            {
                queue.RegenerateShuffle();
            }

            Engine.PlayCurrent();
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

        private static PlaybackEngine Create()
        {
            var engine = new PlaybackEngine(
                AppServices.UrlResolver,
                AppServices.Session,
                AppServices.PlayPrefs,
                AppServices.Network,
                AppServices.Http);
            engine.Initialize();
            engine.AttachEqualizer(AppServices.Equalizer.LoadState());
            return engine;
        }
    }
}
