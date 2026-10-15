# NW-F885 可行性 spike 报告

2026-10-15。设备：Sony Walkman NW-F885（adb `110024C5064474`），Android 4.1.1 / API 16。
测试 App（独立工程 `D:/arcdeck/spike-f885/`，不在本仓库）由 arcdeck 工人 `ncrust-f885-spike` 编写并完成 HTTPS 矩阵，工人在第 91 步因模型侧 400
中断后，派工者（Claude）接手完成其余阶段并写报告。**测试结束后已卸载测试 App、删除其文件、恢复 `debug.hwui.profile=false`，设备无残留。**
原始输出：`D:/arcdeck/spike-f885/shots/`（`info-out.txt`、`device-out.txt`、`gfx-list.txt`、截图）。

## 结论

**可以做。** 原厂 Android 4.1 撑得起一个精简版 Ncrust：网络、取链、播放、实体按键全部跑通，且走的是 Walkman 自己的音频通路——**S-Master HX 音效原样保留**。
这条路线不碰系统，与「能跑就别动」不冲突；系统级工程（`docs/f885-os-research.md` 的方向 B/C）**不需要为产品需求立项**。

唯一走不通的是内嵌网页登录，改用扫码登录即可。

## 逐项结果

### 1. 设备事实 [实测]

| 项 | 值 |
|---|---|
| 屏幕 | 480×800 px（应用可用 480×782）、240 dpi（hdpi，density 1.5）、可用约 **320×521 dp**、刷新率 62 Hz |
| 内存 | 单应用上限 48 MB（`memoryClass`），largeHeap 256 MB；可用约 240 MB |
| 根证书 | 139 个，含 DigiCert Global Root CA / G2 链路所需的根 |
| WebView | 4.1 框架内置 WebKit（AppleWebKit/534.30，非 Chromium），无独立 WebView 包 |
| 音频解码器（MediaCodec） | mp3、aac、vorbis、amr、g711、wma、raw；**无 FLAC 解码器**（只有 FLAC 编码器） |
| 第三方安装 | `adb install` 可装第三方 APK |

### 2. HTTPS [实测]

| 网络栈 | music / interface / interface3 / 音频 CDN | 协商 TLS |
|---|---|---|
| 系统 `HttpsURLConnection` | 全部 200 | TLS 1.0（系统支持 1.2 但默认只启用 SSLv3/TLSv1） |
| OkHttp 3.12.13 + 系统 TLS | 全部 200 | TLS 1.0 |
| **OkHttp 3.12.13 + Conscrypt 2.5.3** | 全部 200 | **TLS 1.2 / 1.3**，AES-GCM / ChaCha20 |

- 证书链（DigiCert Global Root G2、TrustAsia → Certum）均能被系统证书库校验通过。
- 原始 SSLSocket 探针对 `music.163.com` 报信任失败，原因是 API 16 的裸 socket **不发 SNI**，服务器回了默认证书；发 SNI 的 HttpsURLConnection / OkHttp 均正常。不是劫持。
- 建议用 **OkHttp 3.12 + Conscrypt**：不受「网易哪天下线 TLS 1.0」影响。代价是 APK 多一个 armeabi-v7a 原生库。

### 3. 取链与播放 [实测]

- 匿名 eapi `v3/song/detail`（Conscrypt，TLS 1.2）→ 200，周杰伦《屋顶》（5257138）。
- 匿名 eapi `song/enhance/player/url/v1` level=standard → 128k MP3；level=lossless → 服务端按权益降到 exhigh 320k MP3（与 `spec/api/entitlement.md` 一致）。
- 系统 `MediaPlayer` 播放上述 URL：`prepare` 成功、时长正确、12 秒内进度连续推进、音频管线处于活动状态。两档码率都通过。
- **FLAC**：匿名拿不到 FLAC 地址，未实测。[推断] 4.1 的 `MediaPlayer` 由 Stagefright 的 FLAC 解析器解成 PCM 再走 raw 解码器，应能播放；
  但直接调 MediaCodec 的播放器（如 ExoPlayer）因没有 FLAC 解码器而放不了——这也是选 `MediaPlayer` 的理由之一。需 VIP 账号登录后实测。

### 4. 性能底子 [实测，部分定性]

- 最小 View 应用冷启动 392 ms，进程 PSS 约 20 MB（上限 48 MB 内宽裕）。
- 300 行 ListView 滚动：4.1 的 HWUI 逐帧分析未能输出，只有定性观察（滚动正常完成，1433 帧渲染）。正式开发时再用其它方式量化。

### 5. 实体按键 [实测，adb 注入]

播放/暂停（85）、下一首（87）、上一首（88）、播放（126）、暂停（127）、快退（89）均能在前台 Activity 的 `onKeyDown` 收到，
也能经 `RemoteControlClient` + `registerMediaButtonEventReceiver` 以媒体按键广播在后台收到。按键由 adb 注入，与机身实体键共用同一条媒体按键路由
（内核把 `KEY_PLAYPAUSE` / `KEY_NEXTSONG` / `KEY_PREVIOUSSONG` 映射到这些键码）；机身实体键本身未逐一按压验证。

### 6. 登录 [实测]

- 内嵌 WebView 打开 `https://music.163.com/#/login` → 被重定向到 `https://y.music.163.com/m/login` → **页面空白**（旧 WebKit 跑不动现代 JS）。网页登录不可行。
- 替代：**扫码登录**——eapi + PC 身份 + type 3（本仓库 108ddb9 已在 Android / Windows 实现），在 Walkman 上显示二维码、手机网易云扫码确认；
  或复用 Ncrust Android 的 `QrPair` 局域网交接（手机扫码后把 cookie 经局域网发给 Walkman）。两者都只依赖已验证可通的 HTTPS。

## 推荐技术栈（精简版「Ncrust for Walkman」）

| 层 | 选择 | 理由 |
|---|---|---|
| minSdk | 16 | 设备即 API 16 |
| UI | 传统 View（无 Compose） | Compose 要求 API 21+；View 在本机冷启动快、内存小 |
| 网络 | OkHttp 3.12.13 + Conscrypt 2.5.x | 已实测 TLS 1.2/1.3 全通；OkHttp 3.12 是最后支持 API 9+ 的分支 |
| 加密 | 复用 `spec/fixtures/crypto/` 的 eapi / xeapi 夹具，Java 重写 | 三端「共享答案、不共享实现」的既有约定 |
| 播放 | 系统 `MediaPlayer`（不用 ExoPlayer） | 已实测可播；可能借 Stagefright 播 FLAC；走原厂音频通路以保留 S-Master HX |
| 媒体键 / 锁屏 | `RemoteControlClient` + `registerMediaButtonEventReceiver` | 4.1 没有 MediaSession；已实测可收键 |
| 登录 | 扫码登录 / QrPair 局域网交接 | 网页登录不可行 |
| 布局 | 320×521 dp 小屏 | 与荣悦 E5 一类小屏适配可共享设计结论 |

功能范围建议（第一版）：扫码登录、每日推荐、收藏（含 xeapi 收藏写入）、搜索、播放队列、歌词、媒体键。不做：视频、评论、私人 FM 以外的复杂发现页。

## 主要风险

- **TLS 1.0 退役**：若只用系统 TLS，网易一旦关闭 TLS 1.0 即全部失效——用 Conscrypt 规避。
- **xeapi 的 X25519 / AES-GCM**：4.1 的 JCA 没有 X25519，需纯 Java 实现（Android 端 Kotlin 版可直接参照）；AES-GCM 由 Conscrypt 提供。
- **内存上限 48 MB**：封面图需按显示尺寸解码，歌词与列表要节制。
- **FLAC / 高码率** 需登录 VIP 后实测。
- **与原厂播放器的媒体键争夺**：Sony 原厂播放器也注册了媒体键接收器，需要验证切换前后台时按键归属是否正确。
