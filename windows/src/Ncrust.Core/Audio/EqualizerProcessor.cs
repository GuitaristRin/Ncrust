using System;
using System.Collections.Generic;
using System.Threading;

namespace Ncrust.Core.Audio
{
    /// <summary>
    /// 10 段均衡器 DSP：10 个串联的峰值 biquad + 前级增益，处理交错排列的 32 位浮点样本。
    ///
    /// 线程模型：<see cref="Configure"/> 在界面线程上调用，<see cref="Process"/> 在音频线程上调用。
    /// 系数打包成不可变快照整体替换（一次引用写入），音频线程每帧读一次快照，不加锁、不会读到半新半旧的系数。
    /// 滤波器状态（历史样本）只由音频线程访问；换系数时保留状态，调参数不会爆音。
    /// </summary>
    public sealed class EqualizerProcessor
    {
        // 防止滤波器在静音时衰减进非规格化浮点（denormal）导致 CPU 飙升。
        private const double AntiDenormal = 1e-20;

        private Snapshot _snapshot = Snapshot.Bypass;
        private double[] _state = Array.Empty<double>();
        private int _stateChannels;

        /// <summary>当前是否实际参与处理（关闭或全部 0 dB 时直通）。</summary>
        public bool IsActive => Volatile.Read(ref _snapshot).Active;

        /// <summary>按新参数重算系数。任意线程调用；下一帧生效。</summary>
        public void Configure(bool enabled, double preampDb, IReadOnlyList<double> gainsDb, double sampleRate)
        {
            if (!enabled || sampleRate <= 0 || gainsDb == null)
            {
                Volatile.Write(ref _snapshot, Snapshot.Bypass);
                return;
            }

            // 始终保留 10 个槽位（0 dB 的段是恒等滤波器）：槽位固定，某段跨过 0 dB 时历史状态不会错位到
            // 别的滤波器上（错位会听到一下「咔哒」）。
            var filters = new BiquadCoefficients[EqualizerBands.Count];
            var anyBand = false;
            for (var i = 0; i < EqualizerBands.Count; i++)
            {
                var gain = i < gainsDb.Count ? EqualizerBands.Clamp(gainsDb[i]) : 0;
                filters[i] = BiquadCoefficients.Peaking(EqualizerBands.Frequencies[i], EqualizerBands.Q, gain, sampleRate);
                anyBand |= !ReferenceEquals(filters[i], BiquadCoefficients.Identity);
            }

            var preamp = Math.Pow(10, EqualizerBands.Clamp(preampDb) / 20);
            var active = anyBand || Math.Abs(preamp - 1) > 1e-9;
            Volatile.Write(ref _snapshot, active ? new Snapshot(filters, preamp) : Snapshot.Bypass);
        }

        /// <summary>原地处理 <paramref name="count"/> 个交错样本（<paramref name="channels"/> 声道）。仅音频线程调用。</summary>
        public void Process(float[] samples, int count, int channels)
        {
            var snapshot = Volatile.Read(ref _snapshot);
            if (!snapshot.Active || samples == null || channels <= 0)
            {
                return;
            }

            var filters = snapshot.Filters;
            EnsureState(channels);
            var state = _state;
            var gain = snapshot.Preamp;
            count = Math.Min(count, samples.Length);

            for (var i = 0; i < count; i++)
            {
                var channel = i % channels;
                double x = samples[i] * gain;

                for (var f = 0; f < filters.Length; f++)
                {
                    var c = filters[f];
                    if (ReferenceEquals(c, BiquadCoefficients.Identity))
                    {
                        continue;
                    }

                    var s = (channel * EqualizerBands.Count + f) * 4;

                    // state: x1, x2, y1, y2
                    var y = c.B0 * x + c.B1 * state[s] + c.B2 * state[s + 1] - c.A1 * state[s + 2] - c.A2 * state[s + 3]
                            + AntiDenormal;
                    state[s + 1] = state[s];
                    state[s] = x;
                    state[s + 3] = state[s + 2];
                    state[s + 2] = y;
                    x = y;
                }

                samples[i] = (float)x;
            }
        }

        /// <summary>清空历史样本（换曲、跳转时调用，避免把上一段的尾音带进来）。仅音频线程调用。</summary>
        public void Reset()
        {
            Array.Clear(_state, 0, _state.Length);
        }

        private void EnsureState(int channels)
        {
            if (_stateChannels != channels)
            {
                // 每声道 10 个槽位 × (x1, x2, y1, y2)。声道数变化时重建。
                _state = new double[channels * EqualizerBands.Count * 4];
                _stateChannels = channels;
            }
        }

        private sealed class Snapshot
        {
            public static readonly Snapshot Bypass = new Snapshot(Array.Empty<BiquadCoefficients>(), 1.0, active: false);

            public Snapshot(BiquadCoefficients[] filters, double preamp, bool active = true)
            {
                Filters = filters;
                Preamp = preamp;
                Active = active;
            }

            public BiquadCoefficients[] Filters { get; }

            public double Preamp { get; }

            public bool Active { get; }
        }
    }
}
