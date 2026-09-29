using System;
using System.Globalization;

namespace Ncrust.Core.Update
{
    /// <summary>
    /// 可比较的版本号（最多 4 段，缺段按 0 补）。用于把包版本
    /// （<c>Package.Current.Id.Version</c>，形如 <c>1.0.1.0</c>）与发布资产名里的版本
    /// （形如 <c>1.0.1</c>）放在同一把尺子上比较。空串 / 非法段一律解析失败，绝不抛到调用方。
    /// </summary>
    public sealed class AppVersion : IComparable<AppVersion>, IEquatable<AppVersion>
    {
        public AppVersion(int major, int minor = 0, int build = 0, int revision = 0)
        {
            if (major < 0 || minor < 0 || build < 0 || revision < 0)
            {
                throw new ArgumentOutOfRangeException(nameof(major), "版本段不能为负");
            }

            Major = major;
            Minor = minor;
            Build = build;
            Revision = revision;
        }

        public int Major { get; }

        public int Minor { get; }

        public int Build { get; }

        public int Revision { get; }

        /// <summary>解析 <c>1</c> / <c>1.0</c> / <c>1.0.1</c> / <c>1.0.1.0</c>（允许前缀 <c>v</c>）。</summary>
        public static bool TryParse(string? text, out AppVersion version)
        {
            version = new AppVersion(0);
            if (string.IsNullOrWhiteSpace(text))
            {
                return false;
            }

            var trimmed = text!.Trim();
            if (trimmed.Length > 0 && (trimmed[0] == 'v' || trimmed[0] == 'V'))
            {
                trimmed = trimmed.Substring(1);
            }

            var parts = trimmed.Split('.');
            if (parts.Length == 0 || parts.Length > 4)
            {
                return false;
            }

            var numbers = new int[4];
            for (var i = 0; i < parts.Length; i++)
            {
                if (!int.TryParse(parts[i], NumberStyles.None, CultureInfo.InvariantCulture, out var value))
                {
                    return false;
                }

                numbers[i] = value;
            }

            version = new AppVersion(numbers[0], numbers[1], numbers[2], numbers[3]);
            return true;
        }

        public static AppVersion Parse(string text) =>
            TryParse(text, out var version) ? version : throw new FormatException($"非法版本号：{text}");

        public int CompareTo(AppVersion? other)
        {
            if (other is null)
            {
                return 1;
            }

            return Major != other.Major ? Major.CompareTo(other.Major)
                : Minor != other.Minor ? Minor.CompareTo(other.Minor)
                : Build != other.Build ? Build.CompareTo(other.Build)
                : Revision.CompareTo(other.Revision);
        }

        public bool Equals(AppVersion? other) => other is not null && CompareTo(other) == 0;

        public override bool Equals(object? obj) => obj is AppVersion other && Equals(other);

        public override int GetHashCode()
        {
            unchecked
            {
                var hash = Major;
                hash = (hash * 397) ^ Minor;
                hash = (hash * 397) ^ Build;
                hash = (hash * 397) ^ Revision;
                return hash;
            }
        }

        /// <summary>显示用三段式（与设置页 About 一致），不显示开发构建号。</summary>
        public override string ToString() =>
            string.Format(CultureInfo.InvariantCulture, "{0}.{1}.{2}", Major, Minor, Build);
    }
}
