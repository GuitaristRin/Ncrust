using System;
using Windows.UI.Composition;

namespace Kanesumi.Xaml.Motion
{
    /// <summary>
    /// tokens.json 的时长与常用 Composition 动画工厂（对应 sec-a 的 <c>SokuouTweens</c>）。
    /// 只移植 tween 预设；弹簧不移植。改值先改 <c>spec/design/tokens.json</c>。
    /// </summary>
    public static class KanesumiMotion
    {
        public static readonly TimeSpan SheetAppear = Ms(300);
        public static readonly TimeSpan SheetDismiss = Ms(260);
        public static readonly TimeSpan QuickSwitch = Ms(180);
        public static readonly TimeSpan CoverFade = Ms(400);
        public static readonly TimeSpan ColorTransition = Ms(300);
        public static readonly TimeSpan ToggleFlip = Ms(220);
        public static readonly TimeSpan IndicationEnter = Ms(100);
        public static readonly TimeSpan IndicationExit = Ms(200);
        public static readonly TimeSpan TabIndicator = Ms(200);
        public static readonly TimeSpan TabText = Ms(180);
        public static readonly TimeSpan NavIndicator = Ms(200);
        public static readonly TimeSpan MenuAppear = Ms(180);
        public static readonly TimeSpan DetailEnter = Ms(220);
        public static readonly TimeSpan LyricLineSwitch = Ms(180);
        public static readonly TimeSpan LyricPanelFadeIn = Ms(400);
        public static readonly TimeSpan ProgressRingTurn = Ms(1000);
        public static readonly TimeSpan PlayerExpand = Ms(400);
        public static readonly TimeSpan PlayerCollapse = Ms(260);

        // motionParams
        public const float DetailEnterOffset = 12f;
        public const float MenuAppearScaleYFrom = 0.92f;
        public const float MenuOffsetY = 8f;
        public const float LyricScaleMin = 0.82f;
        public const float LyricScaleDistance = 1.8f;
        public const float PlayerSnapThreshold = 0.25f;
        public const float PlayerHeavyChildrenMinProgress = 0.05f;
        public const int PlayerCoverHoldMs = 400;
        public const float DisabledAlpha = 0.4f;

        /// <summary>标准展开：400ms standard。</summary>
        public static ScalarKeyFrameAnimation TweenScalar(
            Compositor compositor,
            float to,
            TimeSpan duration,
            CompositionEasingFunction easing)
        {
            var animation = compositor.CreateScalarKeyFrameAnimation();
            animation.InsertKeyFrame(1f, to, easing);
            animation.Duration = duration;
            return animation;
        }

        public static TimeSpan PlayerDuration(bool expanding) => expanding ? PlayerExpand : PlayerCollapse;

        private static TimeSpan Ms(int milliseconds) => TimeSpan.FromMilliseconds(milliseconds);
    }
}
