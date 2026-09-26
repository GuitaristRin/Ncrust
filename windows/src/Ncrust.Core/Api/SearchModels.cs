using System;
using System.Collections.Generic;
using Ncrust.Core.Json;

namespace Ncrust.Core.Api
{
    /// <summary>搜索结果里的专辑项（对应 Android <c>AlbumSearchItem</c>）。</summary>
    public sealed class AlbumSearchItem
    {
        public long Id { get; set; }

        public string Name { get; set; } = string.Empty;

        public string PicUrl { get; set; } = string.Empty;

        public ArtistRef? Artist { get; set; }

        public long PublishTime { get; set; }

        public int Size { get; set; }

        public string Company { get; set; } = string.Empty;

        public static AlbumSearchItem FromJson(JsonValue item)
        {
            ArtistRef? artist = null;
            var artistJson = item.GetObject("artist");
            if (artistJson != null)
            {
                var id = artistJson.GetLong("id");
                artist = new ArtistRef
                {
                    Id = id != 0 ? id : (long?)null,
                    Name = artistJson.GetString("name", string.Empty) ?? string.Empty,
                };
            }

            return new AlbumSearchItem
            {
                Id = item.GetLong("id"),
                Name = item.GetString("name", string.Empty) ?? string.Empty,
                PicUrl = item.GetString("picUrl", string.Empty) ?? string.Empty,
                Artist = artist,
                PublishTime = item.GetLong("publishTime"),
                Size = item.GetInt("size"),
                Company = item.GetString("company", string.Empty) ?? string.Empty,
            };
        }
    }

    /// <summary>搜索结果里的歌手项（对应 Android <c>ArtistSearchItem</c>）。</summary>
    public sealed class ArtistSearchItem
    {
        public long Id { get; set; }

        public string Name { get; set; } = string.Empty;

        public string PicUrl { get; set; } = string.Empty;

        public long PicId { get; set; }

        public int AlbumSize { get; set; }

        public int MusicSize { get; set; }

        public IReadOnlyList<string> Alias { get; set; } = Array.Empty<string>();

        public string? Trans { get; set; }

        public static ArtistSearchItem FromJson(JsonValue item)
        {
            var alias = new List<string>();
            var aliasArray = item.GetArray("alias");
            if (aliasArray != null)
            {
                foreach (var entry in aliasArray)
                {
                    var text = entry.StringValue;
                    if (!string.IsNullOrEmpty(text))
                    {
                        alias.Add(text!);
                    }
                }
            }

            return new ArtistSearchItem
            {
                Id = item.GetLong("id"),
                Name = item.GetString("name", string.Empty) ?? string.Empty,
                PicUrl = item.GetString("picUrl", string.Empty) ?? string.Empty,
                PicId = item.GetLong("picId"),
                AlbumSize = item.GetInt("albumSize"),
                MusicSize = item.GetInt("musicSize"),
                Alias = alias,
                Trans = item.GetString("trans"),
            };
        }
    }

    /// <summary>歌词接口结果：原文 + 翻译（对应 Android <c>LyricResponse</c>）。</summary>
    public sealed class LyricsResult
    {
        public LyricsResult(string lrc, string translation)
        {
            Lrc = lrc;
            Translation = translation;
        }

        public string Lrc { get; }

        public string Translation { get; }
    }
}
