<div align="center">

# Ncrust

### 把网易云的曲库，装进一块直角玻璃

**Kanesumi Design · GPU 零重组动画 · 无缝播放 · 7 级音质 · 车机适配 · 8 语言**

纯 Kotlin / Jetpack Compose · Media3 播放引擎 · eapi 加密直连 · 无中间服务器

[![Version](https://img.shields.io/badge/version-1.3.0-brightgreen?style=flat-square)](https://github.com/GuitaristRin/Ncrust/releases)
[![APK](https://img.shields.io/badge/APK-2.9%20MB-blue?style=flat-square)](https://github.com/GuitaristRin/Ncrust/releases)
[![API](https://img.shields.io/badge/API-24%2B-green?style=flat-square&logo=android)](https://developer.android.com)
[![Kotlin](https://img.shields.io/badge/Kotlin-1.9.24-purple?style=flat-square&logo=kotlin)](https://kotlinlang.org)
[![Compose](https://img.shields.io/badge/Compose-BOM%202024.12-blue?style=flat-square&logo=jetpackcompose)](https://developer.android.com/jetpack/compose)
[![License](https://img.shields.io/badge/license-MIT-yellow?style=flat-square)](LICENSE)

[**下载安装**](https://github.com/GuitaristRin/Ncrust/releases) · [**Wiki 文档**](https://github.com/GuitaristRin/Ncrust/wiki) · [**问题反馈**](https://github.com/GuitaristRin/Ncrust/issues) · [**Kanesumi 设计库**](https://github.com/GuitaristRin/Kanesumi-sec-a)

</div>

> **⚠️ 重要声明**
>
> 本项目仅供学习交流使用，请勿用于任何违法违规用途。
>
> 使用本工具产生的一切后果由用户自行承担。
>
> 请尊重版权，支持正版音乐。

---

## 为什么是 Ncrust

> 网易云官方客户端很全，但它不是为「直角、克制、信息优先」设计的。
> Ncrust 是一次从零开始的重写：**没有圆角，没有弹簧回弹，没有多余装饰。**

| | |
|---|---|
| 🟩 **直角美学** | 全应用遵循 Kanesumi Design：直角切割、纯色细线、封面贴屏边、浮层返回箭头 |
| ⚡ **GPU 零重组** | 播放器动画由单个 `progress` 经 `graphicsLayer` 驱动，展开 / 收起时**不重组** |
| 🎧 **真·无损** | eapi 直连拉取 FLAC / Hi-Res / 杜比全景声，逐级降级兜底，弱机自动跳过无解档位 |
| 🔀 **5 种播放模式** | 顺序循环 / 单曲循环 / 乱序 / 顺序线性 / 相似无限（私人 FM 电台） |
| 🚗 **车机就绪** | Android Auto / Automotive 媒体源与浏览树，车机系统栏 inset 专项适配 |
| 🪶 **2.9 MB** | R8 全量混淆 + 资源 shrink，冷启动预热，进程被杀也能恢复队列 |
| 🌐 **8 语言** | 运行时切换，不依赖系统 locale |
| 🔐 **登录无忧** | 浏览器登录 + 二维码登录 + 手机扫码授权平板，全程无需手动粘贴 Cookie |

---

## ✨ 功能一览

### 🏠 发现

- **每日推荐**、**推荐歌单**、**新歌速递**，懒加载分页，缓存命中秒开
- **私人 FM**：首页电台入口，持续拉取私人 FM 流无限续播
- **剪贴板识别**：复制 `music.163.com` / `163cn.tv` 分享链接回到 App 自动打开（单曲只载入不自动播放）

### 🔍 搜索

- 单曲 / 专辑 / 艺人三标签，500 ms 防抖，三态 Crossfade 过渡
- 本地搜索历史（每类 10 条、14 天过期）
- 专辑 / 艺人长按可批量「播放全部 / 下一首 / 添加到队尾」

### 📚 曲库

- **收藏单曲**：云端同步「我喜欢的音乐」，列表懒加载分页
- **收藏专辑**：云端订阅专辑网格
- **收藏歌单**：当前账号的用户歌单
- 单曲可收藏 / 插播 / 加队列，本地乐观更新后异步同步云端

### 🎵 播放

- **7 级音质**：压缩 → 较好 → 更好 → 无损 → 高解析 → 高清环绕声 → 杜比全景声
- **分网络偏好**：Wi-Fi 与移动数据独立设置，默认无损 / 较好
- **无缝播放（gapless）**：进入最后 60 秒预加载下一首，切歌即播
- **自动降档**：解码失败或音频输出故障时同曲降一档重试，避免「进度在走但没声音」
- **三区队列**：已播 / 当前 / 待播，触摸即拖重排、边缘自动滚动、清空队列
- **播放上报**：复刻官方 `webLog`，本地收听计入推荐与听歌指数

### 🎨 界面

- **全屏播放器**：三层图层架构，拖拽 25% 阈值吸附，展开 / 收起只做位移缩放
- **歌词**：LRC 逐行解析、黄金分割定位、手动滚动 5 秒后恢复、双语翻译合并、点击行跳转
- **主题**：6 种主题色 × 3 种模式（跟随系统 / 深色 / 浅色），运行时切换
- **响应式**：窄屏限宽居中，宽屏（≥ 600dp）左侧 200dp 常驻 Sidebar
- **进程恢复**：被杀后恢复进度、歌曲信息、封面、歌词与完整队列

### 🔔 系统集成

- **媒体控制**：MediaLibraryService + MediaStyle 通知，锁屏 / 控制中心 / 蓝牙按键，封面主色调着色
- **车机**：Android Auto / Android Automotive（AAOS）媒体源与浏览树
- **后台播放**：`WAKE_LOCK` + 电池优化白名单引导，熄屏不被 ROM 清理
- **音频焦点**：ExoPlayer 自动处理

---

## 🎼 支持音质

| API 参数 | 说明 | 要求 |
|---|---|:--:|
| `standard` | 压缩（128 kbps） | 普通账号 |
| `higher` | 较好 | 普通账号 |
| `exhigh` | 更好（320 kbps） | 普通账号 |
| `lossless` | 无损（FLAC） | 黑胶 VIP |
| `hires` | 高解析 | 黑胶 VIP |
| `jyeffect` | 高清环绕声 | 黑胶 VIP |
| `dolby` | 杜比全景声 | 黑胶 VIP |

> 设备没有 MediaCodec FLAC 解码器（API < 27 或精简 ROM）时会自动跳过无损档位，避免无声。

---

## 📦 安装

1. 打开 [Releases](https://github.com/GuitaristRin/Ncrust/releases)，下载最新的 `app-release.apk`（约 **2.9 MB**）
2. 允许「未知来源」安装
3. 打开 App，在用户页登录（见下）

### 🔐 登录

App 不提供手动粘贴 Cookie，登录方式：

- **浏览器登录（手机默认）** — 用户页 → 头像 → 应用内 WebView 打开网易云登录页，登录后自动提取凭证
- **扫码登录（平板 / 宽屏）** — 生成二维码，用手机网易云 App 扫码
- **手机扫码授权平板** — 平板显示二维码后，用已登录 Ncrust 的手机扫描，经局域网把凭证加密传给平板

登录成功后自动同步云端收藏。

---

## 🛠️ 从源码构建

### 环境要求

Android Studio Hedgehog+ · JDK 11 · Kotlin 1.9.24 · Gradle 8.x · Android SDK 36（minSdk 24）

### ⚠️ 必须先克隆 Kanesumi

`settings.gradle.kts` 通过 `includeBuild("../Kanesumi-sec-a")` 直接依赖同级的 [Kanesumi](https://github.com/GuitaristRin/Kanesumi-sec-a) 源码仓库。**单独克隆 Ncrust 无法构建**，目录结构必须是：

```
projects/
├── Ncrust/
└── Kanesumi-sec-a/
```

### 构建命令

```bash
git clone https://github.com/GuitaristRin/Ncrust.git
git clone https://github.com/GuitaristRin/Kanesumi-sec-a.git

cd Ncrust
./gradlew assembleDebug            # Debug APK -> app/build/outputs/apk/debug/
./gradlew assembleRelease          # Release APK（R8 minify，需 keystore.properties）

benchmark/run_benchmark.sh all     # Macrobenchmark：冷启动 / 滚动 / 播放器展开
```

---

## 🧭 技术架构

| 主题 | 说明 |
|---|---|
| **Kanesumi Design** | 直角、无圆角、无弹簧；共享组件与动画词汇（`Metro*` / `sokuou`）位于外部 Kanesumi 库，App 内**已无 `material3` 依赖** |
| **GPU 零重组** | 播放器动画由单个 `progress: Animatable<Float>` 驱动，视觉属性只在 `graphicsLayer {}` 内读取 |
| **三层图层** | 主页面 / 播放卡 / 导航栏为独立 composable 层，手势转场互不干扰 |
| **网络直连** | 自有 `EapiCrypto`（AES-128-ECB + MD5 签名）与 `WeapiCrypto`（双 AES-CBC + 原始 RSA），直连网易云，无中间服务器 |
| **ContentCache** | 内存网络快照 + Crossfade，消除「空屏 → spinner → 跳变」；LRU-32 详情缓存 |
| **持久化** | 全部 SharedPreferences + Gson，无 Room |
| **性能** | R8 全量混淆 + 资源 shrink（release 2.9 MB）、`AppWarmup` 冷启动预热、状态订阅下推至叶子组件、播放器折叠态子树 gating |

```
app/src/main/java/com/takahashirinta/ncrust/
├── MainActivity.kt        # 入口 + MainScreen 编排（导航 / 队列 / 播放模式）
├── auth/                  # Cookie + 局域网扫码配对
├── cache/                 # ContentCache（内存，不持久化）
├── library/               # 云端收藏 + 搜索历史
├── lyric/                 # LRC 解析 + 歌词缓存
├── network/               # Retrofit / eapi / weapi / 加密
├── player/                # PlaybackService / SongUrlFetcher / PlayReporter
├── warmup/  power/        # 冷启动预热、电池白名单
└── ui/                    # screen / player / components / theme / i18n / navigation
```

> 更完整的架构说明见 [AGENTS.md](AGENTS.md) 与 [Wiki](https://github.com/GuitaristRin/Ncrust/wiki)。

---

## 📋 版本历史

| 版本 | 日期 | 亮点 |
|---|---|---|
| v0.1.0-beta | 2026-04-26 | 初始 MVP，核心播放流程跑通 |
| v1.0.0 | 2026-04-30 | 首个正式版：多屏幕适配、状态持久化、队列管理 |
| v1.0.1 ~ v1.0.3 | 2026-05-04 | 零重组修复、主题色系统、多语言框架、歌单页闪退与 WebView 登录修复 |
| v1.1.0 | 2026-05-05 | 收藏 / 队列 / 音质完善 |
| v1.1.4 | 2026-05-17 | 无缝播放元数据同步、URL 缓存去重、多语言扩充 |
| v1.2.0 | 2026-07-28 | Sokuou 动画系统、ContentCache + Crossfade、专辑收藏、批量入队 |
| v1.2.1 | 2026-07-29 | Kanesumi 化收官、冷启动预热、播放器重组归零、R8（25 MB → 4.1 MB） |
| v1.2.2 | 2026-08-04 | 全页面 Kanesumi 统一、底部导航与迷你条衔接、登录统一为浏览器方式 |
| **v1.3.0** | **2026-09-10** | **平板 / 大屏 Sidebar、车机（Android Auto / AAOS）、AudioSink 降档兜底、歌词竞态与定位修复、队列拖拽重排、私人 FM 续播** |

---

## 🔗 相关项目

| 项目 | 说明 |
|---|---|
| [Kanesumi-sec-a](https://github.com/GuitaristRin/Kanesumi-sec-a) | Kanesumi Design 组件与动画库（Apache-2.0），本项目 UI 依赖 |
| [163CMAnalyser](https://github.com/GuitaristRin/163CMAnalyser) | Rust CLI 无损下载工具 |
| [Netease_url](https://github.com/Suxiaoqinx/Netease_url) | Python 原版网易云解析（MIT） |

---

## 📄 许可证

本项目以 **MIT** 许可发布，Copyright (c) 2026 Takahashi_Rinta。完整文本见 [LICENSE](LICENSE)。Kanesumi 库为 Apache-2.0。

---

<div align="center">

## ⭐ Star History

<a href="https://www.star-history.com/?repos=GuitaristRin%2FNcrust&type=date&legend=top-left">
  <picture>
    <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=GuitaristRin/Ncrust&type=date&theme=dark&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
    <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=GuitaristRin/Ncrust&type=date&theme=light&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
    <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=GuitaristRin/Ncrust&type=date&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
  </picture>
</a>

**如果 Ncrust 让你重新爱上听歌，请赐一颗 Star ⭐**

[问题反馈](https://github.com/GuitaristRin/Ncrust/issues) · [Wiki](https://github.com/GuitaristRin/Ncrust/wiki) · [Releases](https://github.com/GuitaristRin/Ncrust/releases)

</div>
