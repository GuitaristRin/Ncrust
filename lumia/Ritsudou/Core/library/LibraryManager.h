#pragma once

#include "../model/Models.h"

#include <cstdint>
#include <vector>

namespace ritsudou
{
    // 收藏（我喜欢的音乐）：单例，持久化到 ApplicationData.LocalSettings。
    // 与 Android 版 LibraryManager 语义对齐：收藏唯一键是 songId，
    // 专辑按 albumId 聚合成「专辑」分组（albumOf 派生）。
    class LibraryManager
    {
    public:
        static LibraryManager& instance();

        // 从持久化存储加载（首次调用自动执行）。UI 层可在后台线程调用。
        void ensureLoaded();

        std::vector<Song> const& savedSongs() const { return songs_; }
        bool isSaved(int64_t songId) const;
        bool toggle(const Song& song);          // 收藏/取消收藏，返回新的收藏态
        void removeSong(int64_t songId);

    private:
        LibraryManager() = default;
        void save();

        std::vector<Song> songs_;
        bool loaded_ = false;
    };
}
