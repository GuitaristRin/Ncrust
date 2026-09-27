using System;
using System.Globalization;
using System.Numerics;
using Kanesumi.Xaml.Motion;
using Ncrust.Core.Api;
using Ncrust.Core.Playback;
using Ncrust.Pages;
using Ncrust.Playback;
using Windows.Foundation;
using Windows.UI.Composition;
using Windows.UI.ViewManagement;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Controls.Primitives;
using Windows.UI.Xaml.Hosting;
using Windows.UI.Xaml.Input;
using Windows.UI.Xaml.Media.Imaging;

namespace Ncrust.Player
{
    /// <summary>
    /// 播放器覆盖层（见 windows/AGENTS.md「播放器层」）。Progress / Fullscreen 两个标量驱动全部形变：
    /// 卡片整体上滑、迷你栏随动上移淡出、唯一封面从缩略图形变到卡片封面位再到全屏。
    /// 迷你栏（Groove 式传输栏）与卡片各有一套控件，状态由同一组引擎回调同步。
    /// </summary>
    public sealed partial class PlayerHost : UserControl
    {
        private const float MiniCover = 72f;
        private const float BarHeight = 72f;
        private const float NarrowCover = 64f;
        private const string VolumeKey = "volume";
        private const int LyricsTab = 0;
        private const int QueueTab = 1;

        /// <summary>模式按钮的切换顺序（与 Android onTogglePlayMode 相同：循环 → 单曲 → 随机 → 顺序 → 相似无限）。</summary>
        private static readonly PlaybackMode[] ModeCycle =
        {
            PlaybackMode.Cycle, PlaybackMode.Single, PlaybackMode.Shuffle, PlaybackMode.Line, PlaybackMode.Infinity,
        };

        private Compositor _compositor;
        private CompositionPropertySet _props;
        private Visual _coverVisual;

        private bool _initialized;
        private bool _fullscreen;
        private bool _expanded;
        private Rect _coverSlotRect;
        private bool _layoutMeasured;

        private LyricsController _lyrics;
        private QueuePresenter _queue;

        private Slider _dragSlider;
        private bool _seekProgrammatic;
        private bool _volumeProgrammatic;

        public PlayerHost()
        {
            InitializeComponent();
            Loaded += OnLoaded;
            Root.SizeChanged += (_, __) => Guarded(() =>
            {
                ApplyCardLayout();
                RebuildExpressions();
            });

            // 卡片封面位的位置随布局变化（窗口缩放、歌名换行）：位置真的变了才重建表达式。
            // 右栏跟随左栏等高：左栏实际高度出来后再对齐（歌名一行 / 两行会让左栏高度变化）。
            Card.LayoutUpdated += (_, __) => Guarded(() =>
            {
                // 第一次布局后用实测的「信息 + 控件」高度再算一遍封面（之前是估计值）。
                if (!_layoutMeasured && InfoText.ActualHeight > 0 && ControlsPanel.ActualHeight > 0)
                {
                    _layoutMeasured = true;
                    ApplyCardLayout();
                }

                SyncSideHeight();
                var rect = CoverSlotRect();
                if (rect != _coverSlotRect)
                {
                    RebuildExpressions();
                }
            });

            // 真全屏走系统全屏（F11 进）；用户从系统层面退出全屏（任务栏、Win+Shift+Enter）时跟着回卡片。
            ApplicationView.GetForCurrentView().VisibleBoundsChanged += (view, __) =>
            {
                if (_fullscreen && !view.IsFullScreenMode)
                {
                    SetFullscreen(false);
                }
            };

            // 拖动进度条时只在松手后跳转，避免拖动过程中反复 seek 让流媒体卡顿。
            // Slider 自己会处理指针事件，所以要用 handledEventsToo 才收得到。
            foreach (var slider in new[] { SeekSlider, CardSeekSlider })
            {
                var target = slider;
                target.AddHandler(PointerPressedEvent, new PointerEventHandler((_, __) => _dragSlider = target), true);
                target.AddHandler(PointerReleasedEvent, new PointerEventHandler((_, __) => EndSeekDrag()), true);
                target.AddHandler(PointerCaptureLostEvent, new PointerEventHandler((_, __) => EndSeekDrag()), true);
            }
        }

        private static void Guarded(Action action)
        {
            try
            {
                action();
            }
            catch (Exception ex)
            {
                App.WriteCrashLog(ex);
            }
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
                ElementCompositionPreview.SetIsTranslationEnabled(Card, true);
                ElementCompositionPreview.SetIsTranslationEnabled(MiniBar, true);
                _coverVisual.CenterPoint = new Vector3(0f, 0f, 0f);

                // 迷你栏在卡片上滑的前 40% 里淡出（Android 迷你栏在卡片顶部淡出）；全屏时卡片内容淡出、全屏操作层淡入。
                Bind(MiniBar, "Opacity", "Clamp(1 - props.Progress * 2.5, 0, 1)");
                Bind(CardContent, "Opacity", "1 - props.Fullscreen");
                Bind(FullscreenLayer, "Opacity", "props.Fullscreen");

                ApplyCardLayout();
                RebuildExpressions();
            }
            catch (Exception ex)
            {
                // 合成初始化失败时降级：没有形变动画，卡片按展开 / 收起直接显示 / 隐藏。
                App.WriteCrashLog(ex);
                _compositor = null;
            }

            // 卡片在表达式接管之前保持隐藏，否则第一帧会整块盖住界面。
            Card.Visibility = _compositor == null ? Visibility.Collapsed : Visibility.Visible;

            _lyrics = new LyricsController(Lyrics, LyricsStatus);
            _queue = new QueuePresenter(QueueList, QueueEmpty);
            LyricsSettingsChanged += _lyrics.Reload;
            SongActions.LibraryChanged += UpdateLikeButton;

            var engine = PlaybackHost.Engine;
            engine.CurrentSongChanged += OnSongChanged;
            engine.IsPlayingChanged += OnIsPlayingChanged;
            engine.ProgressChanged += OnProgressChanged;
            engine.ModeChanged += UpdateModeButton;
            engine.ModeChanged += _ => _queue.MarkDirty();
            engine.QueueChanged += _queue.MarkDirty;

            RestorePreferences(engine);
            OnSongChanged(AppServices.Queue.Current);
            OnIsPlayingChanged(engine.IsPlaying);
        }

        private void RestorePreferences(PlaybackEngine engine)
        {
            var volume = AppServices.Settings.GetInt(VolumeKey, 100);
            engine.Volume = volume / 100.0;
            SetVolumeSliders(volume);
            UpdateVolumeIcons();

            var mode = (PlaybackMode)AppServices.Settings.GetInt(PlaybackHost.PlayModeKey, (int)PlaybackMode.Cycle);
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

        // ── 布局与形变 ────────────────────────────────────────────────────────

        private bool IsNarrow => Root.ActualWidth < 600;

        /// <summary>
        /// 卡片构图（参考 Apple Music / Groove 的「正在播放」）：
        /// 宽窗口左右两栏作为整体居中，左栏以封面宽度为竖轴（信息与控件都与封面同宽），右栏歌词 / 队列
        /// 与左栏等高。封面尽量大：受左右留白、栏间距与「信息 + 控件」所需高度约束。
        /// 窄窗口是上中下三段：顶部 64 小封面 + 信息，中间歌词 / 队列，底部控件。
        /// </summary>
        private void ApplyCardLayout()
        {
            var width = Root.ActualWidth;
            var height = Root.ActualHeight;
            if (width <= 0 || height <= 0)
            {
                return;
            }

            if (IsNarrow)
            {
                SetSize(CoverSlot, NarrowCover);
                LeftColumn.Width = new GridLength(1, GridUnitType.Star);
                RightColumn.Width = new GridLength(0);
                SideTabs.Height = double.NaN;
                Stage.Width = double.NaN;
                ControlsPanel.Width = double.NaN;
                InfoText.Width = double.NaN;
                Lyrics.SetTypography(22, 30, 15, 21);
                return;
            }

            const double sideMargin = 56;
            const double gap = 64;
            const double topBar = 48;
            const double bottomMargin = 32;

            // 「信息 + 控件」的高度：布局出来后用实测值，第一次用估计值。
            var below = InfoText.ActualHeight > 0 && ControlsPanel.ActualHeight > 0
                ? InfoText.ActualHeight + InfoText.Margin.Top + ControlsPanel.ActualHeight + ControlsPanel.Margin.Top
                : 300;
            var contentWidth = width - sideMargin * 2 - gap;
            var cover = Math.Min(contentWidth * 0.45, height - topBar - bottomMargin - below);
            cover = Math.Max(240, Math.Min(560, Math.Floor(cover)));
            var right = Math.Max(280, Math.Min(560, contentWidth - cover));

            SetSize(CoverSlot, cover);
            LeftColumn.Width = new GridLength(cover);
            RightColumn.Width = new GridLength(right);

            // 舞台宽度显式给定再居中：交给子元素的期望宽度去算时，Pivot 会把舞台撑宽、整体偏左（实测）。
            Stage.Width = cover + gap + right;
            ControlsPanel.Width = cover;
            InfoText.Width = cover;
            Lyrics.SetTypography(28, 38, 17, 24);
        }

        /// <summary>宽窗口：右栏与左栏（封面 + 信息 + 控件）等高，上沿与封面上沿对齐。</summary>
        private void SyncSideHeight()
        {
            if (IsNarrow)
            {
                return;
            }

            // 左栏两块都是顶端对齐，高度只取决于自身内容、不受行高影响；右栏的总占高（高度 + 上移的 -8）
            // 取整后不超过左栏，不会把行撑高 —— 否则行变高 → 左栏变高 → 右栏再变高，形成布局循环
            // （实测：LayoutCycleException，启动即崩）。
            var left = Math.Floor(InfoPanel.ActualHeight + ControlsPanel.ActualHeight + ControlsPanel.Margin.Top);
            var target = left + 8;
            if (left > 0 && (double.IsNaN(SideTabs.Height) || Math.Abs(SideTabs.Height - target) >= 1))
            {
                // Pivot 上移了 8（Margin -8），让页签文字的上沿对齐封面上沿；高度补回这 8。
                SideTabs.Height = target;
            }
        }

        private static void SetSize(FrameworkElement element, double size)
        {
            if (Math.Abs(element.Width - size) > 0.5)
            {
                element.Width = size;
                element.Height = size;
            }
        }

        /// <summary>封面位在 Root 里的位置（XAML 布局位置 = 卡片展开后的位置，合成平移不计入）。</summary>
        private Rect CoverSlotRect()
        {
            if (CoverSlot.ActualWidth <= 0 || Card.Visibility != Visibility.Visible)
            {
                return Rect.Empty;
            }

            var origin = CoverSlot.TransformToVisual(Root).TransformPoint(new Point(0, 0));
            return new Rect(origin.X, origin.Y, CoverSlot.ActualWidth, CoverSlot.ActualHeight);
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

            // 卡片与迷你栏同步上滑：卡片顶边 = 迷你栏顶边 = (H - 72) × (1 - Progress)。
            var travel = height - BarHeight;
            Bind(Card, "Translation", Formattable("Vector3(0, {0} * (1 - props.Progress), 0)", travel));
            Bind(MiniBar, "Translation", Formattable("Vector3(0, -{0} * props.Progress, 0)", travel));

            // 卡片封面位（布局还没出来时先用左栏中央的估计值，LayoutUpdated 会再校正）。
            _coverSlotRect = CoverSlotRect();
            var slot = _coverSlotRect;
            if (slot.IsEmpty)
            {
                var guess = (float)Math.Max(MiniCover, CoverSlot.Width);
                slot = new Rect((width / 2 - guess) / 2, 48, guess, guess);
            }

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
            var cardScale = (float)slot.Width / baseSize;
            const float fullScale = 1f;
            var miniDy = (height - MiniCover) - baseTop;
            var cardDx = (float)slot.X;
            var cardDy = (float)slot.Y - baseTop;
            var fullDy = fullTop - baseTop;

            // 值 = 迷你 + (卡片 - 迷你) * Progress + (全屏 - 卡片) * Fullscreen。
            // 迷你端随迷你栏上移、卡片端随卡片上滑，两端都是 Progress 的线性函数，线性插值与两者同步。
            // Scale 是 Vector3：表达式必须返回 Vector3，标量会抛
            // 「expression output does not match animating property type」。
            var scaleValue = Formattable(
                "{0} + ({1} - {0}) * props.Progress + ({2} - {1}) * props.Fullscreen",
                miniScale, cardScale, fullScale);
            Bind(CoverImage, "Scale", "Vector3(" + scaleValue + ", " + scaleValue + ", 1)");
            Bind(CoverImage, "Translation", Formattable(
                "Vector3({0} * props.Progress + ({1} - {0}) * props.Fullscreen, {2} + ({3} - {2}) * props.Progress + ({4} - {3}) * props.Fullscreen, 0)",
                cardDx, fullLeft, miniDy, cardDy, fullDy));
        }

        private void Bind(UIElement element, string property, string expression)
        {
            var animation = _compositor.CreateExpressionAnimation(expression);
            animation.SetReferenceParameter("props", _props);
            ElementCompositionPreview.GetElementVisual(element).StartAnimation(property, animation);
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
            _lyrics?.OnSongChanged(song);
            UpdateLikeButton();

            if (song == null)
            {
                MiniTitle.Text = "未在播放";
                MiniArtist.Text = string.Empty;
                CardTitle.Text = string.Empty;
                CardArtist.Text = string.Empty;
                CardAlbum.Text = string.Empty;
                CardArtistLink.Visibility = Visibility.Collapsed;
                CardAlbumLink.Visibility = Visibility.Collapsed;
                return;
            }

            MiniTitle.Text = song.Name;
            MiniArtist.Text = song.ArtistText;
            CardTitle.Text = song.Name;
            CardArtist.Text = song.ArtistText;
            CardAlbum.Text = song.Album?.Name ?? string.Empty;
            CardArtistLink.Visibility = string.IsNullOrEmpty(song.ArtistText) ? Visibility.Collapsed : Visibility.Visible;
            CardAlbumLink.Visibility = string.IsNullOrEmpty(CardAlbum.Text) ? Visibility.Collapsed : Visibility.Visible;
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
            var glyph = playing ? Glyphs.Pause : Glyphs.Play;
            MiniPlayIcon.Glyph = glyph;
            CardPlayIcon.Glyph = glyph;
            _lyrics?.OnPlayingChanged(playing);
        }

        private void OnProgressChanged(long positionMs, long durationMs)
        {
            _lyrics?.OnProgress(positionMs);

            var fraction = durationMs > 0 ? (double)positionMs / durationMs : 0;
            SlimProgress.Value = fraction;
            DurationText.Text = FormatTime(durationMs);
            CardDurationText.Text = DurationText.Text;

            if (_dragSlider != null)
            {
                return; // 拖动中：进度条跟手，不被 2Hz 的播放进度拉回去。
            }

            PositionText.Text = FormatTime(positionMs);
            CardPositionText.Text = PositionText.Text;
            _seekProgrammatic = true;
            foreach (var slider in new[] { SeekSlider, CardSeekSlider })
            {
                slider.Maximum = Math.Max(1, durationMs / 1000.0);
                slider.Value = Math.Min(slider.Maximum, positionMs / 1000.0);
            }

            _seekProgrammatic = false;
        }

        private static string FormatTime(long ms)
        {
            if (ms <= 0)
            {
                return "0:00";
            }

            var total = ms / 1000;
            return (total / 60).ToString(CultureInfo.InvariantCulture) + ":" +
                   (total % 60).ToString("00", CultureInfo.InvariantCulture);
        }

        // ── 进度条（迷你栏与卡片各一条，行为相同） ────────────────────────────

        private void SeekValueChanged(object sender, RangeBaseValueChangedEventArgs e)
        {
            if (_seekProgrammatic)
            {
                return;
            }

            var text = FormatTime((long)(e.NewValue * 1000));
            PositionText.Text = text;
            CardPositionText.Text = text;

            // 键盘方向键 / 无拖动的点击：立即跳转；拖动中等松手。
            if (_dragSlider == null)
            {
                PlaybackHost.Engine.Seek((long)(e.NewValue * 1000));
            }
        }

        private void EndSeekDrag()
        {
            var slider = _dragSlider;
            if (slider == null)
            {
                return;
            }

            _dragSlider = null;
            PlaybackHost.Engine.Seek((long)(slider.Value * 1000));
        }

        // ── 模式 ──────────────────────────────────────────────────────────────

        private void ModeClick(object sender, RoutedEventArgs e)
        {
            var engine = PlaybackHost.Engine;
            var index = Array.IndexOf(ModeCycle, engine.Mode);
            var next = ModeCycle[(index + 1) % ModeCycle.Length];
            engine.SetMode(next);
            AppServices.Settings.SetInt(PlaybackHost.PlayModeKey, (int)next);
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
                    tip = "相似无限（队尾自动续播相似歌曲）";
                    break;
                default:
                    glyph = "\uE8EE";
                    tip = "列表循环";
                    break;
            }

            ModeIcon.Glyph = glyph;
            CardModeIcon.Glyph = glyph;
            ToolTipService.SetToolTip(ModeButton, tip);
            ToolTipService.SetToolTip(CardModeButton, tip);
        }

        // ── 音量（迷你栏浮层与卡片内联各一条滑块） ────────────────────────────

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

            var volume = (int)Math.Round(e.NewValue);
            AppServices.Settings.SetInt(VolumeKey, volume);
            SetVolumeSliders(volume);
            UpdateVolumeIcons();
        }

        private void MuteClick(object sender, RoutedEventArgs e)
        {
            var engine = PlaybackHost.Engine;
            engine.IsMuted = !engine.IsMuted;
            UpdateVolumeIcons();
        }

        private void SetVolumeSliders(int volume)
        {
            _volumeProgrammatic = true;
            VolumeSlider.Value = volume;
            CardVolumeSlider.Value = volume;
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
            CardVolumeIcon.Glyph = glyph;
            VolumeText.Text = engine.IsMuted ? "—" : percent.ToString(CultureInfo.InvariantCulture);
        }

        // ── 展开 / 全屏 ───────────────────────────────────────────────────────

        private void Expand()
        {
            _expanded = true;
            ApplyHitTesting();
            AnimateScalar("Progress", 1f, expanding: true);
            UpdateSideActivity();
        }

        private void Collapse()
        {
            _expanded = false;
            SetFullscreen(false);
            ApplyHitTesting();
            AnimateScalar("Progress", 0f, expanding: false);
            UpdateSideActivity();
        }

        private void SetFullscreen(bool fullscreen)
        {
            if (_fullscreen == fullscreen)
            {
                return;
            }

            _fullscreen = fullscreen;

            // 纯封面只属于真全屏：进入时让窗口进系统全屏（隐藏任务栏与标题栏），退出时还原。
            var view = ApplicationView.GetForCurrentView();
            if (fullscreen && !view.IsFullScreenMode)
            {
                view.TryEnterFullScreenMode();
            }
            else if (!fullscreen && view.IsFullScreenMode)
            {
                view.ExitFullScreenMode();
            }

            ApplyHitTesting();
            AnimateScalar("Fullscreen", fullscreen ? 1f : 0f, expanding: fullscreen);
            UpdateSideActivity();
        }

        /// <summary>
        /// 透明 / 平移出去的元素照样接收点击（命中测试按布局位置、不认合成变换），
        /// 所以每个状态只让看得见的那一层接收点击：收起 → 迷你栏；展开 → 卡片；全屏 → 全屏操作层。
        /// </summary>
        private void ApplyHitTesting()
        {
            MiniBar.IsHitTestVisible = !_expanded;
            Card.IsHitTestVisible = _expanded && !_fullscreen;
            FullscreenLayer.IsHitTestVisible = _fullscreen;

            if (_compositor == null)
            {
                // 降级：没有形变，直接显示 / 隐藏。
                Card.Visibility = _expanded ? Visibility.Visible : Visibility.Collapsed;
            }
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

        /// <summary>卡片 ↔ 真全屏切换（只有 F11 与全屏操作层的退出按钮走这里；卡片里不再放全屏按钮）。</summary>
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

        // ── 歌词 / 队列 / 收藏 ─────────────────────────────────────────────────

        /// <summary>设置页切换「歌词翻译」后触发：重新合并当前歌的歌词。</summary>
        internal static event Action LyricsSettingsChanged;

        internal static void NotifyLyricsSettingsChanged() => LyricsSettingsChanged?.Invoke();

        /// <summary>
        /// 只有看得见的那一页才干活（对应 Android progress &lt; 0.05 时卸载重子树的思路）：
        /// 歌词外推定时器、队列重建都只在卡片展开、非全屏、对应页选中时进行。
        /// </summary>
        private void UpdateSideActivity()
        {
            if (_lyrics == null)
            {
                return;
            }

            var sideVisible = _expanded && !_fullscreen;
            _lyrics.Active = sideVisible && SideTabs.SelectedIndex == LyricsTab;
            _queue.Visible = sideVisible && SideTabs.SelectedIndex == QueueTab;
        }

        private void SideTabsChanged(object sender, SelectionChangedEventArgs e) => UpdateSideActivity();

        /// <summary>播放栏的「词」/ 队列按钮：展开卡片并切到对应页。</summary>
        private void ShowSide(int tab)
        {
            SideTabs.SelectedIndex = tab;
            if (!_expanded)
            {
                Expand();
            }
            else
            {
                SetFullscreen(false);
                UpdateSideActivity();
            }
        }

        private void LyricsClick(object sender, RoutedEventArgs e) => ShowSide(LyricsTab);

        private void QueueClick(object sender, RoutedEventArgs e) => ShowSide(QueueTab);

        private async void ClearQueueClick(object sender, RoutedEventArgs e)
        {
            if (AppServices.Queue.Songs.Count == 0)
            {
                return;
            }

            var dialog = new ContentDialog
            {
                Title = "清空播放内容",
                Content = "停止播放并清空播放队列？",
                PrimaryButtonText = "清空",
                CloseButtonText = "取消",
                DefaultButton = ContentDialogButton.Close,
            };
            if (await dialog.ShowAsync() == ContentDialogResult.Primary)
            {
                _queue.Clear();
            }
        }

        private void UpdateLikeButton()
        {
            var song = AppServices.Queue.Current;
            var saved = song != null && AppServices.Library.IsSongSaved(song.Id);
            LikeButton.IsEnabled = song != null;
            LikeIcon.Glyph = saved ? Glyphs.HeartFill : Glyphs.Heart;
            ToolTipService.SetToolTip(LikeButton, saved ? "移除收藏" : "加入库");
        }

        private void LikeClick(object sender, RoutedEventArgs e)
        {
            var song = AppServices.Queue.Current;
            if (song != null)
            {
                _ = SongActions.SetSavedAsync(song, !AppServices.Library.IsSongSaved(song.Id));
            }
        }

        /// <summary>卡片里点歌手 / 专辑：收起卡片并转到对应页面（对应 Android 全屏播放器点歌名的转到入口）。</summary>
        private void CardArtistClick(object sender, RoutedEventArgs e)
        {
            var song = AppServices.Queue.Current;
            if (song != null)
            {
                Collapse();
                _ = SongActions.GoToArtistAsync(song);
            }
        }

        private void CardAlbumClick(object sender, RoutedEventArgs e)
        {
            var song = AppServices.Queue.Current;
            if (song != null)
            {
                Collapse();
                _ = SongActions.GoToAlbumAsync(song);
            }
        }

        // ── 其余点击 ──────────────────────────────────────────────────────────

        private void ExpandClick(object sender, RoutedEventArgs e) => Expand();

        private void CollapseClick(object sender, RoutedEventArgs e) => Collapse();

        private void InfoTapped(object sender, TappedRoutedEventArgs e) => Expand();

        private void CoverTapped(object sender, TappedRoutedEventArgs e) => Expand();

        private void FullscreenClick(object sender, RoutedEventArgs e) => ToggleFullscreen();

        private void FullscreenDoubleTapped(object sender, DoubleTappedRoutedEventArgs e) => SetFullscreen(false);

        private void PlayPauseClick(object sender, RoutedEventArgs e) => PlaybackHost.Engine.PlayPause();

        private void NextClick(object sender, RoutedEventArgs e) => PlaybackHost.Engine.Next();

        private void PreviousClick(object sender, RoutedEventArgs e) => PlaybackHost.Engine.Previous();
    }
}
