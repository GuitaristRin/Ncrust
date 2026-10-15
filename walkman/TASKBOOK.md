# Ncrust for Walkman —— 分支工程任务书

为 Sony Walkman NW-F885（Android 4.1.1 / API 16）做的精简版 Ncrust。**分支工程**：由 arcdeck 工人按阶段实现，派工者（Claude）负责派工、审阅、合入，不亲自实现。
可行性依据：`docs/f885-spike-report.md`（2026-10-15 实测）；背景与决策：`docs/f885-os-research.md`、`docs/PLAN_2026-10.md` §5。

## 0. 不可让渡的约束

1. **不碰设备系统**：不 root、不刷写、不改系统设置；装卸的只有本应用。负责人的原则是「能跑就别动」。
2. **保留原厂音频通路**：播放一律走系统 `MediaPlayer`，不引入自带解码 / 自带音频输出的播放器（ExoPlayer、FFmpeg、OpenSL 自渲染等），
   这样 S-Master HX、DSEE、ClearAudio 等 Sony 音效原样生效。
3. **共享答案，不共享实现**：协议、加密、权益判定、队列语义以 `spec/` 为准，测试直接读 `spec/fixtures/**`，不复制夹具。与 Android `app/` 不共享源码。
4. **设备纪律**：本机同时连着负责人的主力测试机（`HQ645D0044`）。所有 adb 命令必须带 `-s 110024C5064474`，绝不碰另一台设备。
5. **只读调试**：调试属性（如 `debug.hwui.profile`）用完必须恢复；不在设备上留测试文件。

## 1. 工程形态

- 目录：仓库根下 `walkman/`，**独立 Gradle 构建**（自带 `settings.gradle.kts`、`gradlew`），不加入根 `settings.gradle.kts`，与 `windows/` 一样对主工程不可见。
- 包名 `com.takahashirinta.ncrust.walkman`，应用名「Ncrust」（启动器上与主应用区分可加副标题，由负责人定）。
- `minSdk 16`，`targetSdk` 取能在 4.1 上正常运行的最高合理值（工人需说明取值理由），ABI 只打 `armeabi-v7a`。
- 语言 Kotlin；**不用 Compose、不用 AndroidX**（主流 AndroidX 已不支持 API 16）——界面用框架原生 View / Activity。
- 依赖白名单（再加需在最终回复里说明理由，由派工者批准）：`kotlin-stdlib`、OkHttp `3.12.13`（最后支持 API 9+ 的分支）、Okio 1.x、
  Conscrypt `2.5.x`（spike 实测 2.5.3 可用）、JUnit 4 与 Gson（**仅测试**）。协程可选：若在设备上验证可用再引入。
- 提交 scope：`(walkman)`，如 `feat(walkman): …`；正文中文，写清来源（任务 id、模型、用时）。
- `walkman/AGENTS.md`：W0 建立，此后每阶段维护（构建命令、目录结构、设备纪律、已知坑）。

## 2. 目标功能（第一版）

扫码登录、每日推荐、我喜欢的音乐（含 xeapi 收藏写入）、搜索（单曲）、播放队列（顺序 / 随机 / 单曲循环）、歌词（含翻译）、媒体键与锁屏控制、
权益提示（试听 / 无版权 / 音质受限 / 登录失效，规则同 `spec/api/entitlement.md`）。

不做：视频、评论、歌单编辑、私人 FM 以外的发现页、均衡器、下载。

## 3. 阶段与验收

每阶段一个工人任务；前一阶段合入 master 后再派下一阶段。验收命令在仓库根目录执行。

| 阶段 | 内容 | 依赖 |
|---|---|---|
| **W0 地基** | 独立构建 + 网络层 + 加密 + 夹具测试 + 最小冒烟 Activity | 无 |
| **W1 登录与会话** | 扫码登录（eapi + PC 身份 + type 3）、cookie 持久化、会话三态、QrPair 接收端（可选） | W0 |
| **W2 播放核心** | 播放服务、`MediaPlayer` + `setNextMediaPlayer` 无缝、队列、`RemoteControlClient` 媒体键、通知、音频焦点、取链降级与权益判定 | W0 |
| **W3 界面** | 首页 / 我喜欢 / 搜索 / 播放器（歌词）/ 设置，320×521 dp 小屏布局，方向键可操作 | W1、W2 |
| **W4 收尾** | 设备实测性能与内存、FLAC（登录 VIP 后）、发布打包与签名、`docs/RELEASES.md` 增加 Walkman 行 | W3 |

### W0 地基（详见 `tasks/w0-foundation.md`）

- 建 `walkman/` 独立工程，复制根目录 Gradle wrapper，能 `walkman\gradlew.bat -p walkman assembleDebug` 出 APK。
- 网络层：OkHttp 3.12 + Conscrypt（启动时 `Security.insertProviderAt(Conscrypt.newProvider(), 1)`，OkHttp 显式启用 TLS 1.2+ 的 `ConnectionSpec`）。
- 加密：eapi、weapi、xeapi 的 Kotlin 实现，**X25519 与 AES-GCM 不能依赖 4.1 的 JCA**（X25519 纯 Kotlin 实现可参照 `app/.../network/crypto/XeapiCrypto.kt`；
  AES-GCM 由 Conscrypt 提供或纯实现），JVM 单测逐字节跑 `spec/fixtures/crypto/{eapi,weapi,xeapi}.json`。
- 接口：歌曲详情、取链（`song/enhance/player/url/v1`，含降级阶梯，规则同 `spec/fixtures/quality/`）、歌词、每日推荐、我喜欢列表读取。
- 最小冒烟 Activity：在设备上匿名取一首免费歌并用 `MediaPlayer` 播放，日志可见。

验收：
```
walkman\gradlew.bat -p walkman testDebugUnitTest
walkman\gradlew.bat -p walkman assembleDebug
```
外加派工者在设备上冒烟（安装、匿名取链、播放、卸载）。

### W1–W4

各阶段任务书在前一阶段合入后由派工者编写（放在 `walkman/tasks/`），因为后续设计依赖前一阶段落地的接口。
写作时遵循本文件 §0、§1，并引用对应的 `spec/` 规格。

## 4. 已知风险（来自 spike）

- TLS 1.0 退役 → 必须走 Conscrypt。
- 单应用内存上限 48 MB → 封面按显示尺寸解码、列表复用、歌词不一次性渲染。
- 4.1 无 MediaCodec FLAC 解码器 → 依赖 `MediaPlayer`（Stagefright 解析）播 FLAC，需 VIP 登录后实测。
- Sony 原厂播放器也注册了媒体键接收器 → 需验证前后台切换时按键归属。
- 网页登录在 4.1 旧 WebKit 上空白 → 只做扫码 / 局域网交接登录。
