#include "pch.h"
#include "ShellPage.xaml.h"
#include "HomePage.xaml.h"
#include "MainPage.xaml.h"
#include "LibraryPage.xaml.h"
#if __has_include("ShellPage.g.cpp")
#include "ShellPage.g.cpp"
#endif

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::UI::Xaml;
using namespace Windows::UI::Xaml::Controls;
using namespace Windows::UI::Xaml::Media;

namespace winrt::Ritsudou::implementation
{
    ShellPage::ShellPage()
    {
        InitializeComponent();
        homeType_ = winrt::xaml_typename<winrt::Ritsudou::HomePage>();
        searchType_ = winrt::xaml_typename<winrt::Ritsudou::MainPage>();
        libraryType_ = winrt::xaml_typename<winrt::Ritsudou::LibraryPage>();
    }

    void ShellPage::OnNavigatedTo(Windows::UI::Xaml::Navigation::NavigationEventArgs const&)
    {
        // 首次进入：默认落在首页。
        if (ContentFrame().Content() == nullptr)
        {
            ContentFrame().Navigate(homeType_);
            SyncNavHighlight();
        }
    }

    void ShellPage::NavigateTo(Windows::UI::Xaml::Interop::TypeName const& type)
    {
        ContentFrame().Navigate(type);
        SyncNavHighlight();
    }

    void ShellPage::SyncNavHighlight()
    {
        bool home = ContentFrame().CurrentSourcePageType().Name == homeType_.Name;
        bool library = ContentFrame().CurrentSourcePageType().Name == libraryType_.Name;
        bool search = !home && !library;

        SolidColorBrush accent{ Windows::UI::Color{ 255, 29, 185, 84 } };
        SolidColorBrush plain{ Windows::UI::Color{ 255, 255, 255, 255 } };

        HomeNav().Foreground(home ? accent : plain);
        SearchNav().Foreground(search ? accent : plain);
        LibraryNav().Foreground(library ? accent : plain);
    }

    void ShellPage::OnHomeNavClick(IInspectable const&, RoutedEventArgs const&)
    {
        NavigateTo(homeType_);
    }

    void ShellPage::OnSearchNavClick(IInspectable const&, RoutedEventArgs const&)
    {
        NavigateTo(searchType_);
    }

    void ShellPage::OnLibraryNavClick(IInspectable const&, RoutedEventArgs const&)
    {
        NavigateTo(libraryType_);
    }
}
