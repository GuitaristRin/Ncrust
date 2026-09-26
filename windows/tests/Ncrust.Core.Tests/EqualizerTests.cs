using Ncrust.Core.Audio;
using Xunit;

namespace Ncrust.Core.Tests;

public class BiquadTests
{
    private const double Rate = 48000;

    [Theory]
    [InlineData(6)]
    [InlineData(-6)]
    [InlineData(12)]
    public void Peaking_HitsExactGainAtCentre(double gainDb)
    {
        var c = BiquadCoefficients.Peaking(1000, EqualizerBands.Q, gainDb, Rate);
        var db = 20 * Math.Log10(c.MagnitudeAt(1000, Rate));
        Assert.Equal(gainDb, db, 2);
    }

    [Fact]
    public void Peaking_LeavesFarFrequenciesAlone()
    {
        // 1 kHz 推 +12 dB，两个倍频程之外（250 Hz / 4 kHz）影响应小于 2 dB。
        var c = BiquadCoefficients.Peaking(1000, EqualizerBands.Q, 12, Rate);
        Assert.InRange(20 * Math.Log10(c.MagnitudeAt(250, Rate)), 0, 2);
        Assert.InRange(20 * Math.Log10(c.MagnitudeAt(4000, Rate)), 0, 2);
    }

    [Fact]
    public void ZeroGain_IsIdentity()
    {
        Assert.Same(BiquadCoefficients.Identity, BiquadCoefficients.Peaking(1000, 1.41, 0, Rate));
    }

    [Fact]
    public void BandAboveNyquist_IsIdentity()
    {
        // 16 kHz 段在 22.05 kHz 采样率下仍合法；在 16 kHz 采样率下超出奈奎斯特频率，必须退化为恒等。
        Assert.Same(BiquadCoefficients.Identity, BiquadCoefficients.Peaking(16000, 1.41, 6, 16000));
    }
}

public class EqualizerProcessorTests
{
    private const int Rate = 48000;

    [Fact]
    public void Disabled_LeavesSamplesUntouched()
    {
        var p = new EqualizerProcessor();
        p.Configure(false, 6, Gains(12), Rate);
        var samples = Sine(1000, 0.5, 4800, channels: 2);
        var copy = (float[])samples.Clone();

        p.Process(samples, samples.Length, 2);

        Assert.Equal(copy, samples);
        Assert.False(p.IsActive);
    }

    [Fact]
    public void Flat_IsBypass()
    {
        var p = new EqualizerProcessor();
        p.Configure(true, 0, Gains(0), Rate);
        Assert.False(p.IsActive);
    }

    [Fact]
    public void BoostAtBand_DoublesAmplitudeOfThatTone()
    {
        // 1 kHz 段（索引 5）+6 dB ≈ 幅度 ×2；稳态后测峰值。
        var gains = new double[10];
        gains[5] = 6;
        var p = new EqualizerProcessor();
        p.Configure(true, 0, gains, Rate);

        var samples = Sine(1000, 0.25, Rate, channels: 2);
        p.Process(samples, samples.Length, 2);

        var peak = Peak(samples, from: samples.Length / 2);
        Assert.InRange(peak, 0.25 * 1.9, 0.25 * 2.1);
    }

    [Fact]
    public void Preamp_ScalesLinearly()
    {
        var p = new EqualizerProcessor();
        p.Configure(true, -6, Gains(0), Rate);
        var samples = new float[] { 1f, -1f, 0.5f, -0.5f };

        p.Process(samples, samples.Length, 2);

        Assert.Equal(0.501, samples[0], 2);
        Assert.Equal(-0.501, samples[1], 2);
    }

    [Fact]
    public void Channels_AreFilteredIndependently()
    {
        // 左声道有信号、右声道静音：处理后右声道必须仍是（近似）静音，不能串进左声道的历史。
        var gains = new double[10];
        gains[5] = 12;
        var p = new EqualizerProcessor();
        p.Configure(true, 0, gains, Rate);

        var samples = Sine(1000, 0.5, 4800, channels: 2);
        for (var i = 1; i < samples.Length; i += 2)
        {
            samples[i] = 0;
        }

        p.Process(samples, samples.Length, 2);

        for (var i = 1; i < samples.Length; i += 2)
        {
            Assert.InRange(samples[i], -1e-6f, 1e-6f);
        }
    }

    [Fact]
    public void Reconfigure_KeepsProcessing()
    {
        var p = new EqualizerProcessor();
        var gains = new double[10];
        gains[0] = 6;
        p.Configure(true, 0, gains, Rate);
        var samples = Sine(62, 0.1, 4800, channels: 1);
        p.Process(samples, samples.Length, 1);

        // 某段跨过 0 dB（槽位不变）后继续处理，不应产生 NaN 或爆音。
        gains[0] = 0;
        gains[1] = 6;
        p.Configure(true, 0, gains, Rate);
        var next = Sine(62, 0.1, 4800, channels: 1);
        p.Process(next, next.Length, 1);

        Assert.All(next, s => Assert.InRange(s, -1f, 1f));
    }

    private static double[] Gains(double value) => Enumerable.Repeat(value, 10).ToArray();

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

    private static double Peak(float[] samples, int from)
    {
        double peak = 0;
        for (var i = from; i < samples.Length; i++)
        {
            peak = Math.Max(peak, Math.Abs(samples[i]));
        }

        return peak;
    }
}

public class EqualizerStoreTests
{
    [Fact]
    public void State_RoundTrips()
    {
        var store = new EqualizerStore(new FakeSettings(), new InMemoryFileStore());
        var gains = new[] { 1.5, -2, 3, 0, 0, 0, 0, 0, 0, 12 };
        store.SaveState(new EqualizerState(true, -3.5, gains, "我的预设"));

        var state = store.LoadState();

        Assert.True(state.Enabled);
        Assert.Equal(-3.5, state.PreampDb);
        Assert.Equal(gains, state.GainsDb);
        Assert.Equal("我的预设", state.PresetName);
    }

    [Fact]
    public void State_DefaultsToDisabledFlat()
    {
        var state = new EqualizerStore(new FakeSettings(), new InMemoryFileStore()).LoadState();
        Assert.False(state.Enabled);
        Assert.Equal(10, state.GainsDb.Count);
        Assert.All(state.GainsDb, g => Assert.Equal(0, g));
        Assert.Equal(EqualizerPresets.FlatName, state.PresetName);
    }

    [Fact]
    public async Task Presets_SaveOverwriteAndDelete()
    {
        var store = new EqualizerStore(new FakeSettings(), new InMemoryFileStore());

        Assert.Null(await store.SavePresetAsync("  夜间 \"耳机\"  ", -2, new double[] { 3, 2, 1, 0, 0, 0, 0, 0, 0, 0 }));
        Assert.Null(await store.SavePresetAsync("车载", 0, new double[] { 5, 4, 0, 0, 0, 0, 0, 0, 0, 0 }));
        Assert.Null(await store.SavePresetAsync("夜间 \"耳机\"", -4, new double[] { 1, 1, 1, 1, 1, 1, 1, 1, 1, 1 }));

        var presets = await store.LoadUserPresetsAsync();
        Assert.Equal(new[] { "夜间 \"耳机\"", "车载" }, presets.Select(p => p.Name));
        Assert.Equal(-4, presets[0].PreampDb); // 同名覆盖，名称已去首尾空格、引号能正确转义
        Assert.All(presets[0].GainsDb, g => Assert.Equal(1, g));

        await store.DeletePresetAsync("车载");
        Assert.Single(await store.LoadUserPresetsAsync());
    }

    [Theory]
    [InlineData("")]
    [InlineData("   ")]
    [InlineData("摇滚")] // 与内置重名
    [InlineData("一二三四五六七八九十一二三四五六七八九十一二三四五")] // 25 字，超长
    public async Task Presets_RejectInvalidNames(string name)
    {
        var store = new EqualizerStore(new FakeSettings(), new InMemoryFileStore());
        Assert.NotNull(await store.SavePresetAsync(name, 0, new double[10]));
        Assert.Empty(await store.LoadUserPresetsAsync());
    }

    [Fact]
    public async Task CorruptFile_YieldsNoPresets()
    {
        var files = new InMemoryFileStore();
        await files.WriteTextAsync(EqualizerStore.PresetsFileName, "{not json");
        Assert.Empty(await new EqualizerStore(new FakeSettings(), files).LoadUserPresetsAsync());
    }

    [Fact]
    public void BuiltIns_AreValidAndFlatFirst()
    {
        Assert.Equal(EqualizerPresets.FlatName, EqualizerPresets.BuiltIn[0].Name);
        Assert.All(EqualizerPresets.BuiltIn, p =>
        {
            Assert.True(p.IsBuiltIn);
            Assert.Equal(10, p.GainsDb.Count);
            Assert.All(p.GainsDb, g => Assert.InRange(g, EqualizerBands.MinGainDb, EqualizerBands.MaxGainDb));
        });
        Assert.True(EqualizerPresets.Flat.Matches(0, new double[10]));
    }
}
