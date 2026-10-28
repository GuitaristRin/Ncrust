#include "pch.h"
#include "HomePage.xaml.h"
#include "App.xaml.h"
#include "DetailPage.xaml.h"
#if __has_include("HomePage.g.cpp")
#include "HomePage.g.cpp"
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
    HomePage::HomePage()
    {
        InitializeComponent();
        dispatcher_ = Windows::System::DispatcherQueue::GetForCurrentThread();
    }

    void HomePage::OnLoaded(IInspectable const&, RoutedEventArgs const&)
    {
        LoadRecommendations();
    }

    winrt::fire_and_forget HomePage::LoadRecommendations()
    {
        auto dispatcher = dispatcher_;
        LoadingRing().Visibility(Visibility::Visible);
        ErrorText().Visibility(Visibility::Collapsed);

        co_await winrt::resume_background();

        std::vector<PlaylistItem> items;
        std::wstring error;
        try
        {
            items = getRecommendedPlaylists(30);
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

        items_ = std::move(items);
        if (items_.empty())
        {
            ShowError(error.empty() ? L"暂无推荐" : error);
            return;
        }
        RenderPlaylists(items_);
    }

    void HomePage::RenderPlaylists(std::vector<PlaylistItem> const& items)
    {
        LoadingRing().Visibility(Visibility::Collapsed);
        ErrorText().Visibility(Visibility::Collapsed);

        auto list = PlaylistGrid().Items();
        list.Clear();
        for (size_t i = 0; i < items.size(); ++i)
        {
            auto const& p = items[i];

            StackPanel tile{};
            tile.Width(150);
            tile.Margin(Thickness{ 4, 8, 4, 8 });

            auto cover = Image{};
            cover.Width(150);
            cover.Height(150);
            cover.Stretch(Stretch::UniformToFill);
            if (!p.picUrl.empty())
            {
                cover.Source(BitmapImage{ Uri{ hstring{ p.picUrl } } });
            }
            cover.Tapped([this, i](IInspectable const&, TappedRoutedEventArgs const&) {
                if (i < items_.size())
                {
                    App::rootFrame().Navigate(winrt::xaml_typename<winrt::Ritsudou::DetailPage>(),
                        box_value(hstring{ L"playlist:" + std::to_wstring(items_[i].id) }));
                }
            });
            tile.Children().Append(cover);

            auto name = TextBlock{};
            name.Text(hstring{ p.name });
            name.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
            name.FontSize(13);
            name.TextWrapping(TextWrapping::Wrap);
            name.MaxLines(2);
            name.TextTrimming(TextTrimming::CharacterEllipsis);
            name.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 255, 255, 255 } });
            tile.Children().Append(name);

            list.Append(tile);
        }
    }

    void HomePage::ShowError(std::wstring_view message)
    {
        LoadingRing().Visibility(Visibility::Collapsed);
        ErrorText().Text(hstring{ message });
        ErrorText().Visibility(Visibility::Visible);
    }
}
