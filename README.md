# Ncrust - 网易云音乐 Android 第三方客户端

<div align="center">

![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple?style=flat-square&logo=kotlin)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.5%2B-blue?style=flat-square&logo=jetpackcompose)
![API](https://img.shields.io/badge/API-24%2B-green?style=flat-square&logo=android)
![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)
![Version](https://img.shields.io/badge/Version-1.0.0-brightgreen?style=flat-square)

**Metro Design · GPU 零重组动画 · 无损音质 · 本地曲库管理**

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
- **🔍 多维度搜索**：单曲 / 专辑 / 艺人搜索，500ms 防抖，三标签 Tab 切换
- **📚 本地曲库**：单曲封面墙（两列网格）、专辑自动派生（按 albumId 去重）
- **🎧 无损播放**：eapi 加密获取 FLAC 无损流，完整音质降级链
- **🎭 全屏播放器**：三层图层架构，拖拽手势（25% 阈值吸附），迷你栏/全屏流畅切换
- **🎼 歌词显示**：LRC 逐行解析，上黄金分割点自动定位，手动滚动 5 秒后恢复，上下渐变融入
- **⏯️ 播放队列**：插播/追加/移除/点击切歌，三种模式（列表循环/单曲循环/随机），持久化存储
- **🔔 系统媒体控制**：MediaSessionService + MediaStyle 通知，锁屏/控制中心控件，封面主色调提取，实时进度条
- **🔤 音频焦点管理**：ExoPlayer 自动处理，多 App 互不干扰
- **🔐 登录系统**：WebView 浏览器登录 + 手动粘贴 Cookie 降级方案，SharedPreferences 持久化
- **💾 状态持久化**：进程被杀后自动恢复播放进度、歌曲信息、封面、歌词、播放队列
- **📱 多屏幕适配**：21:9 基线，宽屏设备 360dp 居中限制，保持窄屏视觉比例
- **🎨 关于页面**：内建 Markdown 渲染器，项目信息完整展示
- **🖼️ 自定义图标**：绿色唱片风格 Adaptive Icon

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

从 [Releases](https://github.com/GuitaristRin/Ncrust/releases) 下载最新 `Ncrust-v1.0.0.apk`（17.2 MB），允许"未知来源"安装。

---

## 🛠️ 技术架构

```
app/src/main/java/com/takahashirinta/ncrust/
├── MainActivity.kt              # 主入口，顶层导航与状态协调
├── auth/
│   └── CookieManager.kt         # Cookie 存储（SharedPreferences）
├── crypto/
│   └── EapiCrypto.kt            # eapi 加密（AES-128-ECB）
├── library/
│   └── LibraryManager.kt        # 本地曲库管理
├── lyric/
│   └── LrcParser.kt             # LRC 歌词解析
├── network/
│   ├── NcmApi.kt                # Retrofit API 接口
│   ├── PlaylistApi.kt           # 歌单/艺人 eapi 端点
│   ├── RetrofitClient.kt        # OkHttp 客户端
│   └── SearchResponse.kt        # 搜索响应模型
├── player/
│   ├── PlaybackService.kt       # MediaSessionService 媒体服务
│   ├── PlaybackStateManager.kt  # 播放状态与队列持久化
│   └── SongUrlFetcher.kt        # 歌曲 URL 获取
└── ui/
    ├── ResponsiveContent.kt     # 多屏幕适配组件
    ├── theme/
    │   └── MarkdownText.kt      # 简易 Markdown 渲染器
    ├── screen/
    │   ├── AboutScreen.kt       # 关于页面
    │   ├── AlbumDetailScreen.kt # 专辑详情
    │   ├── ArtistDetailScreen.kt# 艺人详情
    │   ├── HomeScreen.kt        # 首页（新歌/推荐/日推）
    │   ├── LibraryScreen.kt     # 本地曲库
    │   ├── PlaylistDetailScreen.kt # 歌单详情
    │   ├── SongDetailScreen.kt  # 歌曲详情
    │   └── SplashScreen.kt      # 启动屏（JIT 预热 + 渐隐）
    └── viewmodel/
        ├── PlayerViewModel.kt   # 播放状态管理
        └── SearchViewModel.kt   # 搜索状态管理
```

### 核心设计决策

| 决策 | 说明 |
|------|------|
| 三层图层架构 | 主页面 → 卡片层 → 导航栏，视觉与触摸独立 |
| GPU 零重组动画 | `graphicsLayer` 替代 `animateFloatAsState`，组件常驻不销毁 |
| 单 Animatable 驱动 | `progress` 0→1 控制所有播放器动画状态 |
| Metro Design | 直角切割，纯色细线进度条，信息优先，拒绝装饰 |
| 专辑派生 | 由本地单曲按 `albumId` 去重，非独立实体 |
| 懒加载分页 | 新歌速递每批 10 首，日推 5 行 × N 列横向滑动 |
| SharedPreferences + Gson | 本地存储，无数据库依赖 |

---

## 📋 版本 1.0.0 功能状态

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
| 关于页面（Markdown 渲染） | ✅ |
| 应用图标（绿色唱片 Adaptive Icon） | ✅ |
| Splash Screen 渐隐 + JIT 预热 | ✅ |
| 竖屏锁定 | ✅ |
| 库页面单曲操作（插播/加队列） | ✅ |

### ⏳ 待完成

| 功能 | 优先级 |
|------|:--:|
| 艺人热门单曲（真实排行而非搜索过滤） | ⭐⭐⭐ |
| 专辑/艺人搜索独立页面 | ⭐⭐⭐ |
| 歌单创建/编辑 | ⭐⭐ |
| 本地封面图片缓存 | ⭐⭐ |
| Release 签名打包 | ⭐ |

### 🐛 已知问题

| 问题 | 状态 |
|------|:--:|
| 艺人热门单曲为搜索过滤结果，非真正热门 | 🔧 |
| WebView Cookie 提取偶有失败 | ⚠️ |
| 日推接口使用 eapi 替代 weapi，长期可能失效 | ⚠️ |
| Coil 封面缓存策略待优化（偶有模糊） | ⚠️ |
| 4GB RAM 设备进程易被杀（已通过状态持久化缓解） | ⚠️ |
| `attributionTag` 系统日志警告（不影响功能） | ⚠️ |

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
# APK 位于 app/build/outputs/apk/debug/app-debug.apk
```

---

## 🔗 相关项目

| 项目 | 说明 |
|------|------|
| [163CMAnalyser](https://github.com/GuitaristRin/163CMAnalyser) | Rust CLI 无损下载工具，本项目的 API 参考 |
| [Netease_url](https://github.com/Suxiaoqinx/Netease_url) | Python 原版网易云解析（MIT） |

---

## 📜 白茶

我做 Ncrust 的时候，脑子里想的不是代码，不是架构，不是用户增长。

我想的是白茶。

清水什么也没有，太淡。
糖水太热烈，甜得发腻。

白茶不一样。
它有味道，但温和。
它不吵，你需要安静下来，才能品到它的回甘。

Ncrust 就是白茶。
直角是白茶，原生是白茶，无下载是白茶。
不讨好，但有立场。
不炫耀，但有质感。

如果你习惯了糖水，Ncrust 可能不适合你。
但如果你想试试白茶——我泡好了。

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

---

主要更新点：

1. 版本号从 0.1.0-beta 改为 1.0.0
2. 功能状态表大幅扩充——首页发现、播放队列持久化、多屏幕适配、Splash 渐隐、应用图标、关于页面全部标为已完成
3. 新增"核心设计决策"表，把 Metro Design、GPU 零重组、三层图层、单 Animatable 驱动这些架构灵魂写进 README
4. 技术栈描述加了"GPU 零重组动画"和"Metro Design"
5. 已知问题更新——旧问题已解决，新问题反映当前真实状态
6. APK 大小标注 17.2 MB
