using System.Text;
using Ncrust.Core.Net;

namespace Ncrust.Core.Playback
{
    /// <summary>
    /// 播放行为上报（听歌打卡）：**每首歌两条 eapi 上报**，缺一不可。
    ///
    /// <c>startplay</c> 进「最近播放」；<c>play</c> 涨「听歌排行」。业务字段只有一个 <c>logs</c>
    /// （JSON **文本**），<c>e_r</c> 与身份 <c>header</c> 由 <see cref="NcmHttp.EapiPostOsxAsync"/>
    /// 补上。通道、身份与字段依据 <c>spec/api/endpoints.md</c> 的「播放上报 / 听歌打卡」
    /// （参考实现 <c>module/scrobble.js</c> + <c>util/request.js</c>）。
    ///
    /// 旧通道（明文表单打 clientlogusf）整套删掉了：那条路回 HTTP 200 / <c>code 200</c> 却被服务端
    /// 静默丢弃、不入账（NeteaseCloudMusicApiEnhanced/api-enhanced#167），继续维护它只会制造
    /// 「看起来成功」的假象。
    ///
    /// 这里只有纯文本构造（可单测）；发送与去重分别由 <see cref="NcmHttp.EapiPostOsxAsync"/>
    /// 与 <see cref="PlayReportPolicy"/> 负责。
    /// </summary>
    public static class PlayReport
    {
        /// <summary>入账的日志域（参考实现 <c>util/config.json</c> 的 <c>clDomian</c>）。</summary>
        public const string ClientLogHost = "https://clientlog.music.163.com";

        /// <summary>
        /// 业务路径按 <c>/api/…</c> 形态书写：请求路径是 <c>/eapi/…</c>，eapi 签名用的是这个
        /// （<see cref="NcmHttp"/> 内部换写）。
        /// </summary>
        public const string WeblogPath = "/api/feedback/weblog";

        /// <summary>
        /// 开始播放那一条的 <c>logs</c> 文本（进「最近播放」）。字段与顺序逐项对齐参考实现。
        /// </summary>
        /// <param name="sourceId">歌曲来源的歌单 / 专辑 id；**不知道就传 null**（<c>content</c> 即 <c>id=</c>），
        /// 不要编一个出来。</param>
        public static string BuildStartplayLogs(long songId, string? sourceId = null)
        {
            var sb = new StringBuilder(128);
            sb.Append("[{\"action\":\"startplay\",\"json\":{");
            sb.Append("\"id\":").Append(songId).Append(',');
            sb.Append("\"type\":\"song\",");
            sb.Append("\"mainsite\":\"1\",\"mainsiteWeb\":\"1\",");
            sb.Append("\"content\":").Append(Content(sourceId));
            sb.Append("}}]");
            return sb.ToString();
        }

        /// <summary>
        /// 听完那一条的 <c>logs</c> 文本（涨「听歌排行」）。
        /// </summary>
        /// <param name="playedMs">实际播放时长（毫秒）；交给服务端的 <c>time</c> 是**秒**
        /// （参考实现的单位，旧通道发的是毫秒）。</param>
        /// <param name="sourceId">同 <see cref="BuildStartplayLogs"/>。</param>
        /// <param name="isWifi">为 true 时 <c>wifi</c> 记 0（0 = Wi-Fi，1 = 计费网络）。</param>
        public static string BuildPlayLogs(long songId, long playedMs, string? sourceId = null, bool isWifi = false)
        {
            var sb = new StringBuilder(224);
            sb.Append("[{\"action\":\"play\",\"json\":{");
            sb.Append("\"download\":0,");
            sb.Append("\"end\":\"playend\",");
            sb.Append("\"id\":").Append(songId).Append(',');
            sb.Append("\"sourceId\":").Append(JsonText.Escape(sourceId ?? string.Empty)).Append(',');
            sb.Append("\"time\":").Append(playedMs / 1000).Append(',');
            sb.Append("\"type\":\"song\",");
            sb.Append("\"wifi\":").Append(isWifi ? 0 : 1).Append(',');
            sb.Append("\"source\":\"list\",");
            sb.Append("\"mainsite\":\"1\",\"mainsiteWeb\":\"1\",");
            sb.Append("\"content\":").Append(Content(sourceId));
            sb.Append("}}]");
            return sb.ToString();
        }

        /// <summary>
        /// 加密信封的业务字段**片段**（不含外层大括号，<see cref="NcmHttp.EapiPostOsxAsync"/>
        /// 会把它拼进 <c>{…,"e_r":false,"header":{…}}</c>）：<c>"logs":"&lt;日志 JSON&gt;"</c>。
        /// logs 的值是**字符串**（参考实现发的是 <c>JSON.stringify([...])</c> 的结果，服务端再
        /// parse 一次），所以这里要转义。
        /// </summary>
        public static string WeblogFields(string logsJson) => "\"logs\":" + JsonText.Escape(logsJson);

        private static string Content(string? sourceId) => JsonText.Escape("id=" + (sourceId ?? string.Empty));
    }

    /// <summary>
    /// 「听完」判定与每曲只报一次的去重（startplay / play 各一条，各自去重）。
    /// </summary>
    public sealed class PlayReportPolicy
    {
        /// <summary>进度达到该比例即视为听完。</summary>
        public const double CompletionThreshold = 0.8;

        private long _lastStartSongId = -1;
        private long _lastReportedSongId = -1;

        public static bool ReachedCompletion(long positionMs, long durationMs) =>
            durationMs > 0 && (double)positionMs / durationMs >= CompletionThreshold;

        /// <summary>
        /// 开始播一首时调用；返回 true 表示本次应发 startplay（并已记下该曲）。
        ///
        /// <paramref name="isTrial"/> 为 true 时不上报：45 秒试听片段不该进「最近播放」，
        /// 与 Android 端 <c>currentIsTrial</c> 的抑制是同一条规则。Windows 的权益判定还在别处
        /// （引擎里目前没有试听标记），所以先留这个参数位，默认按完整播放处理。
        /// </summary>
        public bool TryAcquireStart(long songId, bool isTrial = false)
        {
            if (isTrial || songId <= 0 || songId == _lastStartSongId)
            {
                return false;
            }

            _lastStartSongId = songId;
            return true;
        }

        /// <summary>
        /// 到达进度 tick 或自然结束时调用；返回 true 表示本次应发 play（并已记下该曲）。
        /// <paramref name="ended"/> 为 true 时不检查进度阈值（自然结束一律补报）；
        /// <paramref name="isTrial"/> 语义同 <see cref="TryAcquireStart"/>。
        /// </summary>
        public bool TryAcquire(long songId, long positionMs, long durationMs, bool ended, bool isTrial = false)
        {
            if (isTrial || songId <= 0 || songId == _lastReportedSongId)
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
