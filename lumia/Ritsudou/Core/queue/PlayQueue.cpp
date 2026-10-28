#include "pch.h"
#include "PlayQueue.h"

#include <algorithm>
#include <random>

namespace ritsudou
{
    const Song* PlayQueue::current() const
    {
        if (current_ < 0 || current_ >= static_cast<int>(songs_.size())) return nullptr;
        return &songs_[static_cast<size_t>(current_)];
    }

    int PlayQueue::indexOf(int64_t songId) const
    {
        for (size_t i = 0; i < songs_.size(); ++i)
        {
            if (songs_[i].id == songId) return static_cast<int>(i);
        }
        return -1;
    }

    void PlayQueue::setMode(PlayMode mode)
    {
        if (mode_ == mode) return;
        mode_ = mode;
        if (mode == PlayMode::Shuffle) rebuildShuffle();
    }

    void PlayQueue::replaceQueueAndPlay(std::vector<Song> songs, int startIndex)
    {
        songs_ = std::move(songs);
        current_ = clamp(startIndex);
        rebuildShuffle();
    }

    bool PlayQueue::addToQueue(const Song& song)
    {
        if (indexOf(song.id) >= 0) return false;
        songs_.push_back(song);
        if (mode_ == PlayMode::Shuffle) rebuildShuffle();
        return true;
    }

    void PlayQueue::appendNoDedup(const Song& song)
    {
        songs_.push_back(song);
        if (mode_ == PlayMode::Shuffle) rebuildShuffle();
    }

    bool PlayQueue::insertNext(const Song& song)
    {
        if (indexOf(song.id) >= 0) return false;
        int at = (current_ >= 0) ? current_ + 1 : static_cast<int>(songs_.size());
        songs_.insert(songs_.begin() + at, song);
        // 插入点位于当前歌曲之后，current_ 无需修正。
        if (mode_ == PlayMode::Shuffle) rebuildShuffle();
        return true;
    }

    void PlayQueue::insertAllNext(const std::vector<Song>& songs)
    {
        if (songs.empty()) return;
        int at = (current_ >= 0) ? current_ + 1 : static_cast<int>(songs_.size());
        for (auto const& s : songs)
        {
            if (indexOf(s.id) >= 0) continue;
            songs_.insert(songs_.begin() + at, s);
            ++at;
        }
        if (mode_ == PlayMode::Shuffle) rebuildShuffle();
    }

    void PlayQueue::jumpTo(int index)
    {
        current_ = clamp(index);
        if (mode_ == PlayMode::Shuffle && current_ >= 0)
        {
            // 把当前歌曲旋转到洗牌序列开头，向后不再重复。
            auto it = std::find(shuffleOrder_.begin(), shuffleOrder_.end(), current_);
            if (it != shuffleOrder_.end())
            {
                std::rotate(shuffleOrder_.begin(), it, shuffleOrder_.end());
            }
        }
    }

    int PlayQueue::peekNext() const
    {
        if (songs_.empty() || current_ < 0) return -1;
        if (mode_ == PlayMode::Shuffle)
        {
            if (shuffleOrder_.empty()) return -1;
            auto it = std::find(shuffleOrder_.begin(), shuffleOrder_.end(), current_);
            if (it == shuffleOrder_.end()) return shuffleOrder_.front();
            ++it;
            return (it == shuffleOrder_.end()) ? shuffleOrder_.front() : *it;
        }

        int next = current_ + 1;
        if (mode_ == PlayMode::LoopAll && next >= static_cast<int>(songs_.size())) return 0;
        return clamp(next);
    }

    int PlayQueue::peekPrev() const
    {
        if (songs_.empty() || current_ < 0) return -1;
        if (mode_ == PlayMode::Shuffle)
        {
            if (shuffleOrder_.empty()) return -1;
            auto it = std::find(shuffleOrder_.begin(), shuffleOrder_.end(), current_);
            if (it == shuffleOrder_.end()) return shuffleOrder_.back();
            if (it == shuffleOrder_.begin()) return shuffleOrder_.back();
            --it;
            return *it;
        }

        int prev = current_ - 1;
        if (mode_ == PlayMode::LoopAll && prev < 0) return static_cast<int>(songs_.size()) - 1;
        return clamp(prev);
    }

    bool PlayQueue::advance()
    {
        int next = peekNext();
        if (next < 0) return false;
        current_ = next;
        return true;
    }

    bool PlayQueue::goBack()
    {
        int prev = peekPrev();
        if (prev < 0) return false;
        current_ = prev;
        return true;
    }

    void PlayQueue::rebuildShuffle()
    {
        shuffleOrder_.clear();
        shuffleOrder_.reserve(songs_.size());
        for (size_t i = 0; i < songs_.size(); ++i)
        {
            shuffleOrder_.push_back(static_cast<int>(i));
        }
        std::mt19937 rng{ std::random_device{}() };
        std::shuffle(shuffleOrder_.begin(), shuffleOrder_.end(), rng);

        if (current_ >= 0)
        {
            auto it = std::find(shuffleOrder_.begin(), shuffleOrder_.end(), current_);
            if (it != shuffleOrder_.end())
            {
                std::rotate(shuffleOrder_.begin(), it, shuffleOrder_.end());
            }
        }
    }
}
