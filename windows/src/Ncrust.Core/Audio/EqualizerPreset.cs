using System;
using System.Collections.Generic;
using System.Linq;

namespace Ncrust.Core.Audio
{
    /// <summary>均衡器预设：名称 + 前级增益 + 10 段增益（dB）。</summary>
    public sealed class EqualizerPreset
    {
        public EqualizerPreset(string name, double preampDb, IReadOnlyList<double> gainsDb, bool isBuiltIn = false)
        {
            Name = name ?? throw new ArgumentNullException(nameof(name));
            PreampDb = EqualizerBands.Clamp(preampDb);
            GainsDb = Normalize(gainsDb);
            IsBuiltIn = isBuiltIn;
        }

        public string Name { get; }

        public double PreampDb { get; }

        /// <summary>恰好 10 个值，已夹到 ±12 dB。</summary>
        public IReadOnlyList<double> GainsDb { get; }

        public bool IsBuiltIn { get; }

        /// <summary>参数是否与给定值一致（容差 0.05 dB），用于判断当前设置是否仍是某个预设。</summary>
        public bool Matches(double preampDb, IReadOnlyList<double> gainsDb)
        {
            if (Math.Abs(PreampDb - preampDb) > 0.05 || gainsDb == null || gainsDb.Count != EqualizerBands.Count)
            {
                return false;
            }

            for (var i = 0; i < EqualizerBands.Count; i++)
            {
                if (Math.Abs(GainsDb[i] - gainsDb[i]) > 0.05)
                {
                    return false;
                }
            }

            return true;
        }

        internal static IReadOnlyList<double> Normalize(IReadOnlyList<double>? gainsDb)
        {
            var values = new double[EqualizerBands.Count];
            if (gainsDb != null)
            {
                for (var i = 0; i < values.Length && i < gainsDb.Count; i++)
                {
                    values[i] = EqualizerBands.Clamp(gainsDb[i]);
                }
            }

            return values;
        }
    }

    /// <summary>
    /// 内置预设。前级增益取「最大提升量的一半」左右的负值，给提升留出余量、减少削波。
    /// 顺序即界面顺序；「平直」必须第一个（重置用它）。
    /// </summary>
    public static class EqualizerPresets
    {
        public const string FlatName = "平直";

        public static readonly IReadOnlyList<EqualizerPreset> BuiltIn = new[]
        {
            Preset(FlatName, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            Preset("流行", -2, -1, 1, 3, 4, 3, 0, -1, -1, 0, 1),
            Preset("摇滚", -3, 5, 4, 3, 1, -1, -1, 1, 3, 4, 5),
            Preset("爵士", -2, 3, 2, 1, 2, -1, -1, 0, 1, 2, 3),
            Preset("古典", -2, 4, 3, 2, 1, 0, 0, 0, 1, 2, 3),
            Preset("电子", -3, 6, 5, 2, 0, -2, -1, 1, 3, 5, 5),
            Preset("人声", -2, -2, -2, -1, 1, 3, 4, 3, 1, 0, -1),
            Preset("低音增强", -4, 7, 6, 5, 3, 1, 0, 0, 0, 0, 0),
            Preset("高音增强", -4, 0, 0, 0, 0, 0, 1, 3, 5, 6, 7),
        };

        public static EqualizerPreset Flat => BuiltIn[0];

        public static EqualizerPreset? FindBuiltIn(string? name) =>
            name == null ? null : BuiltIn.FirstOrDefault(p => p.Name == name);

        private static EqualizerPreset Preset(string name, double preamp, params double[] gains) =>
            new EqualizerPreset(name, preamp, gains, isBuiltIn: true);
    }
}
