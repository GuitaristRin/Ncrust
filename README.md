# Ncrust - 网易云音乐 Android 第三方客户端

<div align="center">

![Kotlin](https://img.shields.io/badge/Kotlin-1.9%2B-purple?style=flat-square&logo=kotlin)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-1.5%2B-blue?style=flat-square&logo=jetpackcompose)
![API](https://img.shields.io/badge/API-24%2B-green?style=flat-square&logo=android)
![License](https://img.shields.io/badge/License-MIT-yellow?style=flat-square)
![Version](https://img.shields.io/badge/Version-0.1.0--beta-orange?style=flat-square)

**Material 3 设计 · 流畅动画 · 无损音质 · 本地曲库管理**

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

- **🔍 多维度搜索**：单曲 / 专辑 / 艺人搜索，支持关键词匹配
- **📚 本地曲库**：保存歌曲与专辑，自动派生专辑封面墙
- **🎧 无损播放**：通过 eapi 加密获取 FLAC 无损流
- **🎭 全屏播放器**：拖拽手势、封面动画、歌词滚动
- **🎼 歌词显示**：LRC 逐行解析，自动滚动定位，用户翻页后 3 秒恢复
- **⏯️ 播放队列**：插播 / 追加 / 移除，三种播放模式（列表循环 / 单曲循环 / 随机播放）
- **🔔 系统媒体控制**：通知栏 / 锁屏 / 控制中心，封面提取主色调，实时进度条
- **🔤 音频焦点管理**：与其他音乐 App 互不冲突，自动暂停/恢复
- **🔐 Cookie 管理**：支持网易云音乐账号登录，解锁更高音质
- **💾 状态持久化**：进程被杀后自动恢复播放进度与歌曲信息

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

### 获取 Cookie

1. 登录 [网易云音乐网页版](https://music.163.com)
2. 按 `F12` → `Network` → 刷新页面
3. 点击任意请求，在 `Request Headers` 中找到 `Cookie` 字段，复制全部值
4. 打开 Ncrust → 用户页面 → 粘贴 Cookie → 保存

### 安装 APK

从 [Releases](https://github.com/GuitaristRin/Ncrust/releases) 下载最新 `app-debug.apk`，允许"未知来源"安装。

---

## 🛠️ 技术架构

```
app/src/main/java/com/takahashirinta/ncrust/
├── MainActivity.kt            # 主入口，所有 UI 组件
├── auth/
│   └── CookieManager.kt       # Cookie 存储与验证
├── crypto/
│   └── EapiCrypto.kt          # 网易云 eapi 加密
├── library/
│   └── LibraryManager.kt      # 本地库管理 (SharedPreferences)
├── lyric/
│   └── LrcParser.kt           # LRC 歌词解析
├── network/
│   ├── NcmApi.kt              # 网易云 API 接口
│   ├── RetrofitClient.kt      # HTTP 客户端
│   └── SearchResponse.kt      # 搜索响应模型
├── player/
│   ├── PlaybackService.kt     # 媒体播放服务 (MediaSessionService)
│   ├── PlaybackStateManager.kt # 播放状态持久化
│   └── SongUrlFetcher.kt      # 歌曲 URL 获取 (eapi)
└── ui/
    ├── screen/                 # 各页面 Composable
    └── viewmodel/              # ViewModel
```

---

## 📋 版本 0.1.0-beta 功能状态

### ✅ 已完成

| 功能 | 状态 |
|------|:--:|
| 歌曲搜索（单曲/专辑/艺人） | ✅ |
| 本地曲库（单曲+专辑派生） | ✅ |
| 播放卡片动画（迷你栏/全屏拖拽） | ✅ |
| 播放队列（插播/追加/移除） | ✅ |
| 三种播放模式（列表/单曲/随机） | ✅ |
| 歌词滚动显示 | ✅ |
| 进度条拖拽 | ✅ |
| 系统媒体控制（通知栏/锁屏/控制中心） | ✅ |
| 音频焦点管理、多 App 互不干扰 | ✅ |
| Cookie 管理与持久化 | ✅ |
| 进程被杀后状态恢复 | ✅ |
| 竖屏锁定 | ✅ |

### ❌ 待完成

| 功能 | 优先级 |
|------|:--:|
| 首页推荐 | ⭐⭐⭐ |
| 专辑详情页（API 调试） | ⭐⭐⭐ |
| 艺人详情页（API 调试） | ⭐⭐⭐ |
| 歌单功能（创建/保存/播放） | ⭐⭐ |
| 长按弹窗加入歌单 | ⭐⭐ |
| 封面图片自定义 | ⭐ |
| Release 签名打包 | ⭐ |

### 🐛 已知问题

| 问题 | 状态 |
|------|:--:|
| 专辑/艺人详情页 API 返回 404 | 🔧 |
| 4GB RAM 设备易被杀进程（已通过状态持久化缓解） | ⚠️ |
| 部分设备通知延迟显示 | ⚠️ |

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
| [Netease_url](https://github.com/Suxiaoqinx/Netease_url) | Python 原版网易云解析 |

---

## 📜 我想说的

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
