using System;
using System.Collections.Generic;
using System.Linq;
using System.Threading.Tasks;
using Ncrust.Core.Api;
using Ncrust.Playback;
using Ncrust.Shell;
using Windows.ApplicationModel.DataTransfer;
using Windows.Foundation;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Controls.Primitives;
using Windows.UI.Xaml.Input;
using Windows.UI.Xaml.Media;

namespace Ncrust.Pages
{
    /// <summary>
    /// 歌曲菜单（对应 Android SongMenuSheet + 各页面传入的 SongMenuAction）。桌面上是右键 / 触屏长按 /
    /// Shift+F10 / 菜单键弹出的 <see cref="MenuFlyout"/>。
    ///
    /// 项目与 Android 一致：播放、插播、最后播放、加入库 / 移除收藏，统一追加转到歌手、转到专辑、
    /// 分享（桌面上是复制链接，文本格式同 Android shareSong）。页面可再追加自己的项（音乐库的「重试同步」等）。
    /// </summary>
    internal static class SongActions
    {
        /// <summary>收藏（红心）变化后触发：音乐库页与播放器的收藏按钮据此刷新。</summary>
        public static event Action LibraryChanged;

        /// <summary>
        /// 给歌曲列表挂上下文菜单。列表项的 DataContext 必须是 <see cref="SongItem"/>。
        /// <paramref name="extra"/> 返回页面自己的附加项（插在「分享」一组之前），可为 null。
        /// </summary>
        public static void AttachContextMenu(ListViewBase list, Func<SongItem, IEnumerable<MenuFlyoutItemBase>> extra = null)
        {
            list.ContextRequested += (sender, args) =>
            {
                var song = FindSong(args.OriginalSource as DependencyObject);
                if (song == null)
                {
                    return;
                }

                args.Handled = true;
                var menu = BuildMenu(song, extra?.Invoke(song));
                var target = (FrameworkElement)sender;
                if (args.TryGetPosition(target, out var point))
                {
                    menu.ShowAt(target, point);
                }
                else
                {
                    // 键盘（Shift+F10 / 菜单键）没有指针位置：贴着获得焦点的那一行弹出。
                    menu.ShowAt(args.OriginalSource as FrameworkElement ?? target);
                }
            };
        }

        public static MenuFlyout BuildMenu(SongItem song, IEnumerable<MenuFlyoutItemBase> extra = null)
        {
            var menu = new MenuFlyout();
            menu.Items.Add(Item("播放", Glyphs.Play, () => PlaybackHost.PlaySong(song)));
            menu.Items.Add(Item("插播", Glyphs.PlayNext, () =>
            {
                PlaybackHost.InsertNext(song);
                AppShell.Notice("将在当前曲目之后播放");
            }));
            menu.Items.Add(Item("最后播放", Glyphs.Add, () =>
            {
                PlaybackHost.Append(song);
                AppShell.Notice("已加入播放队列末尾");
            }));

            var saved = AppServices.Library.IsSongSaved(song.Id);
            menu.Items.Add(saved
                ? Item("移除收藏", Glyphs.HeartFill, () => _ = SetSavedAsync(song, false))
                : Item("加入库", Glyphs.Heart, () => _ = SetSavedAsync(song, true)));

            if (extra != null)
            {
                foreach (var item in extra)
                {
                    menu.Items.Add(item);
                }
            }

            menu.Items.Add(new MenuFlyoutSeparator());
            menu.Items.Add(Item("转到歌手", Glyphs.Artist, () => _ = GoToArtistAsync(song)));
            menu.Items.Add(Item("转到专辑", Glyphs.Album, () => _ = GoToAlbumAsync(song)));
            menu.Items.Add(Item("复制链接", Glyphs.Link, () => CopyLink(song)));
            return menu;
        }

        public static MenuFlyoutItem Item(string text, string glyph, Action onClick)
        {
            var item = new MenuFlyoutItem
            {
                Text = text,
                Icon = new FontIcon { FontFamily = new FontFamily("Segoe MDL2 Assets"), Glyph = glyph },
            };
            item.Click += (_, __) => onClick();
            return item;
        }

        /// <summary>加入库（红心）/ 移除收藏。本地立即生效，云端异步同步；失败留在待同步表（音乐库页可重试）。</summary>
        public static async Task SetSavedAsync(SongItem song, bool saved)
        {
            try
            {
                var task = saved
                    ? AppServices.Library.SaveSongAsync(song)
                    : AppServices.Library.RemoveSongAsync(song.Id);

                // 本地列表在第一个 await 之前就改好了：先刷新界面，不等云端往返。
                LibraryChanged?.Invoke();
                AppShell.Notice(saved ? "已加入库" : "已从库中移除");
                await task;
            }
            catch (Exception ex)
            {
                // 云端同步失败不影响本地收藏，待同步表里会记着。
                App.WriteCrashLog(ex);
            }

            LibraryChanged?.Invoke();
        }

        public static void NotifyLibraryChanged() => LibraryChanged?.Invoke();

        /// <summary>
        /// 转到歌手 / 专辑（对应 Android resolveAndNavigate）：列表接口大多不带歌手 / 专辑 id，
        /// 缺失时先用 song/detail 现拉；拉不到就提示，不给死链接。
        /// </summary>
        public static async Task GoToArtistAsync(SongItem song)
        {
            var id = song.Artists.FirstOrDefault()?.Id;
            if (id == null)
            {
                id = (await ResolveAsync(song))?.Artists.FirstOrDefault()?.Id;
            }

            if (id.HasValue)
            {
                AppShell.ToArtist(id.Value);
            }
            else
            {
                AppShell.Notice("没有找到这首歌的歌手信息");
            }
        }

        public static async Task GoToAlbumAsync(SongItem song)
        {
            var id = song.Album?.Id;
            if (id == null)
            {
                id = (await ResolveAsync(song))?.Album?.Id;
            }

            if (id.HasValue)
            {
                AppShell.ToAlbum(id.Value);
            }
            else
            {
                AppShell.Notice("没有找到这首歌的专辑信息");
            }
        }

        public static void CopyLink(SongItem song)
        {
            var text = song.Name;
            if (!string.IsNullOrEmpty(song.ArtistText))
            {
                text += " - " + song.ArtistText;
            }

            text += "\n" + SongUrl(song.Id);

            var package = new DataPackage();
            package.SetText(text);
            Clipboard.SetContent(package);
            AppShell.Notice("链接已复制");
        }

        public static string SongUrl(long id) => "https://music.163.com/song?id=" + id;

        private static async Task<SongItem> ResolveAsync(SongItem song)
        {
            try
            {
                var detail = await AppServices.Songs.GetSongDetailAsync(new[] { song.Id });
                return detail.FirstOrDefault();
            }
            catch
            {
                return null;
            }
        }

        private static SongItem FindSong(DependencyObject source)
        {
            // 从命中的元素往上找，直到遇到数据上下文是歌曲的那一层（行模板根或 ListViewItem）。
            for (var node = source; node != null; node = VisualTreeHelper.GetParent(node))
            {
                if (node is FrameworkElement element && element.DataContext is SongItem song)
                {
                    return song;
                }

                if (node is ListViewBase)
                {
                    break;
                }
            }

            return null;
        }
    }

    /// <summary>
    /// Segoe MDL2 Assets 码点。用 (char) 构造而不写 \u 转义：编辑工具会把 \u 转义直接写成
    /// 看不见的私用区字符（见 windows/AGENTS.md「已知的坑」）。
    /// </summary>
    internal static class Glyphs
    {
        public static readonly string Play = G(0xE768);
        public static readonly string Pause = G(0xE769);
        public static readonly string PlayNext = G(0xE893);
        public static readonly string Add = G(0xE710);
        public static readonly string Heart = G(0xEB51);
        public static readonly string HeartFill = G(0xEB52);
        public static readonly string Artist = G(0xE77B);
        public static readonly string Album = G(0xE93C);
        public static readonly string Link = G(0xE71B);
        public static readonly string Delete = G(0xE74D);
        public static readonly string Sync = G(0xE895);
        public static readonly string Radio = G(0xE8D6);
        public static readonly string List = G(0xE8FD);
        public static readonly string Lyrics = G(0xE8D2);
        public static readonly string Clear = G(0xE894);

        private static string G(int codePoint) => ((char)codePoint).ToString();
    }
}
