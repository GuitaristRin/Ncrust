# fixtures/update —— 应用内更新检查（GitHub Releases）

各端从 GitHub Releases API 的响应里挑出「本平台可用的更高版本」的规则。
发布走滚动班次（`docs/RELEASES.md`），所以**不能看 Latest 徽章**，只能按资产名扫。

## schema

`releases.json` 是用例数组。公共字段 `id` / `description` / `source` / `input` / `expect`，
外加可选的 `platforms`（默认 `["android", "windows"]`，见下）。

```json
{
  "id": "update.train.higher-version",
  "description": "这条用例在防什么（一句话）",
  "source": "依据：代码位置 / 文档 / 实测记录",
  "platforms": ["android", "windows"],
  "input": {
    "currentVersion": "1.3.2",
    "platform": "android",
    "arch": "universal",
    "releases": [ /* GitHub Releases API 的响应数组，只保留用得着的字段 */ ]
  },
  "expect": { /* 见下表 */ }
}
```

`input.releases` 里的每个 release 只保留用得着的字段，形状与
`GET https://api.github.com/repos/GuitaristRin/Ncrust/releases` 一致：

```json
{
  "tag_name": "CrateStack-20261015A",
  "html_url": "https://github.com/GuitaristRin/Ncrust/releases/tag/CrateStack-20261015A",
  "draft": false,
  "prerelease": false,
  "published_at": "2026-10-15T09:30:00Z",
  "body": "release 正文（markdown）",
  "assets": [
    { "name": "ncrust-android-universal-1.4.0.apk", "browser_download_url": "https://…", "size": 18345678 }
  ]
}
```

`expect` 为 `null` 表示「无更新」。否则：

| 字段 | 含义 | 哪端断言 |
|---|---|---|
| `version` | 命中版本，三段显示式（`1.10.0`） | 两端 |
| `releaseTag` | 命中版本所在 release 的 `tag_name` | 两端 |
| `releasePageUrl` | 命中版本所在 release 的 `html_url` | 两端 |
| `assetName` | 命中资产的 `name`（Android = `.apk`） | Android |
| `assetUrl` | 命中资产的 `browser_download_url` | Android |
| `assetSize` | 命中资产的 `size`（字节） | Android |
| `publishedAt` | 命中 release 的 `published_at` | Android |
| `body` | 命中 release 的 `body` | Android |
| `windowsAssets` | `{ appInstallerUrl, msixUrl, cerUrl }`，缺项为 `null` | Windows |

Android 侧另外断言 `assetName` / `assetUrl` / `assetSize` / `publishedAt` / `body`，
是因为更新页要展示大小、日期与正文；Windows 的 `UpdateInfo` 没有通用资产字段
（只有 `.appinstaller` / `.msix` / `.cer` 三样），故用 `windowsAssets` 单独断言。

## 规则（两端必须一致）

1. **资产命名**：`ncrust-[平台]-[架构]-[版本].[扩展名]`。平台与架构按前缀**逐字符**匹配
   （Android：`android` + `universal`；Windows：`windows` + `x86_64`），跨平台资产不互相命中。
   命中资产还必须是该平台**可安装**的扩展名（Android 只认 `.apk`：版本段合规但扩展名是
   `.asc` / `.sha256` 的资产是签名文件，下下来也装不了）。
2. **版本段**：前缀之后到**最后一个点**之间是版本号；最多 4 段、缺段按 0 补、允许前缀 `v`。
   解析失败（缺版本段、`1.4.0.apk.sha256` 这种把扩展名当版本段的写法）→ 该资产忽略，绝不猜。
   不合规范的旧资产（`app-release.apk`、`app-debug.apk`、`manifest.json`）一律忽略。
3. **draft 忽略**；**prerelease 忽略**（`docs/PLAN_2026-10.md` §1）。
4. **班次内取最高**：一个 release 里若有多个合规资产，取其中版本最高者，URL 也跟随该版本。
5. **跨班次取最高**：遍历所有可见 release，取**严格大于当前版本**的最高者；同版本不算更新。
6. **不依赖 Latest 徽章**，也不依赖 release 在数组里的顺序（班次是滚动的，顺序即发布时间）。

## 两端差异（已消除）

2026-10-15 前 Windows 的 `UpdateChecker` 只过滤 `draft`、不校验扩展名，`update.prerelease.ignored` 与
`update.asset.wrong-extension` 两条因此只标了 Android。现已在 Windows 补齐（忽略 `prerelease`；只有 `.appinstaller` /
`.msix` / `.cer` 计入版本），两条恢复为两端都适用，期望值未改。今后新增规则时仍应先改夹具、再让两端同时通过。
