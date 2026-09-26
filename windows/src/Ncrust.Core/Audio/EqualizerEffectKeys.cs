namespace Ncrust.Core.Audio
{
    /// <summary>
    /// 应用与音效组件（Ncrust.Audio.EqualizerEffect）之间传参用的 PropertySet 键。
    /// 同一个 PropertySet 实例在进程内按引用共享：应用改值 → 组件收到 MapChanged → 重算系数，下一帧生效。
    /// </summary>
    public static class EqualizerEffectKeys
    {
        /// <summary>bool。</summary>
        public const string Enabled = "Enabled";

        /// <summary>double（dB）。</summary>
        public const string PreampDb = "PreampDb";

        /// <summary>double[10]（dB）。</summary>
        public const string GainsDb = "GainsDb";

        /// <summary>音效类的激活名（Windows 运行时组件里的类全名）。</summary>
        public const string ActivatableClassId = "Ncrust.Audio.EqualizerEffect";
    }
}
