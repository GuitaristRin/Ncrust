using System;
using Ncrust.Core.Api;
using Ncrust.Pages;

namespace Ncrust.Shell
{
    /// <summary>
    /// 页面与外壳之间的窄接口：导航到详情页、弹出一条轻提示（对应 Android 的 Toast）。
    /// 由 <see cref="ShellPage"/> 在构造时注册实现；外壳还没建好时调用直接忽略。只在 UI 线程上使用。
    /// </summary>
    internal static class AppShell
    {
        internal static Action<Type, object> NavigateHandler { get; set; }

        internal static Action<string> NoticeHandler { get; set; }

        public static void Navigate(Type page, object parameter = null) => NavigateHandler?.Invoke(page, parameter);

        public static void ToAlbum(long albumId)
        {
            if (albumId > 0)
            {
                Navigate(typeof(AlbumPage), albumId);
            }
        }

        public static void ToArtist(long artistId)
        {
            if (artistId > 0)
            {
                Navigate(typeof(ArtistPage), artistId);
            }
        }

        /// <summary>歌单详情。名称与封面随参数带过去，进页面立即有页头（对应 Android NavRoutes.playlist 的参数）。</summary>
        public static void ToPlaylist(PlaylistCard playlist)
        {
            if (playlist != null && playlist.Id > 0)
            {
                Navigate(typeof(PlaylistPage), playlist);
            }
        }

        /// <summary>底部轻提示，约 2 秒后自动消失。</summary>
        public static void Notice(string text) => NoticeHandler?.Invoke(text);
    }
}
