using System.Collections.Generic;
using Ncrust.Core.Api;

namespace Ncrust.Playback
{
    /// <summary>全局播放宿主：进程内单例 <see cref="PlaybackEngine"/>，页面通过它点播。</summary>
    internal static class PlaybackHost
    {
        private static PlaybackEngine _engine;

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

            Engine.PlayCurrent();
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
            return engine;
        }
    }
}
