#pragma once

#include "MainPage.xaml.g.h"

#include "Core/model/Models.h"
#include "Core/player/PlaybackController.h"

#include <vector>

namespace winrt::Ritsudou::implementation
{
    struct MainPage : MainPageT<MainPage>
    {
        MainPage();

        void OnLoaded(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnSearchTextChanged(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::Controls::TextChangedEventArgs const& e);
        void OnTabChanged(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnPlayPauseClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnNextClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnPlayerBarTapped(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::Input::TappedRoutedEventArgs const& e);

        void OnSearchTimerTick(Windows::Foundation::IInspectable const& sender, Windows::Foundation::IInspectable const& e);
        void OnPositionTimerTick(Windows::Foundation::IInspectable const& sender, Windows::Foundation::IInspectable const& e);
        void OnSongRowTapped(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::Input::TappedRoutedEventArgs const& e);
        void OnAlbumRowTapped(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::Input::TappedRoutedEventArgs const& e);
        void OnArtistRowTapped(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::Input::TappedRoutedEventArgs const& e);

    private:
        winrt::fire_and_forget DoSearch();
        void PopulateResults();
        void PlaySong(int index);
        void UpdatePlayerBar();
        void UpdatePlayGlyph();
        void ShowError(std::wstring_view message);

        Windows::UI::Xaml::UIElement BuildSongRow(int index);
        Windows::UI::Xaml::UIElement BuildAlbumRow(int index);
        Windows::UI::Xaml::UIElement BuildArtistRow(int index);

        ritsudou::PlaybackController& controller_;
        Windows::System::DispatcherQueue dispatcher_{ nullptr };
        Windows::UI::Xaml::DispatcherTimer searchTimer_;
        Windows::UI::Xaml::DispatcherTimer positionTimer_;
        std::vector<ritsudou::Song> songs_;
        std::vector<ritsudou::AlbumSearchItem> albums_;
        std::vector<ritsudou::ArtistSearchItem> artists_;
        uint32_t searchVersion_ = 0;
        bool playerBarVisible_ = false;
    };
}

namespace winrt::Ritsudou
{
    struct MainPage : MainPageT<MainPage, implementation::MainPage>
    {
    };
}
