# Ncrust - 网易云音乐 Android 第三方客户端

<div align="center">

![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple?style=flat-square&logo=kotlin)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-BOM%202024.12-blue?style=flat-square&logo=jetpackcompose)
![API](https://img.shields.io/badge/API-24%2B-green?style=flat-square&logo=android)
![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)
![Version](https://img.shields.io/badge/Version-1.2.1-brightgreen?style=flat-square)
![APK](https://img.shields.io/badge/Release%20APK-4MB-blue?style=flat-square)

**Kanesumi Design / Groove 风 · GPU 零重组动画 · 无缝播放 · 13 语言 · 无损音质**

纯 Kotlin/Jetpack Compose 构建 · Media3 播放引擎 · eapi 加密直连

[后端 CLI 工具](https://github.com/GuitaristRin/163CMAnalyser) (Rust) · [问题反馈](https://github.com/GuitaristRin/Ncrust/issues)

</div>

> **⚠️ 重要声明**
>
> 本项目仅供学习交流使用，请勿用于任何违法违规用途。
>
> 使用本工具产生的一切后果由用户自行承担。
>
> 请尊重版权，支持正版音乐。

---

## ✨ 功能特性

### 🎵 核心功能

- **🏠 首页发现**：新歌速递、推荐歌单、日推歌曲，懒加载分页
- **🔍 多维度搜索**：单曲 / 专辑 / 艺人搜索，500ms 防抖，三标签 Tab 切换，Crossfade 三态过渡
- **📚 本地曲库**：单曲封面墙（两列网格）、专辑自动派生（按 albumId 去重），Tab 切换方向感知横向滑入
- **🎧 无损播放**：eapi 加密获取 FLAC 无损流，完整音质降级链
- **🎭 全屏播放器**：三层图层架构，拖拽手势（25% 阈值吸附），迷你栏/全屏流畅切换
- **🎼 歌词显示**：LRC 逐行解析，上黄金分割点自动定位，手动滚动 5 秒后恢复，上下渐变融入
- **⏯️ 播放队列**：插播/追加/移除/点击切歌，三种模式（列表循环/单曲循环/随机），持久化存储
- **🔔 系统媒体控制**：MediaSessionService + MediaStyle 通知，锁屏/控制中心控件，封面主色调提取，实时进度条
- **🔤 音频焦点管理**：ExoPlayer 自动处理，多 App 互不干扰
- **🔐 登录系统**：WebView 浏览器登录 + 手动粘贴 Cookie 降级方案，SharedPreferences 持久化
- **💾 状态持久化**：进程被杀后自动恢复播放进度、歌曲信息、封面、歌词、播放队列
- **📱 多屏幕适配**：21:9 基线，宽屏设备 360dp 居中限制，保持窄屏视觉比例
- **🎨 关于页面**：内建 Markdown 渲染器，5 段级联入场
- **🖼️ 自定义图标**：绿色唱片风格 Adaptive Icon
- **🎨 主题色系统**：6 种预设（云杉 / 钴蓝 / 绯红 / 琥珀 / 堇紫 / 素白），Groove 风一行色墙选择器，运行时切换 + 持久化
- **🌐 多语言**：13 种语言运行时切换，不依赖系统 locale——简中 / 繁中 / English / British English / 日本語 / 조선어 / Deutsch / Русский / Советский русский / Ελληνικά / Lingua Latina / Ænglisc / Middle English
- **⚡ 无缝播放**：gapless 模式下提前预加载下一首 URL，切歌即播；TTL 5 分钟缓存，手动切歌命中缓存时零等待
- **📶 分网络音质**：Wi-Fi / 移动数据独立偏好，默认无损 / 较好；全屏播放页实时显示当前音质标签

### 🚀 v1.2.1 新增

- **🎨 Groove 化收官**：UserScreen 全面无边框改造 + 主题色选择器一行色墙 + 关于页 5 段级联入场，全页面 Kanesumi 风格统一
- **⚡ 冷启动统一预热**：`AppWarmup` 在 Splash 遮挡期并发跑 3 条 Home 请求 + Coil 封面预取 + 5 个 SharedPreferences 文件 IO 线程 parse，进入主页时数据/封面/prefs 全部就位
- **🧪 深度性能优化**：播放器状态订阅下推（彻底根除 4Hz 全屏重组）、PlayerCard 折叠态子树 gating（展开阈值以下 dispose 重型内容）、每屏冗余 background 去除（消除 Mali 一层 fill overdraw）、@Immutable 数据类稳定化、列表补 key + 去 `.toList()` 拷贝、通知元数据等值跳过、HttpLoggingInterceptor 仅 debug、详情页返回主 tab 屏 always-mount 消卡消残影、低端机返回空 HOME 跳过动画 + popExit 延迟淡出
- **📉 R8 全量优化**：release APK 从 25 MB 降至 4.1 MB（84% 缩减），Gson 反射 / Retrofit 注解 / Kotlin 元数据完整保护
- **🎞️ 动画过渡铺开**：Kanesumi 风格页面推入过渡、Tab 切换方向感知横向滑入、搜索页三态 Crossfade + SokuouTweens.CoverFade、详情页内容分支 12dp 微滑上入场
- **📦 ContentCache 歌手详情页接入**：消除返回时的 spinner 闪现
- **🌐 网络层网关化**：所有 `music.163.com` 硬编码 URL 收敛到 RetrofitClient，接口切换零改动
- **🔤 14 语种 emoji 剥离**：首页分区标题统一去掉 emoji 前缀

### 🎼 支持音质

| 参数 | 说明 | 要求 |
|------|------|------|
| `standard` | 标准音质 (128kbps) | 普通账号 |
| `exhigh` | 极高音质 (320kbps) | 普通账号 |
| `lossless` | 无损音质 (FLAC) | 黑胶 VIP |
| `hires` | Hi-Res 音质 | 黑胶 VIP |
| `jymaster` | 超清母带 | 黑胶 SVIP |

---

## 📱 使用说明

### 推荐的自动登录
打开 Ncrust → 用户页面 → 头像 → 浏览器登录

### 获取 Cookie（手动登录）

1. 登录 [网易云音乐网页版](https://music.163.com)
2. 按 `F12` → `Application` → `Cookies` → `music.163.com`
3. 找到 `MUSIC_U` 和 `__csrf` 字段，或直接复制完整 Cookie 字符串
4. 打开 Ncrust → 用户页面 → 粘贴 Cookie → 保存

### 安装 APK

从 [Releases](https://github.com/GuitaristRin/Ncrust/releases) 下载最新 `Ncrust-v1.2.1.apk`（约 4 MB），允许"未知来源"安装。

---

## 🛠️ 技术架构

```
app/src/main/java/com/takahashirinta/ncrust/
├── MainActivity.kt              # 精简入口：调用 MainScreen() + 权限/RetrofitClient/AppWarmup 初始化
├── auth/
│   └── CookieManager.kt         # Cookie 存储（SharedPreferences）
├── cache/
│   └── ContentCache.kt          # 网络响应内存快照（首页 + 详情按 ID）
├── library/
│   ├── LibraryManager.kt        # 本地曲库（收藏歌曲 + 专辑派生）
│   └── SearchHistoryManager.kt  # 搜索历史（10 条上限）
├── lyric/
│   └── LrcParser.kt             # LRC → LrcLine.timeMs
├── network/
│   ├── NcmApi.kt                # Retrofit API 接口
│   ├── PlaylistApi.kt           # 歌单/艺人/日推 eapi 端点
│   ├── RetrofitClient.kt        # OkHttp + 网关统一 URL + Cookie 拦截
│   ├── SearchResponse.kt        # 搜索响应模型（@Immutable）
│   ├── crypto/
│   │   └── EapiCrypto.kt        # eapi 加密（AES-128-ECB + MD5 签名）
│   └── model/                   # 数据模型（全部 @Immutable）
├── player/
│   ├── PlaybackService.kt       # MediaSessionService + MediaStyle 通知
│   ├── PlaybackStateManager.kt  # 队列 + 当前曲目持久化
│   └── SongUrlFetcher.kt        # 音质降级链（super → hi-res → lossless → standard）
├── warmup/
│   └── AppWarmup.kt             # 冷启动预热单例（Home 请求 + 封面预取 + prefs XML parse）
└── ui/
    ├── ResponsiveContent.kt     # 360dp 居中限制
    ├── BottomOverlayInset.kt    # 底部 overlay（miniBar + NavBar）内边距常量
    ├── anim/sokuou/             # Sokuou 动画系统（UWP 缓动族 + Apple 弹簧预设）
    ├── components/              # 复用 Composable：SongCard、DetailScaffold、SongMenuSheet…
    ├── i18n/                    # 运行时 i18n：Strings 数据类 + 每语言文件 + LanguageManager
    ├── navigation/              # NavRoutes + MainNavGraph
    ├── player/                  # PlayerCard 拆分：Overlay、Card、FullControls、Lyrics、Queue、SlimProgressBar
    ├── screen/                  # 每屏一文件：Home、Search、Library、User、AlbumDetail…
    ├── theme/                   # 主题色系统 + MarkdownText
    └── viewmodel/               # PlayerViewModel、SearchViewModel、SongViewModel
```

### 核心设计决策

| 决策 | 说明 |
|------|------|
| 三层图层架构 | 主页面 → 卡片层 → 导航栏，视觉与触摸独立 |
| GPU 零重组动画 | `graphicsLayer` 替代 `animateFloatAsState`，组件常驻不销毁 |
| 单 Animatable 驱动 | `progress` 0→1 控制所有播放器动画状态 |
| Kanesumi Design / Groove 无边框 | 直角切割，纯色细线进度条，封面贴屏边，浮层返回箭头，拒绝装饰 |
| 主 tab 屏 always-mount | 详情页 opaque 从上层覆盖 tab 屏，返回时不 remount 消除动画卡顿 |
| 状态订阅下推 | 高频 StateFlow（currentPosition 4Hz）由子组件在 draw scope / derivedStateOf 内订阅，父级零重组 |
| PlayerCard 折叠态 gating | progress < 0.05 时 LyricsView/QueueView/FullPlayerControls 全部 dispose |
| Sokuou 动画系统 | UWP 缓动族 + Apple 风格弹簧预设，新代码统一动画词汇 |
| ContentCache + Crossfade | 网络响应内存快照 + 平滑替换，彻底消除"空屏→spinner→跳变" |
| AppWarmup 冷启动预热 | Splash 遮挡期并发跑网络 + 图片 + SharedPreferences，进主页时全部就位 |
| R8 全量优化 | isMinifyEnabled + isShrinkResources，proguard-rules 保护 Gson/Retrofit 反射 |
| 专辑派生 | 由本地单曲按 `albumId` 去重，非独立实体 |
| 懒加载分页 | 新歌速递每批 10 首，日推 5 行 × N 列横向滑动 |
| SharedPreferences + Gson | 本地存储，无数据库依赖 |

---

## 📋 版本历史

| 版本 | 日期 | 主要内容 |
|------|------|---------|
| v0.1.0-beta | 4 月 26 日 | 初始 MVP，核心播放流程跑通 |
| v1.0.0 | 4 月 29 日 | 首个正式版：多屏幕适配、状态持久化、队列管理 |
| v1.0.1 | 5 月 4 日 | 性能优化（零重组修复）、主题色系统、多语言框架、导航兼容性修复 |
| v1.0.2 | 5 月 4 日 | 紧急修复：歌单页闪退（Issue #11）、WebView 小屏登录 |
| v1.1.4 | 5 月 17 日 | 无缝播放元数据同步修复、URL 缓存去重、多语言扩充至 13 种 |
| v1.2.0 | 7 月 29 日 | Groove Music 无边框大改、Sokuou 动画系统、ContentCache、Kanesumi 页面推入、AppWarmup 冷启动预热、深度性能优化、R8 minify、网络层网关化 |
| v1.2.1 | 7 月 29 日 | 正式发布版：Groove 化收官（UserScreen / 主题色选择器 / 关于页级联入场）、冷启动预热、全链路性能优化（播放器重组0%、overdraw 消除、PlayerCard gating、@Immutable、列表补 key）、R8 minify（APK 25 MB → 4.1 MB）、导航/搜索/详情页动画过渡、ContentCache 歌手页接入、14 语种 emoji 剥离 |

---

## 📋 版本 1.2.0 功能状态

### ✅ 已完成

| 功能 | 状态 |
|------|:--:|
| 首页（新歌速递/推荐歌单/日推） | ✅ |
| 单曲/专辑/艺人搜索 | ✅ |
| 本地曲库（单曲+专辑派生） | ✅ |
| 全屏播放卡片（拖拽手势/迷你栏切换） | ✅ |
| 播放队列（插播/追加/移除/持久化） | ✅ |
| 三种播放模式（列表/单曲/随机） | ✅ |
| 歌词滚动显示（黄金分割点定位） | ✅ |
| 进度条拖拽跳转 | ✅ |
| 系统媒体控制（通知栏/锁屏/控制中心） | ✅ |
| 音频焦点管理 | ✅ |
| Cookie 管理（WebView 登录 + 手动降级） | ✅ |
| 进程被杀后状态恢复（含队列） | ✅ |
| 多屏幕比例适配（16:9～21:9） | ✅ |
| 关于页面（Markdown + 5 段级联入场） | ✅ |
| 应用图标（绿色唱片 Adaptive Icon） | ✅ |
| Splash + AppWarmup 冷启动预热 | ✅ |
| 库页面单曲操作（插播/加队列） | ✅ |
| 主题色系统（6 种预设，一行色墙选择器） | ✅ |
| 多语言系统（13 种，运行时切换） | ✅ |
| 无缝播放（gapless + URL 缓存去重） | ✅ |
| 分网络音质设置（Wi-Fi / 移动数据） | ✅ |
| Groove Music 无边框设计 | ✅ |
| Sokuou 动画系统（UWP 缓动 + Apple 弹簧） | ✅ |
| ContentCache 内存缓存 + Crossfade | ✅ |
| Kanesumi 页面推入过渡 | ✅ |
| 网络层网关化 | ✅ |
| Release 签名打包 + R8 minify | ✅ |
| 深度性能优化（详见「v1.2.0 新增」章节） | ✅ |

### ⏳ 待完成

| 功能 | 优先级 |
|------|:--:|
| 艺人热门单曲（真实排行而非搜索过滤） | ⭐⭐⭐ |
| 专辑/艺人搜索独立页面 | ⭐⭐⭐ |
| 歌单创建/编辑 | ⭐⭐ |
| Baseline Profiles（macrobenchmark 生成 + profileinstaller 打包） | ⭐⭐ |

### 🐛 已知问题

| 问题 | 状态 |
|------|:--:|
| 艺人热门单曲为搜索过滤结果，非真正热门 | 🔧 |
| WebView Cookie 提取偶有失败 | ⚠️ |
| 日推接口使用 eapi 替代 weapi，长期可能失效 | ⚠️ |
| `attributionTag` 系统日志警告（不影响功能） | ⚠️ |
| gapless 未及时预加载时（短歌/提前跳歌）等待 URL fetch | ⚠️ |

---

## 📦 编译

### 环境要求
- Android Studio Hedgehog+
- Kotlin 1.9+
- Gradle 8.x
- Android SDK 24+

### 步骤

```bash
git clone https://github.com/GuitaristRin/Ncrust.git
cd Ncrust
./gradlew assembleDebug
# Debug APK 位于 app/build/outputs/apk/debug/app-debug.apk（约 26 MB）

# Release 需要根目录放 keystore.properties + 签名 .jks，然后：
./gradlew assembleRelease
# Release APK 位于 app/build/outputs/apk/release/app-release.apk（约 4 MB，R8 minified）
```

---

## 🔗 相关项目

| 项目 | 说明 |
|------|------|
| [163CMAnalyser](https://github.com/GuitaristRin/163CMAnalyser) | Rust CLI 无损下载工具，本项目的 API 参考 |
| [Netease_url](https://github.com/Suxiaoqinx/Netease_url) | Python 原版网易云解析（MIT） |

---

## 📄 许可证

```
MIT License

Copyright (c) 2026 Takahashi_Rinta

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.
```

---

## ⭐ 支持项目

若此工具对你有用，请赐一颗 **Star** ⭐

有问题或建议，欢迎提交 [Issue](https://github.com/GuitaristRin/Ncrust/issues)。

## Star History

## Star History

<a href="https://www.star-history.com/?repos=GuitaristRin%2FNcrust&type=date&legend=top-left">
 <picture>
   <source media="(prefers-color-scheme: dark)" srcset="https://api.star-history.com/chart?repos=GuitaristRin/Ncrust&type=date&theme=dark&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
   <source media="(prefers-color-scheme: light)" srcset="https://api.star-history.com/chart?repos=GuitaristRin/Ncrust&type=date&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
   <img alt="Star History Chart" src="https://api.star-history.com/chart?repos=GuitaristRin/Ncrust&type=date&legend=top-left&sealed_token=-LRNV-LDu7Vj6bFSSrS8kUQlcdjj0utMO2u3MTcbZRDlMP4VOyWmJAJTQk4piLt-FZ7Mo6oSr-Kj5S5UeoN28q87yNN0v05vMrCYRlf6Htd9mtnCxlwQbEQ_bW5KhFdVzpmhb3_RXC9bBp7D5T9unPUL2TOf-Cd1p4AYAqx6ru63QXFwh_7fAvmlKd3V" />
 </picture>
</a>
