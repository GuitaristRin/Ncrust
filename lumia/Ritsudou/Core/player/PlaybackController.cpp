#include "pch.h"
#include "PlaybackController.h"

#include "../api/NcmApi.h"
#include "../util/Text.h"

#include <utility>

namespace ritsudou
{
    PlaybackController& PlaybackController::instance()
    {
        static PlaybackController controller;
        return controller;
    }

    void PlaybackController::attach(winrt::Windows::System::DispatcherQueue const& dispatcher)
    {
        dispatcher_ = dispatcher;
        subscribeMediaEnded();
    }

    void PlaybackController::subscribeMediaEnded()
    {
        if (mediaEndedSubscribed_) return;
        mediaEndedSubscribed_ = true;

        auto self = this;
        mediaEndedToken_ = engine_.mediaEnded([self](
            winrt::Windows::Media::Playback::MediaPlayer const&,
            winrt::Windows::Foundation::IInspectable const&)
        {
            // MediaEnded 在播放器线程触发，切�?UI 线程做队列推进�?            if (self->dispatcher_)
            {
                self->dispatcher_.TryEnqueue([self]() { self->onMediaEndedInternal(); });
            }
        });
    }

    void PlaybackController::onMediaEndedInternal()
    {
        if (queue_.advance())
        {
            loadAndPlay();
        }
    }

    void PlaybackController::replaceQueueAndPlay(std::vector<Song> songs, int index)
    {
        queue_.replaceQueueAndPlay(std::move(songs), index);
        loadAndPlay();
    }

    winrt::fire_and_forget PlaybackController::playNext()
    {
        if (queue_.advance())
        {
            loadAndPlay();
        }
        co_return;
    }

    winrt::fire_and_forget PlaybackController::playPrev()
    {
        if (queue_.goBack())
        {
            loadAndPlay();
        }
        co_return;
    }

    void PlaybackController::togglePlayPause()
    {
        engine_.togglePlayPause();
    }

    void PlaybackController::seekTo(int64_t ms)
    {
        engine_.seekTo(ms);
    }

    void PlaybackController::setPlayMode(PlayMode mode)
    {
        queue_.setMode(mode);
    }

    void PlaybackController::setQuality(std::wstring level)
    {
        if (quality_ == level) return;
        quality_ = std::move(level);
        qualityChangedEvent_(nullptr, nullptr);
    }

    winrt::event_token PlaybackController::songChanged(SongChangedHandler const& handler)
    {
        return songChangedEvent_.add(handler);
    }

    void PlaybackController::songChanged(winrt::event_token const& token) noexcept
    {
        songChangedEvent_.remove(token);
    }

    winrt::event_token PlaybackController::qualityChanged(SongChangedHandler const& handler)
    {
        return qualityChangedEvent_.add(handler);
    }

    void PlaybackController::qualityChanged(winrt::event_token const& token) noexcept
    {
        qualityChangedEvent_.remove(token);
    }

    winrt::fire_and_forget PlaybackController::loadAndPlay()
    {
        auto const* current = queue_.current();
        if (!current) co_return;

        currentSongId_ = current->id;
        const int64_t songId = current->id;
        const uint32_t version = ++playVersion_;
        lyricsLoaded_ = false;
        lyrics_.clear();

        // 先在 UI 线程派发切歌事件，让 UI 立即反映歌名/封面（歌词稍后到）。
        songChangedEvent_(nullptr, nullptr);

        auto dispatcher = dispatcher_;
        co_await winrt::resume_background();

        std::wstring url;
        std::wstring lyricText;
        try
        {
            url = fetchSongUrl(songId, wideToUtf8(quality_)).url;
            lyricText = getLyricText(songId);
        }
        catch (winrt::hresult_error const&)
        {
            url.clear();
        }
        catch (std::exception const&)
        {
            url.clear();
        }

        co_await winrt::resume_foreground(dispatcher);

        // 过期请求：只接受最新一次换歌的结果。
        if (version != playVersion_) co_return;

        if (!url.empty())
        {
            engine_.playUrl(url);
        }
        lyrics_ = LrcParser::parse(lyricText);
        lyricsLoaded_ = true;

        // 歌词到位后再通知一次（UI 此时可渲染歌词）。
        songChangedEvent_(nullptr, nullptr);
    }
}
