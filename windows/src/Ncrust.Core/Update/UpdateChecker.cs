using System;
using Ncrust.Core.Json;

namespace Ncrust.Core.Update
{
    /// <summary>一次可用更新：目标版本与三样资产的下载地址（缺项为 null）。</summary>
    public sealed class UpdateInfo
    {
        internal UpdateInfo(
            AppVersion version,
            string? appInstallerUrl,
            string? msixUrl,
            string? cerUrl,
            string? releaseTag,
            string? releasePageUrl)
        {
            Version = version;
            AppInstallerUrl = appInstallerUrl;
            MsixUrl = msixUrl;
            CerUrl = cerUrl;
            ReleaseTag = releaseTag;
            ReleasePageUrl = releasePageUrl;
        }

        public AppVersion Version { get; }

        /// <summary>`.appinstaller` 资产地址——应用内「下载并安装」优先用它。</summary>
        public string? AppInstallerUrl { get; }

        public string? MsixUrl { get; }

        /// <summary>公开证书 `.cer`（首次安装需要先信任；已信任的可忽略）。</summary>
        public string? CerUrl { get; }

        public string? ReleaseTag { get; }

        public string? ReleasePageUrl { get; }
    }

    /// <summary>
    /// 从 GitHub Releases API 的 JSON（<c>GET /repos/{owner}/{repo}/releases</c>）里找出适用于
    /// 本平台的更新。**纯函数、零反射**，网络由 App 层负责。
    ///
    /// 兼容滚动班次：资产命名规范 <c>ncrust-[平台]-[架构]-[版本].&lt;ext&gt;</c>，逐个班次扫描
    /// 资产、解析版本，取**严格大于当前版本**的最高者；不依赖 GitHub 的「Latest」徽章
    /// （班次是滚动的，徽章只指向最近一班）。
    /// </summary>
    public static class UpdateChecker
    {
        public const string DefaultPlatform = "windows";

        public const string DefaultArch = "x86_64";

        /// <summary>解析失败 / 无更新 / 不是数组时返回 null；绝不抛。</summary>
        public static UpdateInfo? Find(
            string? releasesJson,
            AppVersion current,
            string platform = DefaultPlatform,
            string arch = DefaultArch)
        {
            if (string.IsNullOrWhiteSpace(releasesJson) || current == null)
            {
                return null;
            }

            JsonValue root;
            try
            {
                root = JsonValue.Parse(releasesJson!);
            }
            catch (JsonParseException)
            {
                return null;
            }

            if (!root.IsArray)
            {
                return null;
            }

            var prefix = "ncrust-" + platform + "-" + arch + "-";
            UpdateInfo? best = null;
            foreach (var release in root.Items)
            {
                // 草稿与预发布都不是给用户的版本（docs/PLAN_2026-10.md §1；与 Android 同一份夹具）。
                if (!release.IsObject || release.GetBool("draft") || release.GetBool("prerelease"))
                {
                    continue;
                }

                var assets = release.GetArray("assets");
                if (assets == null)
                {
                    continue;
                }

                AppVersion? releaseVersion = null;
                string? appInstaller = null;
                string? msix = null;
                string? cer = null;
                foreach (var asset in assets)
                {
                    if (!asset.IsObject)
                    {
                        continue;
                    }

                    var name = asset.GetString("name");
                    if (string.IsNullOrEmpty(name) ||
                        !name!.StartsWith(prefix, StringComparison.Ordinal))
                    {
                        continue;
                    }

                    // rest = "<version>.<ext>"
                    var rest = name.Substring(prefix.Length);
                    var dot = rest.LastIndexOf('.');
                    if (dot <= 0)
                    {
                        continue;
                    }

                    if (!AppVersion.TryParse(rest.Substring(0, dot), out var version))
                    {
                        continue;
                    }

                    // 只有认得的三样资产才算数：否则一个只带签名 / 校验文件（如 .asc）的班次会被当成
                    // 「有更新但三样 URL 全空」，用户点了也装不了。
                    var url = asset.GetString("browser_download_url");
                    switch (rest.Substring(dot + 1).ToLowerInvariant())
                    {
                        case "appinstaller":
                            appInstaller = url;
                            break;
                        case "msix":
                            msix = url;
                            break;
                        case "cer":
                            cer = url;
                            break;
                        case "apk":
                            // 共享夹具（spec/fixtures/update）也拿 Android 平台的用例喂本函数：.apk 计入版本，
                            // 本端没有对应的 URL 槽，Windows 应用本身永远不会以 android 平台调用它。
                            break;
                        default:
                            continue;
                    }

                    if (releaseVersion == null || version.CompareTo(releaseVersion) > 0)
                    {
                        releaseVersion = version;
                    }
                }

                if (releaseVersion == null || releaseVersion.CompareTo(current) <= 0)
                {
                    continue;
                }

                if (best == null || releaseVersion.CompareTo(best.Version) > 0)
                {
                    best = new UpdateInfo(
                        releaseVersion,
                        appInstaller,
                        msix,
                        cer,
                        release.GetString("tag_name"),
                        release.GetString("html_url"));
                }
            }

            return best;
        }
    }
}
