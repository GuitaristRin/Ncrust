using System;
using System.Collections.Generic;
using System.Globalization;
using System.Numerics;
using Kanesumi.Xaml.Motion;
using Windows.Foundation;
using Windows.UI.Composition;
using Windows.UI.Text;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Automation;
using Windows.UI.Xaml.Automation.Peers;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Hosting;
using Windows.UI.Xaml.Input;
using Windows.UI.Xaml.Media;

namespace Kanesumi.Xaml.Controls
{
    /// <summary>一行歌词：时间戳、原文、译文（无译文为空串）。</summary>
    public sealed class MetroLyricLine
    {
        public MetroLyricLine(long timeMs, string text, string translation)
        {
            TimeMs = timeMs;
            Text = text ?? string.Empty;
            Translation = translation ?? string.Empty;
        }

        public long TimeMs { get; }

        public string Text { get; }

        public string Translation { get; }
    }

    /// <summary>
    /// 歌词面板（对应 sec-a <c>MetroLyricsPanel</c>，规格见 windows/docs/KANESUMI_XAML.md）：
    ///
    /// - 左对齐大字；当前行 primary，已唱行 onBackground × 0.6，未唱行 onSurfaceVariant × 0.4
    ///   （颜色离散切换，跨行时一次性翻转；颜色用 ThemeResource 样式，明暗主题下都可读）。
    /// - 缩放连续：一个共享标量 <c>SmoothIndex</c> 跨行时 180ms fastOutSlowIn 滑到新行号，
    ///   每行的 Scale 都是引用它的 ExpressionAnimation —— 逐帧零 UI 线程开销。
    /// - 当前行滚到视口 36% 高处；手动滚动后暂停自动跟随 5s；新歌词出现时直接跳到当前行、
    ///   整个面板 400ms 淡入；点击某行触发 <see cref="LineInvoked"/>（跳转播放进度）。
    /// - 当前行经 UIA LiveRegion 播报，只在跨行时更新。
    ///
    /// 只负责呈现；播放位置由宿主通过 <see cref="UpdatePosition"/> 喂进来（宿主负责在 2Hz 采样之间外推）。
    /// </summary>
    public sealed class MetroLyricsPanel : Grid
    {
        private const double AnchorRatio = 0.36;
        private static readonly TimeSpan ManualScrollPause = TimeSpan.FromSeconds(5);

        private readonly ScrollViewer _scroll;
        private readonly StackPanel _stack;
        private readonly TextBlock _liveRegion;
        private readonly List<FrameworkElement> _rows = new List<FrameworkElement>();
        private readonly List<TextBlock[]> _texts = new List<TextBlock[]>();

        private long[] _times = Array.Empty<long>();
        private int _current = -1;
        private bool _jumpNext = true;
        private DateTimeOffset _manualUntil = DateTimeOffset.MinValue;
        private bool _followSuspended;

        private Compositor _compositor;
        private CompositionPropertySet _props;

        public MetroLyricsPanel()
        {
            _stack = new StackPanel();
            _scroll = new ScrollViewer
            {
                Content = _stack,
                HorizontalScrollMode = ScrollMode.Disabled,
                HorizontalScrollBarVisibility = ScrollBarVisibility.Disabled,
                VerticalScrollBarVisibility = ScrollBarVisibility.Hidden,
                IsTabStop = false,
            };
            Children.Add(_scroll);

            // 屏幕阅读器播报当前行：一个不可见的 LiveRegion，只在跨行时改文字。
            _liveRegion = new TextBlock { Visibility = Visibility.Collapsed };
            AutomationProperties.SetLiveSetting(_liveRegion, AutomationLiveSetting.Polite);
            Children.Add(_liveRegion);

            _scroll.DirectManipulationStarted += (_, __) => PauseFollow();
            _scroll.AddHandler(PointerWheelChangedEvent, new PointerEventHandler((_, __) => PauseFollow()), true);

            SizeChanged += (_, __) =>
            {
                UpdateEdgePadding();
                ScrollToCurrent(animated: false);
            };
        }

        /// <summary>点击某一行：参数是该行的时间戳（毫秒），宿主据此跳转播放进度。</summary>
        public event Action<long> LineInvoked;

        /// <summary>行字号（默认 32 / 行高 42，译文 20 / 26）。</summary>
        public double LineFontSize { get; set; } = 32;

        public double LineHeightValue { get; set; } = 42;

        public double TranslationFontSize { get; set; } = 20;

        public double TranslationLineHeight { get; set; } = 26;

        /// <summary>
        /// 改字号（宿主按窗口宽度切换：宽窗口与窄窗口的字号不同）。已有的行就地更新，
        /// 然后把当前行重新定位到锚点（行高变了，滚动位置要跟着变）。
        /// </summary>
        public void SetTypography(double lineFontSize, double lineHeight, double translationFontSize, double translationLineHeight)
        {
            if (LineFontSize == lineFontSize && LineHeightValue == lineHeight &&
                TranslationFontSize == translationFontSize && TranslationLineHeight == translationLineHeight)
            {
                return;
            }

            LineFontSize = lineFontSize;
            LineHeightValue = lineHeight;
            TranslationFontSize = translationFontSize;
            TranslationLineHeight = translationLineHeight;

            foreach (var texts in _texts)
            {
                texts[0].FontSize = lineFontSize;
                texts[0].LineHeight = lineHeight;
                if (texts.Length > 1)
                {
                    texts[1].FontSize = translationFontSize;
                    texts[1].LineHeight = translationLineHeight;
                }
            }

            _jumpNext = true;
        }

        public int LineCount => _rows.Count;

        /// <summary>换一组歌词（切歌 / 翻译开关）。面板淡入，下一次定位直接跳到当前行不做滚动动画。</summary>
        public void SetLines(IReadOnlyList<MetroLyricLine> lines)
        {
            EnsureComposition();
            _stack.Children.Clear();
            _rows.Clear();
            _texts.Clear();
            _current = -1;
            _jumpNext = true;
            _times = new long[lines?.Count ?? 0];

            for (var i = 0; i < _times.Length; i++)
            {
                var line = lines[i];
                _times[i] = line.TimeMs;
                AddRow(i, line);
            }

            _props?.InsertScalar("SmoothIndex", 0f);
            UpdateEdgePadding();
            _scroll.ChangeView(null, 0, null, true);

            // 淡入状态由数据派生：每次换歌词都从 0 开始，而不是依赖某个一次性事件（sec-a 2bfd1cc）。
            if (_compositor != null && _rows.Count > 0)
            {
                var visual = ElementCompositionPreview.GetElementVisual(this);
                var fade = _compositor.CreateScalarKeyFrameAnimation();
                fade.InsertKeyFrame(0f, 0f);
                fade.InsertKeyFrame(1f, 1f, KanesumiEasing.Standard(_compositor));
                fade.Duration = KanesumiMotion.LyricPanelFadeIn;
                visual.StartAnimation("Opacity", fade);
            }
        }

        /// <summary>喂入播放位置（毫秒）。只有跨行时才改颜色、推进 SmoothIndex 与滚动。</summary>
        public void UpdatePosition(long positionMs)
        {
            if (_rows.Count == 0)
            {
                return;
            }

            var index = IndexAt(positionMs);
            var resumed = _followSuspended && DateTimeOffset.Now >= _manualUntil;
            if (index == _current && !resumed && !_jumpNext)
            {
                return;
            }

            if (index != _current)
            {
                Recolor(_current, index);
                _current = index;
                _liveRegion.Text = index >= 0 ? _texts[index][0].Text : string.Empty;
                if (index >= 0)
                {
                    var peer = FrameworkElementAutomationPeer.FromElement(_liveRegion) ?? FrameworkElementAutomationPeer.CreatePeerForElement(_liveRegion);
                    peer?.RaiseAutomationEvent(AutomationEvents.LiveRegionChanged);
                }

                AnimateSmoothIndex(Math.Max(0, index), animate: !_jumpNext);
            }

            if (DateTimeOffset.Now >= _manualUntil)
            {
                _followSuspended = false;
                ScrollToCurrent(animated: !_jumpNext);
                _jumpNext = false;
            }
        }

        /// <summary>强制把滚动位置拉回当前行（播放器展开、切到歌词页时调用；对应 sec-a f2f315e）。</summary>
        public void ForceLocate()
        {
            _manualUntil = DateTimeOffset.MinValue;
            _followSuspended = false;
            ScrollToCurrent(animated: false);
        }

        private void AddRow(int index, MetroLyricLine line)
        {
            var row = new StackPanel
            {
                Padding = new Thickness(0, 8, 0, 8),
                Background = new SolidColorBrush(Windows.UI.Colors.Transparent),
            };

            var main = CreateText(line.Text, LineFontSize, LineHeightValue, FontWeights.Bold);
            row.Children.Add(main);
            TextBlock translation = null;
            if (line.Translation.Length > 0)
            {
                translation = CreateText(line.Translation, TranslationFontSize, TranslationLineHeight, FontWeights.Normal);
                translation.Margin = new Thickness(0, 4, 0, 0);
                row.Children.Add(translation);
            }

            var texts = translation == null ? new[] { main } : new[] { main, translation };
            ApplyState(texts, LineState.Future);

            var time = line.TimeMs;
            row.Tapped += (_, __) => LineInvoked?.Invoke(time);

            if (_compositor != null)
            {
                var visual = ElementCompositionPreview.GetElementVisual(row);
                row.SizeChanged += (_, e) => visual.CenterPoint = new Vector3(0f, (float)(e.NewSize.Height / 2), 0f);

                // 离当前行越远越小：Lerp(1, 0.82, Clamp(|i - SmoothIndex| / 1.8, 0, 1))。
                var s = string.Format(
                    CultureInfo.InvariantCulture,
                    "Lerp(1, {0}, Clamp(Abs({1} - props.SmoothIndex) / {2}, 0, 1))",
                    KanesumiMotion.LyricScaleMin,
                    index,
                    KanesumiMotion.LyricScaleDistance);
                var scale = _compositor.CreateExpressionAnimation("Vector3(" + s + ", " + s + ", 1)");
                scale.SetReferenceParameter("props", _props);
                visual.StartAnimation("Scale", scale);
            }

            _rows.Add(row);
            _texts.Add(texts);
            _stack.Children.Add(row);
        }

        private static TextBlock CreateText(string text, double size, double lineHeight, FontWeight weight) => new TextBlock
        {
            Text = text,
            FontSize = size,
            LineHeight = lineHeight,
            FontWeight = weight,
            TextWrapping = TextWrapping.WrapWholeWords,
            IsTextScaleFactorEnabled = false,
        };

        private void Recolor(int from, int to)
        {
            // 相邻跨行只动两行；seek 跨多行时整段重算。
            if (Math.Abs(to - from) == 1)
            {
                if (from >= 0)
                {
                    ApplyState(_texts[from], from < to ? LineState.Past : LineState.Future);
                }

                if (to >= 0)
                {
                    ApplyState(_texts[to], LineState.Current);
                }

                return;
            }

            for (var i = 0; i < _texts.Count; i++)
            {
                ApplyState(_texts[i], i < to ? LineState.Past : i == to ? LineState.Current : LineState.Future);
            }
        }

        private static void ApplyState(TextBlock[] texts, LineState state)
        {
            string styleKey;
            double opacity;
            switch (state)
            {
                case LineState.Current:
                    styleKey = "MetroLyricCurrentTextStyle";
                    opacity = 1;
                    break;
                case LineState.Past:
                    styleKey = "MetroLyricPastTextStyle";
                    opacity = 0.6;
                    break;
                default:
                    styleKey = "MetroLyricFutureTextStyle";
                    opacity = 0.4;
                    break;
            }

            var style = FindStyle(styleKey);
            foreach (var text in texts)
            {
                if (style != null)
                {
                    text.Style = style;
                }

                text.Opacity = opacity;
            }
        }

        /// <summary>
        /// 样式在 Kanesumi 的合并字典里：要用索引器查（它会查合并字典），TryGetValue / ContainsKey 不查。
        /// </summary>
        private static Style FindStyle(string key)
        {
            try
            {
                return Application.Current.Resources[key] as Style;
            }
            catch
            {
                return null;
            }
        }

        private int IndexAt(long positionMs)
        {
            // 最后一个时间戳 <= 位置的行；还没到第一行时为 -1。
            int lo = 0, hi = _times.Length - 1, found = -1;
            while (lo <= hi)
            {
                var mid = (lo + hi) / 2;
                if (_times[mid] <= positionMs)
                {
                    found = mid;
                    lo = mid + 1;
                }
                else
                {
                    hi = mid - 1;
                }
            }

            return found;
        }

        private void AnimateSmoothIndex(int index, bool animate)
        {
            if (_props == null)
            {
                return;
            }

            if (!animate)
            {
                _props.InsertScalar("SmoothIndex", index);
                return;
            }

            _props.StartAnimation(
                "SmoothIndex",
                KanesumiMotion.TweenScalar(_compositor, index, KanesumiMotion.LyricLineSwitch, KanesumiEasing.FastOutSlowIn(_compositor)));
        }

        private void ScrollToCurrent(bool animated)
        {
            if (_rows.Count == 0 || ActualHeight <= 0)
            {
                return;
            }

            var row = _rows[Math.Max(0, _current)];
            if (row.ActualHeight <= 0)
            {
                return; // 还没布局：等 SizeChanged 再定位。
            }

            var top = row.TransformToVisual(_stack).TransformPoint(new Point(0, 0)).Y;
            var target = top + row.ActualHeight / 2 - ActualHeight * AnchorRatio;
            _scroll.ChangeView(null, Math.Max(0, target), null, !animated);
        }

        /// <summary>上下留白：让第一行、最后一行也能停到 36% 的锚点上。</summary>
        private void UpdateEdgePadding()
        {
            var height = ActualHeight;
            if (height > 0)
            {
                _stack.Padding = new Thickness(0, height * AnchorRatio, 0, height * (1 - AnchorRatio));
            }
        }

        private void PauseFollow()
        {
            _manualUntil = DateTimeOffset.Now + ManualScrollPause;
            _followSuspended = true;
        }

        private void EnsureComposition()
        {
            if (_compositor != null)
            {
                return;
            }

            try
            {
                _compositor = ElementCompositionPreview.GetElementVisual(this).Compositor;
                _props = _compositor.CreatePropertySet();
                _props.InsertScalar("SmoothIndex", 0f);
            }
            catch
            {
                // 合成不可用时降级：没有缩放与淡入，颜色与滚动照常。
                _compositor = null;
                _props = null;
            }
        }

        private enum LineState
        {
            Past,
            Current,
            Future,
        }
    }
}
