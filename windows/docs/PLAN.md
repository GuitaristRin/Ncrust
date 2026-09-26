# Ncrust for Windows —— 实施计划（到完成）

本文是 `windows/` 的施工单：把 `AGENTS.md` 的里程碑拆成**可勾选、可验证**的条目，
按依赖排序。规则：每条完成后勾选并简短注明落点（文件 / 测试 / commit）；改设计先改
`AGENTS.md` 与 `KANESUMI_XAML.md`，再动代码。状态标记：`[x]` 已落地、`[~]` 进行中、
`[ ]` 未开始。

依赖方向永远是：`spec/` → `Ncrust.Core` → `Ncrust.App`；`Kanesumi.Xaml` 独立于 Ncrust。

---

## 阶段 0 · 工程骨架（M0 配套）

- [x] 解决方案四项目：`Ncrust.Core` / `Kanesumi.Xaml` / `Ncrust.App` / `Ncrust.Core.Tests`
- [x] Release|x64 构建、.NET Native、MSIX、图标
- [x] Kanesumi.Xaml 的 `Colors` / `Typography` / `Overrides`
- [ ] 从 arc-deck 移植 `windows/tools/shot`（截图 / UIA / Contrast / ResourceAudit）
- [ ] 附带评估：UWP on 现代 .NET（半天，只记结论）

## 阶段 1 · Ncrust.Core（协议与业务，全部 dotnet test 可验）

### 1.1 网络与加密

- [x] `Net/Crypto/EapiCrypto`、`WeapiCrypto`（`spec/fixtures/crypto`）
- [x] `Net/NcmHttp`（eapi / eapi-official / weapi / GET / weblog；`UseCookies=false`）
- [x] `spec/api/endpoints.md` 端点目录
- [x] `Api/`：搜索（三类）、歌曲详情、歌词、歌单详情（`SearchApi` / `SongApi` / `PlaylistApi`）
- [ ] `Api/`：专辑详情、歌手详情 + 热门歌曲、用户资料
- [x] 端点响应模型 + FakeHandler 单测（搜索 / 详情 / 歌词 / 歌单排序与补齐）

### 1.2 JSON 与工具

- [x] `Json/JsonValue` 只读 DOM（零反射）；`Net/JsonText` 写入；`Util/RandomText`

### 1.3 登录与账号

- [x] `Auth/SessionCookie`、`Auth/QrLoginClient`、`Platform/IBrowserCookieSource`
- [ ] `Auth/CookieSession`：登录态判断、`__csrf` / deviceId / osver 派生、与 `ICredentialStore` 装配
- [ ] 二维码轮询节奏封装（2s / 150 次）与「单次失败继续」策略

### 1.4 播放

- [x] `Playback/PlaybackQueue`（5 模式 + 队列操作，`spec/fixtures/queue`）
- [x] `Playback/QualityLadder`（`spec/fixtures/quality`）
- [x] `Playback/SongUrlResolver`（含 FLAC 门控）
- [x] `Playback/PlayReport` / `PlayReportPolicy`
- [ ] `Playback/PlaybackSessionState`：把「队列 + 当前 + 模式 + 预载窗口（60s）」串成一个可观测状态层

### 1.5 缓存与持久化

- [ ] `Cache/ContentCache`：首页快照（15s 新鲜期）+ 专辑 / 歌单 / 歌手的 LRU-32 + 用户资料
- [ ] `Lyrics/LyricsCache`（200 条，落 `IFileStore`）
- [ ] `Search/SearchHistory`（每类 10 条、14 天过期）
- [ ] `Library/LibraryManager`：喜欢的歌曲 / 收藏专辑云同步，**云端读失败不覆盖本地**

### 1.6 平台接口

- [x] `Platform/ISettingsStore`、`IFileStore`、`ICredentialStore`、`ICodecProbe`、`INetworkInfo`、`IBrowserCookieSource`

## 阶段 2 · Ncrust.App 平台层

- [ ] `Platform/` 实现：`LocalSettingsStore`、`LocalFileStore`、`PasswordVaultCredentialStore`、
      `WindowsCodecProbe`（恒 true）、`ConnectionProfileNetworkInfo`（`IsMetered`）
- [ ] `Login/BrowserLogin`：`Launcher` 开浏览器 + `IBrowserCookieSource` 导入 + `PasswordVault` 存储
- [ ] **M0 #4 实测**：浏览器 Cookie 库（Chrome / Edge、DPAPI / App-Bound、AppContainer 沙箱）；
      读不到时的 full-trust 伴随进程方案；结论回写 `AGENTS.md`
- [ ] 设置键名对齐 Android（`ncrust_settings` 同名键）

## 阶段 3 · PlaybackEngine（M0 #2 / #3）

- [ ] `Playback/PlaybackEngine`：`MediaPlayer` + `MediaPlaybackList` 滑动窗口（当前 + 下一首），
      **不用** `ShuffleEnabled` / `AutoRepeatEnabled`；`CurrentItemChanged` 驱动状态机前进一步
- [ ] `MediaSource.CreateFromMediaBinder` 延迟取 URL（`Binding` + deferral → `SongUrlResolver`）；
      元数据随条目走（`MediaItemDisplayProperties`）
- [ ] `ItemFailed` → `QualityLadder` 降级重取，`songId@level` 3s 去重；standard 再失败跳歌
- [ ] 进度 `PositionChanged` 节流 2Hz；`needsPreload`（60s 窗口）；SMTC 元数据随曲更新
- [ ] 播放上报接入 `PlayReportPolicy` + `NcmHttp.PostWeblogAsync`
- [ ] 单测：引擎与状态机的接线用可注入的 fake 播放器/取链覆盖
- [ ] **M0 #3 实测**：UWP 进程内带 cookie 取每日推荐（`UseCookies=false` 生效）

## 阶段 4 · Shell 与窗口

- [ ] 自定义标题栏（`ExtendViewIntoTitleBar` + `SetTitleBar`），最小尺寸
- [ ] `ShellPage`：宽窗口 `MetroSidebar`（200）/ 窄窗口 `MetroBottomNav`（56）+ 内容 `Frame` + 播放器层
- [ ] `BottomOverlayInset`（宽 80 / 窄 120）统一底部内边距
- [ ] 全局快捷键（Space / Ctrl+←→ / Ctrl+F / Ctrl+L / F11 / Esc / 返回键）
- [ ] 剪贴板 / `ncrust://`（M3）

## 阶段 5 · Kanesumi.Xaml 控件（见 `KANESUMI_XAML.md`）

- [ ] M1：`MetroButton` / `MetroIconButton` / `MetroListRow` / `MetroDivider` / `MetroTabRow` /
      `MetroProgressRing` / `MetroTextBox` / `MetroSidebar` / `MetroDetailScaffold` /
      `MetroLyricsPanel`；`MetroIndication`（PressTint + VisualStates）
- [ ] M1：`KanesumiEasing` / `KanesumiMotion`（tokens → Composition / KeySpline）
- [ ] M2：`MetroToggleSwitch` / `MetroComboBox` / `MetroMenuFlyout` / `MetroContentDialog` /
      `MetroBottomNav`
- [ ] 每个控件：深浅 × 两强调色截图对比 + `Contrast.ps1` + `ResourceAudit.ps1` 零未定义键

## 阶段 6 · 播放器层（Composition）

- [ ] `Player/PlayerHost` 覆盖层 + `PlayerProps`（`Progress` / `Fullscreen` / `LyricAnim` / `QueueSlide`）
- [ ] **唯一封面元素** + `ExpressionAnimation` 形变（迷你栏 → 卡片 → 真全屏），`COVER_HOLD_MS = 400`
- [ ] 展开 400ms `standard` / 收起 260ms `fastOutSlowIn`；触屏拖拽（25% 阈值）；鼠标点击
- [ ] `Progress < 0.05` 重子树 `x:Load` 门控（歌词 / 队列 / 完整控件）
- [ ] 真全屏专用按钮 + `F11`；`Esc` 逐层退出；控件闲置自动隐藏
- [ ] `PlayerControls` / `SeekBar`（Composition 插值）/ `QueueView` / `LyricsView`（封装 `MetroLyricsPanel`）
- [ ] **M0 #1 实测**：Release 下展开收起无掉帧、拖拽跟手、收起后重子树卸载

## 阶段 7 · 页面（M1）

- [ ] Home：每日推荐 / 推荐歌单 / 新歌（先读 `ContentCache`，后台刷新，`Crossfade` 400ms）
- [ ] Playlist 详情、Search（三类 Tab + 500ms 防抖 + 历史建议）、Library、User（设置 / 账户）、About
- [ ] `Login/QrLoginDialog` + 浏览器导入入口
- [ ] 页面加载态沿用「缓存优先 + 后台刷新」模式，不整屏替换加载器

## 阶段 8 · M2 对齐 Android 主干

- [ ] 音乐库云同步 UI、专辑 / 歌手页
- [ ] 私人 FM + INFINITY（相似 / FM 续播、去重、防重入）
- [ ] 音质设置（6 档偏好 + Wi-Fi / 计费两档默认 3 / 1）、歌词翻译开关、gapless 开关
- [ ] 主题 6 色 × 3 模式（`KanesumiTheme.Apply`，共享 `KPrimaryBrush`）
- [ ] 多选批量操作、窄窗口布局
- [ ] dolby / jyeffect 在 Windows 上的解码实测（解不了靠自动降级兜底）

## 阶段 9 · M3 收尾与发布

- [ ] 8 种语言（`Strings` 一一对应英文；运行时切换 + `Bindings.Update()`）
- [ ] `QrPair` 被扫端、`ncrust://` 协议、剪贴板链接
- [ ] Android 端接入 `spec/fixtures` 测试
- [ ] MSIX 发布流程（`Package.appxmanifest` 版本 → tag `win-vX.Y.Z` → `gh release --draft`）

---

## 当前状态速览

- 阶段 1.1–1.4 主体完成；`Ncrust.Core.Playback` 完整；搜索 / 歌曲详情 / 歌词 / 歌单详情端点落地。
  Core 测试 134 个通过。
- 下一步：**1.1 剩余端点（专辑 / 歌手 / 用户）** → 1.5 缓存 → 1.4 状态层 → 阶段 2 平台层。
