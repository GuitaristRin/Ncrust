#pragma once

#include "ShellPage.xaml.g.h"

namespace winrt::Ritsudou::implementation
{
    // 壳页面：底部三栏导航（首页 / 搜索 / 收藏），内容用嵌套 Frame 承载。
    struct ShellPage : ShellPageT<ShellPage>
    {
        ShellPage();

        void OnHomeNavClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnSearchNavClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnLibraryNavClick(Windows::Foundation::IInspectable const& sender, Windows::UI::Xaml::RoutedEventArgs const& e);
        void OnNavigatedTo(Windows::UI::Xaml::Navigation::NavigationEventArgs const& e);

    private:
        void NavigateTo(Windows::UI::Xaml::Interop::TypeName const& type);
        void SyncNavHighlight();

        Windows::UI::Xaml::Interop::TypeName homeType_;
        Windows::UI::Xaml::Interop::TypeName searchType_;
        Windows::UI::Xaml::Interop::TypeName libraryType_;
    };
}

namespace winrt::Ritsudou
{
    struct ShellPage : ShellPageT<ShellPage, implementation::ShellPage>
    {
    };
}
