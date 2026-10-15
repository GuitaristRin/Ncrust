using System;
using System.Collections.Generic;
using System.Linq;
using Ncrust.Core.Account;
using Ncrust.Core.Json;

namespace Ncrust.Core.Api
{
    public sealed class ArtistRef
    {
        public long? Id { get; set; }

        public string Name { get; set; } = string.Empty;
    }

    public sealed class AlbumRef
    {
        public long? Id { get; set; }

        public string Name { get; set; } = string.Empty;

        public string PicUrl { get; set; } = string.Empty;
    }

    /// <summary>
    /// 歌曲的最小共用模型。网易的响应有两代字段名：新接口用 <c>ar</c> / <c>al</c> / <c>dt</c>，
    /// 老接口用 <c>artists</c> / <c>album</c> / <c>duration</c>，这里都兜底（对应 Android
    /// 各 parser 里的 <c>optJSONArray("ar") ?: optJSONArray("artists")</c>）。
    /// </summary>
    public sealed class SongItem
    {
        public long Id { get; set; }

        public string Name { get; set; } = string.Empty;

        public IReadOnlyList<ArtistRef> Artists { get; set; } = Array.Empty<ArtistRef>();

        public AlbumRef? Album { get; set; }

        /// <summary>毫秒；未知为 0。</summary>
        public long Duration { get; set; }

        /// <summary>
        /// 歌曲详情的 <c>privilege</c>（<c>st</c> / <c>pl</c> / <c>fee</c>）。
        /// 取链判定「无版权」靠 <c>st &lt; 0</c>（见 <see cref="PlaybackEntitlement.Classify"/>），
        /// 所以它随队列与收藏一起落盘，重启后判定仍准。
        /// </summary>
        public SongPrivilege? Privilege { get; set; }

        // 下面是给 XAML 绑定用的便捷属性（对应 Android SongCard 里的展示派生）。
        public string ArtistText => Artists.Count == 0 ? string.Empty : string.Join("/", Artists.Select(a => a.Name));

        public string CoverUrl => Album?.PicUrl ?? string.Empty;

        public string DurationText
        {
            get
            {
                if (Duration <= 0)
                {
                    return string.Empty;
                }

                var totalSeconds = Duration / 1000;
                var minutes = totalSeconds / 60;
                var seconds = totalSeconds % 60;
                return minutes + ":" + seconds.ToString("00");
            }
        }

        public static SongItem FromJson(JsonValue song)
        {
            var privilege = song.GetObject("privilege");
            return FromJson(song, privilege == null ? null : SongPrivilege.FromJson(privilege));
        }

        /// <summary>
        /// <paramref name="privilege"/> 是调用方另外关联进来的权益信息：<c>song/detail</c> /
        /// <c>playlist/detail</c> 把 <c>songs[]</c> 与 <c>privileges[]</c> 作为两个并列数组返回，
        /// 得按 id 对上（见 <see cref="AttachPrivileges"/>）。
        /// </summary>
        public static SongItem FromJson(JsonValue song, SongPrivilege? privilege)
        {
            var id = song.GetLong("id");
            var artists = new List<ArtistRef>();
            var artistArray = song.GetArray("ar") ?? song.GetArray("artists");
            if (artistArray != null)
            {
                foreach (var artist in artistArray)
                {
                    var artistId = artist.GetLong("id");
                    artists.Add(new ArtistRef
                    {
                        Id = artistId != 0 ? artistId : (long?)null,
                        Name = artist.GetString("name", string.Empty) ?? string.Empty,
                    });
                }
            }

            var albumJson = song.GetObject("al") ?? song.GetObject("album");
            AlbumRef? album = null;
            if (albumJson != null)
            {
                var albumId = albumJson.GetLong("id");
                album = new AlbumRef
                {
                    Id = albumId != 0 ? albumId : (long?)null,
                    Name = albumJson.GetString("name", string.Empty) ?? string.Empty,
                    PicUrl = albumJson.GetString("picUrl", string.Empty) ?? string.Empty,
                };
            }

            var duration = song.GetLong("dt");
            if (duration == 0)
            {
                duration = song.GetLong("duration");
            }

            return new SongItem
            {
                Id = id,
                Name = song.GetString("name", string.Empty) ?? string.Empty,
                Artists = artists,
                Album = album,
                Duration = duration,
                Privilege = privilege,
            };
        }

        /// <summary>
        /// 把顶层的 <c>privileges[]</c> 按 id 关联到歌曲上。关联不上（列表里没有这首歌、或整段缺省）的
        /// 保持 <c>Privilege == null</c> —— 判定函数对 null 已有一路兜底，不编数据。
        /// </summary>
        public static void AttachPrivileges(IReadOnlyList<SongItem> songs, IReadOnlyList<JsonValue>? privileges)
        {
            if (privileges == null || privileges.Count == 0)
            {
                return;
            }

            var byId = new Dictionary<long, SongPrivilege>(privileges.Count);
            foreach (var entry in privileges)
            {
                var id = entry.GetLong("id");
                if (id != 0)
                {
                    byId[id] = SongPrivilege.FromJson(entry);
                }
            }

            foreach (var song in songs)
            {
                if (song.Privilege == null && byId.TryGetValue(song.Id, out var privilege))
                {
                    song.Privilege = privilege;
                }
            }
        }
    }
}
