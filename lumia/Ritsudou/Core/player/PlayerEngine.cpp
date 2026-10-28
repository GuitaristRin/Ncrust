#include "pch.h"
#include "PlayerEngine.h"

namespace ritsudou
{
    PlayerEngine::PlayerEngine()
    {
        player_.AudioCategory(winrt::Windows::Media::Playback::MediaPlayerAudioCategory::Media);
        player_.AudioDeviceType(winrt::Windows::Media::Playback::MediaPlayerAudioDeviceType::Multimedia);
        player_.IsLoopingEnabled(false);
    }

    void PlayerEngine::playUrl(const std::wstring& url)
    {
        winrt::Windows::Media::Core::MediaSource source =
            winrt::Windows::Media::Core::MediaSource::CreateFromUri(
                winrt::Windows::Foundation::Uri{ winrt::hstring{ url } });
        player_.Source(source);
        player_.Play();
    }

    void PlayerEngine::togglePlayPause()
    {
        if (isPlaying())
        {
            player_.Pause();
        }
        else
        {
            player_.Play();
        }
    }

    void PlayerEngine::stop()
    {
        player_.Pause();
        player_.Source(nullptr);
    }

    bool PlayerEngine::isPlaying() const
    {
        try
        {
            return player_.PlaybackSession().PlaybackState() ==
                winrt::Windows::Media::Playback::MediaPlaybackState::Playing;
        }
        catch (...)
        {
            return false;
        }
    }

    int64_t PlayerEngine::positionMs() const
    {
        try
        {
            // TimeSpan 单位 100ns
            return player_.PlaybackSession().Position().count() / 10000;
        }
        catch (...)
        {
            return 0;
        }
    }

    int64_t PlayerEngine::durationMs() const
    {
        try
        {
            return player_.PlaybackSession().NaturalDuration().count() / 10000;
        }
        catch (...)
        {
            return 0;
        }
    }

    void PlayerEngine::seekTo(int64_t ms)
    {
        try
        {
            player_.PlaybackSession().Position(std::chrono::milliseconds(ms));
        }
        catch (...)
        {
        }
    }

    winrt::event_token PlayerEngine::mediaEnded(winrt::Windows::Foundation::TypedEventHandler<
        winrt::Windows::Media::Playback::MediaPlayer, winrt::Windows::Foundation::IInspectable> const& handler)
    {
        return player_.MediaEnded(handler);
    }

    void PlayerEngine::mediaEnded(winrt::event_token const& token) noexcept
    {
        player_.MediaEnded(token);
    }
}
