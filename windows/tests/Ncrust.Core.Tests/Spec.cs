using System.Text.Json;

namespace Ncrust.Core.Tests;

/// <summary>定位仓根的 spec/ 目录。测试直接读取那里的文件，不复制。</summary>
internal static class Spec
{
    private static readonly Lazy<string> Root = new(() =>
    {
        var dir = new DirectoryInfo(AppContext.BaseDirectory);
        while (dir != null)
        {
            var candidate = Path.Combine(dir.FullName, "spec", "design", "tokens.json");
            if (File.Exists(candidate)) return Path.Combine(dir.FullName, "spec");
            dir = dir.Parent;
        }
        throw new DirectoryNotFoundException("从测试输出目录向上找不到 spec/design/tokens.json");
    });

    public static string PathOf(params string[] parts) =>
        Path.Combine(new[] { Root.Value }.Concat(parts).ToArray());

    public static JsonDocument LoadJson(params string[] parts) =>
        JsonDocument.Parse(File.ReadAllText(PathOf(parts)));
}
