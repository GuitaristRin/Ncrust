using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Threading.Tasks;
using Ncrust.Core.Audio;
using Ncrust.Playback;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Controls.Primitives;
using Windows.UI.Xaml.Navigation;

namespace Ncrust.Pages
{
    /// <summary>10 段均衡器设置页。参数经 <see cref="PlaybackEngine.ApplyEqualizer"/> 即时生效，并存进 <see cref="EqualizerStore"/>。</summary>
    public sealed partial class EqualizerPage : Page
    {
        private const double SliderHeight = 240;

        private readonly Slider[] _bandSliders = new Slider[EqualizerBands.Count];
        private readonly TextBlock[] _bandValues = new TextBlock[EqualizerBands.Count];
        private Slider _preampSlider;
        private TextBlock _preampValue;

        private IReadOnlyList<EqualizerPreset> _userPresets = Array.Empty<EqualizerPreset>();
        private EqualizerState _state;
        private bool _loading;

        public EqualizerPage()
        {
            InitializeComponent();
            BuildSliders();
        }

        /// <summary>设置页摘要用：当前状态对应的预设名，手动调过则为 null（显示「自定义」）。</summary>
        public static string DescribePreset(EqualizerState state) => state.PresetName;

        protected override async void OnNavigatedTo(NavigationEventArgs e)
        {
            base.OnNavigatedTo(e);
            UnavailableText.Visibility = PlaybackHost.Engine.EqualizerAvailable ? Visibility.Collapsed : Visibility.Visible;

            _state = AppServices.Equalizer.LoadState();
            _userPresets = await AppServices.Equalizer.LoadUserPresetsAsync();
            ShowState(_state);
            RebuildPresetBox(_state.PresetName);
        }

        // ── 界面构建 ──────────────────────────────────────────────────────────

        private void BuildSliders()
        {
            // 列 0：前级；列 1：分隔线；列 2..11：10 个频段。
            for (var i = 0; i < EqualizerBands.Count + 2; i++)
            {
                SliderGrid.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(i == 1 ? 17 : 52) });
            }

            SliderGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            SliderGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });
            SliderGrid.RowDefinitions.Add(new RowDefinition { Height = GridLength.Auto });

            _preampSlider = AddColumn(0, "前级", "dB", out _preampValue);
            _preampSlider.ValueChanged += (_, __) => OnSliderChanged();

            // 列 1 的分隔线写在 XAML 里（ThemeResource，切换明暗时跟着变）。
            for (var i = 0; i < EqualizerBands.Count; i++)
            {
                _bandSliders[i] = AddColumn(i + 2, EqualizerBands.Labels[i], "Hz", out _bandValues[i]);
                _bandSliders[i].ValueChanged += (_, __) => OnSliderChanged();
            }
        }

        private Slider AddColumn(int column, string label, string unit, out TextBlock valueText)
        {
            valueText = new TextBlock
            {
                HorizontalAlignment = HorizontalAlignment.Center,
                Style = (Style)Application.Current.Resources["LabelTextStyle"],
                Text = "0",
            };
            Grid.SetColumn(valueText, column);
            SliderGrid.Children.Add(valueText);

            var slider = new Slider
            {
                Orientation = Orientation.Vertical,
                Height = SliderHeight,
                Minimum = EqualizerBands.MinGainDb,
                Maximum = EqualizerBands.MaxGainDb,
                StepFrequency = 0.5,
                SmallChange = 0.5,
                LargeChange = 3,
                TickFrequency = 6,
                TickPlacement = TickPlacement.Outside,
                HorizontalAlignment = HorizontalAlignment.Center,
                Margin = new Thickness(0, 8, 0, 8),
                IsThumbToolTipEnabled = false,
            };
            Windows.UI.Xaml.Automation.AutomationProperties.SetName(slider, label + " " + unit);
            Grid.SetColumn(slider, column);
            Grid.SetRow(slider, 1);
            SliderGrid.Children.Add(slider);

            var labelText = new StackPanel { HorizontalAlignment = HorizontalAlignment.Center };
            labelText.Children.Add(new TextBlock
            {
                HorizontalAlignment = HorizontalAlignment.Center,
                Style = (Style)Application.Current.Resources["BodyTextStyle"],
                Text = label,
            });
            labelText.Children.Add(new TextBlock
            {
                HorizontalAlignment = HorizontalAlignment.Center,
                Style = (Style)Application.Current.Resources["LabelTextStyle"],
                Text = unit,
            });
            Grid.SetColumn(labelText, column);
            Grid.SetRow(labelText, 2);
            SliderGrid.Children.Add(labelText);
            return slider;
        }

        private void RebuildPresetBox(string selectedName)
        {
            _loading = true;
            PresetBox.Items.Clear();
            foreach (var preset in EqualizerPresets.BuiltIn)
            {
                PresetBox.Items.Add(new ComboBoxItem { Content = preset.Name, Tag = preset });
            }

            if (_userPresets.Count > 0)
            {
                PresetBox.Items.Add(new ComboBoxItem { Content = "—— 我的预设 ——", IsEnabled = false });
                foreach (var preset in _userPresets)
                {
                    PresetBox.Items.Add(new ComboBoxItem { Content = preset.Name, Tag = preset });
                }
            }

            PresetBox.SelectedItem = PresetBox.Items
                .OfType<ComboBoxItem>()
                .FirstOrDefault(item => item.Tag is EqualizerPreset p && p.Name == selectedName);
            DeleteButton.IsEnabled = SelectedPreset()?.IsBuiltIn == false;
            _loading = false;
        }

        // ── 状态 ──────────────────────────────────────────────────────────────

        private void ShowState(EqualizerState state)
        {
            _loading = true;
            EnabledSwitch.IsOn = state.Enabled;
            _preampSlider.Value = state.PreampDb;
            for (var i = 0; i < EqualizerBands.Count; i++)
            {
                _bandSliders[i].Value = state.GainsDb[i];
            }

            UpdateValueTexts();
            _loading = false;
        }

        private void UpdateValueTexts()
        {
            _preampValue.Text = FormatDb(_preampSlider.Value);
            for (var i = 0; i < EqualizerBands.Count; i++)
            {
                _bandValues[i].Text = FormatDb(_bandSliders[i].Value);
            }
        }

        /// <summary>保存并即时应用到播放器。</summary>
        private void Commit(EqualizerState state)
        {
            _state = state;
            AppServices.Equalizer.SaveState(state);
            PlaybackHost.Engine.ApplyEqualizer(state);
        }

        private double[] CurrentGains() => _bandSliders.Select(s => s.Value).ToArray();

        private EqualizerPreset SelectedPreset() => (PresetBox.SelectedItem as ComboBoxItem)?.Tag as EqualizerPreset;

        // ── 事件 ──────────────────────────────────────────────────────────────

        private void EnabledToggled(object sender, RoutedEventArgs e)
        {
            if (!_loading && _state != null)
            {
                Commit(new EqualizerState(EnabledSwitch.IsOn, _state.PreampDb, _state.GainsDb, _state.PresetName));
            }
        }

        private void OnSliderChanged()
        {
            if (_loading || _state == null)
            {
                return;
            }

            UpdateValueTexts();

            // 手动调过：仍与所选预设一致就保留名字，否则显示「自定义」。
            var gains = CurrentGains();
            var selected = SelectedPreset();
            var name = selected != null && selected.Matches(_preampSlider.Value, gains) ? selected.Name : null;
            if (name == null && PresetBox.SelectedIndex >= 0)
            {
                _loading = true;
                PresetBox.SelectedIndex = -1;
                DeleteButton.IsEnabled = false;
                _loading = false;
            }

            Commit(new EqualizerState(_state.Enabled, _preampSlider.Value, gains, name));
        }

        private void PresetChanged(object sender, SelectionChangedEventArgs e)
        {
            if (_loading)
            {
                return;
            }

            var preset = SelectedPreset();
            DeleteButton.IsEnabled = preset?.IsBuiltIn == false;
            if (preset == null)
            {
                return;
            }

            // 选预设通常就是想听效果：顺手打开均衡器。
            var state = new EqualizerState(true, preset.PreampDb, preset.GainsDb, preset.Name);
            ShowState(state);
            Commit(state);
        }

        private void ResetClick(object sender, RoutedEventArgs e)
        {
            var flat = EqualizerPresets.Flat;
            var state = new EqualizerState(_state?.Enabled ?? false, flat.PreampDb, flat.GainsDb, flat.Name);
            ShowState(state);
            RebuildPresetBox(flat.Name);
            Commit(state);
        }

        private async void SaveClick(object sender, RoutedEventArgs e)
        {
            var nameBox = new TextBox
            {
                PlaceholderText = "预设名称",
                MaxLength = EqualizerStore.MaxNameLength,
                Text = SelectedPreset()?.IsBuiltIn == false ? SelectedPreset().Name : string.Empty,
            };
            var errorText = new TextBlock
            {
                Style = (Style)Application.Current.Resources["CaptionTextStyle"],
                TextWrapping = TextWrapping.Wrap,
            };
            var content = new StackPanel { Spacing = 8 };
            content.Children.Add(nameBox);
            content.Children.Add(errorText);

            var dialog = new ContentDialog
            {
                Title = "保存为预设",
                Content = content,
                PrimaryButtonText = "保存",
                CloseButtonText = "取消",
                DefaultButton = ContentDialogButton.Primary,
            };

            // 同名用户预设会被覆盖：输入时提示，名称不合法时拦住「保存」。
            nameBox.TextChanged += (_, __) =>
            {
                var trimmed = nameBox.Text.Trim();
                errorText.Text = _userPresets.Any(p => p.Name == trimmed) ? "将覆盖同名预设" : string.Empty;
            };
            dialog.PrimaryButtonClick += (_, args) =>
            {
                var error = EqualizerStore.ValidateName(nameBox.Text);
                if (error != null)
                {
                    errorText.Text = error;
                    args.Cancel = true;
                }
            };

            if (await dialog.ShowAsync() != ContentDialogResult.Primary)
            {
                return;
            }

            var name = nameBox.Text.Trim();
            await AppServices.Equalizer.SavePresetAsync(name, _preampSlider.Value, CurrentGains());
            await ReloadPresetsAsync(name);
            Commit(new EqualizerState(_state.Enabled, _preampSlider.Value, CurrentGains(), name));
        }

        private async void DeleteClick(object sender, RoutedEventArgs e)
        {
            var preset = SelectedPreset();
            if (preset == null || preset.IsBuiltIn)
            {
                return;
            }

            var dialog = new ContentDialog
            {
                Title = "删除预设",
                Content = "删除「" + preset.Name + "」？当前的均衡器设置不受影响。",
                PrimaryButtonText = "删除",
                CloseButtonText = "取消",
                DefaultButton = ContentDialogButton.Close,
            };

            if (await dialog.ShowAsync() != ContentDialogResult.Primary)
            {
                return;
            }

            await AppServices.Equalizer.DeletePresetAsync(preset.Name);
            await ReloadPresetsAsync(null);
            Commit(new EqualizerState(_state.Enabled, _state.PreampDb, _state.GainsDb, null));
        }

        private async Task ReloadPresetsAsync(string selectedName)
        {
            _userPresets = await AppServices.Equalizer.LoadUserPresetsAsync();
            RebuildPresetBox(selectedName);
        }

        private static string FormatDb(double value) =>
            (value > 0 ? "+" : string.Empty) + value.ToString("0.#", CultureInfo.InvariantCulture);
    }
}
