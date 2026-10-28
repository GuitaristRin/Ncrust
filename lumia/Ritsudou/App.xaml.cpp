#include "pch.h"
#include "App.xaml.h"
#include "ShellPage.xaml.h"

namespace winrt::Ritsudou::implementation
{
    App::App()
    {
        InitializeComponent();
    }

    void App::OnLaunched(Windows::ApplicationModel::Activation::LaunchActivatedEventArgs const& /*e*/)
    {
        Windows::UI::Xaml::Window window = Windows::UI::Xaml::Window::Current();
        window.Content(rootFrame());
        rootFrame().Navigate(winrt::xaml_typename<winrt::Ritsudou::ShellPage>());
        window.Activate();
    }
}
