# Ncrust for Windows —— 实施计划（到完成）

本文是 `windows/` 的施工单：把 `AGENTS.md` 的里程碑拆成**可勾选、可验证**的条目，
按依赖排序。规则：每条完成后勾选并简短注明落点（文件 / 测试 / commit）；改设计先改
`AGENTS.md` 与 `KANESUMI_XAML.md`，再动代码。状态标记：`[x]` 已落地、`[~]` 进行中、
`[ ]` 未开始。

依赖方向永远是：`spec/` → `Ncrust.Core` → `Ncrust.App`；`Kanesumi.Xaml` 独立于 Ncrust。

**设计自由**：Windows 允许在 Kanesumi 语言之上做**新设计**，桌面端以美观为先，不追求与
Android 逐像素一致；Kanesumi.Xaml 提供 token 与基础控件，页面层可自行演进布局与视觉。

---

## 阶段 0 · 工程骨架（M0 配套）

- [x] 解决方案四项目：`Ncrust.Core` / `Kanesumi.Xaml` / `Ncrust.App` / `Ncrust.Core.Tests`
- [x] Release|x64 构建、.NET Native、MSIX、图标
- [x] Kanesumi.Xaml 的 `Colors` / `Typography` / `Overrides`
- [~] `windows/tools/shot`：`Shot.ps1`（固定尺寸截图 + 点击 / 按键）已落地；UIA / Contrast / ResourceAudit 未移植
- [x] 应用图标：整块绿底唱片纹，`tools/icons/MakeIcons.ps1` 生成 33 张 scale / targetsize 资源
- [ ] 附带评估：UWP on 现代 .NET（半天，只记结论）

## 阶段 1 · Ncrust.Core（协议与业务，全部 dotnet test 可验）

### 1.1 网络与加密

- [x] `Net/Crypto/EapiCrypto`、`WeapiCrypto`（`spec/fixtures/crypto`）
- [x] `Net/NcmHttp`（eapi / eapi-official / weapi / GET / weblog；`UseCookies=false`）
- [x] `spec/api/endpoints.md` 端点目录
- [x] `Api/`：搜索（三类）、歌曲详情、歌词、歌单详情（`SearchApi` / `SongApi` / `PlaylistApi`）
- [x] `Api/`：专辑详情（`AlbumApi`）、歌手专辑（`ArtistApi`）、账号 / 用户歌单（`AccountApi`）
- [x] 端点响应模型 + FakeHandler 单测（搜索 / 详情 / 歌词 / 歌单排序与补齐 / 专辑 / 歌手 / 账号）

### 1.2 JSON 与工具

- [x] `Json/JsonValue` 只读 DOM（零反射）；`Net/JsonText` 写入；`Util/RandomText`

### 1.3 登录与账号

- [x] `Auth/SessionCookie`、`Auth/QrLoginClient`
- [ ] `Auth/CookieSession`：登录态判断、`__csrf` / deviceId / osver 派生、与 `ICredentialStore` 装配
- [ ] 二维码轮询节奏封装（2s / 150 次）与「单次失败继续」策略

### 1.4 播放

- [x] `Playback/PlaybackQueue`（5 模式 + 队列操作，`spec/fixtures/queue`）
- [x] `Playback/QualityLadder`（`spec/fixtures/quality`）
- [x] `Playback/SongUrlResolver`（含 FLAC 门控）
- [x] `Playback/PlayReport` / `PlayReportPolicy`
- [x] `Playback/PlaybackSessionState`：队列 + 模式 + 60s 预载窗口 + `PeekNext/PeekPrevious`；
      `PlaybackPreferences`（设置键同 Android）、`PlaybackStateStore`（playback_state.json）

### 1.5 缓存与持久化

- [x] `Cache/ContentCache` + `Cache/LruCache`：首页快照（15s 新鲜期）+ 专辑 / 歌单 / 歌手 LRU-32 + 用户资料
- [x] `Lyrics/LyricsCache`（200 条，落 `IFileStore`）
- [x] `Search/SearchHistory`（每类 10 条、14 天过期，落 `IFileStore`）
- [x] `Library/LibraryManager`：喜欢的歌曲 / 收藏专辑云同步，**云端读失败不覆盖本地**、待同步表；
      `LibraryApi` / `ApiLibraryCloud` + 红心 ids / 收藏专辑 / like / sub album 端点已补

### 1.6 平台接口

- [x] `Platform/ISettingsStore`、`IFileStore`、`ICredentialStore`、`ICodecProbe`、`INetworkInfo`

## 阶段 2 · Ncrust.App 平台层

- [x] `Platform/` 实现：`LocalSettingsStore`、`LocalFileStore`、`PasswordVaultCredentialStore`、
      `WindowsCodecProbe`（恒 true）、`ConnectionProfileNetworkInfo`（`IsMetered`）
- [x] 播放设置键名对齐 Android（`gapless_playback` / `lyrics_translation` / `wifi_quality` / `mobile_quality`）
- [x] **M0 #4 实测**：浏览器 Cookie 导入**不可行**（App-Bound Encryption，前缀 `v20`；DB 运行中被锁）。
      决策：改用**独立登录窗口**——内嵌 WebView2（主）+ 二维码（辅）。结论已回写 `AGENTS.md`。
- [ ] `Login/LoginWindow`：WebView2 登录 + 二维码渲染（QRCoder）+ 轮询，成功后落 `ICredentialStore`

## 阶段 3 · PlaybackEngine（M0 #2 / #3）

- [x] `AppServices` 服务定位器（NcmHttp / 平台实现 / 各 Api / 缓存 / 播放状态）
- [x] `Playback/PlaybackEngine`：`MediaPlayer` + `MediaPlaybackList` 滑动窗口（当前 + 下一首），
      **不用** `ShuffleEnabled` / `AutoRepeatEnabled`；`CurrentItemChanged` 推进状态机
- [x] `MediaSource.CreateFromMediaBinder` 延迟取 URL（`Binding` + deferral → `SongUrlResolver`）；
      元数据随条目走（`MediaItemDisplayProperties`），SMTC 自动接管
- [x] `ItemFailed` → `QualityLadder` 降级重取（songId@level 3s 去重；失败的是当前曲则切回重建条目；
      全部音质失败按 Android onUnplayable 跳下一首，连续失败满一队停下）
- [x] 进度 500ms ticker（约 2Hz）；60s 预载窗口由 `PlaybackSessionState` 判定；播放上报接入
- [x] review 修正：事件统一封送 UI 线程（原先首次播放即 RPC_E_WRONG_THREAD）；窗口只看「当前项之后
      有没有条目」（原先 CYCLE 回绕会停播）；队列推进用 `PlaybackQueue.MoveNext`（随机模式推进打乱位置）；
      上一首由队列后退并重建窗口；启动恢复上次队列（`PlaybackHost.RestoreAsync`）；
      `SetMode` / `RefreshNext` / 音量 / 静音
- [ ] 引擎接线单测（MediaPlayer 不可注入，靠设备实测）
- [ ] **M0 #2 实测**：真实 URL 连播两首无空隙、第二首 URL 在 Binding 里才取、SMTC 元数据正确
- [ ] **M0 #3 实测**：UWP 进程内带 cookie 取每日推荐（`UseCookies=false` 生效）
- [x] Shell 临时入口：登录 / 播放测试曲 / 播放暂停 / 下一首（M0 验证用）

## 阶段 4 · Shell 与窗口

- [x] 自定义标题栏（`ExtendViewIntoTitleBar` + `SetTitleBar`，随显示模式让位），最小尺寸 360x500；
      覆盖层让出标题栏高度
- [x] `ShellPage`：**标准汉堡菜单** `NavigationView`（Version1 样式 + Kanesumi 覆盖，自适应三态）+ 面板搜索框 +
      「我的音乐」分组 + 底部账户项（登录 / 昵称头像）+ 返回按钮 + 内容 `Frame` + 登录覆盖层
- [x] 播放栏占外壳第二行（72），内容不被遮挡 —— 取代原 `BottomOverlayInset` 方案
- [x] 全局快捷键（Space / Ctrl+P / Ctrl+←→ / Ctrl+F / Ctrl+L / F11 / Esc / Alt+← / 鼠标侧键）
- [ ] 剪贴板 / `ncrust://`（M3）

## 阶段 5 · Kanesumi.Xaml 控件（见 `KANESUMI_XAML.md`）

- [x] M1：`KanesumiEasing` / `KanesumiMotion`（tokens → Composition / KeySpline）
- [x] M1 控件起步：`MetroButtonStyle`（主）/ `MetroSecondaryButtonStyle` / `MetroGhostButtonStyle` /
      `MetroIconButtonStyle`、`MetroDividerStyle`、`MetroListRowStyle`；
      `MetroIndication`（PressTint + VisualStates，进入 100ms / 离开 200ms / 悬停 0.5）
- [x] M1：`MetroProgressRing`（Composition 旋转 270° 弧）、`MetroTextBoxStyle`
- [x] M1：`MetroSidebarStyle` / `MetroTabRowStyle`（ListView + 重写 ListViewItem，选中指示条）
      （外壳已改用 NavigationView，`MetroSidebarStyle` 保留但不再使用）
- [x] NavigationView 资源覆盖（`Themes/Controls/Navigation.xaml`）；`MetroGridTileStyle`
- [x] 平台控件优先：搜索页 Tab 改原生 `Pivot`，删除自绘 `MetroSidebarStyle` / `MetroTabRowStyle`（Lists.xaml）
- [x] 强调色跟随 Windows（`KanesumiAccent`），不再覆盖 `SystemAccentColor`
- [ ] M1 剩余：`MetroDetailScaffold` / `MetroLyricsPanel`（跨项滑动指示条与 Composition 指示条待补）
- [ ] M2：`MetroToggleSwitch` / `MetroComboBox` / `MetroMenuFlyout` / `MetroContentDialog`
      （`MetroBottomNav` 不再需要：窄窗口由 NavigationView 最小模式覆盖）
- [ ] 每个控件：深浅 × 两强调色截图对比 + `Contrast.ps1` + `ResourceAudit.ps1` 零未定义键

## 阶段 6 · 播放器层（Composition）

- [x] `Player/PlayerHost` 覆盖层 + `CompositionPropertySet`（`Progress` / `Fullscreen`）
- [x] **唯一封面元素** + `ExpressionAnimation` 形变（迷你栏 → 卡片 → 真全屏）；换歌旧封面保留
- [x] 展开 400ms `standard` / 收起 260ms `fastOutSlowIn`；鼠标点击封面/词按钮展开
- [x] 真全屏专用按钮 + `F11`；`Esc` 逐层退出（全屏 → 卡片 → 收起）
- [x] Groove 式传输栏：模式按钮（循环 / 单曲 / 随机 / 顺序）、上一首 / 播放 / 下一首、可拖动进度条
      （松手才 seek）+ 时长、音量浮层（静音）、歌词 / 展开；音量与模式持久化；窄窗口退化为迷你栏
- [x] 卡片不再重复传输控件；封面落在左栏；全屏时传输栏关闭命中测试
- [ ] `LyricAnim` / `QueueSlide` 标量 + 歌词 / 队列层
- [ ] 触屏拖拽（25% 阈值）；`Progress < 0.05` 重子树 `x:Load` 门控；全屏控件闲置自动隐藏
- [ ] 进度条两次 tick 之间的 Composition 插值（目前是 2Hz 跳动）/ `QueueView` / `LyricsView`
- [ ] **M0 #1 实测**：Release 下展开收起无掉帧、拖拽跟手、收起后重子树卸载

## 阶段 7 · 页面（M1）

- [x] Home：每日推荐 / 推荐歌单 / 新歌（缓存优先 + 后台刷新 + `MetroProgressRing` 加载态）
- [x] Home 第二轮：未登录提示卡、空分区隐藏、每日推荐「全部播放」、点歌改为 `playSongItem`（对齐 Android）、
      页面缓存 + 登录状态变化刷新（`AppServices.SessionChanged`）
- [x] `Login/LoginPage`：WebView2（主）+ 二维码（辅），独立登录层
- [x] Search：面板搜索框即时建议（500ms 防抖，选中即播）+ 搜索页三类 Tab、并发请求、取消过期查询、
      点歌记历史；共享模板字典 `Resources/Templates.xaml`
- [ ] 搜索历史的展示入口（空查询时显示历史）；专辑 / 歌手结果可点（依赖详情页）
- [ ] Playlist 详情、Library、User（设置 / 账户）、About
- [ ] 页面加载态沿用「缓存优先 + 后台刷新」模式，不整屏替换加载器

## 阶段 8 · M2 对齐 Android 主干

- [ ] 音乐库云同步 UI、专辑 / 歌手页
- [ ] 私人 FM + INFINITY（相似 / FM 续播、去重、防重入）
- [ ] 音质设置（6 档偏好 + Wi-Fi / 计费两档默认 3 / 1）、歌词翻译开关、gapless 开关
- [~] 主题：强调色已跟随 Windows 系统强调色（`KanesumiAccent`，不再做 6 色预设）；明暗 3 模式待做
- [ ] 多选批量操作、窄窗口细节（导航已由 NavigationView 最小模式覆盖）
- [ ] dolby / jyeffect 在 Windows 上的解码实测（解不了靠自动降级兜底）

## 阶段 9 · M3 收尾与发布

- [ ] 8 种语言（`Strings` 一一对应英文；运行时切换 + `Bindings.Update()`）
- [ ] `QrPair` 被扫端、`ncrust://` 协议、剪贴板链接
- [ ] Android 端接入 `spec/fixtures` 测试
- [ ] MSIX 发布流程（`Package.appxmanifest` 版本 → tag `win-vX.Y.Z` → `gh release --draft`）

---

## 设备验收（2026-09-26 晚，第二轮）

第二轮是在负责人不便开应用时写的，当晚上机验收。结果：

| # | 项目 | 结果 |
|---|---|---|
| 1 | 启动不崩 | ✅ `Version1` 与模板字典合并均正常 |
| 2 | 图标 | ⏳ 未专门核对开始菜单 / 任务栏（标题栏小图标正常） |
| 3 | 汉堡菜单 | ✅ 展开态、返回按钮、选中竖条、分组标题、标题栏拖动区；紧凑 / 最小态未测 |
| 4 | 账户 / 登录 | ❌→✅ 打开登录层即**死锁**（二维码在 UI 线程上 `.Wait()`），已修；网页登录与扫码均可用，登录后显示昵称与头像 |
| 5 | 传输栏 | ✅ 按钮可点、播放 / 暂停、进度走动；拖动松手、模式重启保留、音量浮层、窄窗口未逐项测 |
| 6 | 播放 | ✅ 首次播放不崩、下一首正常、队列启动恢复；❌→✅ 封面不显示 / 卡片背景不显示 / 卡片封面发糊，已修；自动续播、随机、SMTC 未测 |
| 7 | 搜索 | ✅ 即时建议、搜索页；❌→✅ 无封面的专辑 / 歌手**闪退**（空串绑 Image.Source），已修；Tab 按负责人要求改用原生 Pivot |
| 8 | 首页 | ✅ 未登录登录卡、登录后每日推荐、「全部播放」按钮 |
| 9 | 快捷键 | ✅ Esc 逐层退出播放器；其余未逐项测 |

另按负责人要求：强调色改为**跟随 Windows 系统强调色**（实测蓝色生效），平台控件优先、不再自绘。

仍待验：紧凑 / 最小导航态、自动续播与随机模式长时间播放、SMTC 媒体键、音量与模式持久化、各快捷键。

## 当前状态速览

- **阶段 1（Ncrust.Core）全部完成**，Core 测试 200 个通过；阶段 2 平台层 + 独立登录窗口完成；
  阶段 3 PlaybackEngine 经 review 修正并上机验证可播放。Release|x64 + .NET Native 构建零警告。
- 外壳（标准汉堡菜单 + 自定义标题栏）、Groove 式传输栏、搜索页（原生 Pivot）、首页均已上机验证（见上节）。
- 下一步：**歌词 / 队列层** → 歌单 / 专辑 / 歌手详情页 → 音乐库页 → 设置页。
