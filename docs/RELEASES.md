# Ncrust 发布索引

Ncrust 在同一个仓库里发布多个平台，各平台**版本线独立**。GitHub 一个仓库只有一个
「Latest」徽章，所以某平台的最新版一律以本表为准，**不要看 GitHub 的 Latest**。

## 各平台最新版

| 平台 | 版本 | Tag | 下载 | 日期 |
|---|---|---|---|---|
| Android | 1.3.2 | [`android-v1.3.2`](https://github.com/GuitaristRin/Ncrust/releases/tag/android-v1.3.2) | APK | 2026-09-28 |
| Windows（UWP，x64） | 1.0.0 | [`win-v1.0.0`](https://github.com/GuitaristRin/Ncrust/releases/tag/win-v1.0.0) | [`.appinstaller`](https://github.com/GuitaristRin/Ncrust/releases/download/win-v1.0.0/Ncrust.appinstaller) · `.msix` · `.cer` | 2026-09-27 |
| Windows Phone（Lumia 950 等） | — | `wp-vX.Y.Z` | — | 计划中 |
| Linux | — | `linux-vX.Y.Z` | — | 计划中 |
| macOS | — | `macos-vX.Y.Z` | — | 计划中 |

发布时更新本表。

## 版本与 tag

- 各平台独立版本线，tag 前缀区分：`android-vX.Y.Z`、`win-vX.Y.Z`、`wp-vX.Y.Z`、
  `linux-vX.Y.Z`、`macos-vX.Y.Z`。
- 已发布的 Android 旧 tag（`v1.3.1` 及更早）保持不动；自 `v1.3.2` 起改用 `android-vX.Y.Z`。
- Release 标题带平台名（`Windows 1.0.0` / `Android 1.3.2`）；创建时一律 `--latest=false`，
  不让任意一端霸占 Latest 徽章。
- 各平台版本号字段各自维护。Windows 的唯一来源是 `Package.appxmanifest` 的
  `Identity Version`（`X.Y.Z.0`，第 4 段是开发构建号）；Windows 发布线从 `1.0.0` 起。

## Windows 下载与更新

- 每个 `win-vX.Y.Z` 发布三样：**签名 MSIX** + **`.appinstaller`** + **公开证书 `.cer`**。
- **请用 `.appinstaller` 安装**：只有经它安装的包，系统的 App Installer 才会自动更新；
  直接装 `.msix` 的没有更新源，永远不会自动更新。
- 首次安装需要信任自签证书：把 `.cer` 导入「受信任人」（`install-cert.ps1`，或右键 →
  安装证书 → 本地计算机 → 受信任的根证书颁发机构 / 受信任人）。若日后换成正规代码签名证书，
  这一步就不需要了。
- 应用内只做「版本检查 + 通知」：发现新版会提示并打开 `.appinstaller`，实际安装由系统的
  App Installer 完成（UWP 应用无法静默自装）。
- 包身份（`Identity Name` + `Publisher = CN=TakahashiRinta`）永不改变；改了会导致老版本无法升级。

详细规则见 `windows/AGENTS.md` 的「版本与发布」。
