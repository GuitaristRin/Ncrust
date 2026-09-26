using System;
using System.Globalization;
using System.Numerics;
using Kanesumi.Xaml.Motion;
using Ncrust.Core.Api;
using Ncrust.Playback;
using Windows.UI.Composition;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Hosting;
using Windows.UI.Xaml.Input;
using Windows.UI.Xaml.Media.Imaging;

namespace Ncrust.Player
{
    /// <summary>
    /// 播放器覆盖层。Progress / Fullscreen 两个标量驱动全部形变（见 windows/AGENTS.md「播放器层」）。
    /// </summary>
    public sealed partial class PlayerHost : UserControl
    {
        private const float MiniCover = 72f;
        private static readonly TimeSpan Duration = TimeSpan.FromMilliseconds(400);

        private Compositor _compositor;
        private CompositionPropertySet _props;
        private Visual _coverVisual;
        private Visual _miniBarVisual;

        private float _toCardScale = 1f;
        private float _toCardDx;
        private float _toCardDy;
        private float _toFullScale = 1f;
        private float _toFullDx;
        private float _toFullDy;

        private bool _fullscreen;
        private bool _expanded;

        public PlayerHost()
        {
            InitializeComponent();
            Loaded += OnLoaded;
            SizeChanged += (_, __) =>
            {
                try
                {
                    RebuildExpressions();
                }
                catch (Exception ex)
                {
                    App.WriteCrashLog(ex);
                }
            };
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            try
            {
                _compositor = ElementCompositionPreview.GetElementVisual(Root).Compositor;
                _props = _compositor.CreatePropertySet();
                _props.InsertScalar("Progress", 0f);
                _props.InsertScalar("Fullscreen", 0f);

                _coverVisual = ElementCompositionPreview.GetElementVisual(CoverImage);
                ElementCompositionPreview.SetIsTranslationEnabled(CoverImage, true);
                _coverVisual.CenterPoint = new Vector3(0f, 0f, 0f);

                _miniBarVisual = ElementCompositionPreview.GetElementVisual(MiniBar);

                var cardOpacity = _compositor.CreateExpressionAnimation("props.Progress");
                cardOpacity.SetReferenceParameter("props", _props);
                ElementCompositionPreview.GetElementVisual(CardLayer).StartAnimation("Opacity", cardOpacity);

                var miniOpacity = _compositor.CreateExpressionAnimation("1 - props.Fullscreen");
                miniOpacity.SetReferenceParameter("props", _props);
                _miniBarVisual.StartAnimation("Opacity", miniOpacity);

                RebuildExpressions();
            }
            catch (Exception ex)
            {
                // 合成初始化失败时降级：播放栏仍可用，只是没有形变动画。
                App.WriteCrashLog(ex);
            }

            var engine = PlaybackHost.Engine;
            engine.CurrentSongChanged += OnSongChanged;
            engine.IsPlayingChanged += OnIsPlayingChanged;
            engine.ProgressChanged += OnProgressChanged;
            OnSongChanged(AppServices.Queue.Current);
        }

        private void RebuildExpressions()
        {
            if (_compositor == null)
            {
                return;
            }

            var width = (float)Root.ActualWidth;
            var height = (float)Root.ActualHeight;
            if (width <= 0 || height <= 0)
            {
                return;
            }

            // 迷你封面布局在 Root 左下角：top-left = (0, height - 72)。
            var miniTop = height - MiniCover;

            var cardCover = Math.Min(width - 96f, height * 0.42f);
            var cardLeft = (width - cardCover) / 2f;
            var cardTop = 48f;

            var fullCover = Math.Min(width, height);
            var fullLeft = (width - fullCover) / 2f;
            var fullTop = 0f;

            _toCardScale = cardCover / MiniCover;
            _toCardDx = cardLeft;
            _toCardDy = cardTop - miniTop;

            _toFullScale = fullCover / MiniCover;
            _toFullDx = fullLeft;
            _toFullDy = fullTop - miniTop;

            // Scale 是 Vector3：表达式必须返回 Vector3，标量会抛
            // 「expression output does not match animating property type」。
            var scaleValue = Formattable(
                "1 + ({0} - 1) * props.Progress + ({1} - {2}) * props.Fullscreen",
                _toCardScale, _toFullScale, _toCardScale);
            var scale = _compositor.CreateExpressionAnimation("Vector3(" + scaleValue + ", " + scaleValue + ", 1)");
            scale.SetReferenceParameter("props", _props);
            _coverVisual.StartAnimation("Scale", scale);

            var translation = _compositor.CreateExpressionAnimation(
                Formattable(
                    "Vector3({0} * props.Progress + ({1} - {0}) * props.Fullscreen, {2} * props.Progress + ({3} - {2}) * props.Fullscreen, 0)",
                    _toCardDx, _toFullDx, _toCardDy, _toFullDy));
            translation.SetReferenceParameter("props", _props);
            _coverVisual.StartAnimation("Translation", translation);
        }

        private static string Formattable(string template, params float[] values)
        {
            var args = new object[values.Length];
            for (var i = 0; i < values.Length; i++)
            {
                args[i] = values[i].ToString("R", CultureInfo.InvariantCulture);
            }

            return string.Format(CultureInfo.InvariantCulture, template, args);
        }

        private void OnSongChanged(SongItem song)
        {
            if (song == null)
            {
                MiniTitle.Text = "未在播放";
                MiniArtist.Text = string.Empty;
                return;
            }

            MiniTitle.Text = song.Name;
            MiniArtist.Text = song.ArtistText;
            CardTitle.Text = song.Name;
            CardArtist.Text = song.ArtistText;
            SetCover(song.CoverUrl);
        }

        private void SetCover(string url)
        {
            if (string.IsNullOrEmpty(url))
            {
                return;
            }

            // 新封面解码完成后再替换，换歌期间旧封面保留（对应 COVER_HOLD_MS 的观感）。
            var bitmap = new BitmapImage(new Uri(url));
            bitmap.ImageOpened += (_, __) => CoverImage.Source = bitmap;
        }

        private void OnIsPlayingChanged(bool playing)
        {
            var glyph = playing ? "\uE769" : "\uE768";
            MiniPlayIcon.Glyph = glyph;
            CardPlayIcon.Glyph = glyph;
        }

        private void OnProgressChanged(long positionMs, long durationMs)
        {
            MiniProgress.Value = durationMs > 0 ? (double)positionMs / durationMs : 0;
        }

        private void Expand()
        {
            _expanded = true;
            CardLayer.IsHitTestVisible = true;
            AnimateProgress(1f, KanesumiMotion.PlayerExpand, KanesumiEasing.Standard(_compositor));
        }

        private void Collapse()
        {
            _expanded = false;
            _fullscreen = false;
            CardLayer.IsHitTestVisible = false;
            AnimateScalar("Fullscreen", 0f, KanesumiMotion.PlayerCollapse, KanesumiEasing.FastOutSlowIn(_compositor));
            AnimateProgress(0f, KanesumiMotion.PlayerCollapse, KanesumiEasing.FastOutSlowIn(_compositor));
        }

        /// <summary>Esc 逐层退出：全屏 → 回卡片；卡片 → 收起。返回是否处理了。</summary>
        public bool HandleEscape()
        {
            if (_fullscreen)
            {
                _fullscreen = false;
                AnimateScalar("Fullscreen", 0f, KanesumiMotion.PlayerCollapse, KanesumiEasing.FastOutSlowIn(_compositor));
                return true;
            }

            if (_expanded)
            {
                Collapse();
                return true;
            }

            return false;
        }

        private void AnimateProgress(float to, TimeSpan duration, CompositionEasingFunction easing) =>
            AnimateScalar("Progress", to, duration, easing);

        private void AnimateScalar(string property, float to, TimeSpan duration, CompositionEasingFunction easing)
        {
            var animation = _compositor.CreateScalarKeyFrameAnimation();
            animation.InsertKeyFrame(1f, to, easing);
            animation.Duration = duration;
            _props.StartAnimation(property, animation);
        }

        private void ExpandClick(object sender, RoutedEventArgs e)
        {
            if (_expanded)
            {
                Collapse();
            }
            else
            {
                Expand();
            }
        }

        private void CoverTapped(object sender, TappedRoutedEventArgs e)
        {
            if (!_expanded)
            {
                Expand();
            }
        }

        /// <summary>卡片 ↔ 真全屏切换（F11 与卡片里的专用按钮都走这里）。</summary>
        public void ToggleFullscreen()
        {
            if (!_expanded)
            {
                Expand();
            }

            _fullscreen = !_fullscreen;
            AnimateScalar(
                "Fullscreen",
                _fullscreen ? 1f : 0f,
                _fullscreen ? KanesumiMotion.PlayerExpand : KanesumiMotion.PlayerCollapse,
                _fullscreen ? KanesumiEasing.Standard(_compositor) : KanesumiEasing.FastOutSlowIn(_compositor));
        }

        private void FullscreenClick(object sender, RoutedEventArgs e) => ToggleFullscreen();

        private void PlayPauseClick(object sender, RoutedEventArgs e) => PlaybackHost.Engine.PlayPause();

        private void NextClick(object sender, RoutedEventArgs e) => PlaybackHost.Engine.Next();

        private void PreviousClick(object sender, RoutedEventArgs e) => PlaybackHost.Engine.Previous();
    }
}
