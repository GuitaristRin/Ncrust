#pragma once

#include "LibraryPage.xaml.g.h"

#include "Core/library/LibraryManager.h"
#include "Core/player/PlaybackController.h"

namespace winrt::Ritsudou::implementation
{
    struct LibraryPage : LibraryPageT<LibraryPage>
    {
        LibraryPage();

        void OnLoaded(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);

    private:
        void RefreshSongs();
        Windows::UI::Xaml::UIElement BuildRow(ritsudou::Song const& s);
        void OnSongTapped(Windows::Foundation::IInspectable const& sender,
            Windows::UI::Xaml::Input::TappedRoutedEventArgs const& e);

        ritsudou::LibraryManager& library_;
        ritsudou::PlaybackController& controller_;
    };
}

namespace winrt::Ritsudou
{
    struct LibraryPage : LibraryPageT<LibraryPage, implementation::LibraryPage>
    {
    };
}
