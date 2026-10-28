#include "pch.h"
#include "LibraryPage.xaml.h"
#if __has_include("LibraryPage.g.cpp")
#include "LibraryPage.g.cpp"
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
    LibraryPage::LibraryPage()
        : library_(LibraryManager::instance()),
          controller_(PlaybackController::instance())
    {
        InitializeComponent();
    }

    void LibraryPage::OnLoaded(IInspectable const&, RoutedEventArgs const&)
    {
        RefreshSongs();
    }

    void LibraryPage::RefreshSongs()
    {
        library_.ensureLoaded();
        auto items = SongList().Items();
        items.Clear();

        auto const& songs = library_.savedSongs();
        for (auto const& s : songs)
        {
            items.Append(BuildRow(s));
        }

        EmptyHint().Visibility(songs.empty() ? Visibility::Visible : Visibility::Collapsed);
    }

    Windows::UI::Xaml::UIElement LibraryPage::BuildRow(Song const& s)
    {
        Grid grid{};
        grid.Height(64);
        grid.Tag(box_value(s.id));
        grid.Tapped({ this, &LibraryPage::OnSongTapped });

        auto coverCol = ColumnDefinition{};
        coverCol.Width(GridLength{ 60 });
        grid.ColumnDefinitions().Append(coverCol);

        auto textCol = ColumnDefinition{};
        textCol.Width(GridLength{ 1, GridUnitType::Star });
        grid.ColumnDefinitions().Append(textCol);

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
        title.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 255, 255, 255 } });
        textStack.Children().Append(title);

        auto sub = TextBlock{};
        sub.Text(hstring{ s.artistLabel() });
        sub.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
        sub.FontSize(12);
        sub.TextTrimming(TextTrimming::CharacterEllipsis);
        sub.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 179, 179, 179 } });
        textStack.Children().Append(sub);

        Grid::SetColumn(textStack, 1);
        grid.Children().Append(textStack);
        return grid;
    }

    void LibraryPage::OnSongTapped(IInspectable const& sender, TappedRoutedEventArgs const&)
    {
        auto fe = sender.as<FrameworkElement>();
        int64_t id = unbox_value<int64_t>(fe.Tag());

        // 用当前收藏整队播放，定位到所点歌曲。
        auto songs = library_.savedSongs();
        for (size_t i = 0; i < songs.size(); ++i)
        {
            if (songs[i].id == id)
            {
                controller_.replaceQueueAndPlay(songs, static_cast<int>(i));
                break;
            }
        }
    }
}
