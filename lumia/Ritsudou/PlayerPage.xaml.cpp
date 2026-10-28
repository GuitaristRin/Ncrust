#include "pch.h"
#include "PlayerPage.xaml.h"
#include "App.xaml.h"
#if __has_include("PlayerPage.g.cpp")
#include "PlayerPage.g.cpp"
#endif

using namespace winrt;
using namespace Windows::Foundation;
using namespace Windows::UI::Xaml;
using namespace Windows::UI::Xaml::Controls;
using namespace Windows::UI::Xaml::Media;
using namespace Windows::UI::Xaml::Media::Imaging;
using namespace Windows::UI::Xaml::Input;
using namespace Windows::UI::Xaml::Controls::Primitives;

using namespace ritsudou;

namespace winrt::Ritsudou::implementation
{
    namespace
    {
        const std::vector<std::pair<std::wstring, std::wstring>>& qualityPresets()
        {
            static const std::vector<std::pair<std::wstring, std::wstring>> presets{
                { L"standard", L"压缩" },
                { L"higher", L"较好" },
                { L"exhigh", L"更好" },
                { L"lossless", L"无损" },
                { L"hires", L"高解析" },
            };
            return presets;
        }
    }

    PlayerPage::PlayerPage()
        : controller_(PlaybackController::instance())
    {
        InitializeComponent();

        dispatcher_ = Windows::System::DispatcherQueue::GetForCurrentThread();

        positionTimer_ = Windows::UI::Xaml::DispatcherTimer{};
        positionTimer_.Interval(std::chrono::milliseconds(250));
        positionTimer_.Tick({ this, &PlayerPage::OnPositionTimerTick });
    }

    void PlayerPage::OnLoaded(IInspectable const&, RoutedEventArgs const&)
    {
        songChangedToken_ = controller_.songChanged({ this, &PlayerPage::OnSongChanged });
        RefreshSong();
        RefreshModeGlyph();
        RefreshQualityBadge();
        positionTimer_.Start();
    }

    void PlayerPage::OnUnloaded(IInspectable const&, RoutedEventArgs const&)
    {
        positionTimer_.Stop();
        controller_.songChanged(songChangedToken_);
    }

    void PlayerPage::OnSongChanged(IInspectable const&, IInspectable const&)
    {
        RefreshSong();
    }

    void PlayerPage::RefreshSong()
    {
        auto const* current = controller_.queue().current();
        if (!current)
        {
            SongTitle().Text(hstring{ L"未在播放" });
            SongSubtitle().Text(hstring{ L"" });
            return;
        }

        SongTitle().Text(hstring{ current->name });
        SongSubtitle().Text(hstring{ current->artistLabel() });

        if (!current->album.picUrl.empty())
        {
            CoverImage().Source(BitmapImage{ Uri{ hstring{ current->album.picUrl } } });
        }

        if (controller_.lyricsLoaded())
        {
            BuildLyrics();
        }
        RefreshPlayState();
    }

    void PlayerPage::BuildLyrics()
    {
        LyricsPanel().Children().Clear();
        lyricBlocks_.clear();
        highlightedLyric_ = -1;
        displayedDurationMs_ = 0;

        auto const& lines = controller_.lyrics();
        for (auto const& line : lines)
        {
            TextBlock block{};
            block.Text(hstring{ line.text });
            block.Tag(box_value(line.timeMs));
            block.FontFamily(FontFamily{ hstring{ L"Segoe WP" } });
            block.FontSize(17);
            block.TextWrapping(TextWrapping::Wrap);
            block.Foreground(SolidColorBrush{ Windows::UI::Color{ 255, 179, 179, 179 } });
            block.Tapped({ this, &PlayerPage::OnLyricTapped });
            LyricsPanel().Children().Append(block);
            lyricBlocks_.push_back(block);
        }

        bool hasLyrics = !lines.empty();
        LyricsHint().Visibility(hasLyrics ? Visibility::Collapsed : Visibility::Visible);
        LyricsScroll().Visibility(hasLyrics ? Visibility::Visible : Visibility::Collapsed);
        if (!hasLyrics) LyricsHint().Text(hstring{ L"暂无歌词" });

        // 回到顶部。
        if (hasLyrics)
        {
            LyricsScroll().ChangeView(nullptr, 0.0, nullptr);
        }
    }

    void PlayerPage::RefreshPlayState()
    {
        PlayGlyph().Glyph(hstring{ controller_.engine().isPlaying() ? L"\uE103" : L"\uE102" });
        RefreshModeGlyph();
    }

    void PlayerPage::RefreshQueue()
    {
        auto items = QueueList().Items();
        items.Clear();

        auto const& songs = controller_.queue().songs();
        int current = controller_.queue().currentIndex();
        for (int i = 0; i < static_cast<int>(songs.size()); ++i)
        {
            auto const& s = songs[static_cast<size_t>(i)];

            Grid grid{};
            grid.Height(56);
            grid.Tag(box_value(i));
            grid.Tapped({ this, &PlayerPage::OnQueueSongTapped });

            auto numCol = ColumnDefinition{};
            numCol.Width(GridLength{ 40 });
            grid.ColumnDefinitions().Append(numCol);

            auto textCol = ColumnDefinition{};
            textCol.Width(GridLength{ 1, GridUnitType::Star });
            grid.ColumnDefinitions().Append(textCol);

            bool isCurrent = (i == current);
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

            items.Append(grid);
        }
    }

    void PlayerPage::RefreshModeGlyph()
    {
        switch (controller_.playMode())
        {
        case PlayMode::Shuffle: ModeGlyph().Glyph(hstring{ L"\uE14B" }); break;
        case PlayMode::Sequence: ModeGlyph().Glyph(hstring{ L"\uE1C6" }); break;
        default: ModeGlyph().Glyph(hstring{ L"\uE1CD" }); break;
        }
    }

    void PlayerPage::RefreshQualityBadge()
    {
        auto const& presets = qualityPresets();
        for (auto const& [level, label] : presets)
        {
            if (level == controller_.quality())
            {
                QualityBadge().Text(hstring{ label });
                return;
            }
        }
        QualityBadge().Text(hstring{ L"标准" });
    }

    void PlayerPage::OnBackClick(IInspectable const&, RoutedEventArgs const&)
    {
        if (Frame() && Frame().CanGoBack())
        {
            Frame().GoBack();
        }
    }

    void PlayerPage::OnToggleViewClick(IInspectable const&, RoutedEventArgs const&)
    {
        viewIsLyrics_ = !viewIsLyrics_;
        if (viewIsLyrics_)
        {
            CoverArea().Visibility(Visibility::Collapsed);
            LyricsArea().Visibility(Visibility::Visible);
            ViewToggleGlyph().Glyph(hstring{ L"\uEB9F" });
            if (controller_.lyricsLoaded()) BuildLyrics();
        }
        else
        {
            CoverArea().Visibility(Visibility::Visible);
            LyricsArea().Visibility(Visibility::Collapsed);
            ViewToggleGlyph().Glyph(hstring{ L"\uE8B9" });
        }
    }

    void PlayerPage::OnQueueClick(IInspectable const&, RoutedEventArgs const&)
    {
        RefreshQueue();
        QueueOverlay().Visibility(Visibility::Visible);
    }

    void PlayerPage::OnQueueCloseClick(IInspectable const&, RoutedEventArgs const&)
    {
        QueueOverlay().Visibility(Visibility::Collapsed);
    }

    void PlayerPage::OnQueueSongTapped(IInspectable const& sender, TappedRoutedEventArgs const&)
    {
        auto fe = sender.as<FrameworkElement>();
        int index = unbox_value<int>(fe.Tag());
        auto& queue = controller_.queue();
        if (index >= 0 && index < static_cast<int>(queue.size()))
        {
            queue.jumpTo(index);
            controller_.replaceQueueAndPlay(queue.songs(), index);
            QueueOverlay().Visibility(Visibility::Collapsed);
        }
    }

    void PlayerPage::OnModeClick(IInspectable const&, RoutedEventArgs const&)
    {
        auto mode = controller_.playMode();
        PlayMode next = (mode == PlayMode::Shuffle) ? PlayMode::LoopAll
            : (mode == PlayMode::LoopAll) ? PlayMode::Sequence
            : PlayMode::Shuffle;
        controller_.setPlayMode(next);
        RefreshModeGlyph();
    }

    void PlayerPage::OnPrevClick(IInspectable const&, RoutedEventArgs const&)
    {
        controller_.playPrev();
    }

    void PlayerPage::OnPlayPauseClick(IInspectable const&, RoutedEventArgs const&)
    {
        controller_.togglePlayPause();
        RefreshPlayState();
    }

    void PlayerPage::OnNextClick(IInspectable const&, RoutedEventArgs const&)
    {
        controller_.playNext();
    }

    void PlayerPage::OnQualityClick(IInspectable const&, RoutedEventArgs const&)
    {
        auto const& presets = qualityPresets();
        auto it = presets.begin();
        while (it != presets.end() && it->first != controller_.quality()) ++it;
        auto next = (it == presets.end() || std::next(it) == presets.end()) ? presets.begin() : std::next(it);
        controller_.setQuality(next->first);
    }

    void PlayerPage::OnSliderPointerPressed(IInspectable const&, PointerRoutedEventArgs const&)
    {
        isSeeking_ = true;
    }

    void PlayerPage::OnSliderPointerCaptureLost(IInspectable const&, PointerRoutedEventArgs const&)
    {
        SeekFromSlider();
        isSeeking_ = false;
    }

    void PlayerPage::SeekFromSlider()
    {
        if (displayedDurationMs_ <= 0) return;
        double ratio = ProgressSlider().Value();
        int64_t ms = static_cast<int64_t>(ratio * static_cast<double>(displayedDurationMs_));
        controller_.seekTo(ms);
    }

    void PlayerPage::OnLyricTapped(IInspectable const& sender, TappedRoutedEventArgs const&)
    {
        auto fe = sender.as<FrameworkElement>();
        int64_t timeMs = unbox_value<int64_t>(fe.Tag());
        controller_.seekTo(timeMs);
    }

    void PlayerPage::OnPositionTimerTick(IInspectable const&, IInspectable const&)
    {
        SyncProgressSlider();

        auto const& lines = controller_.lyrics();
        if (lines.empty() || !viewIsLyrics_) return;
        int64_t index = CurrentLyricIndex(controller_.engine().positionMs());
        if (index >= 0 && static_cast<size_t>(index) < lyricBlocks_.size())
        {
            ScrollToLyric(index);
        }
    }

    void PlayerPage::SyncProgressSlider()
    {
        auto duration = controller_.engine().durationMs();
        auto position = controller_.engine().positionMs();
        displayedDurationMs_ = duration > 0 ? duration : 0;

        DurationText().Text(hstring{ FormatTime(duration) });
        PositionText().Text(hstring{ FormatTime(position) });

        // 用户拖动期间不抢进度条。
        if (isSeeking_) return;
        if (displayedDurationMs_ > 0)
        {
            double ratio = static_cast<double>(position) / static_cast<double>(displayedDurationMs_);
            ProgressSlider().Value(ratio);
        }
    }

    int64_t PlayerPage::CurrentLyricIndex(int64_t positionMs) const
    {
        auto const& lines = controller_.lyrics();
        if (lines.empty()) return -1;
        // 找到最后一条 timeMs <= position 的行。
        int64_t best = -1;
        for (size_t i = 0; i < lines.size(); ++i)
        {
            if (lines[i].timeMs <= positionMs) best = static_cast<int64_t>(i);
            else break;
        }
        return best;
    }

    void PlayerPage::ScrollToLyric(int64_t index)
    {
        const size_t n = lyricBlocks_.size();
        const size_t current = static_cast<size_t>(index);
        if (index == highlightedLyric_) return;
        highlightedLyric_ = index;

        for (size_t i = 0; i < n; ++i)
        {
            auto brush = SolidColorBrush{ Windows::UI::Color{ 255, 255, 255, 255 } };
            if (i == current) brush = SolidColorBrush{ Windows::UI::Color{ 255, 29, 185, 84 } };
            lyricBlocks_[i].Foreground(brush);
        }

        // 黄金分割定位：当前行滚到内容区约 36% 处。
        auto block = lyricBlocks_[current];
        auto transform = block.TransformToVisual(LyricsScroll() as UIElement);
        Point pos = transform.TransformPoint(Point{ 0, 0 });
        double viewHeight = LyricsScroll().ViewportHeight();
        double offset = pos.Y - viewHeight * 0.36;
        if (offset < 0) offset = 0;
        LyricsScroll().ChangeView(nullptr, offset, nullptr, true);
    }

    std::wstring PlayerPage::FormatTime(int64_t ms) const
    {
        if (ms <= 0) return L"0:00";
        int64_t total = ms / 1000;
        int64_t m = total / 60;
        int64_t s = total % 60;
        wchar_t buf[16];
        swprintf_s(buf, 16, L"%lld:%02lld", m, s);
        return buf;
    }
}
