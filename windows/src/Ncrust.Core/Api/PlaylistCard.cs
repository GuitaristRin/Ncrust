using Ncrust.Core.Json;

namespace Ncrust.Core.Api
{
    /// <summary>首页推荐歌单卡片的轻量模型。段位与 Android <c>PlaylistCard</c> 一致。</summary>
    public sealed class PlaylistCard
    {
        public long Id { get; set; }

        public string Name { get; set; } = string.Empty;

        public string CoverUrl { get; set; } = string.Empty;

        public long PlayCount { get; set; }

        public int TrackCount { get; set; }

        public static PlaylistCard FromJson(JsonValue item)
        {
            var name = item.GetString("name", string.Empty) ?? string.Empty;
            return new PlaylistCard
            {
                Id = item.GetLong("id"),
                Name = name,
                CoverUrl = item.GetString("picUrl", string.Empty) ?? string.Empty,
                PlayCount = item.GetLong("playCount"),
                // 私人雷达的 trackCount 不真实，Android 固定显示 35。
                TrackCount = name == "私人雷达" ? 35 : item.GetInt("trackCount"),
            };
        }
    }
}
