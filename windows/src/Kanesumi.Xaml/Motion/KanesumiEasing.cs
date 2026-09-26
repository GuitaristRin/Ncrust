using System.Numerics;
using Windows.Foundation;
using Windows.UI.Composition;
using Windows.UI.Xaml.Media.Animation;

namespace Kanesumi.Xaml.Motion
{
    /// <summary>
    /// tokens.json 的 easing → Composition 的 <see cref="CubicBezierEasingFunction"/> 与 XAML 的
    /// <see cref="KeySpline"/>。取值与 <c>spec/design/tokens.json</c> 一一对应（改值先改 tokens）。
    /// Ncrust 不使用弹簧。
    /// </summary>
    public static class KanesumiEasing
    {
        // cubic-bezier(x1, y1, x2, y2)
        private static readonly Vector2 StandardP1 = new Vector2(0.2f, 0.0f);
        private static readonly Vector2 StandardP2 = new Vector2(0.0f, 1.0f);
        private static readonly Vector2 FastOutSlowInP1 = new Vector2(0.4f, 0.0f);
        private static readonly Vector2 FastOutSlowInP2 = new Vector2(0.2f, 1.0f);
        private static readonly Vector2 LinearOutSlowInP1 = new Vector2(0.0f, 0.0f);
        private static readonly Vector2 LinearOutSlowInP2 = new Vector2(0.2f, 1.0f);
        private static readonly Vector2 MetroDefaultP1 = new Vector2(0.333333f, 0.666667f);
        private static readonly Vector2 MetroDefaultP2 = new Vector2(0.666667f, 1.0f);
        private static readonly Vector2 MetroCubicP1 = new Vector2(0.333333f, 1.0f);
        private static readonly Vector2 MetroCubicP2 = new Vector2(0.666667f, 1.0f);

        public static CubicBezierEasingFunction Standard(Compositor compositor) =>
            compositor.CreateCubicBezierEasingFunction(StandardP1, StandardP2);

        public static CubicBezierEasingFunction FastOutSlowIn(Compositor compositor) =>
            compositor.CreateCubicBezierEasingFunction(FastOutSlowInP1, FastOutSlowInP2);

        public static CubicBezierEasingFunction LinearOutSlowIn(Compositor compositor) =>
            compositor.CreateCubicBezierEasingFunction(LinearOutSlowInP1, LinearOutSlowInP2);

        public static CubicBezierEasingFunction MetroDefault(Compositor compositor) =>
            compositor.CreateCubicBezierEasingFunction(MetroDefaultP1, MetroDefaultP2);

        public static CubicBezierEasingFunction MetroCubic(Compositor compositor) =>
            compositor.CreateCubicBezierEasingFunction(MetroCubicP1, MetroCubicP2);

        // XAML KeySpline 版本（独立动画用）
        public static KeySpline StandardSpline { get; } = MakeSpline(0.2, 0.0, 0.0, 1.0);

        public static KeySpline FastOutSlowInSpline { get; } = MakeSpline(0.4, 0.0, 0.2, 1.0);

        public static KeySpline MetroDefaultSpline { get; } = MakeSpline(0.333333, 0.666667, 0.666667, 1.0);

        public static KeySpline MetroCubicSpline { get; } = MakeSpline(0.333333, 1.0, 0.666667, 1.0);

        private static KeySpline MakeSpline(double x1, double y1, double x2, double y2) => new KeySpline
        {
            ControlPoint1 = new Point(x1, y1),
            ControlPoint2 = new Point(x2, y2),
        };
    }
}
