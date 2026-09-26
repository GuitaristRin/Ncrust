using System;
using System.Text;
using Ncrust.Core.Net;

namespace Ncrust.Core.Playback
{
    /// <summary>
    /// 播放行为上报（对应 Android <c>PlayReporter.kt</c> + <c>PlayerViewModel</c> 里的去重）。
    /// 复刻官方 web 播放器的 webLog：自然结束或进度 ≥80% 时上报一条 play，同一首歌只报一次。
    /// 链路不加密，仅普通表单 POST。构造文本部分可单测；实际发送由 App 调
    /// <see cref="NcmHttp.PostWeblogAsync"/>。
    /// </summary>
    public static class PlayReport
    {
        public const string WeblogHost = "https://clientlogusf.music.163.com";
        public const string WeblogPath = "/api/feedback/weblog";

        public static string WeblogUrl(string csrfToken) =>
            WeblogHost + WeblogPath + "?csrf_token=" + Uri.EscapeDataString(csrfToken);

        /// <summary>
        /// 构造 <c>logs</c> 表单值。字段与官方 web 播放器一致；<paramref name="isWifi"/> 为 true
        /// 时 <c>wifi</c> 记 0（0 表示 Wi-Fi，1 表示计费网络）。
        /// </summary>
        public static string BuildLogs(
            long songId,
            long playedMs,
            string end = "playend",
            string? strategy = null,
            bool isWifi = false)
        {
            var sb = new StringBuilder(160);
            sb.Append("[{\"action\":\"play\",\"json\":{");
            sb.Append("\"type\":\"song\",");
            sb.Append("\"wifi\":").Append(isWifi ? 0 : 1).Append(',');
            sb.Append("\"download\":0,");
            sb.Append("\"id\":").Append(songId).Append(',');
            sb.Append("\"time\":").Append(playedMs).Append(',');
            sb.Append("\"end\":").Append(JsonText.Escape(end)).Append(',');
            sb.Append("\"mainsite\":\"1\",\"mainsiteWeb\":\"1\"");
            if (!string.IsNullOrEmpty(strategy))
            {
                sb.Append(",\"alg\":").Append(JsonText.Escape(strategy!));
            }

            sb.Append("}}]");
            return sb.ToString();
        }
    }

    /// <summary>「听完」判定与每曲只报一次的去重。</summary>
    public sealed class PlayReportPolicy
    {
        /// <summary>进度达到该比例即视为听完。</summary>
        public const double CompletionThreshold = 0.8;

        private long _lastReportedSongId = -1;

        public static bool ReachedCompletion(long positionMs, long durationMs) =>
            durationMs > 0 && (double)positionMs / durationMs >= CompletionThreshold;

        /// <summary>
        /// 到达进度 tick 或自然结束时调用；返回 true 表示本次应上报（并已记下该曲）。
        /// <paramref name="ended"/> 为 true 时不检查进度阈值（自然结束一律补报）。
        /// </summary>
        public bool TryAcquire(long songId, long positionMs, long durationMs, bool ended)
        {
            if (songId <= 0 || songId == _lastReportedSongId)
            {
                return false;
            }

            if (!ended && !ReachedCompletion(positionMs, durationMs))
            {
                return false;
            }

            _lastReportedSongId = songId;
            return true;
        }
    }
}
