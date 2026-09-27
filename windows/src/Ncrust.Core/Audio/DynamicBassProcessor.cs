using System;
using System.Threading;

namespace Ncrust.Core.Audio
{
    /// <summary>
    /// 动态低音（Clear Bass 类似物）参数：一个低架滤波器，架起量随输入电平变化——
    /// 小声时抬得多，大声时抬得少，所以既「顶」又不容易削波。
    ///
    /// 拟合自索尼 Clear Bass 的公开行为与 ADI「Dynamic Bass Boost」的通用结构：
    /// 低频侧链检测 → 电平映射到架起量 → 可变低架。字段含义见各属性注释。
    /// </summary>
    public sealed class DynamicBassSettings
    {
        public const double DefaultFrequency = 100.0;
        public const double DefaultMaxBoostDb = 10.0;
        public const double DefaultDetectorFrequency = 200.0;
        public const double DefaultLowLevelDb = -40.0;
        public const double DefaultHighLevelDb = -10.0;
        public const double DefaultAttackMs = 20.0;
        public const double DefaultReleaseMs = 250.0;

        public DynamicBassSettings(
            bool enabled,
            double amount,
            double sampleRate,
            double frequency = DefaultFrequency,
            double maxBoostDb = DefaultMaxBoostDb,
            double minBoostDb = 0.0,
            double detectorFrequency = DefaultDetectorFrequency,
            double lowLevelDb = DefaultLowLevelDb,
            double highLevelDb = DefaultHighLevelDb,
            double attackMs = DefaultAttackMs,
            double releaseMs = DefaultReleaseMs,
            double shelfSlope = 1.0)
        {
            Enabled = enabled;
            Amount = Clamp(amount, 0, 1);
            SampleRate = sampleRate;
            Frequency = frequency;
            MaxBoostDb = maxBoostDb;
            MinBoostDb = minBoostDb;
            DetectorFrequency = detectorFrequency;
            LowLevelDb = lowLevelDb;
            HighLevelDb = highLevelDb;
            AttackMs = Clamp(attackMs, 0.1, 2000);
            ReleaseMs = Clamp(releaseMs, 0.1, 5000);
            ShelfSlope = Clamp(shelfSlope, 0.01, 10);
        }

        /// <summary>总开关。</summary>
        public bool Enabled { get; }

        /// <summary>强度 0..1，线性缩放最大 / 最小提升量（对应 Clear Bass 的档位）。</summary>
        public double Amount { get; }

        public double SampleRate { get; }

        /// <summary>低架拐点（Hz）。</summary>
        public double Frequency { get; }

        /// <summary>电平很低时的架起量（dB）。</summary>
        public double MaxBoostDb { get; }

        /// <summary>电平很高时的架起量（dB），通常 0（也可为负，用于「减低频」）。</summary>
        public double MinBoostDb { get; }

        /// <summary>电平检测侧链的低通截止（Hz），只让低音进入检测器。</summary>
        public double DetectorFrequency { get; }

        /// <summary>低于此电平（dBFS）给满 <see cref="MaxBoostDb"/>。</summary>
        public double LowLevelDb { get; }

        /// <summary>高于此电平（dBFS）给 <see cref="MinBoostDb"/>。</summary>
        public double HighLevelDb { get; }

        public double AttackMs { get; }

        public double ReleaseMs { get; }

        /// <summary>低架斜率 S。</summary>
        public double ShelfSlope { get; }

        /// <summary>当前是否实际参与处理。</summary>
        public bool Active =>
            Enabled &&
            SampleRate > 0 &&
            Frequency > 0 &&
            Frequency < SampleRate / 2 &&
            HighLevelDb > LowLevelDb &&
            Math.Abs(MaxBoostDb - MinBoostDb) > 1e-3;

        private static double Clamp(double value, double min, double max) =>
            value < min ? min : value > max ? max : value;
    }

    /// <summary>
    /// 动态低音 DSP：对交错排列的 32 位浮点样本做「随电平变化的低架提升」。
    ///
    /// 与 <see cref="EqualizerProcessor"/> 相同的线程模型：<see cref="Configure"/> 在任意线程调用，
    /// 写一个不可变快照；<see cref="Process"/> 只在音频线程读快照，滤波器状态只由音频线程访问，不加锁。
    /// 每个声道独立检测、独立滤波。
    /// </summary>
    public sealed class DynamicBassProcessor
    {
        // 防止滤波器在静音时衰减进非规格化浮点（denormal）导致 CPU 飙升。
        private const double AntiDenormal = 1e-20;

        // 架起量变化超过这个量（dB）才重算滤波器系数：省掉每样本的三角函数，也避免系数抖动。
        private const double CoefficientUpdateThresholdDb = 0.05;

        // 架起量对目标值的平滑时间（秒）：让系数跟着检测包络缓慢走，不会有「拉链」噪声。
        private const double GainSmoothSeconds = 0.015;

        private Settings _settings = Settings.Off;
        private double[] _detector = Array.Empty<double>();
        private double[] _envelope = Array.Empty<double>();
        private double[] _gainDb = Array.Empty<double>();
        private double[] _coefGainDb = Array.Empty<double>();
        private double[] _b0 = Array.Empty<double>();
        private double[] _b1 = Array.Empty<double>();
        private double[] _b2 = Array.Empty<double>();
        private double[] _a1 = Array.Empty<double>();
        private double[] _a2 = Array.Empty<double>();
        private double[] _x1 = Array.Empty<double>();
        private double[] _x2 = Array.Empty<double>();
        private double[] _y1 = Array.Empty<double>();
        private double[] _y2 = Array.Empty<double>();
        private int _channels;

        /// <summary>当前是否实际参与处理。</summary>
        public bool IsActive => Volatile.Read(ref _settings).Active;

        /// <summary>按新参数重建。任意线程调用；下一帧生效。</summary>
        public void Configure(DynamicBassSettings settings)
        {
            if (settings == null || !settings.Active)
            {
                Volatile.Write(ref _settings, Settings.Off);
                return;
            }

            var rate = settings.SampleRate;
            var detectorA = Math.Exp(-2 * Math.PI * settings.DetectorFrequency / rate);
            var attack = Math.Exp(-1 / (settings.AttackMs / 1000.0 * rate));
            var release = Math.Exp(-1 / (settings.ReleaseMs / 1000.0 * rate));
            var gainSmooth = Math.Exp(-1 / (GainSmoothSeconds * rate));

            Volatile.Write(ref _settings, new Settings(settings, detectorA, attack, release, gainSmooth));
        }

        /// <summary>原地处理 <paramref name="count"/> 个交错样本。仅音频线程调用。</summary>
        public void Process(float[] samples, int count, int channels)
        {
            var settings = Volatile.Read(ref _settings);
            if (!settings.Active || samples == null || channels <= 0)
            {
                return;
            }

            EnsureState(channels);
            count = Math.Min(count, samples.Length);

            for (var i = 0; i < count; i++)
            {
                var c = i % channels;
                var x = samples[i];

                // 低频侧链 + RMS 包络检测（对每个声道独立）。
                var bass = settings.DetectorA * _detector[c] + (1 - settings.DetectorA) * x;
                _detector[c] = bass;
                var power = bass * bass;
                var envelope = _envelope[c];
                var envelopeCoef = power > envelope ? settings.Attack : settings.Release;
                envelope = envelopeCoef * envelope + (1 - envelopeCoef) * power;
                _envelope[c] = envelope;

                var levelDb = 20 * Math.Log10(Math.Sqrt(envelope) + 1e-9);
                var target = settings.BoostAt(levelDb);

                // 让架起量缓慢跟随目标，避免系数跳变。
                var gain = settings.GainSmooth * _gainDb[c] + (1 - settings.GainSmooth) * target;
                _gainDb[c] = gain;

                if (Math.Abs(gain - _coefGainDb[c]) > CoefficientUpdateThresholdDb)
                {
                    var shelf = BiquadCoefficients.LowShelf(
                        settings.Frequency, settings.ShelfSlope, gain, settings.SampleRate);
                    _b0[c] = shelf.B0;
                    _b1[c] = shelf.B1;
                    _b2[c] = shelf.B2;
                    _a1[c] = shelf.A1;
                    _a2[c] = shelf.A2;
                    _coefGainDb[c] = gain;
                }

                var y = _b0[c] * x + _b1[c] * _x1[c] + _b2[c] * _x2[c]
                        - _a1[c] * _y1[c] - _a2[c] * _y2[c] + AntiDenormal;
                _x2[c] = _x1[c];
                _x1[c] = x;
                _y2[c] = _y1[c];
                _y1[c] = y;
                samples[i] = (float)y;
            }
        }

        /// <summary>清空历史（换曲、跳转时调用）。仅音频线程调用。</summary>
        public void Reset()
        {
            if (_detector.Length == 0)
            {
                return;
            }

            Array.Clear(_detector, 0, _detector.Length);
            Array.Clear(_envelope, 0, _envelope.Length);
            Array.Clear(_gainDb, 0, _gainDb.Length);
            Array.Clear(_coefGainDb, 0, _coefGainDb.Length);
            Array.Clear(_x1, 0, _x1.Length);
            Array.Clear(_x2, 0, _x2.Length);
            Array.Clear(_y1, 0, _y1.Length);
            Array.Clear(_y2, 0, _y2.Length);
        }

        private void EnsureState(int channels)
        {
            if (_channels == channels && _detector.Length > 0)
            {
                return;
            }

            _channels = channels;
            _detector = new double[channels];
            _envelope = new double[channels];
            _gainDb = new double[channels];
            _coefGainDb = new double[channels];
            _b0 = new double[channels];
            _b1 = new double[channels];
            _b2 = new double[channels];
            _a1 = new double[channels];
            _a2 = new double[channels];
            _x1 = new double[channels];
            _x2 = new double[channels];
            _y1 = new double[channels];
            _y2 = new double[channels];

            // 起步给恒等滤波（0 dB 架），首样本不会因为系数未算而突变。
            for (var c = 0; c < channels; c++)
            {
                _b0[c] = 1;
            }
        }

        /// <summary>不可变快照：一个引用写入，音频线程整份读取。</summary>
        private sealed class Settings
        {
            public static readonly Settings Off = new Settings();

            private readonly double _lowLevelDb;
            private readonly double _highLevelDb;
            private readonly double _maxBoostDb;
            private readonly double _minBoostDb;

            private Settings()
            {
            }

            public Settings(DynamicBassSettings source, double detectorA, double attack, double release, double gainSmooth)
            {
                Active = true;
                SampleRate = source.SampleRate;
                Frequency = source.Frequency;
                ShelfSlope = source.ShelfSlope;
                DetectorA = detectorA;
                Attack = attack;
                Release = release;
                GainSmooth = gainSmooth;
                _lowLevelDb = source.LowLevelDb;
                _highLevelDb = source.HighLevelDb;
                _maxBoostDb = source.MaxBoostDb * source.Amount;
                _minBoostDb = source.MinBoostDb * source.Amount;
            }

            public bool Active { get; }

            public double SampleRate { get; }

            public double Frequency { get; }

            public double ShelfSlope { get; }

            public double DetectorA { get; }

            public double Attack { get; }

            public double Release { get; }

            public double GainSmooth { get; }

            /// <summary>把检测到的电平（dBFS）映射成架起量（dB）：低电平给满、高电平给最小，中间线性过渡。</summary>
            public double BoostAt(double levelDb)
            {
                if (levelDb <= _lowLevelDb)
                {
                    return _maxBoostDb;
                }

                if (levelDb >= _highLevelDb)
                {
                    return _minBoostDb;
                }

                var t = (levelDb - _lowLevelDb) / (_highLevelDb - _lowLevelDb);
                return _maxBoostDb + (_minBoostDb - _maxBoostDb) * t;
            }
        }
    }
}
