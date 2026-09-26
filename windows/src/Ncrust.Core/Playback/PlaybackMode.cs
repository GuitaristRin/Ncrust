namespace Ncrust.Core.Playback
{
    /// <summary>5 种播放模式（对应 Android <c>QueueModes</c>）。</summary>
    public enum PlaybackMode
    {
        Cycle = 0,
        Single = 1,
        Shuffle = 2,
        Line = 3,
        Infinity = 4,
    }
}
