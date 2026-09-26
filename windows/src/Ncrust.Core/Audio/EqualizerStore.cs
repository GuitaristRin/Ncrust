using System;
using System.Collections.Generic;
using System.Globalization;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using Ncrust.Core.Json;
using Ncrust.Core.Net;
using Ncrust.Core.Platform;

namespace Ncrust.Core.Audio
{
    /// <summary>均衡器当前状态：开关、前级、10 段增益、最后选用的预设名（可能已被手动改过）。</summary>
    public sealed class EqualizerState
    {
        public EqualizerState(bool enabled, double preampDb, IReadOnlyList<double> gainsDb, string? presetName)
        {
            Enabled = enabled;
            PreampDb = EqualizerBands.Clamp(preampDb);
            GainsDb = EqualizerPreset.Normalize(gainsDb);
            PresetName = presetName;
        }

        public bool Enabled { get; }

        public double PreampDb { get; }

        public IReadOnlyList<double> GainsDb { get; }

        public string? PresetName { get; }
    }

    /// <summary>
    /// 均衡器持久化。当前状态放 <see cref="ISettingsStore"/>（小，键前缀 eq_）；
    /// 用户命名预设放 <see cref="IFileStore"/> 的 <c>eq_presets.json</c>。
    /// Android 端没有均衡器，这是 Windows 端新增的功能。
    /// </summary>
    public sealed class EqualizerStore
    {
        public const string PresetsFileName = "eq_presets.json";
        public const int MaxNameLength = 24;

        private const string EnabledKey = "eq_enabled";
        private const string PreampKey = "eq_preamp";
        private const string GainsKey = "eq_gains";
        private const string PresetKey = "eq_preset";

        private readonly ISettingsStore _settings;
        private readonly IFileStore _files;

        public EqualizerStore(ISettingsStore settings, IFileStore files)
        {
            _settings = settings ?? throw new ArgumentNullException(nameof(settings));
            _files = files ?? throw new ArgumentNullException(nameof(files));
        }

        public EqualizerState LoadState()
        {
            var gains = ParseDoubles(_settings.GetString(GainsKey));
            var preamp = ParseDouble(_settings.GetString(PreampKey)) ?? 0;
            var preset = _settings.GetString(PresetKey) ?? EqualizerPresets.FlatName;
            return new EqualizerState(_settings.GetBool(EnabledKey, false), preamp, gains, preset);
        }

        public void SaveState(EqualizerState state)
        {
            _settings.SetBool(EnabledKey, state.Enabled);
            _settings.SetString(PreampKey, Format(state.PreampDb));
            _settings.SetString(GainsKey, string.Join(",", state.GainsDb.Select(Format)));
            _settings.SetString(PresetKey, state.PresetName);
        }

        /// <summary>用户预设（按保存顺序）。文件损坏时返回空列表，不抛异常。</summary>
        public async Task<IReadOnlyList<EqualizerPreset>> LoadUserPresetsAsync()
        {
            var raw = await _files.ReadTextAsync(PresetsFileName).ConfigureAwait(false);
            if (string.IsNullOrWhiteSpace(raw))
            {
                return Array.Empty<EqualizerPreset>();
            }

            try
            {
                var root = JsonValue.Parse(raw!);
                var list = new List<EqualizerPreset>();
                foreach (var item in root.Items)
                {
                    var name = item.GetString("name");
                    if (ValidateName(name) != null)
                    {
                        continue;
                    }

                    var gains = item.GetArray("gains")?.Select(v => v.DoubleValue).ToList() ?? new List<double>();
                    list.Add(new EqualizerPreset(name!.Trim(), item.GetDouble("preamp"), gains));
                }

                return list;
            }
            catch (JsonParseException)
            {
                return Array.Empty<EqualizerPreset>();
            }
        }

        /// <summary>
        /// 保存为命名预设；同名用户预设会被覆盖。名称不合法（空、过长、与内置预设重名）时返回错误说明，成功返回 null。
        /// </summary>
        public async Task<string?> SavePresetAsync(string? name, double preampDb, IReadOnlyList<double> gainsDb)
        {
            var error = ValidateName(name);
            if (error != null)
            {
                return error;
            }

            var trimmed = name!.Trim();
            var presets = (await LoadUserPresetsAsync().ConfigureAwait(false)).ToList();
            var index = presets.FindIndex(p => p.Name == trimmed);
            var preset = new EqualizerPreset(trimmed, preampDb, gainsDb);
            if (index >= 0)
            {
                presets[index] = preset;
            }
            else
            {
                presets.Add(preset);
            }

            await WriteAsync(presets).ConfigureAwait(false);
            return null;
        }

        public async Task DeletePresetAsync(string name)
        {
            var presets = (await LoadUserPresetsAsync().ConfigureAwait(false)).ToList();
            if (presets.RemoveAll(p => p.Name == name) > 0)
            {
                await WriteAsync(presets).ConfigureAwait(false);
            }
        }

        /// <summary>名称校验：合法返回 null，否则返回给用户看的原因。</summary>
        public static string? ValidateName(string? name)
        {
            var trimmed = name?.Trim() ?? string.Empty;
            if (trimmed.Length == 0)
            {
                return "请输入预设名称";
            }

            if (trimmed.Length > MaxNameLength)
            {
                return "名称不能超过 " + MaxNameLength + " 个字符";
            }

            if (EqualizerPresets.FindBuiltIn(trimmed) != null)
            {
                return "不能与内置预设重名";
            }

            return null;
        }

        private Task WriteAsync(IEnumerable<EqualizerPreset> presets)
        {
            var sb = new StringBuilder("[");
            var first = true;
            foreach (var preset in presets)
            {
                if (!first)
                {
                    sb.Append(',');
                }

                first = false;
                sb.Append("{\"name\":").Append(JsonText.Escape(preset.Name))
                  .Append(",\"preamp\":").Append(Format(preset.PreampDb))
                  .Append(",\"gains\":[").Append(string.Join(",", preset.GainsDb.Select(Format))).Append("]}");
            }

            sb.Append(']');
            return _files.WriteTextAsync(PresetsFileName, sb.ToString());
        }

        private static string Format(double value) => value.ToString("0.##", CultureInfo.InvariantCulture);

        private static double? ParseDouble(string? text) =>
            double.TryParse(text, NumberStyles.Float, CultureInfo.InvariantCulture, out var value) ? value : (double?)null;

        private static IReadOnlyList<double> ParseDoubles(string? text) =>
            string.IsNullOrWhiteSpace(text)
                ? Array.Empty<double>()
                : text!.Split(',').Select(part => ParseDouble(part) ?? 0).ToList();
    }
}
