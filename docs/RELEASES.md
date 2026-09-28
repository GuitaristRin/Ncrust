# Ncrust 发布索引

Ncrust 在同一个仓库里发布多个平台，各平台**版本线独立**。发布走**滚动多平台班次
（release train）**：一个 Release 装载当日各平台的最新版资产，tag 形如
`CrateStack-YYYYMMDD<序号>`（如 `CrateStack-20260928A`），GitHub 的「Latest」徽章即指向
当前班次。班次内附 [`manifest.json`](https://github.com/GuitaristRin/Ncrust/releases/download/CrateStack-20260928A/manifest.json)
（版本 × 资产清单，机器可读），产物命名规范 `ncrust-[平台]-[架构]-[版本]`。

> 历史版本按平台独立 tag 发布（`v1.3.1`、`win-v1.0.0` 等），旧资产链接保持有效。
> 某平台的最新版一律以本表为准，**不要看 Latest 徽章判断**——徽章只指向最近一班。

## 各平台最新版

| 平台 | 版本 | 所在班次 | 下载 | 日期 |
|---|---|---|---|---|
| Android（通用包） | 1.3.2 | [`CrateStack-20260928A`](https://github.com/GuitaristRin/Ncrust/releases/tag/CrateStack-20260928A) | [`ncrust-android-universal-1.3.2.apk`](https://github.com/GuitaristRin/Ncrust/releases/download/CrateStack-20260928A/ncrust-android-universal-1.3.2.apk) | 2026-09-28 |
| Windows（UWP，x64） | 1.0.0 | [`CrateStack-20260928A`](https://github.com/GuitaristRin/Ncrust/releases/tag/CrateStack-20260928A) | [`.appinstaller`](https://github.com/GuitaristRin/Ncrust/releases/download/CrateStack-20260928A/ncrust-windows-x86_64-1.0.0.appinstaller) · [`.msix`](https://github.com/GuitaristRin/Ncrust/releases/download/CrateStack-20260928A/ncrust-windows-x86_64-1.0.0.msix) · [`.cer`](https://github.com/GuitaristRin/Ncrust/releases/download/CrateStack-20260928A/ncrust-windows-x86_64-1.0.0.cer) | 2026-09-27 |
| Windows Phone（Lumia 950 等） | — | — | — | 计划中 |
| Linux | — | — | — | 计划中 |
| macOS | — | — | — | 计划中 |

发布时更新本表。

## 版本与班次

- 滚动班次 tag：`CrateStack-YYYYMMDD<A..Z>`（同日多班依次编号），每班装载当时各平台
  最新版资产；班次内附 `manifest.json`，程序化检查以它为准。
- 资产命名：`ncrust-[平台]-[架构]-[版本]`，如 `ncrust-android-universal-1.3.2.apk`、
  `ncrust-windows-x86_64-1.0.0.msix`。
- 各平台版本号字段各自维护。Android 唯一来源 `app/build.gradle.kts` 的
  `versionName` / `versionCode`；Windows 唯一来源 `Package.appxmanifest` 的
  `Identity Version`（`X.Y.Z.0`，第 4 段是开发构建号）。
- 修复类改动允许**不 bump 版本号、原地更换班次里的同名资产**（先例：2026-09-28 晚更换
  Android 1.3.2 修复冷启动闪退），但必须在 Release 说明里注明更换时间与原因，
  并同步本表。

## Windows 下载与更新

- Windows 每班发布三样：**签名 MSIX** + **`.appinstaller`** + **公开证书 `.cer`**。
- **请用 `.appinstaller` 安装**：只有经它安装的包，系统的 App Installer 才会自动更新；
  直接装 `.msix` 的没有更新源，永远不会自动更新。
- 首次安装需要信任自签证书：把 `.cer` 导入「受信任人」（`install-cert.ps1`，或右键 →
  安装证书 → 本地计算机 → 受信任的根证书颁发机构 / 受信任人）。若日后换成正规代码签名证书，
  这一步就不需要了。
- 应用内只做「版本检查 + 通知」：发现新版会提示并打开 `.appinstaller`，实际安装由系统的
  App Installer 完成（UWP 应用无法静默自装）。
- 包身份（`Identity Name` + `Publisher = CN=TakahashiRinta`）永不改变；改了会导致老版本无法升级。

详细规则见 `windows/AGENTS.md` 的「版本与发布」。
