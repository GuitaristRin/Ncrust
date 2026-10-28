#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace ritsudou
{
    struct Artist
    {
        int64_t id = 0;
        std::wstring name;
    };

    struct Album
    {
        int64_t id = 0;
        std::wstring name;
        std::wstring picUrl;
    };

    struct Song
    {
        int64_t id = 0;
        std::wstring name;
        std::vector<Artist> artists;
        Album album;
        int64_t durationMs = 0;

        std::wstring artistLabel() const
        {
            std::wstring out;
            for (auto const& a : artists)
            {
                if (!out.empty()) out += L"/";
                out += a.name;
            }
            return out;
        }
    };

    struct SongUrl
    {
        std::wstring url;
        std::wstring actualLevel;
    };

    struct AlbumSearchItem
    {
        int64_t id = 0;
        std::wstring name;
        std::wstring picUrl;
        std::wstring artistName;
        int64_t publishTime = 0;
        int size = 0;
    };

    struct ArtistSearchItem
    {
        int64_t id = 0;
        std::wstring name;
        std::wstring picUrl;
        int albumSize = 0;
        int musicSize = 0;
    };

    struct LyricLine
    {
        int64_t timeMs = 0;
        std::wstring text;
    };

    struct AlbumDetail
    {
        int64_t id = 0;
        std::wstring name;
        std::wstring picUrl;
        std::wstring artistLabel;
        std::wstring description;
        std::vector<Song> songs;
    };

    struct PlaylistDetail
    {
        int64_t id = 0;
        std::wstring name;
        std::wstring picUrl;
        std::wstring creatorLabel;
        std::wstring description;
        std::vector<Song> songs;
    };

    struct ArtistDetail
    {
        int64_t id = 0;
        std::wstring name;
        std::wstring picUrl;
        std::wstring description;
        std::vector<Song> hotSongs;
    };

    struct PlaylistItem
    {
        int64_t id = 0;
        std::wstring name;
        std::wstring picUrl;
        int64_t playCount = 0;
    };
}
