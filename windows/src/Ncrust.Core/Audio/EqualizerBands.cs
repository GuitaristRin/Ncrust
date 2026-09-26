using System.Collections.Generic;

namespace Ncrust.Core.Audio
{
    /// <summary>10 段均衡器的频段定义（ISO 倍频程中心频率，与 Winamp / Foobar 等常见 10 段一致）。</summary>
    public static class EqualizerBands
    {
        public const int Count = 10;

        /// <summary>单段增益范围（dB）。</summary>
        public const double MinGainDb = -12.0;

        public const double MaxGainDb = 12.0;

        /// <summary>
        /// 峰值滤波器的 Q。倍频程间距的频段取 √2 ≈ 1.41，相邻频段在 -3dB 处衔接，整体推一段不会出现明显凹坑。
        /// </summary>
        public const double Q = 1.41;

        public static readonly IReadOnlyList<double> Frequencies = new[]
        {
            31.0, 62.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0,
        };

        /// <summary>频段标签（界面用）。</summary>
        public static readonly IReadOnlyList<string> Labels = new[]
        {
            "31", "62", "125", "250", "500", "1k", "2k", "4k", "8k", "16k",
        };

        public static double Clamp(double gainDb) =>
            gainDb < MinGainDb ? MinGainDb : gainDb > MaxGainDb ? MaxGainDb : gainDb;
    }
}
