using System;
using System.Collections.Generic;
using System.Globalization;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary>
    /// 云收藏的写操作（对应 Android <c>PlaylistApi.likeSong / subAlbum</c>）。
    /// 成功与否只由业务码 == 200 判定。
    /// </summary>
    public sealed class LibraryApi
    {
        private const string XeapiLikePath = "/api/v1/radio/like";
        private const string SubPath = "/eapi/album/sub";
        private const string UnsubPath = "/eapi/album/unsub";

        private readonly NcmHttp _http;

        public LibraryApi(NcmHttp http)
        {
            _http = http ?? throw new ArgumentNullException(nameof(http));
        }

        /// <summary>
        /// 收藏 / 取消收藏单曲。**只提交 xeapi <c>/api/v1/radio/like</c>，不回退任何旧通道。**
        ///
        /// 字段集按官方 9.6.13 / 9.6.22 反编译实证（<c>NeteaseMusicApiImpl.b7</c>）：<c>trackId,time,alg,like,
        /// rqRefer,songRqRefer,mode,subMode,source,checkToken</c>。官方用 HashMap 组字段，顺序不参与
        /// 指纹、字段齐全才参与；<c>rqRefer/songRqRefer</c> 可为空串，<c>mode/subMode</c> 无场景时发空串，
        /// <c>source</c> 兜底 <c>userfm</c>。见 <c>spec/api/xeapi.md</c> §6。
        ///
        /// 为什么不回退：weapi / eapi 的 <c>/api/radio/like</c> 写入会被服务端风控事后回滚
        /// （「收藏隔天消失」，gmg137/netease-cloud-music-gtk#410 实测回 524）。xeapi 失败时回退老通道
        /// 只会让一次失败的写入看似成功、随后被撤销，并清掉本该保留的待同步标记。Android 自 1.4.2 起已禁用回退
        /// （4133381），Windows 自 1.0.4 起同步。
        ///
        /// 成功判定与官方一致：<c>200</c> 与 <c>502</c>（歌曲已在期望状态）。其余（含 524「当前环境异常」、
        /// 未提交、HTTP 错误、响应不可读）一律 false，由 <see cref="Library.LibraryManager"/> 保留待同步。
        /// </summary>
        public async Task<bool> LikeSongAsync(long songId, bool like, CancellationToken cancellationToken = default)
        {
            var pairs = new[]
            {
                new KeyValuePair<string, string>("trackId", songId.ToString(CultureInfo.InvariantCulture)),
                new KeyValuePair<string, string>("time", "3"),
                new KeyValuePair<string, string>("alg", "itembased"),
                new KeyValuePair<string, string>("like", like ? "true" : "false"),
                new KeyValuePair<string, string>("rqRefer", string.Empty),
                new KeyValuePair<string, string>("songRqRefer", string.Empty),
                new KeyValuePair<string, string>("mode", string.Empty),
                new KeyValuePair<string, string>("subMode", string.Empty),
                new KeyValuePair<string, string>("source", "userfm"),
            };

            try
            {
                var json = await _http
                    .XeapiPostAsync(XeapiLikePath, pairs, antiCheat: true, cancellationToken)
                    .ConfigureAwait(false);
                var code = ReadCode(json);
                return code == 200 || code == 502;
            }
            catch (OperationCanceledException) when (cancellationToken.IsCancellationRequested)
            {
                throw;
            }
            catch
            {
                return false;
            }
        }

        /// <summary>收藏 / 取消收藏专辑（与收藏单曲解耦）。</summary>
        public async Task<bool> SubscribeAlbumAsync(long albumId, bool subscribe, CancellationToken cancellationToken = default)
        {
            var path = subscribe ? SubPath : UnsubPath;
            using (var response = await _http.EapiPostAsync(path, new[]
            {
                new KeyValuePair<string, string>("id", albumId.ToString()),
            }, useInterface: false, cancellationToken).ConfigureAwait(false))
            {
                var body = await response.Content.ReadAsStringAsync().ConfigureAwait(false);
                return ReadCode(body) == 200;
            }
        }

        /// <summary>解析业务码并把 301 交给请求层（登录失效：收藏写入会回「用户未登录」）。</summary>
        private int ReadCode(string text)
        {
            try
            {
                var code = JsonValue.Parse(text).GetInt("code", -1);
                _http.ReportBusinessCode(code);
                return code;
            }
            catch (JsonParseException)
            {
                return -1;
            }
        }
    }
}
