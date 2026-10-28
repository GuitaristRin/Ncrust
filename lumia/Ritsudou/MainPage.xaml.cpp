#include "pch.h"
#include "MainPage.xaml.h"
#include "App.xaml.h"
#include "PlayerPage.xaml.h"
#include "DetailPage.xaml.h"
#if __has_include("MainPage.g.cpp")
#include "MainPage.g.cpp"
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
    MainPage::MainPage()
        : controller_(PlaybackController::instance())
    {
        InitializeComponent();

        dispatcher_ = Windows::System::DispatcherQueue::GetForCurrentThread();

        searchTimer_ = Windows::UI::Xaml::DispatcherTimer{};
        searchTimer_.Interval(std::chrono::milliseconds(500));
        searchTimer_.Tick({ this, &MainPage::OnSearchTimerTick });

        positionTimer_ = Windows::UI::Xaml::DispatcherTimer{};
        positionTimer_.Interval(std::chrono::milliseconds(250));
        positionTimer_.Tick({ this, &MainPage::OnPositionTimerTick });
    }

    void MainPage::OnLoaded(IInspectable const&, RoutedEventArgs const&)
    {
        controller_.attach(Windows::System::DispatcherQueue::GetForCurrentThread());
        EmptyHint().Text(hstring{ L"输入关键词，搜索网易云音乐" });
        positionTimer_.Start();
    }

    void MainPage::OnSearchTextChanged(IInspectable const&, TextChangedEventArgs const&)
    {
        if (SearchBox().Text().empty())
        {
            searchTimer_.Stop();
            DoSearch();
        }
        else
        {
            searchTimer_.Stop();
            searchTimer_.Start();
        }
    }

    void MainPage::OnTabChanged(IInspectable const&, RoutedEventArgs const&)
    {
        // 切分类立即用当前关键词重新搜索。
        searchTimer_.Stop();
        DoSearch();
    }

    void MainPage::OnSearchTimerTick(IInspectable const&, IInspectable const&)
    {
        DoSearch();
    }

    winrt::fire_and_forget MainPage::DoSearch()
    {
        auto keyword = SearchBox().Text();
        auto version = ++searchVersion_;
        auto dispatcher = dispatcher_;

        if (keyword.empty())
        {
            songs_.clear();
            PopulateResults();
            EmptyHint().Text(hstring{ L"输入关键词，搜索网易云音乐" });
            EmptyHint().Visibility(Visibility::Visible);
            co_return;
        }

        co_await winrt::resume_background();

        std::vector<Song> songs;
        std::vector<AlbumSearchItem> albums;
        std::vector<ArtistSearchItem> artists;
        std::wstring error;
        try
        {
            if (AlbumTab().IsChecked().Value())
            {
                albums = searchAlbums(keyword);
            }
            else if (ArtistTab().IsChecked().Value())
            {
                artists = searchArtists(keyword);
            }
            else
            {
                songs = searchSongs(keyword);
            }
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

        // 过期请求：只接受最新一次搜索的结果。
        if (version != searchVersion_) co_return;

        songs_ = std::move(songs);
        albums_ = std::move(albums);
        artists_ = std::move(artists);
        PopulateResults();
        if (!error.empty()) ShowError(error);
    }

    void MainPage::PopulateResults()
    {
        auto items = ResultList().Items();
        items.Clear();

        if (AlbumTab().IsChecked().Value())
        {
            for (size_t i = 0; i < albums_.size(); ++i)
            {
                items.Append(BuildAlbumRow(static_cast<int>(i)));
            }
        }
        else if (ArtistTab().IsChecked().Value())
        {
            for (size_t i = 0; i < artists_.size(); ++i)
            {
                items.Append(BuildArtistRow(static_cast<int>(i)));
            }
        }
        else
        {
            for (size_t i = 0; i < songs_.size(); ++i)
            {
                items.Append(BuildSongRow(static_cast<int>(i)));
            }
        }

        bool empty = songs_.empty() && albums_.empty() && artists_.empty();
        if (empty)
        {
            EmptyHint().Text(hstring{ L"没有找到结果" });
            EmptyHint().Visibility(Visibility::Visible);
        }
        else
        {
            EmptyHint().Visibility(Visibility::Collapsed);
        }
    }

    Windows::UI::Xaml::UIElement MainPage::BuildSongRow(int index)
    {
        auto const& s = songs_[static_cast<size_t>(index)];

        Grid grid{};
        grid.Height(64);
        grid.Tag(box_value(index));
        grid.Tapped({ this, &MainPage::OnSongRowTapped });

        auto coverCol = ColumnDefinition{};
        coverCol.Width(GridLength{ 60 });
        grid.ColumnDefinitions().Append(coverCol);

        auto textCol = ColumnDefinition{};
        textCol.Width(GridLength{ 1, GridUnitType::Star });
        grid.ColumnDefinitions().Append(textCol);

        auto indicatorCol = ColumnDefinition{};
        indicatorCol.Width(GridLength{ 36 });
        grid.ColumnDefinitions().Append(indicatorCol);

        if (!s.album.picUrl.empty())
        {
            auto img = Image{};
            img.Width(52);
            img.Height(52);
            img.Stretch(Stretch::UniformToFill);
            img.Margin(Thickness{ 4 });
            img.Source(BitmapImage{ Uri{ hstring{ s.album.picUrl } } });
            Grid::SetColumn(img, 0);
            grid.Children().Append(img);
        }

        auto textStack = StackPanel{};
        textStack.VerticalAlignment(VerticalAlignment::Center);

        auto title = TextBlock{};
        title.Text(hstring{ s.name });
        title.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
        title.FontSize(15);
        title.TextTrimming(TextTrimming::CharacterEllipsis);
        title.Foreground(s.id == controller_.currentSongId()
            ? SolidColorBrush{ Windows::UI::Color{ 255, 29, 185, 84 } }
            : SolidColorBrush{ Windows::UI::Color{ 255, 255, 255, 255 } });
        textStack.Children().Append(title);

        if (!s.artistLabel().empty())
        {
            auto sub = TextBlock{};
            sub.Text(hstring{ s.artistLabel() });
            sub.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
            sub.FontSize(12);
            sub.TextTrimming(TextTrimming::CharacterEllipsis);
            sub.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 179, 179, 179 } });
            textStack.Children().Append(sub);
        }

        Grid::SetColumn(textStack, 1);
        grid.Children().Append(textStack);

        if (s.id == controller_.currentSongId())
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

        return grid;
    }

    void MainPage::OnSongRowTapped(IInspectable const& sender, TappedRoutedEventArgs const&)
    {
        auto fe = sender.as<FrameworkElement>();
        int index = unbox_value<int>(fe.Tag());
        if (index >= 0 && index < static_cast<int>(songs_.size()))
        {
            PlaySong(index);
        }
    }

    void MainPage::OnAlbumRowTapped(IInspectable const& sender, TappedRoutedEventArgs const&)
    {
        auto fe = sender.as<FrameworkElement>();
        int index = unbox_value<int>(fe.Tag());
        if (index >= 0 && index < static_cast<int>(albums_.size()))
        {
            auto const& a = albums_[static_cast<size_t>(index)];
            App::rootFrame().Navigate(winrt::xaml_typename<winrt::Ritsudou::DetailPage>(),
                box_value(hstring{ L"album:" + std::to_wstring(a.id) }));
        }
    }

    void MainPage::OnArtistRowTapped(IInspectable const& sender, TappedRoutedEventArgs const&)
    {
        auto fe = sender.as<FrameworkElement>();
        int index = unbox_value<int>(fe.Tag());
        if (index >= 0 && index < static_cast<int>(artists_.size()))
        {
            auto const& a = artists_[static_cast<size_t>(index)];
            App::rootFrame().Navigate(winrt::xaml_typename<winrt::Ritsudou::DetailPage>(),
                box_value(hstring{ L"artist:" + std::to_wstring(a.id) }));
        }
    }

    Windows::UI::Xaml::UIElement MainPage::BuildAlbumRow(int index)
    {
        auto const& a = albums_[static_cast<size_t>(index)];

        Grid grid{};
        grid.Height(64);
        grid.Tag(box_value(index));
        grid.Tapped({ this, &MainPage::OnAlbumRowTapped });

        auto coverCol = ColumnDefinition{};
        coverCol.Width(GridLength{ 60 });
        grid.ColumnDefinitions().Append(coverCol);

        auto textCol = ColumnDefinition{};
        textCol.Width(GridLength{ 1, GridUnitType::Star });
        grid.ColumnDefinitions().Append(textCol);

        if (!a.picUrl.empty())
        {
            auto img = Image{};
            img.Width(52);
            img.Height(52);
            img.Stretch(Stretch::UniformToFill);
            img.Margin(Thickness{ 4 });
            img.Source(BitmapImage{ Uri{ hstring{ a.picUrl } } });
            Grid::SetColumn(img, 0);
            grid.Children().Append(img);
        }

        auto textStack = StackPanel{};
        textStack.VerticalAlignment(VerticalAlignment::Center);

        auto title = TextBlock{};
        title.Text(hstring{ a.name });
        title.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
        title.FontSize(15);
        title.TextTrimming(TextTrimming::CharacterEllipsis);
        title.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 255, 255, 255 } });
        textStack.Children().Append(title);

        auto sub = TextBlock{};
        sub.Text(hstring{ a.artistName });
        sub.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
        sub.FontSize(12);
        sub.TextTrimming(TextTrimming::CharacterEllipsis);
        sub.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 179, 179, 179 } });
        textStack.Children().Append(sub);

        Grid::SetColumn(textStack, 1);
        grid.Children().Append(textStack);
        return grid;
    }

    Windows::UI::Xaml::UIElement MainPage::BuildArtistRow(int index)
    {
        auto const& a = artists_[static_cast<size_t>(index)];

        Grid grid{};
        grid.Height(64);
        grid.Tag(box_value(index));
        grid.Tapped({ this, &MainPage::OnArtistRowTapped });

        auto coverCol = ColumnDefinition{};
        coverCol.Width(GridLength{ 60 });
        grid.ColumnDefinitions().Append(coverCol);

        auto textCol = ColumnDefinition{};
        textCol.Width(GridLength{ 1, GridUnitType::Star });
        grid.ColumnDefinitions().Append(textCol);

        if (!a.picUrl.empty())
        {
            auto img = Image{};
            img.Width(52);
            img.Height(52);
            img.Stretch(Stretch::UniformToFill);
            img.Margin(Thickness{ 4 });
            img.Source(BitmapImage{ Uri{ hstring{ a.picUrl } } });
            Grid::SetColumn(img, 0);
            grid.Children().Append(img);
        }

        auto textStack = StackPanel{};
        textStack.VerticalAlignment(VerticalAlignment::Center);

        auto title = TextBlock{};
        title.Text(hstring{ a.name });
        title.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
        title.FontSize(15);
        title.TextTrimming(TextTrimming::CharacterEllipsis);
        title.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 255, 255, 255 } });
        textStack.Children().Append(title);

        Grid::SetColumn(textStack, 1);
        grid.Children().Append(textStack);
        return grid;
    }

    void MainPage::PlaySong(int index)
    {
        // 队列替换 + 播放交给 PlaybackController 统一处理（含后台取 URL、歌词）。
        controller_.replaceQueueAndPlay(songs_, index);
        playerBarVisible_ = true;
        PlayerBar().Visibility(Visibility::Visible);
        UpdatePlayerBar();
    }

    void MainPage::UpdatePlayerBar()
    {
        auto const* current = controller_.queue().current();
        if (!current) return;

        PlayerTitle().Text(hstring{ current->name });
        PlayerSubtitle().Text(hstring{ current->artistLabel() });
        if (!current->album.picUrl.empty())
        {
            PlayerCover().Source(BitmapImage{ Uri{ hstring{ current->album.picUrl } } });
        }
        UpdatePlayGlyph();
    }

    void MainPage::UpdatePlayGlyph()
    {
        auto icon = FontIcon{};
        icon.FontFamily(FontFamily{ hstring{ L"Segoe MDL2 Assets" } });
        icon.FontSize(22);
        icon.Glyph(hstring{ controller_.engine().isPlaying() ? L"\uE103" : L"\uE102" });
        PlayPauseButton().Content(icon);
    }

    void MainPage::OnPositionTimerTick(IInspectable const&, IInspectable const&)
    {
        if (playerBarVisible_)
        {
            auto duration = controller_.engine().durationMs();
            auto position = controller_.engine().positionMs();
            PlayerProgress().Maximum(static_cast<double>(duration > 0 ? duration : 1));
            PlayerProgress().Value(static_cast<double>(position));
        }
        UpdatePlayGlyph();
    }

    void MainPage::OnPlayPauseClick(IInspectable const&, RoutedEventArgs const&)
    {
        controller_.togglePlayPause();
        UpdatePlayGlyph();
    }

    void MainPage::OnNextClick(IInspectable const&, RoutedEventArgs const&)
    {
        controller_.playNext();
        UpdatePlayerBar();
    }

    void MainPage::OnPlayerBarTapped(IInspectable const&, TappedRoutedEventArgs const& e)
    {
        // 点内部按钮时事件已被 Button 标记 Handled，忽略，避免误触全屏页。
        if (e.Handled()) return;
        // 进入全屏播放页。
        App::rootFrame().Navigate(winrt::xaml_typename<winrt::Ritsudou::PlayerPage>());
    }

    void MainPage::ShowError(std::wstring_view message)
    {
        EmptyHint().Text(hstring{ message });
        EmptyHint().Visibility(Visibility::Visible);
    }
}
