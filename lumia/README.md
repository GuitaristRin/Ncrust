# Ncrust for Lumia（迁入的历史原型）

2026-10-28，按负责人决定，将原 Ritsudou 的 Lumia 950 开发线迁入 Ncrust 的 `lumia/`。来源为 `GuitaristRin/Ritsudou` 提交 `ae46a99e5f21935ba0680d56001f14aa69ee41f6`，文件按 Git 行尾规则规范化后逐一核对 blob。源仓历史保留，未删除原文件。

当前为原型基线：工程名、命名空间、包身份与下述旧里程碑仍保留 Ritsudou，尚未迁移为正式 Ncrust 安装包，未在本轮构建或上机验证。后续在这里维护 Ncrust 的 C++ / UWP（Windows.UI.Xaml）Lumia 950 线；Ritsudou 的独立方向暂缓。平台总规划见 [PLATFORMS](../docs/PLATFORMS.md)。

以下为原型工程说明，仅作构建与历史参考；协议和行为须对齐根 `spec/`，尤其不能继承旧外链播放兜底作为现行规则。

网易云音乐 UWP 独立客户端，C++/WinRT 实现，Metro 设计语言，性能敏感，目标测试设备 **Lumia 950**（Windows 10 Mobile，ARM32，SDK ≥ 14393）。

律動（Ritsudō）—— 节奏律动：既是音乐，也是 GPU 动画的内核。视觉上继承自 Ncrust 的 Metro 体系：OLED 纯黑背景 + 云杉绿 `#1DB954` 强调色 + `#1A1A1A` 表面 + 直角无圆角。

## 目录结构

```
Ritsudou.sln
Ritsudou/
  Ritsudou.vcxproj           # ARM / x64 / x86 三平台，AppContainer 打包
  Package.appxmanifest      # MinVersion 10.0.14393.0，internetClient
  App.xaml(.h/.cpp)         # 应用入口（Frame 导航根）
  ShellPage.xaml(.h/.cpp)   # 壳页面：底部三栏导航（首页/搜索/收藏），内容用嵌套 Frame
  HomePage.xaml(.h/.cpp)    # 首页：推荐歌单 GridView
  MainPage.xaml(.h/.cpp)    # 搜索页（歌曲/专辑/艺人分类）+ 播放条
  PlayerPage.xaml(.h/.cpp)  # 全屏播放页：封面/歌词切换、进度条、播放控制、质量徽标、队列
  DetailPage.xaml(.h/.cpp)  # 通用详情页：专辑/歌单/艺人（导航参数 "album:<id>" 等）
  LibraryPage.xaml(.h/.cpp) # 收藏页：我喜欢的音乐
  Themes/Metro.xaml         # 律動 Metro 色板 / 文本层级 / 控件样式
  Core/                     # 纯 C++/WinRT，可独立单测，同步阻塞（仅供后台线程）
    crypto/EapiCrypto       # BCrypt: AES-128-ECB(PKCS7) + MD5，与 Android 版逐字节等价
    net/NcmHttp             # Windows.Web.Http，手动 Cookie/UA/Referer
    api/NcmApi              # 搜索 / 歌词 / 歌曲详情 / 歌曲URL / 专辑·歌单·艺人详情 / 推荐歌单
    lyric/LrcParser         # [MM:SS.mm] → LyricLine，与 Android 正则对齐
    model/Models.h          # Song / Album / Artist / LyricLine / 详情模型 等
    player/PlayerEngine     # MediaPlayer 前台播放
    player/PlaybackController # 播放会话单例：队列+引擎+歌词+质量，UI 事件派发
    queue/PlayQueue         # 播放队列：顺序/循环/随机，去重插入，自动连播索引
    library/LibraryManager  # 收藏（我喜欢的音乐），ApplicationData 持久化
    util/Text.h             # UTF-8 互转 / JSON 转义 / URL 编码
```

## 一、环境（必须）

当前 BuildTools **缺少 UWP workload**，需先安装。以管理员打开终端执行：

```
"C:\Program Files (x86)\Microsoft Visual Studio\Installer\setup.exe" modify `
  --installPath "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools" `
  --add Microsoft.VisualStudio.Workload.Universal --includeRecommended --passive
```

或：打开 Visual Studio Installer → BuildTools → 修改 → 勾选「通用 Windows 平台开发」（Universal Windows Platform development，含 C++ 工具）。约 2–3 GB 下载。

首次构建还需联网还原 NuGet 包 `Microsoft.Windows.CppWinRT`（版本固定在 vcxproj 中）。

## 二、构建

```powershell
# 先还原（生成 .g.h/.g.cpp、XAML 投影）
msbuild Ritsudou.sln /t:Restore /p:Configuration=Debug /p:Platform=x64

# x64 桌面调试（快速验证 UI 与网络链路）
msbuild Ritsudou.sln /t:Build   /p:Configuration=Debug /p:Platform=x64

# ARM32 真机发布（Lumia 950）
msbuild Ritsudou.sln /t:Build   /p:Configuration=Release /p:Platform=ARM
```

产物：`build\ARM\Release\AppxPackages\Ritsudou_1.0.0.0_arm_Release\`（含 `.appx`）。

> 也可以在 Visual Studio 里直接打开 `Ritsudou.sln` 构建/部署。

## 三、签名证书（已 gitignore）

打包签名证书 `Ritsudou_TemporaryKey.pfx` 是私钥，**不入库**（安全起见）。克隆后本地重新生成：

```powershell
$cert = New-SelfSignedCertificate -Type CodeSigningCert -Subject "CN=TakahashiRinta" `
  -CertStoreLocation Cert:\CurrentUser\My -KeyExportPolicy Exportable -KeySpec Signature -NotAfter (Get-Date).AddYears(3)
$bytes = $cert.Export([System.Security.Cryptography.X509Certificates.X509ContentType]::Pfx, "")
[System.IO.File]::WriteAllBytes("$PSScriptRoot\Ritsudou\Ritsudou_TemporaryKey.pfx", $bytes)
```

## 四、部署到 Lumia 950

前提：手机进入 设置 → 更新与安全 → 面向开发人员 → 开启「开发人员模式」，并确保手机与 PC 在同一 Wi-Fi（或用 USB 共享网络）。

**方式 A：WinAppDeployCmd（推荐）**

```powershell
$sdk = "C:\Program Files (x86)\Windows Kits\10\bin\10.0.22621.0\x64"
& "$sdk\WinAppDeployCmd.exe" install `
  -file "build\ARM\Release\AppxPackages\Ritsudou_1.0.0.0_arm_Release\Ritsudou_1.0.0.0_arm_Release.appx" `
  -ip <手机IP>
```

首次会询问是否信任开发证书，选是。

**方式 B：Windows Device Portal** — 手机浏览器打开 `http://<手机IP>:8080` 启用 WDP 后，在「应用管理器」上传 `.appx` 安装。

## 五、架构约定

- **Core 全同步阻塞**，只允许在后台线程调用；UI 层统一用 `co_await winrt::resume_background()` 切线程、`co_await winrt::resume_foreground(dispatcher)` 回 UI 线程。已在 `pch.h` 的 cppwinrt 头 + 协程调用上做过桌面冒烟编译验证。
- **EapiCrypto**：`urlPath = url.path`（去 `/eapi/` 前缀）；`digest = MD5("nobody"+urlPath+"use"+payloadJson+"md5forencrypt")`；`params = AES-128-ECB(urlPath + "-36cd479b6b5-" + payloadJson + "-36cd479b6b5-" + digest)`；返回小写 hex。MD5/AES 已通过 .NET 参考向量单测。
- **质量降级链**：hires → lossless → exhigh → higher → standard，全败走外链。
- 无任何第三方 JSON / HTTP 依赖：JSON 用 `Windows.Data.Json`，加密用 BCrypt，网络用 `Windows.Web.Http`。

## 六、里程碑路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| M1 | eapi/AES/MD5 + 搜索 + 歌曲URL + MediaPlayer 前台播放 + Metro 主界面 | 骨架完成，待环境就绪编译 |
| M2 | 歌词（LrcParser 已就绪）+ 全屏播放页 + 队列 + 歌词滚动 | 完成（含队列视图），待环境编译 |
| M3 | 专辑/歌单/艺人详情 + 首页推荐 + 收藏 | 完成（详情 + 推荐 + 收藏 + 底部导航） |
| M4 | 后台音频任务 + SMTC + 锁屏控制 | 待做 |
| M5 | 登录 / Cookie 持久化 / 我的歌单 | 待做 |

## 七、性能注意事项（Lumia 950 / ARM32）

- 列表用 `ListView` 容器虚拟化；**M2 起**把搜索结果行改成 `runtimeclass`（IDL + `x:Bind`）以换取 UIElement 级别的容器复用（当前为省事用命令式建行）。
- 封面 `BitmapImage` 远程 URL 自动异步解码，行高固定避免布局抖动。
- Release 全优化（`/O2` + COMDAT + 引用合并），`/utf-8 /await`。
