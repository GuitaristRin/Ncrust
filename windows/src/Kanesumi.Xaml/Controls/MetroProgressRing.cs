using System.Numerics;
using Kanesumi.Xaml.Motion;
using Windows.UI.Composition;
using Windows.UI.Xaml;
using Windows.UI.Xaml.Controls;
using Windows.UI.Xaml.Hosting;
using Windows.UI.Xaml.Shapes;

namespace Kanesumi.Xaml.Controls
{
    /// <summary>
    /// 直角风格的进度环（对应 <c>MetroProgressIndicator</c>）：36×36、描边 3、270° 弧、圆头端帽，
    /// 由 Composition 的 <c>RotationAngleInDegrees</c> 线性旋转（1s 一圈，合成线程）。
    ///
    /// 不重写平台 ProgressRing：WinUI 2 的实现是 Lottie 动画，改不动弧形。
    /// </summary>
    public sealed class MetroProgressRing : Control
    {
        private const float RingCenter = 18f;

        public MetroProgressRing()
        {
            DefaultStyleKey = typeof(MetroProgressRing);
        }

        protected override void OnApplyTemplate()
        {
            base.OnApplyTemplate();
            if (GetTemplateChild("ArcPath") is not Path arc)
            {
                return;
            }

            var visual = ElementCompositionPreview.GetElementVisual(arc);
            visual.CenterPoint = new Vector3(RingCenter, RingCenter, 0f);

            var compositor = visual.Compositor;
            var animation = compositor.CreateScalarKeyFrameAnimation();
            animation.InsertKeyFrame(1f, 360f, compositor.CreateLinearEasingFunction());
            animation.Duration = KanesumiMotion.ProgressRingTurn;
            animation.IterationBehavior = AnimationIterationBehavior.Forever;
            visual.StartAnimation("RotationAngleInDegrees", animation);
        }
    }
}
