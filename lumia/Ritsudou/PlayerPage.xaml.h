#pragma once

#include "PlayerPage.xaml.g.h"

#include "Core/player/PlaybackController.h"

#include <vector>

namespace winrt::Ritsudou::implementation
{
    struct PlayerPage : PlayerPageT<PlayerPage>
    {
        PlayerPage();

        void OnLoaded(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnUnloaded(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);

        void OnBackClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnToggleViewClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnQueueClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnQueueCloseClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnQueueSongTapped(Windows::Foundation::IInspectable const& sender,
            Windows::UI::Xaml::Input::TappedRoutedEventArgs const& e);
        void OnModeClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnPrevClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnPlayPauseClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnNextClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnQualityClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnSliderPointerPressed(Windows::Foundation::IInspectable const& sender,
            Windows::UI::Xaml::Input::PointerRoutedEventArgs const& e);
        void OnSliderPointerCaptureLost(Windows::Foundation::IInspectable const& sender,
            Windows::UI::Xaml::Input::PointerRoutedEventArgs const& e);
        void OnLyricTapped(Windows::Foundation::IInspectable const& sender,
            Windows::UI::Xaml::Input::TappedRoutedEventArgs const& e);
        void OnPositionTimerTick(Windows::Foundation::IInspectable const& sender,
            Windows::Foundation::IInspectable const& e);

    private:
        void OnSongChanged(Windows::Foundation::IInspectable const& sender,
            Windows::Foundation::IInspectable const& args);
        void RefreshSong();
        void BuildLyrics();
        void RefreshQueue();
        void RefreshPlayState();
        void RefreshModeGlyph();
        void RefreshQualityBadge();
        void SyncProgressSlider();
        void SeekFromSlider();
        std::wstring FormatTime(int64_t ms) const;
        int64_t CurrentLyricIndex(int64_t positionMs) const;
        void ScrollToLyric(int64_t index);

        ritsudou::PlaybackController& controller_;
        Windows::System::DispatcherQueue dispatcher_{ nullptr };
        Windows::UI::Xaml::DispatcherTimer positionTimer_;
        winrt::event_token songChangedToken_{};
        bool viewIsLyrics_ = false;
        bool isSeeking_ = false;
        int64_t displayedDurationMs_ = 0;
        int64_t highlightedLyric_ = -1;
        std::vector<Windows::UI::Xaml::Controls::TextBlock> lyricBlocks_;
    };
}

namespace winrt::Ritsudou
{
    struct PlayerPage : PlayerPageT<PlayerPage, implementation::PlayerPage>
    {
    };
}
