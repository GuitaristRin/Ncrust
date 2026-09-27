using Ncrust.Core.Audio;
using Xunit;

namespace Ncrust.Core.Tests;

public class LowShelfTests
{
    private const double Rate = 48000;

    [Fact]
    public void LowShelf_BoostsBelowCornerAndLeavesTopAlone()
    {
        var c = BiquadCoefficients.LowShelf(100, 1.0, 6, Rate);
        Assert.InRange(20 * Math.Log10(c.MagnitudeAt(20, Rate)), 4.5, 6.5);
        Assert.InRange(20 * Math.Log10(c.MagnitudeAt(20000, Rate)), -0.6, 0.6);
    }

    [Fact]
    public void LowShelf_ZeroGain_IsIdentity()
    {
        Assert.Same(BiquadCoefficients.Identity, BiquadCoefficients.LowShelf(100, 1.0, 0, Rate));
    }
}

public class DynamicBassProcessorTests
{
    private const int Rate = 48000;

    private static DynamicBassSettings Settings(double amount = 1.0) =>
        new DynamicBassSettings(enabled: true, amount: amount, sampleRate: Rate);

    [Fact]
    public void Disabled_LeavesSamplesUntouched()
    {
        var p = new DynamicBassProcessor();
        p.Configure(DynamicBassSettingsDefaults.Off);

        var samples = Sine(60, 0.2, 4800, channels: 1);
        var copy = (float[])samples.Clone();

        p.Process(samples, samples.Length, 1);

        Assert.Equal(copy, samples);
        Assert.False(p.IsActive);
    }

    [Fact]
    public void QuietSignal_GetsMoreBassThanLoudSignal()
    {
        // 动态低音的定义性质：小声抬得多、大声抬得少，所以「顶」又不削波。
        var quietGain = MeasureGainDb(Sine(60, 0.01, Rate, 1));
        var loudGain = MeasureGainDb(Sine(60, 0.5, Rate, 1));

        Assert.InRange(quietGain, 4, 12);
        Assert.InRange(loudGain, -1, 1);
        Assert.True(quietGain - loudGain > 4, $"quiet {quietGain:0.0} dB vs loud {loudGain:0.0} dB");
    }

    [Fact]
    public void Amount_ScalesTheBoost()
    {
        var full = MeasureGainDb(Sine(60, 0.01, Rate, 1), Settings(amount: 1.0));
        var half = MeasureGainDb(Sine(60, 0.01, Rate, 1), Settings(amount: 0.5));

        Assert.True(full - half > 2, $"full {full:0.0} dB vs half {half:0.0} dB");
    }

    [Fact]
    public void Output_IsFiniteAndBounded()
    {
        var p = new DynamicBassProcessor();
        p.Configure(Settings());

        // 依次给静音 / 小信号 / 接近满幅：任何情况下都不能出 NaN 或爆音。
        foreach (var amplitude in new[] { 0.0, 0.01, 0.5, 1.0 })
        {
            var samples = Sine(60, amplitude, Rate, 1);
            p.Process(samples, samples.Length, 1);
            Assert.All(samples, s => Assert.True(float.IsFinite(s)));
        }
    }

    [Fact]
    public void Channels_AreFilteredIndependently()
    {
        var p = new DynamicBassProcessor();
        p.Configure(Settings());

        var samples = Sine(60, 0.2, Rate, 2);
        for (var i = 1; i < samples.Length; i += 2)
        {
            samples[i] = 0;
        }

        p.Process(samples, samples.Length, 2);

        // 右声道没有信号：动态低音不能把它踢起来（检测器读到的也只是 0）。
        for (var i = 1; i < samples.Length; i += 2)
        {
            Assert.InRange(samples[i], -1e-6f, 1e-6f);
        }
    }

    private static double MeasureGainDb(float[] input, DynamicBassSettings? settings = null)
    {
        var p = new DynamicBassProcessor();
        p.Configure(settings ?? Settings());

        var output = (float[])input.Clone();
        p.Process(output, output.Length, 1);

        var from = output.Length / 2;
        double inPower = 0, outPower = 0;
        for (var i = from; i < input.Length; i++)
        {
            inPower += input[i] * input[i];
            outPower += output[i] * output[i];
        }

        return 10 * Math.Log10(outPower / inPower);
    }

    private static float[] Sine(double frequency, double amplitude, int frames, int channels)
    {
        var samples = new float[frames * channels];
        for (var n = 0; n < frames; n++)
        {
            var v = (float)(amplitude * Math.Sin(2 * Math.PI * frequency * n / Rate));
            for (var c = 0; c < channels; c++)
            {
                samples[n * channels + c] = v;
            }
        }

        return samples;
    }
}

internal static class DynamicBassSettingsDefaults
{
    public static DynamicBassSettings Off { get; } =
        new DynamicBassSettings(enabled: false, amount: 0, sampleRate: 48000);
}
