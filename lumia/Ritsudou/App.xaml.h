#pragma once

#include "App.xaml.g.h"

namespace winrt::Ritsudou::implementation
{
    struct App : AppT<App>
    {
        App();

        void OnLaunched(Windows::ApplicationModel::Activation::LaunchActivatedEventArgs const& e);

        // 根导航帧：MainPage 与 PlayerPage 之间的往返由它承载。
        static Windows::UI::Xaml::Controls::Frame& rootFrame()
        {
            static Windows::UI::Xaml::Controls::Frame frame;
            return frame;
        }
    };
}

namespace winrt::Ritsudou
{
    struct App : AppT<App, implementation::App>
    {
    };
}
