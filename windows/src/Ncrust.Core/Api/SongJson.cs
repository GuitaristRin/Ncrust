using System.Collections.Generic;
using System.Text;
using Ncrust.Core.Json;
using Ncrust.Core.Net;

namespace Ncrust.Core.Api
{
    /// <summary>
    /// <see cref="SongItem"/> 的手写 JSON（对应 Android 里用 Gson 存 SongItem 列表的地方：
    /// LibraryManager 的 saved_songs、PlaybackStateManager 的队列）。字段名沿用网易的
    /// <c>ar</c> / <c>al</c> / <c>dt</c>，正好能被 <see cref="SongItem.FromJson"/> 读回。
    /// </summary>
    internal static class SongJson
    {
        public static void Append(StringBuilder sb, SongItem song)
        {
            sb.Append("{\"id\":").Append(song.Id).Append(",\"name\":").Append(JsonText.Escape(song.Name)).Append(",\"ar\":[");
            for (var i = 0; i < song.Artists.Count; i++)
            {
                if (i > 0)
                {
                    sb.Append(',');
                }

                var artist = song.Artists[i];
                sb.Append("{\"name\":").Append(JsonText.Escape(artist.Name));
                if (artist.Id.HasValue)
                {
                    sb.Append(",\"id\":").Append(artist.Id.Value);
                }

                sb.Append('}');
            }

            sb.Append(']');
            if (song.Album != null)
            {
                sb.Append(",\"al\":{\"name\":").Append(JsonText.Escape(song.Album.Name));
                if (song.Album.Id.HasValue)
                {
                    sb.Append(",\"id\":").Append(song.Album.Id.Value);
                }

                if (!string.IsNullOrEmpty(song.Album.PicUrl))
                {
                    sb.Append(",\"picUrl\":").Append(JsonText.Escape(song.Album.PicUrl));
                }

                sb.Append('}');
            }

            if (song.Duration > 0)
            {
                sb.Append(",\"dt\":").Append(song.Duration);
            }

            sb.Append('}');
        }

        public static string Write(IReadOnlyList<SongItem> songs)
        {
            var sb = new StringBuilder();
            sb.Append('[');
            for (var i = 0; i < songs.Count; i++)
            {
                if (i > 0)
                {
                    sb.Append(',');
                }

                Append(sb, songs[i]);
            }

            sb.Append(']');
            return sb.ToString();
        }

        public static List<SongItem> ReadArray(IReadOnlyList<JsonValue>? array)
        {
            var songs = new List<SongItem>();
            if (array == null)
            {
                return songs;
            }

            foreach (var item in array)
            {
                songs.Add(SongItem.FromJson(item));
            }

            return songs;
        }
    }
}
