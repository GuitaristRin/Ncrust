using System;
using System.Collections.ObjectModel;
using System.Linq;
using Ncrust.Core.Api;
using Ncrust.Core.Playback;
using Ncrust.Pages;
using Ncrust.Playback;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;

namespace Ncrust.Player
{
    /// <summary>队列视图里的一行：分区标题、歌曲，或 INFINITY 的续播占位。</summary>
    public sealed class QueueRow
    {
        public QueueRowKind Kind { get; set; }

        public string Title { get; set; } = string.Empty;

        public SongItem Song { get; set; }

        /// <summary>歌曲在队列里的索引（非歌曲行为 -1）。</summary>
        public int QueueIndex { get; set; } = -1;

        public bool IsCurrent { get; set; }

        public Visibility CurrentMarkVisibility => IsCurrent ? Visibility.Visible : Visibility.Collapsed;
    }

    public enum QueueRowKind
    {
        Header,
        Song,
        Placeholder,
    }

    /// <summary>按行类型选模板（标题 / 歌曲 / 占位）。模板在 Resources/Templates.xaml。</summary>
    public sealed class QueueRowTemplateSelector : DataTemplateSelector
    {
        public DataTemplate HeaderTemplate { get; set; }

        public DataTemplate SongTemplate { get; set; }

        public DataTemplate PlaceholderTemplate { get; set; }

        protected override DataTemplate SelectTemplateCore(object item, DependencyObject container) => SelectTemplateCore(item);

        protected override DataTemplate SelectTemplateCore(object item)
        {
            switch ((item as QueueRow)?.Kind)
            {
                case QueueRowKind.Header:
                    return HeaderTemplate;
                case QueueRowKind.Placeholder:
                    return PlaceholderTemplate;
                default:
                    return SongTemplate;
            }
        }
    }

    /// <summary>
    /// 播放队列视图（对应 Android QueueView）：三个分区 —— 过去播放 / 现在播放 / 将要播放；
    /// INFINITY 模式在末尾放「相似歌曲即将续播」占位。点一首切过去播放；行尾 ✕ 移除；
    /// 只允许在「将要播放」区里拖动排序（拖到别的分区视为取消，列表按队列重建回原样）。
    ///
    /// 队列变化时只标脏，视图可见时才重建（播放中每换一首都会变，没必要在看不见时重建）。
    /// </summary>
    internal sealed class QueuePresenter
    {
        private readonly ListView _list;
        private readonly TextBlock _emptyText;
        private readonly ObservableCollection<QueueRow> _rows = new ObservableCollection<QueueRow>();

        private bool _dirty = true;
        private bool _visible;
        private QueueRow _dragged;

        public QueuePresenter(ListView list, TextBlock emptyText)
        {
            _list = list;
            _emptyText = emptyText;
            _list.ItemsSource = _rows;
            _list.ItemClick += OnItemClick;
            _list.DragItemsStarting += OnDragItemsStarting;
            _list.DragItemsCompleted += OnDragItemsCompleted;
            SongActions.AttachContextMenu(_list, song => new MenuFlyoutItemBase[]
            {
                SongActions.Item("从队列移除", Glyphs.Delete, () => Remove(song)),
            });
        }

        /// <summary>视图可见性（卡片展开且队列页选中）。变为可见时按需重建并定位到当前曲。</summary>
        public bool Visible
        {
            get => _visible;
            set
            {
                if (_visible == value)
                {
                    return;
                }

                _visible = value;
                if (_visible)
                {
                    RebuildIfDirty();
                    ScrollToCurrent();
                }
            }
        }

        public void MarkDirty()
        {
            _dirty = true;
            if (_visible)
            {
                RebuildIfDirty();
            }
        }

        /// <summary>行尾 ✕：按队列索引移除（模板里的按钮通过 Templates 代码隐藏转到这里）。</summary>
        public static void RemoveAt(int queueIndex) => PlaybackHost.RemoveAt(queueIndex);

        public void Clear()
        {
            PlaybackHost.Clear();
        }

        private void Remove(SongItem song)
        {
            var songs = AppServices.Queue.Songs;
            for (var i = 0; i < songs.Count; i++)
            {
                if (songs[i].Id == song.Id)
                {
                    PlaybackHost.RemoveAt(i);
                    return;
                }
            }
        }

        private void RebuildIfDirty()
        {
            if (!_dirty)
            {
                return;
            }

            _dirty = false;
            var queue = AppServices.Queue;
            var songs = queue.Songs;
            var current = queue.CurrentIndex;

            _rows.Clear();
            if (current > 0)
            {
                _rows.Add(Header("过去播放"));
                for (var i = 0; i < current; i++)
                {
                    _rows.Add(SongRow(songs[i], i, false));
                }
            }

            if (current >= 0 && current < songs.Count)
            {
                _rows.Add(Header("现在播放"));
                _rows.Add(SongRow(songs[current], current, true));
            }

            if (current + 1 < songs.Count || queue.Mode == PlaybackMode.Infinity)
            {
                _rows.Add(Header("将要播放"));
                for (var i = Math.Max(0, current + 1); i < songs.Count; i++)
                {
                    _rows.Add(SongRow(songs[i], i, false));
                }

                if (queue.Mode == PlaybackMode.Infinity)
                {
                    _rows.Add(new QueueRow { Kind = QueueRowKind.Placeholder, Title = "相似歌曲即将续播" });
                }
            }

            _emptyText.Visibility = songs.Count == 0 ? Visibility.Visible : Visibility.Collapsed;
        }

        private static QueueRow Header(string title) => new QueueRow { Kind = QueueRowKind.Header, Title = title };

        private static QueueRow SongRow(SongItem song, int index, bool isCurrent) =>
            new QueueRow { Kind = QueueRowKind.Song, Song = song, QueueIndex = index, IsCurrent = isCurrent };

        /// <summary>打开时自动定位：把「现在播放」标题滚到顶部附近（对应 Android 打开队列时居中当前曲）。</summary>
        private void ScrollToCurrent()
        {
            var header = _rows.FirstOrDefault(row => row.Kind == QueueRowKind.Header && row.Title == "现在播放");
            if (header != null)
            {
                _list.ScrollIntoView(header, ScrollIntoViewAlignment.Leading);
            }
        }

        private void OnItemClick(object sender, ItemClickEventArgs e)
        {
            if (e.ClickedItem is QueueRow row && row.Kind == QueueRowKind.Song && !row.IsCurrent)
            {
                PlaybackHost.PlayAt(row.QueueIndex);
            }
        }

        private void OnDragItemsStarting(object sender, DragItemsStartingEventArgs e)
        {
            // 只有「将要播放」区的歌能拖。
            var row = e.Items.FirstOrDefault() as QueueRow;
            if (row == null || row.Kind != QueueRowKind.Song || row.QueueIndex <= AppServices.Queue.CurrentIndex)
            {
                e.Cancel = true;
                return;
            }

            _dragged = row;
        }

        private void OnDragItemsCompleted(ListViewBase sender, DragItemsCompletedEventArgs args)
        {
            var dragged = _dragged;
            _dragged = null;
            if (dragged == null)
            {
                return;
            }

            // ListView 已经在集合里挪好了位置：由它在「将要播放」区里的新位置推出目标队列索引。
            var position = _rows.IndexOf(dragged);
            var upcomingHeader = -1;
            for (var i = 0; i < _rows.Count; i++)
            {
                if (_rows[i].Kind == QueueRowKind.Header && _rows[i].Title == "将要播放")
                {
                    upcomingHeader = i;
                    break;
                }
            }

            _dirty = true;
            if (position <= upcomingHeader || upcomingHeader < 0)
            {
                RebuildIfDirty(); // 拖出了「将要播放」区：视为取消，按队列重建回原样。
                return;
            }

            var target = AppServices.Queue.CurrentIndex + (position - upcomingHeader);
            target = Math.Min(target, AppServices.Queue.Songs.Count - 1);
            if (target != dragged.QueueIndex)
            {
                PlaybackHost.Move(dragged.QueueIndex, target);
            }
            else
            {
                RebuildIfDirty();
            }
        }
    }
}
