#pragma once

#include "HomePage.xaml.g.h"

#include "Core/model/Models.h"

#include <vector>

namespace winrt::Ritsudou::implementation
{
    struct HomePage : HomePageT<HomePage>
    {
        HomePage();

        void OnLoaded(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);

    private:
        winrt::fire_and_forget LoadRecommendations();
        void RenderPlaylists(std::vector<ritsudou::PlaylistItem> const& items);
        void ShowError(std::wstring_view message);

        Windows::System::DispatcherQueue dispatcher_{ nullptr };
        std::vector<ritsudou::PlaylistItem> items_;
    };
}

namespace winrt::Ritsudou
{
    struct HomePage : HomePageT<HomePage, implementation::HomePage>
    {
    };
}
