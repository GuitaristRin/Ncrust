using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading;
using System.Threading.Tasks;
using Ncrust.Core.Api;

namespace Ncrust.Core.Playback
{
    /// <summary>
    /// INFINITY 模式的队尾续播取数（对应 Android <c>launchInfinity</c> 的数据部分）：
    ///
    /// - 私人 FM 入口（<c>fmMode</c>）继续拉 FM 流，保持「电台」体验；
    /// - 相似无限（按模式按钮进入）以当前曲为种子拉相似歌曲；
    /// - 主数据源去重后为空时兜底每日推荐，避免断播；
    /// - 已在队列里的歌过滤掉，防止环绕重复；同一批里的重复也去掉。
    ///
    /// 各数据源失败都按空列表处理（Android 是 runCatching），只有取消会抛出。
    /// 防重入（同一时刻只跑一次）由调用方负责。
    /// </summary>
    public sealed class InfinityFeeder
    {
        private readonly Func<CancellationToken, Task<IReadOnlyList<SongItem>>> _personalFm;
        private readonly Func<long, CancellationToken, Task<IReadOnlyList<SongItem>>> _similar;
        private readonly Func<CancellationToken, Task<IReadOnlyList<SongItem>>> _daily;

        public InfinityFeeder(
            Func<CancellationToken, Task<IReadOnlyList<SongItem>>> personalFm,
            Func<long, CancellationToken, Task<IReadOnlyList<SongItem>>> similar,
            Func<CancellationToken, Task<IReadOnlyList<SongItem>>> daily)
        {
            _personalFm = personalFm ?? throw new ArgumentNullException(nameof(personalFm));
            _similar = similar ?? throw new ArgumentNullException(nameof(similar));
            _daily = daily ?? throw new ArgumentNullException(nameof(daily));
        }

        public static InfinityFeeder FromApi(DiscoveryApi discovery) => new InfinityFeeder(
            discovery.GetPersonalFmAsync,
            (seed, ct) => discovery.GetSimilarSongsAsync(seed, 20, ct),
            discovery.GetDailyRecommendSongsAsync);

        /// <summary>
        /// 取一批续播歌曲。<paramref name="existingIds"/> 是当前队列里所有歌的 id；
        /// 返回空列表表示没有可续的（调用方停在队尾）。
        /// </summary>
        public async Task<IReadOnlyList<SongItem>> FetchAsync(
            bool fmMode,
            long seedSongId,
            IEnumerable<long> existingIds,
            CancellationToken cancellationToken = default)
        {
            var existing = new HashSet<long>(existingIds);

            var primary = fmMode
                ? await SafeAsync(() => _personalFm(cancellationToken)).ConfigureAwait(false)
                : await SafeAsync(() => _similar(seedSongId, cancellationToken)).ConfigureAwait(false);
            var continuation = Filter(primary, existing);
            if (continuation.Count > 0)
            {
                return continuation;
            }

            var daily = await SafeAsync(() => _daily(cancellationToken)).ConfigureAwait(false);
            return Filter(daily, existing);
        }

        /// <summary>过滤掉已在队列里的歌与本批内的重复，保持原顺序。</summary>
        public static IReadOnlyList<SongItem> Filter(IReadOnlyList<SongItem> candidates, ISet<long> existing)
        {
            var seen = new HashSet<long>(existing);
            return candidates.Where(song => song.Id > 0 && seen.Add(song.Id)).ToList();
        }

        private static async Task<IReadOnlyList<SongItem>> SafeAsync(Func<Task<IReadOnlyList<SongItem>>> fetch)
        {
            try
            {
                return await fetch().ConfigureAwait(false) ?? Array.Empty<SongItem>();
            }
            catch (OperationCanceledException)
            {
                throw;
            }
            catch
            {
                return Array.Empty<SongItem>();
            }
        }
    }
}
