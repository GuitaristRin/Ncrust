#pragma once

#include <winrt/Windows.Media.Playback.h>

#include <string>

namespace ritsudou
{
    // 基于 Windows.Media.Playback.MediaPlayer 的播放引擎。
    // 里程碑 1：前台播放。后台音频任务 / SMTC 留到下一步。
    class PlayerEngine
    {
    public:
        PlayerEngine();

        void playUrl(const std::wstring& url);
        void togglePlayPause();
        void stop();
        bool isPlaying() const;
        int64_t positionMs() const;
        int64_t durationMs() const;
        void seekTo(int64_t ms);

        winrt::Windows::Media::Playback::MediaPlayer const& player() const noexcept { return player_; }

        // 自动连播钩子：MediaPlayer 播放到结尾时触发（UI 层据此调用队列 advance 换歌）。
        winrt::event_token mediaEnded(winrt::Windows::Foundation::TypedEventHandler<
            winrt::Windows::Media::Playback::MediaPlayer, winrt::Windows::Foundation::IInspectable> const& handler);
        void mediaEnded(winrt::event_token const& token) noexcept;

    private:
        winrt::Windows::Media::Playback::MediaPlayer player_;
    };
}
