namespace Ncrust.Core.Lyrics
{
    /// <summary>一行原始歌词（对应 Android <c>LrcLine</c>）。</summary>
    public sealed class LrcLine
    {
        public long TimeMs { get; set; }

        public string Text { get; set; } = string.Empty;
    }

    /// <summary>原句 + 对齐后的译文（对应 Android <c>MetroLyricLine</c> 的文本部分）。</summary>
    public sealed class LyricLine
    {
        public long TimeMs { get; set; }

        public string Text { get; set; } = string.Empty;

        /// <summary>没有对应译文时为空串。</summary>
        public string Translation { get; set; } = string.Empty;
    }
}
