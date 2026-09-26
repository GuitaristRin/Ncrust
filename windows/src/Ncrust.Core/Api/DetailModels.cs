using System;
using System.Collections.Generic;
using Ncrust.Core.Json;

namespace Ncrust.Core.Api
{
    /// <summary>专辑详情（对应 Android <c>AlbumDetail</c>）。</summary>
    public sealed class AlbumDetail
    {
        public long Id { get; set; }

        public string Name { get; set; } = string.Empty;

        public string PicUrl { get; set; } = string.Empty;

        public ArtistRef? Artist { get; set; }

        public long PublishTime { get; set; }

        public string Company { get; set; } = string.Empty;

        public string Description { get; set; } = string.Empty;

        public int Size { get; set; }

        public static AlbumDetail FromJson(JsonValue item)
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

            return new AlbumDetail
            {
                Id = item.GetLong("id"),
                Name = item.GetString("name", string.Empty) ?? string.Empty,
                PicUrl = item.GetString("picUrl", string.Empty) ?? string.Empty,
                Artist = artist,
                PublishTime = item.GetLong("publishTime"),
                Company = item.GetString("company", string.Empty) ?? string.Empty,
                Description = item.GetString("description", string.Empty) ?? string.Empty,
                Size = item.GetInt("size"),
            };
        }
    }

    public sealed class AlbumDetailResult
    {
        public AlbumDetailResult(AlbumDetail? album, IReadOnlyList<SongItem> songs)
        {
            Album = album;
            Songs = songs;
        }

        public AlbumDetail? Album { get; }

        public IReadOnlyList<SongItem> Songs { get; }
    }

    /// <summary>歌手信息（对应 Android <c>ArtistDetail</c>）。</summary>
    public sealed class ArtistDetail
    {
        public long Id { get; set; }

        public string Name { get; set; } = string.Empty;

        public string PicUrl { get; set; } = string.Empty;

        public int AlbumSize { get; set; }

        public int MusicSize { get; set; }

        public static ArtistDetail FromJson(JsonValue item) => new ArtistDetail
        {
            Id = item.GetLong("id"),
            Name = item.GetString("name", string.Empty) ?? string.Empty,
            PicUrl = item.GetString("picUrl", string.Empty) ?? string.Empty,
            AlbumSize = item.GetInt("albumSize"),
            MusicSize = item.GetInt("musicSize"),
        };
    }

    /// <summary>歌手的一张专辑（对应 Android <c>ArtistAlbumItem</c>）。</summary>
    public sealed class ArtistAlbumItem
    {
        public long Id { get; set; }

        public string Name { get; set; } = string.Empty;

        public string PicUrl { get; set; } = string.Empty;

        public long PublishTime { get; set; }

        public int Size { get; set; }

        public static ArtistAlbumItem FromJson(JsonValue item) => new ArtistAlbumItem
        {
            Id = item.GetLong("id"),
            Name = item.GetString("name", string.Empty) ?? string.Empty,
            PicUrl = item.GetString("picUrl", string.Empty) ?? string.Empty,
            PublishTime = item.GetLong("publishTime"),
            Size = item.GetInt("size"),
        };
    }

    public sealed class ArtistAlbumsResult
    {
        public ArtistAlbumsResult(ArtistDetail? artist, IReadOnlyList<ArtistAlbumItem> albums, bool more)
        {
            Artist = artist;
            Albums = albums;
            More = more;
        }

        public ArtistDetail? Artist { get; }

        public IReadOnlyList<ArtistAlbumItem> Albums { get; }

        public bool More { get; }
    }

    /// <summary>云端「我收藏的专辑」的一项（对应 Android <c>PlaylistApi.CloudAlbum</c>）。</summary>
    public sealed class CloudAlbum
    {
        public long AlbumId { get; set; }

        public string Name { get; set; } = string.Empty;

        public string Artist { get; set; } = string.Empty;

        public string PicUrl { get; set; } = string.Empty;

        public int SongCount { get; set; }

        public static CloudAlbum FromJson(JsonValue item)
        {
            var artists = item.GetArray("artists");
            var artist = artists != null && artists.Count > 0
                ? artists[0].GetString("name", string.Empty)
                : item.GetObject("artist")?.GetString("name", string.Empty);

            return new CloudAlbum
            {
                AlbumId = item.GetLong("id"),
                Name = item.GetString("name", string.Empty) ?? string.Empty,
                Artist = artist ?? string.Empty,
                PicUrl = item.GetString("picUrl", string.Empty) ?? string.Empty,
                SongCount = item.GetInt("size"),
            };
        }
    }

    /// <summary>当前登录用户资料（对应 Android <c>PlaylistApi.UserProfile</c>）。</summary>
    public sealed class UserProfile
    {
        public UserProfile(long userId, string nickname, string avatarUrl)
        {
            UserId = userId;
            Nickname = nickname;
            AvatarUrl = avatarUrl;
        }

        public long UserId { get; }

        public string Nickname { get; }

        public string AvatarUrl { get; }
    }

    /// <summary>用户的一个歌单（对应 Android <c>PlaylistApi.PlaylistInfo</c>）。</summary>
    public sealed class UserPlaylistInfo
    {
        public long Id { get; set; }

        public string Name { get; set; } = string.Empty;

        public string CoverImgUrl { get; set; } = string.Empty;

        public int TrackCount { get; set; }

        public long CreatorUserId { get; set; }

        /// <summary>0 = 普通自建歌单；非 0 = 「我喜欢的音乐」等特殊歌单。</summary>
        public int SpecialType { get; set; }

        public int Privacy { get; set; }

        public static UserPlaylistInfo FromJson(JsonValue item) => new UserPlaylistInfo
        {
            Id = item.GetLong("id"),
            Name = item.GetString("name", string.Empty) ?? string.Empty,
            CoverImgUrl = item.GetString("coverImgUrl", string.Empty) ?? string.Empty,
            TrackCount = item.GetInt("trackCount"),
            CreatorUserId = item.GetObject("creator")?.GetLong("userId") ?? 0,
            SpecialType = item.GetInt("specialType"),
            Privacy = item.GetInt("privacy"),
        };
    }
}
