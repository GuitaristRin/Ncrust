using System;
using System.Collections.Generic;
using Ncrust.Core.Api;
using Ncrust.Playback;
using Ncrust.Resources;
using Ncrust.Shell;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;

namespace Ncrust.Pages
{
    /// <summary>详情页页头：封面、标题、信息行与「全部播放 / 插播 / 最后播放」。歌曲列表由页面通过 <see cref="Songs"/> 提供。</summary>
    public sealed partial class DetailHeader : UserControl
    {
        private string _coverUrl;

        public DetailHeader()
        {
            InitializeComponent();
        }

        /// <summary>副标题被点击（专辑页：转到歌手）。</summary>
        public event Action SubtitleInvoked;

        /// <summary>操作按钮作用的歌曲；为空时按钮禁用。</summary>
        internal IReadOnlyList<SongItem> Songs { get; private set; } = Array.Empty<SongItem>();

        /// <summary>页面追加的操作按钮（放在「最后播放」之后）。</summary>
        public void AddAction(UIElement element) => Actions.Children.Add(element);

        public void SetContent(string kind, string title, string subtitle, bool subtitleClickable, string info, string coverUrl)
        {
            KindText.Text = kind ?? string.Empty;
            TitleText.Text = title ?? string.Empty;

            var hasSubtitle = !string.IsNullOrEmpty(subtitle);
            SubtitleLinkText.Text = subtitle ?? string.Empty;
            SubtitleText.Text = subtitle ?? string.Empty;
            SubtitleLink.Visibility = hasSubtitle && subtitleClickable ? Visibility.Visible : Visibility.Collapsed;
            SubtitleText.Visibility = hasSubtitle && !subtitleClickable ? Visibility.Visible : Visibility.Collapsed;

            InfoText.Text = info ?? string.Empty;
            InfoText.Visibility = string.IsNullOrEmpty(info) ? Visibility.Collapsed : Visibility.Visible;

            // 同一张封面不重新解码（后台刷新写回时常见）。
            if (!string.IsNullOrEmpty(coverUrl) && coverUrl != _coverUrl)
            {
                _coverUrl = coverUrl;
                CoverImage.Source = DisplayFormat.Cover(coverUrl, 400);
            }
        }

        internal void SetSongs(IReadOnlyList<SongItem> songs)
        {
            Songs = songs ?? Array.Empty<SongItem>();
            var any = Songs.Count > 0;
            PlayAllButton.IsEnabled = any;
            InsertNextButton.IsEnabled = any;
            AppendButton.IsEnabled = any;
        }

        private void SubtitleClick(object sender, RoutedEventArgs e) => SubtitleInvoked?.Invoke();

        private void PlayAllClick(object sender, RoutedEventArgs e) => PlaybackHost.PlayAll(Songs);

        private void InsertNextClick(object sender, RoutedEventArgs e)
        {
            PlaybackHost.InsertAllNext(Songs);
            AppShell.Notice(DisplayFormat.SongCount(Songs.Count) + "将在当前曲目之后播放");
        }

        private void AppendClick(object sender, RoutedEventArgs e)
        {
            PlaybackHost.AppendAll(Songs);
            AppShell.Notice(DisplayFormat.SongCount(Songs.Count) + "已加入播放队列末尾");
        }
    }
}
