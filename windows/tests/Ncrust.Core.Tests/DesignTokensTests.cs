using System.Text.Json;
using Xunit;

namespace Ncrust.Core.Tests;

/// <summary>
/// spec/design/tokens.json 的自洽性检查。Windows 端的 Kanesumi.Xaml 与 Composition 动画
/// 都直接使用这里的取值，所以取值本身要先经得起验证。
/// </summary>
public class DesignTokensTests
{
    private static JsonElement Tokens => Spec.LoadJson("design", "tokens.json").RootElement;

    [Fact]
    public void Accents_AreSixPresetsIndexedFromZero_WithDefaultZero()
    {
        var accents = Tokens.GetProperty("color").GetProperty("accents");
        Assert.Equal(0, accents.GetProperty("default").GetInt32());

        var presets = accents.GetProperty("presets").EnumerateArray().ToList();
        Assert.Equal(6, presets.Count);
        for (var i = 0; i < presets.Count; i++)
        {
            Assert.Equal(i, presets[i].GetProperty("index").GetInt32());
        }
    }

    [Fact]
    public void DarkAndLight_DefineTheSameColorKeys()
    {
        var color = Tokens.GetProperty("color");
        var dark = Keys(color.GetProperty("dark"));
        var light = Keys(color.GetProperty("light"));
        Assert.Equal(dark, light);
    }

    [Fact]
    public void EveryMotionPreset_ReferencesADefinedEasing()
    {
        var easings = Keys(Tokens.GetProperty("easing"));
        foreach (var preset in Tokens.GetProperty("motion").EnumerateObject())
        {
            if (preset.Name.StartsWith("$")) continue;
            var easing = preset.Value[1].GetString();
            Assert.True(easings.Contains(easing!), $"motion.{preset.Name} 引用了未定义的缓动 {easing}");
        }
    }

    /// <summary>
    /// tokens.json 声称 UWP 的 Quadratic / Cubic EaseOut 可以精确写成三次贝塞尔。
    /// 这里在整个 [0,1] 上与原公式逐点比对，防止有人把控制点改成「看着差不多」的近似值。
    /// </summary>
    [Theory]
    [InlineData("metroDefault", 2)]
    [InlineData("metroCubic", 3)]
    public void UwpEaseOut_BezierIsExact(string name, int power)
    {
        var p = Tokens.GetProperty("easing").GetProperty(name).GetProperty("bezier")
            .EnumerateArray().Select(e => e.GetDouble()).ToArray();

        for (var i = 0; i <= 100; i++)
        {
            var x = i / 100.0;
            var expected = 1 - Math.Pow(1 - x, power);
            Assert.Equal(expected, CubicBezier(p[0], p[1], p[2], p[3], x), 4);
        }
    }

    private static HashSet<string> Keys(JsonElement obj) =>
        obj.EnumerateObject().Select(p => p.Name).Where(n => !n.StartsWith("$")).ToHashSet();

    /// <summary>CSS 语义的 cubic-bezier：给定 x，二分求参数 t，返回 y(t)。</summary>
    private static double CubicBezier(double x1, double y1, double x2, double y2, double x)
    {
        static double B(double a, double b, double t) =>
            3 * a * t * (1 - t) * (1 - t) + 3 * b * t * t * (1 - t) + t * t * t;

        double lo = 0, hi = 1;
        for (var i = 0; i < 60; i++)
        {
            var mid = (lo + hi) / 2;
            if (B(x1, x2, mid) < x) lo = mid; else hi = mid;
        }
        return B(y1, y2, (lo + hi) / 2);
    }
}
