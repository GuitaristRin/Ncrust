using System;
using System.Collections.Generic;
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

        public static SongItem FromJson(JsonValue song)
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
            };
        }
    }
}
