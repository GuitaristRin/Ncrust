#include "pch.h"
#include "NcmApi.h"

#include "../net/NcmHttp.h"
#include "../util/Text.h"

#include <stdexcept>

namespace ritsudou
{
    namespace
    {
        using winrt::Windows::Data::Json::JsonArray;
        using winrt::Windows::Data::Json::JsonObject;
        using winrt::Windows::Data::Json::JsonValueType;

        std::wstring str(JsonObject const& o, std::wstring_view key, std::wstring_view def = L"")
        {
            try
            {
                if (!o.HasKey(winrt::hstring{ key })) return std::wstring(def);
                auto v = o.GetNamedValue(winrt::hstring{ key });
                if (v.ValueType() == JsonValueType::String) return std::wstring(v.GetString());
                return std::wstring(def);
            }
            catch (...) { return std::wstring(def); }
        }

        int64_t num(JsonObject const& o, std::wstring_view key, int64_t def = 0)
        {
            try
            {
                if (!o.HasKey(winrt::hstring{ key })) return def;
                auto v = o.GetNamedValue(winrt::hstring{ key });
                if (v.ValueType() == JsonValueType::Number) return static_cast<int64_t>(v.GetNumber());
                if (v.ValueType() == JsonValueType::String)
                {
                    try { return std::stoll(std::wstring(v.GetString())); } catch (...) { return def; }
                }
                return def;
            }
            catch (...) { return def; }
        }

        JsonArray arr(JsonObject const& o, std::wstring_view key)
        {
            try
            {
                if (!o.HasKey(winrt::hstring{ key })) return JsonArray{};
                auto v = o.GetNamedValue(winrt::hstring{ key });
                if (v.ValueType() == JsonValueType::Array) return v.GetArray();
                return JsonArray{};
            }
            catch (...) { return JsonArray{}; }
        }

        Song parseSong(JsonObject const& o)
        {
            Song s;
            s.id = num(o, L"id");
            s.name = str(o, L"name");
            s.durationMs = num(o, L"dt");

            auto ar = arr(o, L"ar");
            for (uint32_t i = 0; i < ar.Size(); ++i)
            {
                try
                {
                    auto jo = ar.GetAt(i).GetObject();
                    Artist a;
                    a.id = num(jo, L"id");
                    a.name = str(jo, L"name");
                    s.artists.push_back(a);
                }
                catch (...) {}
            }

            try
            {
                if (o.HasKey(L"al"))
                {
                    auto v = o.GetNamedValue(L"al");
                    if (v.ValueType() == JsonValueType::Object)
                    {
                        auto al = v.GetObject();
                        s.album.id = num(al, L"id");
                        s.album.name = str(al, L"name");
                        s.album.picUrl = str(al, L"picUrl");
                        if (s.album.picUrl.empty()) s.album.picUrl = str(al, L"pic");
                    }
                }
            }
            catch (...) {}

            return s;
        }

        std::vector<std::string> qualityLadder(std::string_view level)
        {
            if (level == "hires") return { "hires", "lossless", "exhigh", "higher", "standard" };
            if (level == "lossless") return { "lossless", "exhigh", "higher", "standard" };
            if (level == "exhigh") return { "exhigh", "higher", "standard" };
            if (level == "higher") return { "higher", "standard" };
            if (level == "standard") return { "standard" };
            return { std::string(level), "lossless", "exhigh", "higher", "standard" };
        }

        std::vector<Song> parseSongArray(JsonArray const& songs)
        {
            std::vector<Song> out;
            out.reserve(songs.Size());
            for (uint32_t i = 0; i < songs.Size(); ++i)
            {
                try { out.push_back(parseSong(songs.GetAt(i).GetObject())); }
                catch (...) {}
            }
            return out;
        }

        std::wstring trackListToIds(std::vector<int64_t> const& ids)
        {
            // 歌单详情返回的 trackIds 是数组成员，转成 ",1,2,3" 供 /song/detail 使用。
            std::wstring out;
            for (size_t i = 0; i < ids.size(); ++i)
            {
                out += L"," + std::to_wstring(ids[i]);
            }
            return out;
        }
    }

    std::vector<Song> searchSongs(std::wstring_view keyword, int type, int limit)
    {
        std::vector<std::pair<std::wstring, std::wstring>> fields{
            { L"s", std::wstring(keyword) },
            { L"type", std::to_wstring(type) },
            { L"limit", std::to_wstring(limit) },
        };
        auto body = NcmHttp::postForm(L"/api/cloudsearch/pc", fields);

        auto root = winrt::Windows::Data::Json::JsonObject::Parse(winrt::hstring{ body });
        JsonObject result{};
        try { result = root.GetNamedObject(L"result"); } catch (...) { return {}; }

        auto songs = arr(result, L"songs");
        std::vector<Song> out;
        out.reserve(songs.Size());
        for (uint32_t i = 0; i < songs.Size(); ++i)
        {
            try { out.push_back(parseSong(songs.GetAt(i).GetObject())); }
            catch (...) {}
        }
        return out;
    }

    std::vector<AlbumSearchItem> searchAlbums(std::wstring_view keyword, int type, int limit)
    {
        std::vector<std::pair<std::wstring, std::wstring>> fields{
            { L"s", std::wstring(keyword) },
            { L"type", std::to_wstring(type) },
            { L"limit", std::to_wstring(limit) },
        };
        auto body = NcmHttp::postForm(L"/api/cloudsearch/pc", fields);

        auto root = winrt::Windows::Data::Json::JsonObject::Parse(winrt::hstring{ body });
        JsonObject result{};
        try { result = root.GetNamedObject(L"result"); } catch (...) { return {}; }

        auto items = arr(result, L"albums");
        std::vector<AlbumSearchItem> out;
        out.reserve(items.Size());
        for (uint32_t i = 0; i < items.Size(); ++i)
        {
            try
            {
                auto o = items.GetAt(i).GetObject();
                AlbumSearchItem a;
                a.id = num(o, L"id");
                a.name = str(o, L"name");
                a.picUrl = str(o, L"picUrl");
                a.publishTime = num(o, L"publishTime");
                a.size = static_cast<int>(num(o, L"size"));
                try
                {
                    auto v = o.GetNamedValue(L"artist");
                    if (v.ValueType() == JsonValueType::Object)
                        a.artistName = str(v.GetObject(), L"name");
                }
                catch (...) {}
                out.push_back(a);
            }
            catch (...) {}
        }
        return out;
    }

    std::vector<ArtistSearchItem> searchArtists(std::wstring_view keyword, int type, int limit)
    {
        std::vector<std::pair<std::wstring, std::wstring>> fields{
            { L"s", std::wstring(keyword) },
            { L"type", std::to_wstring(type) },
            { L"limit", std::to_wstring(limit) },
        };
        auto body = NcmHttp::postForm(L"/api/cloudsearch/pc", fields);

        auto root = winrt::Windows::Data::Json::JsonObject::Parse(winrt::hstring{ body });
        JsonObject result{};
        try { result = root.GetNamedObject(L"result"); } catch (...) { return {}; }

        auto items = arr(result, L"artists");
        std::vector<ArtistSearchItem> out;
        out.reserve(items.Size());
        for (uint32_t i = 0; i < items.Size(); ++i)
        {
            try
            {
                auto o = items.GetAt(i).GetObject();
                ArtistSearchItem a;
                a.id = num(o, L"id");
                a.name = str(o, L"name");
                a.picUrl = str(o, L"picUrl");
                a.albumSize = static_cast<int>(num(o, L"albumSize"));
                a.musicSize = static_cast<int>(num(o, L"musicSize"));
                out.push_back(a);
            }
            catch (...) {}
        }
        return out;
    }

    std::wstring getLyricText(int64_t songId)
    {
        std::vector<std::pair<std::wstring, std::wstring>> fields{
            { L"id", std::to_wstring(songId) },
            { L"cp", L"false" },
            { L"tv", L"0" },
            { L"lv", L"0" },
            { L"rv", L"0" },
            { L"kv", L"0" },
            { L"yv", L"0" },
            { L"ytv", L"0" },
            { L"yrv", L"0" },
        };
        auto body = NcmHttp::postForm(L"/api/song/lyric", fields);
        auto root = winrt::Windows::Data::Json::JsonObject::Parse(winrt::hstring{ body });
        try
        {
            return str(root.GetNamedObject(L"lrc"), L"lyric");
        }
        catch (...)
        {
            return L"";
        }
    }

    std::vector<Song> getSongsDetail(const std::vector<int64_t>& ids)
    {
        if (ids.empty()) return {};

        // POST /api/song/detail：c=[{"id":...},{"id":...}]，返回完整歌曲元数据。
        std::wstring c = L"[";
        for (size_t i = 0; i < ids.size(); ++i)
        {
            if (i > 0) c += L",";
            c += L"{\"id\":" + std::to_wstring(ids[i]) + L"}";
        }
        c += L"]";

        std::vector<std::pair<std::wstring, std::wstring>> fields{ { L"c", c } };
        auto body = NcmHttp::postForm(L"/api/song/detail", fields);
        auto root = winrt::Windows::Data::Json::JsonObject::Parse(winrt::hstring{ body });

        auto songs = arr(root, L"songs");
        std::vector<Song> out;
        out.reserve(songs.Size());
        for (uint32_t i = 0; i < songs.Size(); ++i)
        {
            try { out.push_back(parseSong(songs.GetAt(i).GetObject())); }
            catch (...) {}
        }
        return out;
    }

    SongUrl fetchSongUrl(int64_t songId, std::string_view level)
    {
        for (auto const& tryLevel : qualityLadder(level))
        {
            std::vector<std::pair<std::wstring, std::wstring>> payload{
                { L"ids", L"[" + std::to_wstring(songId) + L"]" },
                { L"level", utf8ToWide(tryLevel) },
            };
            if (tryLevel == "lossless" || tryLevel == "hires")
                payload.push_back({ L"encodeType", L"flac" });

            try
            {
                auto body = NcmHttp::eapiPost(L"/eapi/song/enhance/player/url/v1", payload, true);
                auto root = winrt::Windows::Data::Json::JsonObject::Parse(winrt::hstring{ body });
                auto data = arr(root, L"data");
                if (data.Size() > 0)
                {
                    auto obj = data.GetAt(0).GetObject();
                    std::wstring url = str(obj, L"url");
                    if (!url.empty())
                    {
                        return SongUrl{ url, str(obj, L"level", utf8ToWide(tryLevel)) };
                    }
                }
            }
            catch (...)
            {
                // 该档失败，继续降级
            }
        }

        return SongUrl{
            L"https://music.163.com/song/media/outer/url?id=" + std::to_wstring(songId) + L".mp3",
            L"standard"
        };
    }

    AlbumDetail getAlbumDetail(int64_t albumId)
    {
        auto body = NcmHttp::get(L"/api/album/" + std::to_wstring(albumId));
        auto root = winrt::Windows::Data::Json::JsonObject::Parse(winrt::hstring{ body });

        AlbumDetail d;
        try
        {
            auto album = root.GetNamedObject(L"album");
            d.id = num(album, L"id");
            d.name = str(album, L"name");
            d.picUrl = str(album, L"picUrl");
            if (d.picUrl.empty()) d.picUrl = str(album, L"pic");
            d.description = str(album, L"description");
            try
            {
                auto v = album.GetNamedValue(L"artist");
                if (v.ValueType() == JsonValueType::Object)
                    d.artistLabel = str(v.GetObject(), L"name");
            }
            catch (...) {}
        }
        catch (...) {}

        d.songs = parseSongArray(arr(root, L"songs"));
        return d;
    }

    PlaylistDetail getPlaylistDetail(int64_t playlistId)
    {
        std::vector<std::pair<std::wstring, std::wstring>> payload{
            { L"id", std::to_wstring(playlistId) },
            { L"n", L"100000" },
            { L"s", L"8" },
        };
        auto body = NcmHttp::eapiPost(L"/eapi/v3/playlist/detail", payload, true);
        auto root = winrt::Windows::Data::Json::JsonObject::Parse(winrt::hstring{ body });

        PlaylistDetail d;
        try
        {
            auto playlist = root.GetNamedObject(L"playlist");
            d.id = num(playlist, L"id");
            d.name = str(playlist, L"name");
            d.picUrl = str(playlist, L"coverImgUrl");
            d.description = str(playlist, L"description");
            try
            {
                auto v = playlist.GetNamedValue(L"creator");
                if (v.ValueType() == JsonValueType::Object)
                    d.creatorLabel = str(v.GetObject(), L"nickname");
            }
            catch (...) {}

            auto tracks = arr(playlist, L"tracks");
            d.songs = parseSongArray(tracks);
        }
        catch (...) {}

        return d;
    }

    ArtistDetail getArtistDetail(int64_t artistId)
    {
        auto body = NcmHttp::get(L"/api/artist/" + std::to_wstring(artistId));
        auto root = winrt::Windows::Data::Json::JsonObject::Parse(winrt::hstring{ body });

        ArtistDetail d;
        try
        {
            auto artist = root.GetNamedObject(L"artist");
            d.id = num(artist, L"id");
            d.name = str(artist, L"name");
            d.picUrl = str(artist, L"picUrl");
            d.description = str(artist, L"briefDesc");
        }
        catch (...) {}

        d.hotSongs = parseSongArray(arr(root, L"hotSongs"));
        return d;
    }

    std::vector<PlaylistItem> getRecommendedPlaylists(int limit)
    {
        std::vector<std::pair<std::wstring, std::wstring>> fields{
            { L"limit", std::to_wstring(limit) },
        };
        auto body = NcmHttp::postForm(L"/api/personalized", fields);
        auto root = winrt::Windows::Data::Json::JsonObject::Parse(winrt::hstring{ body });

        auto items = arr(root, L"result");
        std::vector<PlaylistItem> out;
        out.reserve(items.Size());
        for (uint32_t i = 0; i < items.Size(); ++i)
        {
            try
            {
                auto o = items.GetAt(i).GetObject();
                PlaylistItem p;
                p.id = num(o, L"id");
                p.name = str(o, L"name");
                p.picUrl = str(o, L"picUrl");
                p.playCount = num(o, L"playCount");
                out.push_back(std::move(p));
            }
            catch (...) {}
        }
        return out;
    }
}
