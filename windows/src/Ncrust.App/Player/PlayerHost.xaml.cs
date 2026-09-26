using System;
using System.Globalization;
using System.Numerics;
using Kanesumi.Xaml.Motion;
using Ncrust.Core.Api;
using Ncrust.Core.Playback;
using Ncrust.Playback;
using Windows.UI.Composition;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Controls.Primitives;
using Windows.UI.Xaml.Hosting;
using Windows.UI.Xaml.Input;
using Windows.UI.Xaml.Media.Imaging;

namespace Ncrust.Player
{
    /// <summary>
    /// 播放器覆盖层。Progress / Fullscreen 两个标量驱动全部形变（见 windows/AGENTS.md「播放器层」）。
    /// 底部是 Groove 式传输栏：模式 / 上一首 / 播放 / 下一首、可拖动进度条、音量、歌词、展开。
    /// </summary>
    public sealed partial class PlayerHost : UserControl
    {
        private const float MiniCover = 72f;
        private const float BarHeight = 72f;
        private const string VolumeKey = "volume";
        private const string PlayModeKey = "play_mode";

        /// <summary>模式按钮的切换顺序。INFINITY（FM / 相似歌曲续播）落地后再加入。</summary>
        private static readonly PlaybackMode[] ModeCycle =
        {
            PlaybackMode.Cycle, PlaybackMode.Single, PlaybackMode.Shuffle, PlaybackMode.Line,
        };

        private Compositor _compositor;
        private CompositionPropertySet _props;
        private Visual _coverVisual;

        private bool _initialized;
        private bool _fullscreen;
        private bool _expanded;

        private bool _seekDragging;
        private bool _seekProgrammatic;
        private bool _volumeProgrammatic;

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

            // 拖动进度条时只在松手后跳转，避免拖动过程中反复 seek 让流媒体卡顿。
            // Slider 自己会处理指针事件，所以要用 handledEventsToo 才收得到。
            SeekSlider.AddHandler(PointerPressedEvent, new PointerEventHandler((_, __) => _seekDragging = true), true);
            SeekSlider.AddHandler(PointerReleasedEvent, new PointerEventHandler((_, __) => EndSeekDrag()), true);
            SeekSlider.AddHandler(PointerCaptureLostEvent, new PointerEventHandler((_, __) => EndSeekDrag()), true);
        }

        private void OnLoaded(object sender, RoutedEventArgs e)
        {
            // Loaded 在元素重新入树时会再次触发；合成与事件订阅只做一次。
            if (_initialized)
            {
                return;
            }

            _initialized = true;

            try
            {
                _compositor = ElementCompositionPreview.GetElementVisual(Root).Compositor;
                _props = _compositor.CreatePropertySet();
                _props.InsertScalar("Progress", 0f);
                _props.InsertScalar("Fullscreen", 0f);

                _coverVisual = ElementCompositionPreview.GetElementVisual(CoverImage);
                ElementCompositionPreview.SetIsTranslationEnabled(CoverImage, true);
                _coverVisual.CenterPoint = new Vector3(0f, 0f, 0f);

                var cardOpacity = _compositor.CreateExpressionAnimation("props.Progress");
                cardOpacity.SetReferenceParameter("props", _props);
                ElementCompositionPreview.GetElementVisual(CardLayer).StartAnimation("Opacity", cardOpacity);

                var barOpacity = _compositor.CreateExpressionAnimation("1 - props.Fullscreen");
                barOpacity.SetReferenceParameter("props", _props);
                ElementCompositionPreview.GetElementVisual(MiniBar).StartAnimation("Opacity", barOpacity);

                RebuildExpressions();
            }
            catch (Exception ex)
            {
                // 合成初始化失败时降级：播放栏仍可用，只是没有形变动画与卡片
                // （卡片层的 XAML 透明度是 1，没有合成表达式压住它就会整块盖住界面）。
                App.WriteCrashLog(ex);
                _compositor = null;
                CardLayer.Visibility = Visibility.Collapsed;
            }

            var engine = PlaybackHost.Engine;
            engine.CurrentSongChanged += OnSongChanged;
            engine.IsPlayingChanged += OnIsPlayingChanged;
            engine.ProgressChanged += OnProgressChanged;
            engine.ModeChanged += UpdateModeButton;

            RestorePreferences(engine);
            OnSongChanged(AppServices.Queue.Current);
            OnIsPlayingChanged(engine.IsPlaying);
        }

        private void RestorePreferences(PlaybackEngine engine)
        {
            var volume = AppServices.Settings.GetInt(VolumeKey, 100);
            engine.Volume = volume / 100.0;
            SetVolumeSlider(volume);
            UpdateVolumeIcons();

            var mode = (PlaybackMode)AppServices.Settings.GetInt(PlayModeKey, (int)PlaybackMode.Cycle);
            if (Array.IndexOf(ModeCycle, mode) < 0)
            {
                mode = PlaybackMode.Cycle;
            }

            if (engine.Mode != mode)
            {
                engine.SetMode(mode);
            }

            UpdateModeButton(engine.Mode);
        }

        // ── 形变 ──────────────────────────────────────────────────────────────

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

            // 卡片：封面放在左栏（右栏留给歌词 / 队列），位于曲目信息与传输栏之上；窄窗口占满宽度。
            const float top = 48f;
            const float infoBlock = 120f;
            var columnWidth = width >= 600f ? width / 2f : width;
            var availableHeight = Math.Max(MiniCover, height - BarHeight - infoBlock - top);
            var cardCover = Math.Max(MiniCover, Math.Min(columnWidth - 96f, availableHeight));
            var cardLeft = (columnWidth - cardCover) / 2f;
            var cardTop = top + (availableHeight - cardCover) / 2f;

            // 真全屏：封面按窗口短边铺满、居中。
            var fullCover = Math.Min(width, height);
            var fullLeft = (width - fullCover) / 2f;
            var fullTop = (height - fullCover) / 2f;

            // 封面元素按最大尺寸（真全屏）布局，贴 Root 左下：top-left = (0, height - base)。
            // 三个状态都是「缩小 + 平移」：按最大尺寸渲染再缩小，任何状态下都清晰。
            var baseSize = Math.Max(MiniCover, fullCover);
            if (Math.Abs(CoverImage.Width - baseSize) > 0.5)
            {
                CoverImage.Width = baseSize;
                CoverImage.Height = baseSize;
            }

            var baseTop = height - baseSize;
            var miniScale = MiniCover / baseSize;
            var cardScale = cardCover / baseSize;
            const float fullScale = 1f;
            var miniDy = (height - MiniCover) - baseTop;
            var cardDy = cardTop - baseTop;
            var fullDy = fullTop - baseTop;

            // 值 = 迷你 + (卡片 - 迷你) * Progress + (全屏 - 卡片) * Fullscreen。
            // Scale 是 Vector3：表达式必须返回 Vector3，标量会抛
            // 「expression output does not match animating property type」。
            var scaleValue = Formattable(
                "{0} + ({1} - {0}) * props.Progress + ({2} - {1}) * props.Fullscreen",
                miniScale, cardScale, fullScale);
            var scale = _compositor.CreateExpressionAnimation("Vector3(" + scaleValue + ", " + scaleValue + ", 1)");
            scale.SetReferenceParameter("props", _props);
            _coverVisual.StartAnimation("Scale", scale);

            var translation = _compositor.CreateExpressionAnimation(
                Formattable(
                    "Vector3({0} * props.Progress + ({1} - {0}) * props.Fullscreen, {2} + ({3} - {2}) * props.Progress + ({4} - {3}) * props.Fullscreen, 0)",
                    cardLeft, fullLeft, miniDy, cardDy, fullDy));
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

        // ── 引擎回调（均在 UI 线程） ────────────────────────────────────────────

        private void OnSongChanged(SongItem song)
        {
            if (song == null)
            {
                MiniTitle.Text = "未在播放";
                MiniArtist.Text = string.Empty;
                CardTitle.Text = string.Empty;
                CardArtist.Text = string.Empty;
                return;
            }

            MiniTitle.Text = song.Name;
            MiniArtist.Text = song.ArtistText;
            CardTitle.Text = song.Name;
            CardArtist.Text = song.ArtistText;
            SetCover(song.CoverUrl);
            OnProgressChanged(0, song.Duration);
        }

        private void SetCover(string url)
        {
            // 大封面（全屏时铺满窗口）；空串或非法 URL 时保留旧封面，不抛异常。
            var sized = CoverUrls.Large(url);
            if (sized == null || !Uri.TryCreate(sized, UriKind.Absolute, out var uri))
            {
                return;
            }

            // 新封面解码完成后再替换，换歌期间旧封面保留（对应 COVER_HOLD_MS 的观感）。
            // 必须挂到树里的预加载器上才会开始下载；只 new 出来等 ImageOpened 永远等不到。
            var bitmap = new BitmapImage(uri);
            bitmap.ImageOpened += (_, __) =>
            {
                // 连续换歌时只采用最后一次请求的封面。
                if (ReferenceEquals(CoverPreloader.Source, bitmap))
                {
                    CoverImage.Source = bitmap;
                }
            };
            CoverPreloader.Source = bitmap;
        }

        private void OnIsPlayingChanged(bool playing)
        {
            MiniPlayIcon.Glyph = playing ? "\uE769" : "\uE768";
        }

        private void OnProgressChanged(long positionMs, long durationMs)
        {
            var fraction = durationMs > 0 ? (double)positionMs / durationMs : 0;
            SlimProgress.Value = fraction;
            DurationText.Text = FormatTime(durationMs);

            if (_seekDragging)
            {
                return; // 拖动中：进度条跟手，不被 2Hz 的播放进度拉回去。
            }

            PositionText.Text = FormatTime(positionMs);
            _seekProgrammatic = true;
            SeekSlider.Maximum = Math.Max(1, durationMs / 1000.0);
            SeekSlider.Value = Math.Min(SeekSlider.Maximum, positionMs / 1000.0);
            _seekProgrammatic = false;
        }

        private static string FormatTime(long ms)
        {
            if (ms <= 0)
            {
                return "0:00";
            }

            var total = (long)(ms / 1000);
            return (total / 60).ToString(CultureInfo.InvariantCulture) + ":" +
                   (total % 60).ToString("00", CultureInfo.InvariantCulture);
        }

        // ── 进度条 ────────────────────────────────────────────────────────────

        private void SeekValueChanged(object sender, RangeBaseValueChangedEventArgs e)
        {
            if (_seekProgrammatic)
            {
                return;
            }

            PositionText.Text = FormatTime((long)(e.NewValue * 1000));

            // 键盘方向键 / 无拖动的点击：立即跳转；拖动中等松手。
            if (!_seekDragging)
            {
                PlaybackHost.Engine.Seek((long)(e.NewValue * 1000));
            }
        }

        private void EndSeekDrag()
        {
            if (!_seekDragging)
            {
                return;
            }

            _seekDragging = false;
            PlaybackHost.Engine.Seek((long)(SeekSlider.Value * 1000));
        }

        // ── 模式 ──────────────────────────────────────────────────────────────

        private void ModeClick(object sender, RoutedEventArgs e)
        {
            var engine = PlaybackHost.Engine;
            var index = Array.IndexOf(ModeCycle, engine.Mode);
            var next = ModeCycle[(index + 1) % ModeCycle.Length];
            engine.SetMode(next);
            AppServices.Settings.SetInt(PlayModeKey, (int)next);
        }

        private void UpdateModeButton(PlaybackMode mode)
        {
            string glyph;
            string tip;
            switch (mode)
            {
                case PlaybackMode.Single:
                    glyph = "\uE8ED";
                    tip = "单曲循环";
                    break;
                case PlaybackMode.Shuffle:
                    glyph = "\uE8B1";
                    tip = "随机播放";
                    break;
                case PlaybackMode.Line:
                    glyph = "\uE72A";
                    tip = "顺序播放（播完停止）";
                    break;
                case PlaybackMode.Infinity:
                    glyph = "\uE895";
                    tip = "无限续播";
                    break;
                default:
                    glyph = "\uE8EE";
                    tip = "列表循环";
                    break;
            }

            ModeIcon.Glyph = glyph;
            ToolTipService.SetToolTip(ModeButton, tip);
        }

        // ── 音量 ──────────────────────────────────────────────────────────────

        private void VolumeValueChanged(object sender, RangeBaseValueChangedEventArgs e)
        {
            if (_volumeProgrammatic)
            {
                return;
            }

            var engine = PlaybackHost.Engine;
            engine.Volume = e.NewValue / 100.0;
            if (engine.IsMuted && e.NewValue > 0)
            {
                engine.IsMuted = false;
            }

            AppServices.Settings.SetInt(VolumeKey, (int)Math.Round(e.NewValue));
            UpdateVolumeIcons();
        }

        private void MuteClick(object sender, RoutedEventArgs e)
        {
            var engine = PlaybackHost.Engine;
            engine.IsMuted = !engine.IsMuted;
            UpdateVolumeIcons();
        }

        private void SetVolumeSlider(int volume)
        {
            _volumeProgrammatic = true;
            VolumeSlider.Value = volume;
            _volumeProgrammatic = false;
        }

        private void UpdateVolumeIcons()
        {
            var engine = PlaybackHost.Engine;
            var percent = (int)Math.Round(engine.Volume * 100);
            string glyph;
            if (engine.IsMuted || percent == 0)
            {
                glyph = "\uE74F";
            }
            else if (percent < 34)
            {
                glyph = "\uE993";
            }
            else if (percent < 67)
            {
                glyph = "\uE994";
            }
            else
            {
                glyph = "\uE995";
            }

            VolumeIcon.Glyph = glyph;
            MuteIcon.Glyph = glyph;
            VolumeText.Text = engine.IsMuted ? "—" : percent.ToString(CultureInfo.InvariantCulture);
        }

        // ── 展开 / 全屏 ───────────────────────────────────────────────────────

        private void Expand()
        {
            _expanded = true;
            CardLayer.IsHitTestVisible = true;
            ExpandIcon.Glyph = "\uE70D";
            AnimateScalar("Progress", 1f, expanding: true);
        }

        private void Collapse()
        {
            _expanded = false;
            SetFullscreen(false);
            CardLayer.IsHitTestVisible = false;
            ExpandIcon.Glyph = "\uE70E";
            AnimateScalar("Progress", 0f, expanding: false);
        }

        private void SetFullscreen(bool fullscreen)
        {
            if (_fullscreen == fullscreen)
            {
                return;
            }

            _fullscreen = fullscreen;

            // 全屏时传输栏透明度为 0，但透明元素照样接收点击：一并关掉命中测试。
            MiniBar.IsHitTestVisible = !fullscreen;
            AnimateScalar("Fullscreen", fullscreen ? 1f : 0f, expanding: fullscreen);
        }

        /// <summary>Esc 逐层退出：全屏 → 回卡片；卡片 → 收起。返回是否处理了。</summary>
        public bool HandleEscape()
        {
            if (_fullscreen)
            {
                SetFullscreen(false);
                return true;
            }

            if (_expanded)
            {
                Collapse();
                return true;
            }

            return false;
        }

        /// <summary>展开卡片 ↔ 收起（Ctrl+L 与播放栏按钮都走这里）。</summary>
        public void ToggleExpanded()
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

        /// <summary>卡片 ↔ 真全屏切换（F11 与卡片里的专用按钮都走这里）。</summary>
        public void ToggleFullscreen()
        {
            if (!_expanded)
            {
                Expand();
            }

            SetFullscreen(!_fullscreen);
        }

        /// <summary>展开方向 400ms standard，收起方向 260ms fastOutSlowIn（与 Android 播放卡一致）。</summary>
        private void AnimateScalar(string property, float to, bool expanding)
        {
            if (_compositor == null)
            {
                return; // 合成初始化失败时降级为无动画（缓动也要在判空之后才创建）。
            }

            var easing = expanding ? KanesumiEasing.Standard(_compositor) : KanesumiEasing.FastOutSlowIn(_compositor);
            _props.StartAnimation(property, KanesumiMotion.TweenScalar(_compositor, to, KanesumiMotion.PlayerDuration(expanding), easing));
        }

        private void ExpandClick(object sender, RoutedEventArgs e) => ToggleExpanded();

        private void InfoTapped(object sender, TappedRoutedEventArgs e) => ToggleExpanded();

        private void CoverTapped(object sender, TappedRoutedEventArgs e)
        {
            if (_fullscreen)
            {
                return;
            }

            ToggleExpanded();
        }

        private void FullscreenClick(object sender, RoutedEventArgs e) => ToggleFullscreen();

        private void PlayPauseClick(object sender, RoutedEventArgs e) => PlaybackHost.Engine.PlayPause();

        private void NextClick(object sender, RoutedEventArgs e) => PlaybackHost.Engine.Next();

        private void PreviousClick(object sender, RoutedEventArgs e) => PlaybackHost.Engine.Previous();
    }
}
