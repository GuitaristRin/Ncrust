#pragma once

#include "../model/Models.h"

#include <string>
#include <string_view>
#include <vector>

namespace ritsudou
{
    // 网易云音乐公开 REST + eapi 接口。
    // 同步阻塞，仅允许在后台线程调用。
    std::vector<Song> searchSongs(std::wstring_view keyword, int type = 1, int limit = 30);
    std::vector<AlbumSearchItem> searchAlbums(std::wstring_view keyword, int type = 10, int limit = 30);
    std::vector<ArtistSearchItem> searchArtists(std::wstring_view keyword, int type = 100, int limit = 30);

    std::wstring getLyricText(int64_t songId);
    std::vector<Song> getSongsDetail(const std::vector<int64_t>& ids);

    // 详情页数据：专辑 / 歌单 / 艺人。同步阻塞，仅允许在后台线程调用。
    AlbumDetail getAlbumDetail(int64_t albumId);
    PlaylistDetail getPlaylistDetail(int64_t playlistId);
    ArtistDetail getArtistDetail(int64_t artistId);

    // 首页推荐：热门歌单。REST /api/personalized，无需登录。
    std::vector<PlaylistItem> getRecommendedPlaylists(int limit = 20);

    // 质量降级链：hires → lossless → exhigh → higher → standard，全部失败走外链。
    SongUrl fetchSongUrl(int64_t songId, std::string_view level = "lossless");
}
