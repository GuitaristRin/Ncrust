using System;

namespace Ncrust.Core.Audio
{
    /// <summary>
    /// 二阶 IIR（biquad）系数，已按 a0 归一化：
    /// y[n] = b0·x[n] + b1·x[n-1] + b2·x[n-2] − a1·y[n-1] − a2·y[n-2]。
    /// </summary>
    public sealed class BiquadCoefficients
    {
        public BiquadCoefficients(double b0, double b1, double b2, double a1, double a2)
        {
            B0 = b0;
            B1 = b1;
            B2 = b2;
            A1 = a1;
            A2 = a2;
        }

        public static BiquadCoefficients Identity { get; } = new BiquadCoefficients(1, 0, 0, 0, 0);

        public double B0 { get; }

        public double B1 { get; }

        public double B2 { get; }

        public double A1 { get; }

        public double A2 { get; }

        /// <summary>
        /// 峰值（peaking）均衡滤波器，公式取自 Robert Bristow-Johnson《Audio EQ Cookbook》。
        /// 0 dB 时返回恒等滤波器（不改变信号，也省掉计算误差）。
        /// </summary>
        public static BiquadCoefficients Peaking(double frequency, double q, double gainDb, double sampleRate)
        {
            if (Math.Abs(gainDb) < 1e-6 || sampleRate <= 0 || frequency <= 0 || frequency >= sampleRate / 2)
            {
                return Identity;
            }

            var a = Math.Pow(10, gainDb / 40);
            var w0 = 2 * Math.PI * frequency / sampleRate;
            var cos = Math.Cos(w0);
            var alpha = Math.Sin(w0) / (2 * q);

            var b0 = 1 + alpha * a;
            var b1 = -2 * cos;
            var b2 = 1 - alpha * a;
            var a0 = 1 + alpha / a;
            var a1 = -2 * cos;
            var a2 = 1 - alpha / a;

            return new BiquadCoefficients(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0);
        }

        /// <summary>频率 f 处的幅度响应（线性倍数），用于测试与界面曲线。</summary>
        public double MagnitudeAt(double frequency, double sampleRate)
        {
            var w = 2 * Math.PI * frequency / sampleRate;
            double cos1 = Math.Cos(w), sin1 = Math.Sin(w), cos2 = Math.Cos(2 * w), sin2 = Math.Sin(2 * w);
            var numRe = B0 + B1 * cos1 + B2 * cos2;
            var numIm = -(B1 * sin1 + B2 * sin2);
            var denRe = 1 + A1 * cos1 + A2 * cos2;
            var denIm = -(A1 * sin1 + A2 * sin2);
            return Math.Sqrt((numRe * numRe + numIm * numIm) / (denRe * denRe + denIm * denIm));
        }
    }
}
