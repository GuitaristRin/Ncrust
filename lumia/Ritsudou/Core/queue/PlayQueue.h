#pragma once

#include "../model/Models.h"

#include <cstdint>
#include <vector>

namespace ritsudou
{
    // 播放模式：与 Ncrust 的 playMode 语义一致。
    // 0 = 顺序播放（播到队尾停止），1 = 列表循环，2 = 随机播放。
    enum class PlayMode
    {
        Sequence = 0,
        LoopAll = 1,
        Shuffle = 2,
    };

    // 播放队列：纯数据逻辑，无 WinRT 依赖，可独立单测。
    // 对外一律用「歌曲下标」沟通；洗牌顺序由内部 shuffleOrder_ 维护，
    // 随机播放时把当前歌曲旋转到洗牌序列开头，保证切歌不回头。
    class PlayQueue
    {
    public:
        bool empty() const { return songs_.empty(); }
        size_t size() const { return songs_.size(); }

        const std::vector<Song>& songs() const { return songs_; }
        int currentIndex() const { return current_; }
        const Song* current() const;
        int indexOf(int64_t songId) const;

        void setMode(PlayMode mode);
        PlayMode mode() const { return mode_; }

        // 整队替换并从 startIndex 开始播放。
        void replaceQueueAndPlay(std::vector<Song> songs, int startIndex);

        // 加歌：默认去重。addToQueue 队尾去重；appendNoDedup 队尾不去重；
        // insertNext 插到当前歌曲之后（去重）；insertAllNext 批量插入（去重）。
        bool addToQueue(const Song& song);
        void appendNoDedup(const Song& song);
        bool insertNext(const Song& song);
        void insertAllNext(const std::vector<Song>& songs);

        void jumpTo(int index);

        // 预取下一首 / 上一首的下标；顺序播放到队尾返回 -1（不自动换歌）。
        int peekNext() const;
        int peekPrev() const;

        // 应用换歌：返回是否成功换到。
        bool advance();
        bool goBack();

    private:
        int clamp(int index) const
        {
            return (index < 0 || index >= static_cast<int>(songs_.size())) ? -1 : index;
        }
        void rebuildShuffle();

        std::vector<Song> songs_;
        std::vector<int> shuffleOrder_;   // 洗牌后的歌曲下标排列
        int current_ = -1;
        PlayMode mode_ = PlayMode::LoopAll;
    };
}
