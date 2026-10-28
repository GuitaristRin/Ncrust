#pragma once

#include "DetailPage.xaml.g.h"

#include "Core/model/Models.h"
#include "Core/player/PlaybackController.h"

#include <vector>

namespace winrt::Ritsudou::implementation
{
    // 通用详情页：通过导航参数区分类型。
    // 参数格式："album:<id>" / "playlist:<id>" / "artist:<id>"。
    struct DetailPage : DetailPageT<DetailPage>
    {
        DetailPage();

        void OnLoaded(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnNavigatedTo(Windows::UI::Xaml::Navigation::NavigationEventArgs const& e);

        void OnBackClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnPlayAllClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnSongTapped(Windows::Foundation::IInspectable const& sender,
            Windows::UI::Xaml::Input::TappedRoutedEventArgs const& e);

    private:
        winrt::fire_and_forget Load();
        void RenderAlbum(ritsudou::AlbumDetail const& d);
        void RenderPlaylist(ritsudou::PlaylistDetail const& d);
        void RenderArtist(ritsudou::ArtistDetail const& d);
        void RenderSongs(std::vector<ritsudou::Song> const& songs);
        void ShowError(std::wstring_view message);

        ritsudou::PlaybackController& controller_;
        Windows::System::DispatcherQueue dispatcher_{ nullptr };
        std::wstring kind_;
        int64_t id_ = 0;
        std::vector<ritsudou::Song> songs_;
    };
}

namespace winrt::Ritsudou
{
    struct DetailPage : DetailPageT<DetailPage, implementation::DetailPage>
    {
    };
}
