#include "pch.h"
#include "DetailPage.xaml.h"
#if __has_include("DetailPage.g.cpp")
#include "DetailPage.g.cpp"
#endif

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::UI::Xaml;
using namespace Windows::UI::Xaml::Controls;
using namespace Windows::UI::Xaml::Media;
using namespace Windows::UI::Xaml::Media::Imaging;
using namespace Windows::UI::Xaml::Input;

using namespace ritsudou;

namespace winrt::Ritsudou::implementation
{
    DetailPage::DetailPage()
        : controller_(PlaybackController::instance())
    {
        InitializeComponent();
        dispatcher_ = Windows::System::DispatcherQueue::GetForCurrentThread();
    }

    void DetailPage::OnLoaded(IInspectable const&, RoutedEventArgs const&)
    {
        if (!songs_.empty())
        {
            LoadingRing().Visibility(Visibility::Collapsed);
        }
    }

    void DetailPage::OnNavigatedTo(Windows::UI::Xaml::Navigation::NavigationEventArgs const& e)
    {
        auto param = unbox_value_or<hstring>(e.Parameter(), hstring{});
        std::wstring p(param);

        auto colon = p.find(L':');
        if (colon == std::wstring::npos) return;
        kind_ = p.substr(0, colon);
        try { id_ = std::stoll(p.substr(colon + 1)); }
        catch (...) { return; }

        PageTitle().Text(hstring{ kind_ == L"playlist" ? L"歌单" :
                                   kind_ == L"artist" ? L"艺人" : L"专辑" });
        Load();
    }

    winrt::fire_and_forget DetailPage::Load()
    {
        auto dispatcher = dispatcher_;
        LoadingRing().Visibility(Visibility::Visible);
        ErrorText().Visibility(Visibility::Collapsed);

        co_await winrt::resume_background();

        std::wstring error;
        try
        {
            if (kind_ == L"playlist")
            {
                auto d = getPlaylistDetail(id_);
                co_await winrt::resume_foreground(dispatcher);
                RenderPlaylist(d);
            }
            else if (kind_ == L"artist")
            {
                auto d = getArtistDetail(id_);
                co_await winrt::resume_foreground(dispatcher);
                RenderArtist(d);
            }
            else
            {
                auto d = getAlbumDetail(id_);
                co_await winrt::resume_foreground(dispatcher);
                RenderAlbum(d);
            }
            co_return;
        }
        catch (winrt::hresult_error const& e)
        {
            error = std::wstring(e.message());
        }
        catch (std::exception const& e)
        {
            error = utf8ToWide(e.what());
        }

        co_await winrt::resume_foreground(dispatcher);
        ShowError(error);
    }

    void DetailPage::RenderAlbum(AlbumDetail const& d)
    {
        TitleText().Text(hstring{ d.name });
        SubtitleText().Text(hstring{ d.artistLabel });
        if (!d.picUrl.empty())
        {
            CoverImage().Source(BitmapImage{ Uri{ hstring{ d.picUrl } } });
        }
        if (!d.description.empty())
        {
            DescText().Text(hstring{ d.description });
            DescText().Visibility(Visibility::Visible);
        }
        songs_ = d.songs;
        RenderSongs(songs_);
    }

    void DetailPage::RenderPlaylist(PlaylistDetail const& d)
    {
        TitleText().Text(hstring{ d.name });
        SubtitleText().Text(hstring{ d.creatorLabel });
        if (!d.picUrl.empty())
        {
            CoverImage().Source(BitmapImage{ Uri{ hstring{ d.picUrl } } });
        }
        if (!d.description.empty())
        {
            DescText().Text(hstring{ d.description });
            DescText().Visibility(Visibility::Visible);
        }
        songs_ = d.songs;
        RenderSongs(songs_);
    }

    void DetailPage::RenderArtist(ArtistDetail const& d)
    {
        TitleText().Text(hstring{ d.name });
        SubtitleText().Text(hstring{ L"热门歌曲 " + std::to_wstring(d.hotSongs.size()) });
        if (!d.picUrl.empty())
        {
            CoverImage().Source(BitmapImage{ Uri{ hstring{ d.picUrl } } });
        }
        if (!d.description.empty())
        {
            DescText().Text(hstring{ d.description });
            DescText().Visibility(Visibility::Visible);
        }
        songs_ = d.hotSongs;
        RenderSongs(songs_);
    }

    void DetailPage::RenderSongs(std::vector<Song> const& songs)
    {
        LoadingRing().Visibility(Visibility::Collapsed);
        ErrorText().Visibility(Visibility::Collapsed);

        auto items = SongList().Items();
        items.Clear();

        int64_t currentId = controller_.currentSongId();
        for (int i = 0; i < static_cast<int>(songs.size()); ++i)
        {
            auto const& s = songs[static_cast<size_t>(i)];

            Grid grid{};
            grid.Height(60);
            grid.Tag(box_value(i));
            grid.Tapped({ this, &DetailPage::OnSongTapped });

            auto numCol = ColumnDefinition{};
            numCol.Width(GridLength{ 40 });
            grid.ColumnDefinitions().Append(numCol);

            auto textCol = ColumnDefinition{};
            textCol.Width(GridLength{ 1, GridUnitType::Star });
            grid.ColumnDefinitions().Append(textCol);

            auto indicatorCol = ColumnDefinition{};
            indicatorCol.Width(GridLength{ 36 });
            grid.ColumnDefinitions().Append(indicatorCol);

            bool isCurrent = (s.id == currentId);

            auto num = TextBlock{};
            num.Text(hstring{ std::to_wstring(i + 1) });
            num.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
            num.FontSize(13);
            num.HorizontalAlignment(HorizontalAlignment::Center);
            num.VerticalAlignment(VerticalAlignment::Center);
            num.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 179, 179, 179 } });
            Grid::SetColumn(num, 0);
            grid.Children().Append(num);

            auto stack = StackPanel{};
            stack.VerticalAlignment(VerticalAlignment::Center);

            auto title = TextBlock{};
            title.Text(hstring{ s.name });
            title.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
            title.FontSize(14);
            title.TextTrimming(TextTrimming::CharacterEllipsis);
            title.Foreground(isCurrent
                ? SolidColorBrush{ Windows::UI::Color{ 255, 29, 185, 84 } }
                : SolidColorBrush{ Windows::UI::Color{ 255, 255, 255, 255 } });
            stack.Children().Append(title);

            auto sub = TextBlock{};
            sub.Text(hstring{ s.artistLabel() });
            sub.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
            sub.FontSize(11);
            sub.TextTrimming(TextTrimming::CharacterEllipsis);
            sub.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 179, 179, 179 } });
            stack.Children().Append(sub);

            Grid::SetColumn(stack, 1);
            grid.Children().Append(stack);

            if (isCurrent)
            {
                auto icon = FontIcon{};
                icon.Glyph(hstring{ L"\uE102" });
                icon.FontFamily(FontFamily{ hstring{ L"Segoe MDL2 Assets" } });
                icon.FontSize(16);
                icon.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 29, 185, 84 } });
                icon.VerticalAlignment(VerticalAlignment::Center);
                Grid::SetColumn(icon, 2);
                grid.Children().Append(icon);
            }

            items.Append(grid);
        }

        if (songs.empty())
        {
            ErrorText().Text(hstring{ L"暂无内容" });
            ErrorText().Visibility(Visibility::Visible);
        }
    }

    void DetailPage::OnSongTapped(IInspectable const& sender, TappedRoutedEventArgs const&)
    {
        auto fe = sender.as<FrameworkElement>();
        int index = unbox_value<int>(fe.Tag());
        if (index >= 0 && index < static_cast<int>(songs_.size()))
        {
            controller_.replaceQueueAndPlay(songs_, index);
        }
    }

    void DetailPage::OnPlayAllClick(IInspectable const&, RoutedEventArgs const&)
    {
        if (!songs_.empty())
        {
            controller_.replaceQueueAndPlay(songs_, 0);
        }
    }

    void DetailPage::OnBackClick(IInspectable const&, RoutedEventArgs const&)
    {
        if (Frame() && Frame().CanGoBack())
        {
            Frame().GoBack();
        }
    }

    void DetailPage::ShowError(std::wstring_view message)
    {
        LoadingRing().Visibility(Visibility::Collapsed);
        ErrorText().Text(hstring{ message });
        ErrorText().Visibility(Visibility::Visible);
    }
}
