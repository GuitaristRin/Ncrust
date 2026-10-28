#pragma once

#include "../queue/PlayQueue.h"
#include "../player/PlayerEngine.h"
#include "../lyric/LrcParser.h"

#include <winrt/Windows.System.h>

#include <cstdint>
#include <string>
#include <vector>

namespace ritsudou
{
    // 播放会话单例：队列 + 引擎 + 歌词 + 质量偏好的统一入口。
    // 主页面（迷你条）与全屏播放页共享同一实例，避免状态分叉。
    // 事件全部在 UI 线程派发（attach 时捕获 DispatcherQueue），UI 处理无需再切线程。
    class PlaybackController
    {
    public:
        using SongChangedHandler = winrt::Windows::Foundation::TypedEventHandler<
            winrt::Windows::Foundation::IInspectable, winrt::Windows::Foundation::IInspectable>;

        static PlaybackController& instance();

        // UI 线程初始化：绑定调度器与自动连播钩子。只调用一次。
        void attach(winrt::Windows::System::DispatcherQueue const& dispatcher);

        // 播放操作（幂等，可重复调用；内部自行切后台线程做网络请求）。
        void replaceQueueAndPlay(std::vector<Song> songs, int index);
        winrt::fire_and_forget playNext();
        winrt::fire_and_forget playPrev();
        void togglePlayPause();
        void seekTo(int64_t ms);
        void setPlayMode(PlayMode mode);
        PlayMode playMode() const { return queue_.mode(); }

        // 状态查询。
        PlayQueue& queue() noexcept { return queue_; }
        PlayerEngine& engine() noexcept { return engine_; }
        int64_t currentSongId() const noexcept { return currentSongId_; }
        std::wstring const& quality() const noexcept { return quality_; }
        void setQuality(std::wstring level);
        std::vector<LyricLine> const& lyrics() const noexcept { return lyrics_; }
        bool lyricsLoaded() const noexcept { return lyricsLoaded_; }

        // 事件：歌曲切换 / 质量切换 / 播放态变化。UI 在回调里直接读状态即可。
        winrt::event_token songChanged(SongChangedHandler const& handler);
        void songChanged(winrt::event_token const& token) noexcept;
        winrt::event_token qualityChanged(SongChangedHandler const& handler);
        void qualityChanged(winrt::event_token const& token) noexcept;

    private:
        PlaybackController() = default;

        void subscribeMediaEnded();
        void onMediaEndedInternal();
        winrt::fire_and_forget loadAndPlay();

        PlayQueue queue_;
        PlayerEngine engine_;
        std::vector<LyricLine> lyrics_;
        std::wstring quality_{ L"lossless" };
        int64_t currentSongId_ = 0;
        bool lyricsLoaded_ = false;
        bool mediaEndedSubscribed_ = false;
        uint32_t playVersion_ = 0;

        winrt::Windows::System::DispatcherQueue dispatcher_{ nullptr };
        winrt::event<SongChangedHandler> songChangedEvent_;
        winrt::event<SongChangedHandler> qualityChangedEvent_;
        winrt::event_token mediaEndedToken_{};
    };
}
