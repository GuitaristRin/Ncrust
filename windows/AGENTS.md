# AGENTS.md —— Ncrust for Windows

本文件是 `windows/` 目录下的权威指南，面向在此工作的编码 agent（Claude Code、Codex 等）和人。
仓库级约定（提交规范、「一个逻辑单元一个 commit」等）见根目录 `AGENTS.md`；根文件里
Android 专属的章节不适用于这里。

**状态**（2026-09-26）：**M0 实施中，功能未开始。** 解决方案的四个项目都已建好，
Release|x64 构建零警告并生成 MSIX；注册后能启动，显示纯黑底加 34px 页头（颜色和字号来自
Kanesumi.Xaml）。`Ncrust.Core` 已落地网络与加密层（eapi / weapi、`NcmHttp`）、
自研只读 `JsonValue`、登录层（`SessionCookie` / `QrLoginClient` / `IBrowserCookieSource`）、
`DiscoveryApi`（首页）、`SearchApi` / `SongApi` / `PlaylistApi` / `AlbumApi` / `ArtistApi` /
`AccountApi`（搜索 / 详情 / 歌词 / 歌单 / 专辑 / 歌手 / 账号）、`QualityLadder`、
`LrcParser` / `LyricMerger`、`PlaybackQueue`、`SongUrlResolver`、`PlayReport`、
缓存与持久化（`ContentCache` / `SearchHistory` / `LyricsCache`）、云收藏库
（`LibraryManager` / `LibraryApi` / `ApiLibraryCloud`）、播放状态层
（`PlaybackSessionState` / `PlaybackPreferences` / `PlaybackStateStore`）。
**`Ncrust.Core` 的阶段 1 已全部完成**，`spec/fixtures` 第一批（crypto / quality / queue / lrc）与
`spec/api/endpoints.md` 已落地，Core 测试 183 个通过。App 侧平台层已实现
（`LocalSettingsStore` / `LocalFileStore` / `PasswordVaultCredentialStore` / `WindowsCodecProbe` /
`ConnectionProfileNetworkInfo`），Release|x64 + .NET Native 构建零警告。施工单见 `windows/docs/PLAN.md`。
下一步：`BrowserLogin` 与 M0 #4 浏览器 Cookie 导入；M0 的其余 UWP 验证项还没开始。

本文描述的是**已定的架构**，除「目录结构」里列出的现有文件外，其余都是待实现的设计。
写代码时如果发现与本文冲突，先改本文、再改代码，并在 commit 里说明原因。

相关文档：

- `windows/docs/KANESUMI_XAML.md` —— 控件迁移规格（从 Kanesumi-sec-a 移植）
- `spec/README.md` —— 跨平台规格与夹具约定
- `spec/design/tokens.json` —— 设计 token（颜色、字号、缓动、时长、尺寸）

## 立项决策

| 决策 | 结论 | 原因 |
|---|---|---|
| 仓库 | monorepo：`windows/` 与 `spec/` 放在 Ncrust 仓库里，Android 的 `app/` 不挪 | 改协议时，spec 与两端实现可以在同一个 commit 里改完；零迁移成本 |
| 目录名 | `windows/`（按平台命名） | 与 `app/`（Android）并列，语义清楚 |
| UI 框架 | **UWP + WinUI 2**（`Microsoft.UI.Xaml` 2.8.x） | arc-deck 已验证：平台免费提供文本、IME、滚动、虚拟化、无障碍，框架搭起来就成型 |
| WinUI 3 / Windows App SDK | **短期内不考虑。不要引入，不要主动提议迁移** | 项目负责人的决定 |
| 运行时 | .NET Native（`UseDotNetNativeToolchain`），C# `LangVersion` 10 | arc-deck 已在本机验证。「UWP on 现代 .NET」只在 M0 花半天评估，结论记入本文，不切换主线 |
| 视觉 | 控件从 Kanesumi-sec-a 迁移为 **Kanesumi.Xaml**，不用 WinUI 默认的 Fluent 外观；Windows 允许在 Kanesumi 语言之上做**新设计** | 与 Android 在语言 / 控件层同源，但**不逐像素对齐**；桌面端**以美观为先**，可另设布局与视觉 |
| 底色 | 深色 `#000000`，与 Ncrust Android 一致 | 同一个产品两端一致；不跟 Ether 桌面扇区的 `#1A1A1A` |
| 代码共享 | 不共享实现，共享 `spec/` 夹具 | 见 `spec/README.md` |
| 业务核心 | `Ncrust.Core` 用 netstandard2.0，**不依赖 WinRT，也不依赖 UI** | 测试可以用普通 `dotnet test` 秒级跑完；以后换运行时也能原样复用 |
| 播放 | `MediaPlayer` + `MediaPlaybackList` + `MediaBinder` | 系统提供无缝播放、SMTC、后台音频；延迟绑定正好解决 URL 过期问题 |
| 动画 | `Windows.UI.Composition`，单一 progress 标量驱动 | 对应 Android 的「单一 progress + `graphicsLayer`」，在合成线程执行 |
| DI / MVVM 框架 | 不用。与 Android 一样用单例充当服务定位器；`INotifyPropertyChanged` 手写；只用 `x:Bind` | 依赖越少，.NET Native 的反射问题越少；`x:Bind` 是编译期绑定 |

## 构建、测试与运行

环境：VS 2022 Build Tools（已验证 MSBuild 17.14）+ UWP 工作负载（Windows SDK 10.0.22621）、
.NET 9 SDK。

UWP 项目**只能用 MSBuild**（不支持 `dotnet build`），并且**用 Release 配置**：
Debug 版的 .NET Native 依赖一个默认不预装的调试运行时。解决方案里也只有 `Release|x64` 一种配置。

```powershell
$msbuild = "C:\Program Files (x86)\Microsoft Visual Studio\2022\BuildTools\MSBuild\Current\Bin\MSBuild.exe"

& $msbuild windows\Ncrust.Windows.sln /t:Restore /p:Configuration=Release /p:Platform=x64
& $msbuild windows\Ncrust.Windows.sln /p:Configuration=Release /p:Platform=x64
# 产物：windows\src\Ncrust.App\AppPackages\Ncrust.App_<版本>_x64_Test\Ncrust.App_<版本>_x64.msix

# Core 单元测试（net9.0，不需要部署 UWP；同时检查 spec/design/tokens.json）
dotnet test windows\tests\Ncrust.Core.Tests
```

本地运行（需要开启 Windows「开发者模式」）：注册 .NET Native 编译后的布局，再启动。

```powershell
Add-AppxPackage -Register windows\src\Ncrust.App\bin\Release\ilc\AppxManifest.xml
explorer.exe "shell:AppsFolder\TakahashiRinta.Ncrust_98kk3q0vty278!App"

# 未处理异常写在这里：
Get-Content "$env:LOCALAPPDATA\Packages\TakahashiRinta.Ncrust_98kk3q0vty278\LocalState\crash.log"

# 卸载（注册指向构建输出目录，清理 bin 之前先卸载）：
Get-AppxPackage TakahashiRinta.Ncrust | Remove-AppxPackage
```

覆盖安装前先提高 `Package.appxmanifest` 的版本号；卸载重装会清掉 LocalSettings。

界面验证工具：M0 时从 arc-deck `uwp/tools/shot/` 移植到 `windows/tools/shot/`
（`ShotWindow`、`Uia`、`Verify`、`Contrast`、`ResourceAudit` 等）。移植时注意「已知的坑」里
关于截图脚本的两条。

## 目录结构

```
windows/
├── AGENTS.md
├── Ncrust.Windows.sln          # 手写维护（dotnet sln 无法添加 UWP 项目），仅 Release|x64
├── docs/
│   └── KANESUMI_XAML.md
├── src/
│   ├── Ncrust.Core/            # netstandard2.0 —— 协议与业务，不依赖 WinRT 和 UI
│   ├── Kanesumi.Xaml/          # UWP 类库 —— token、样式、自定义控件；不依赖 Ncrust
│   └── Ncrust.App/             # UWP 应用 —— 页面、播放引擎、平台集成
├── tests/
│   └── Ncrust.Core.Tests/      # net9.0 + xUnit，直接读取 ../../spec/
└── tools/                      # （尚未建立）
    └── shot/                   # 截图、UIA、对比度、资源审计脚本
```

目前已有的文件：

| 项目 | 现有内容 |
|---|---|
| `Ncrust.Core` | `Platform/` 下的五个平台接口：`ISettingsStore`、`IFileStore`、`ICredentialStore`、`ICodecProbe`、`INetworkInfo` |
| `Kanesumi.Xaml` | `Themes/Kanesumi.xaml`（合并入口）、`Colors.xaml`、`Typography.xaml`、`Overrides.xaml`（圆角归零）、`Generic.xaml`（空，留给自定义控件） |
| `Ncrust.App` | `App`（资源装配、崩溃日志）、`Shell/ShellPage`（占位）、`Package.appxmanifest`、`Properties/Default.rd.xml`、`Assets/`（按 Android `ic_launcher.xml` 的几何等比绘制的图标） |
| `Ncrust.Core.Tests` | `DesignTokensTests`：tokens.json 自洽性检查，以及 UWP 缓动贝塞尔写法精确性的逐点验证 |

应用引用 Kanesumi 资源的方式：`App.xaml` 先合并 `XamlControlsResources`，再合并
`ms-appx:///Kanesumi.Xaml/Themes/Kanesumi.xaml`。顺序不能反，否则圆角归零等覆盖不生效。

依赖方向（不得反向）：

```
Ncrust.App ──► Kanesumi.Xaml
    │
    └────────► Ncrust.Core ◄── Ncrust.Core.Tests ──► spec/fixtures
```

### Ncrust.Core

| 模块 | 内容 | Android 对应 |
|---|---|---|
| `Net/` | `NcmHttp`：`EapiPost` / `EapiPostOfficial` / `WeapiPost` / `Get` / `PostWeblog`；Cookie 注入；PC UA 与 Referer | `RetrofitClient` |
| `Net/Crypto/` | `EapiCrypto`（AES-128-ECB + MD5 签名，响应解密）、`WeapiCrypto`（双层 AES-CBC + 不加填充的 RSA，用 `BigInteger.ModPow`） | `network/crypto/` |
| `Json/` | `JsonValue`：只读 JSON DOM，零反射、零外部依赖，供所有响应解析 | —— |
| `Api/` | REST 端点与 eapi 端点、响应模型 | `NcmApi`、`PlaylistApi`、`CoverUrls` |
| `Auth/` | 解析 cookie（`MUSIC_U`、`__csrf`、deviceId、osver）、二维码登录轮询状态机 | `CookieManager`、`QrLoginDialog` 的逻辑部分 |
| `Playback/` | `PlaybackQueue`（纯状态机：5 种模式、全部队列操作、索引不变量）、`QualityLadder`、`SongUrlResolver`、`PlayReportPolicy` | `MainScreen` 里的队列函数、`SongUrlFetcher`、`PlayerViewModel.handlePlaybackError`、`PlayReporter` |
| `Library/` | 喜欢的歌曲、收藏专辑的云同步（云端读取失败时不覆盖本地） | `LibraryManager` |
| `Lyrics/` | `LrcParser`、双语歌词合并、`LyricsCache`（200 条） | `lyric/` |
| `Cache/` | `ContentCache`：首页快照（15s 新鲜期）+ 专辑 / 歌单 / 歌手的 LRU-32 | `cache/` |
| `Search/` | 搜索历史（每类最多 10 条，14 天过期） | `SearchHistoryManager` |
| `Platform/` | 接口：`ISettingsStore`、`IFileStore`、`ICredentialStore`、`ICodecProbe`、`INetworkInfo`、`IBrowserCookieSource` | —— |

规则：Core 里的 `await` 一律 `ConfigureAwait(false)`，不假设有 UI 线程；由 App 负责切回 Dispatcher。

### Ncrust.App

| 目录 | 内容 |
|---|---|
| `Shell/` | `ShellPage`（侧栏 / 底部导航 + 内容 Frame + 播放栏 + 播放器层）、自定义标题栏、全局快捷键 |
| `Pages/` | Home、Search、Library、User（账户与设置）、Album、Artist、Playlist、About |
| `Player/` | `PlayerHost`（Composition 驱动的展开层）、`PlayerControls`、`SeekBar`、`QueueView`、`LyricsView`（封装 `MetroLyricsPanel`） |
| `Playback/` | `PlaybackEngine`：把 `PlaybackQueue` 的决定落到 `MediaPlaybackList` 上；`MediaBinder` 的绑定处理 |
| `Login/` | `QrLoginDialog`、`BrowserLogin`（唤起默认浏览器 + 从浏览器 Cookie 库导入 `MUSIC_U`） |
| `Platform/` | Core 平台接口的实现 |
| `I18n/` | `Strings` 类 + 各语言实例 |

## Android → Windows 映射

| Android | Windows | 备注 |
|---|---|---|
| `PlaybackService`（ExoPlayer + MediaLibraryService） | `PlaybackEngine`（`MediaPlayer` + `MediaPlaybackList`） | 单进程后台播放，manifest 声明 `backgroundMediaPlayback` |
| MediaSession + 通知 + 锁屏 | SMTC（由 `MediaPlayer` 自动接管） | 媒体键、系统音量浮窗都不用自己写 |
| Android Auto 浏览树 | 不移植 | Windows 上没有对应物 |
| `SongUrlFetcher` + 5 分钟预载缓存 | `SongUrlResolver` + `MediaBinder.Binding` | 即将播放时才取 URL；失败层级的记录照样保留 |
| `handlePlaybackError` / `onAudioSinkError` | `MediaPlaybackList.ItemFailed` → `QualityLadder` | 见「播放架构」 |
| FLAC 解码器门控 | `ICodecProbe` | 最低支持的 17763 已内置 FLAC 解码，探测恒为 true；保留接口，让 Core 逻辑与 spec 一致 |
| Wi-Fi / 移动网络两档音质 | 按网络是否计费区分（`ConnectionProfile.GetConnectionCost()`） | 计费网络使用「移动」档；默认值同 Android：3（无损）/ 1（较好） |
| `PlaybackStateManager` | `LocalFolder/playback_state.json` | |
| `CookieManager`（明文 SharedPreferences） | `ICredentialStore` → `PasswordVault` | M0 验证能否存下完整 cookie；存不下就改用 `DataProtectionProvider` 加密后写文件 |
| `ncrust_settings` | `LocalSettings` | 键名尽量与 Android 相同 |
| `ncrust_library`、歌词缓存、搜索历史 | `LocalFolder/*.json` | `LocalSettings` 单个值有大小上限，大块数据不能放进去 |
| WebView 登录 | 唤起默认浏览器登录 + 从浏览器 Cookie 库导入 `MUSIC_U`（`IBrowserCookieSource`） | **不引入 WebView2**。Chrome / Edge 的 Cookies 库需 DPAPI 解密，新版 Chrome 的 App-Bound 加密在 AppContainer 里的可行性与是否要 `broadFileSystemAccess` 由 M0 实测；读不到就退回二维码登录 |
| 二维码登录（宽屏默认） | 二维码登录（**桌面默认**），用 ZXing.Net 渲染 | 同一套 weapi 轮询：每 2s 一次，最多 150 次；800 过期 / 802 已扫码 / 803 成功 |
| `QrPair`：手机扫码，把 cookie 交给平板 | M3：Windows 作为被扫端（`QrPairServer`） | 需要 `privateNetworkClientServer` 能力；UDP 广播在 AppContainer 里的表现必须实测 |
| `QrScannerScreen` / `QrAuthorizeScreen` | 不移植 | Windows 不做扫码端 |
| 电池白名单、屏幕方向策略 | 不移植 | —— |
| 剪贴板链接检测 | M3：`ncrust://` 协议激活；搜索框粘贴 NetEase 链接时识别 | 桌面上自动读剪贴板太打扰 |
| `SongDetailScreen` | 不移植 | Android 上本来就进不去 |
| `SongMenuSheet` | 右键 / 长按 `MenuFlyout` | 菜单项与 Android 一致 |
| `PlayAllDialog` | `MetroContentDialogStyle` | |
| `LocalStrings` + `Strings.kt` | `Strings` 类，属性与 `Strings.kt` 一一对应 | 带参数的文案用 `Func<int, string>` |

## 播放架构

**分工**：`Ncrust.Core.Playback.PlaybackQueue` 是纯状态机，拥有队列、当前索引、5 种模式
（`CYCLE`、`SINGLE`、`SHUFFLE`、`LINE`、`INFINITY`）和打乱顺序表，负责决定「下一首是谁」。
`Ncrust.App.Playback.PlaybackEngine` 只负责把决定落到系统播放器上。队列逻辑不写在页面里
（Android 的队列函数散在 `MainScreen` 里，这次收拢成可测试的单元）。

**关键不变量**（与 Android 相同，由 `spec/fixtures/queue` 覆盖）：`Queue[CurrentIndex]`
永远等于正在播放的歌。去重时先记下当前歌的 id，过滤后再重新定位索引。
每次修改队列都要持久化；`SHUFFLE` 模式下任何修改都要重新生成打乱顺序表。

**滑动窗口**：`MediaPlaybackList` 里只放「当前曲 + 下一首」两项，**不使用**它自带的
`ShuffleEnabled` / `AutoRepeatEnabled`。触发 `CurrentItemChanged` 时通知状态机前进一步，
状态机算出新的下一首，追加进列表，并移除已播完的项。这样播放模式、INFINITY 续播、
队列编辑都只由 Core 决定，系统播放器只负责无缝衔接。

- `SINGLE`：下一首是同一首歌的新条目。
- `LINE`：播到末尾时不追加。
- `INFINITY`：接近末尾时由 Core 触发续播（私人 FM，或相似歌曲，失败再退到每日推荐），
  去重，并防止重复发起请求。

**延迟取 URL**：每个条目都通过 `MediaSource.CreateFromMediaBinder` 创建，元数据（歌名、歌手、
封面）在创建时就用 `MediaItemDisplayProperties` 挂到条目上，URL 则在 `Binding` 事件里、
拿着 deferral 调用 `SongUrlResolver`。预取窗口对齐 Android 的 60s（`MaxPrefetchTime`，M0 验证）。
元数据跟着条目走，所以 Android `5247497` 修过的元数据串歌问题在结构上不会出现。

**音质降级**：`ItemFailed` 时交给 `QualityLadder`：沿阶梯降一级，按 `songId@level` 在 3s 内去重，
然后在原位置重建条目；已经失败过的高音质 URL 不会再用。`standard` 也失败就跳到下一首。
阶梯与降级序列见 `spec/fixtures/quality`。**永远不要回退到 `.../song/media/outer/url?id=X.mp3`**
（它会 302 到一个 404 的 HTML 页面，然后一直缓冲）。
`dolby` / `jyeffect` 在 Windows 上能否解码要在 M2 实测；解码不了就由自动降级兜底。

**播放上报**：自然播完或进度 ≥ 80% 时，由 Core 的 `PlayReportPolicy` 触发 webLog，
同一首歌只报一次；`wifi` 字段取「网络是否计费」的反值。不加密，发出后不管结果，不阻塞播放。

**进度**：`PlaybackEngine` 把 `PlaybackSession.PositionChanged` 节流到 2Hz 再发给状态层；
`SeekBar` 在自己内部用 Composition 线性动画在两次 tick 之间插值。页面层不绑定高频位置。

## 动画架构

规则（对应 Android 的「GPU 零重组」）：

1. **播放器层由一个 `CompositionPropertySet`（`PlayerProps`）驱动**，里面放 `Progress`
   （0 = 迷你栏，1 = 展开卡片）、`Fullscreen`（0 = 卡片，1 = 真全屏）、`LyricAnim`、`QueueSlide`，
   与 Android 的 `lyricAnimProgress`、`queueSlideProgress` 一一对应。卡片位移、封面从播放栏缩略图
   变到大图再铺满窗口、各层透明度，全部是引用这个 PropertySet 的 `ExpressionAnimation`。
   **不用 Storyboard，不用数据绑定，不逐帧改 XAML 属性。** 封面是全应用唯一的那个元素（见下节）。
2. 展开：400ms `standard`；收起：260ms `fastOutSlowIn`。都用 `ScalarKeyFrameAnimation`
   作用在 `Progress` 上。没有弹簧，没有回弹。
3. **触屏拖拽**：`ManipulationDelta` 直接在 UI 线程写 `Progress`（等同 Android 拖拽时的
   `snapTo`）；松手时位移超过 25% 就切换状态，否则弹回原状态，结尾动画同第 2 条。
   **鼠标不拖拽**，只用点击。只有当 UI 线程拖拽实测卡顿时，才换成 `InteractionTracker`。
4. **重子树门控**：`Progress < 0.05` 时，歌词、队列、完整控件通过 `x:Load` 卸载；
   **开始展开的那一刻就加载**，避免展开第一帧卡顿。
5. 其他控件的动画规则见 `KANESUMI_XAML.md`：只用独立动画（`Opacity` / `RenderTransform`）
   或 Composition，禁止依赖动画。
6. 页面内容加载沿用 Android 模式：先读 `ContentCache`，有缓存就直接渲染；无论有没有缓存，
   都在后台刷新，成功后写回；加载态与内容之间用 400ms `standard` 交叉淡化，
   不在加载时直接替换成全屏加载器。

## UI 与交互

断点与 Android 相同：**窗口宽度 600**。窄窗口退化成 Android 手机布局，宽窗口对应 Android
宽屏布局，再加上桌面习惯。

### 窗口

- 内容延伸进标题栏（`ExtendViewIntoTitleBar`），标题栏高 32，用 `Window.SetTitleBar`
  指定拖动区域；系统标题栏按钮背景透明，前景色随主题。
- 最小尺寸 360 × 500（`SetPreferredMinSize` 上限是 500 × 500）。
- 页面背景**显式设为** token 的 `background`，不依赖系统材质（arc-deck 实测：窗口背景会被混合成中灰）。

### 布局

**宽窗口（≥ 600）**：

```
┌──────────────────────────────────────────────────────────────┐
│ Ncrust                                  标题栏 32，纯黑，可拖动 │
├──────────┬───────────────────────────────────────────────────┤
│ [搜索  ] │  首页                                     页头 34px │
│          │                                                    │
│▌首页     │  每日推荐 ▸   ■■■■■■   网格间距 2                   │
│ 搜索     │  推荐歌单 ▸   ■■■■■■                                │
│ 音乐库   │  新歌 ▸       ──────────                            │
│ 我的     │                                                    │
│ 侧栏 200 │                                                    │
├──────────┴───────────────────────────────────────────────────┤
│■■■■■■│ 歌名        ⏮  ⏯  ⏭      0:42 ━━━━○───── 3:51  🔊 无损 ≡ 词│
│■■■■■■│ 歌手                                        播放栏高 72    │
└──────────────────────────────────────────────────────────────┘
```

- 侧栏：`MetroSidebar`，宽 200，顶部放常驻搜索框。
- 播放栏：高 72。**封面 72×72 贴左边**（与列表行的无边框贴边风格一致）；歌名用 `body`，
  歌手用 `caption`；中间是上一首 / 播放 / 下一首加可拖动的进度条；右侧是音量（点开浮层）、
  音质标签、队列开关、歌词开关。
- 内容底部留白 `BottomOverlayInset = 72 + 8 = 80`。

**窄窗口（< 600）**：`MetroBottomNav`（56）+ 迷你播放栏（56，顶边一条细进度条，
对应 Android 的 `SlimProgressBar`），内容宽度最多 360、居中；
`BottomOverlayInset = 56 + 56 + 8 = 120`。

`BottomOverlayInset` 按实际叠层高度算，**不照抄 Android 的 144**（那里的 80 是 M3 时代导航栏的高度）。
所有可滚动内容都用它作为底部内边距，**不在列表末尾手动加 Spacer**。

### 播放器层：迷你栏 → 展开卡片 → 真全屏

播放器是**一个常驻覆盖层**（`PlayerHost`），始终在内容与侧栏之上，由一个
`CompositionPropertySet`（`PlayerProps`）驱动。两个标量：`Progress`（0 迷你栏 → 1 展开卡片）
与 `Fullscreen`（0 展开卡片 → 1 真全屏）。参考 Groove Music：传输栏的封面与「正在播放」的大封面
是**同一个元素的形变**，不是两张图。

**封面唯一性**：全应用只有**一个**封面元素（`PlayerHost` 的子节点），迷你栏与卡片 / 全屏都用它，
尺寸、位置、缩放全部由引用 `Progress`、`Fullscreen` 的 `ExpressionAnimation` 算出。
**不要**在迷你栏放一张、播放器里再放一张——那会破坏形变连续性、重复解码，并让换歌时闪烁。
换歌时旧封面保留 400ms（`COVER_HOLD_MS`），形变与落定期间不露空。

三个状态：

1. **迷你栏**（`Progress=0, Fullscreen=0`）：底栏。宽窗口高 72、封面 72 贴左；窄窗口高 56、顶边一条
   细进度条（对应 Android 的 `SlimProgressBar`）。
2. **展开卡片**（`Progress=1, Fullscreen=0`）：覆盖标题栏以下的整个窗口（含侧栏），左上角收起按钮。
   宽窗口双栏：左封面 + 控件，右歌词；歌词与队列用 `QueueSlide` 切换（对应 `wideSplit`）。
   窄窗口沿用 Android 手机布局。封面完整显示、不裁切。
   进入：点封面或歌名、点「词」（展开并定位歌词）、`Ctrl+L`、触屏上拉。
   退出：`Esc` / 收起按钮 / 触屏下拉。
3. **真全屏**（`Progress=1, Fullscreen=1`）：封面铺满整个窗口、无边距，控件与返回箭头闲置后自动隐藏、
   指针移动再出现。**只能由卡片里的专用全屏按钮（⤢）或 `F11` 进入** —— 它与「拖上来的卡片」是两件事，
   不共用触发方式。退出：按钮 / 双击封面 / `Esc`。
   `Esc` 的层级：全屏 → 回卡片；卡片 → 收起迷你栏。

（可选）真 OS 全屏（隐藏任务栏）用 `ApplicationView.TryEnterFullScreenMode`；默认只做应用内全屏，
是否进 OS 全屏在实现时再定。

### 列表与操作

| 操作 | 方式 |
|---|---|
| 播放一首歌 | 双击或 Enter；鼠标悬停时行内出现 ▶，单击它播放。「替换队列并播放」还是「插入播放」，以 Android 对应页面的现行行为为准 |
| 歌曲菜单 | 右键、触屏长按、Shift+F10 或菜单键 → `MenuFlyout`（与 `SongMenuSheet` 项目一致） |
| 多选 | Ctrl / Shift 多选，批量「下一首播放 / 加入队列」（M2） |
| 返回 | 详情页左上角悬浮箭头；Alt+←、鼠标侧键、焦点不在输入框时的 Backspace |
| 搜索 | 侧栏搜索框（窄窗口时在搜索页里），Ctrl+F 聚焦；结果页用 `MetroTabRow` 分歌曲 / 专辑 / 歌手三类；**500ms 防抖不能去掉**；搜索历史作为输入建议显示 |

### 快捷键

| 键 | 作用 | 条件 |
|---|---|---|
| Space | 播放 / 暂停 | 焦点不在文本输入框 |
| Ctrl+← / Ctrl+→ | 上一首 / 下一首 | |
| Ctrl+F | 聚焦搜索框 | |
| Ctrl+L | 展开播放器并显示歌词 | |
| F11 | 展开卡片并在卡片 / 真全屏之间切换 | |
| Esc | 全屏 → 回卡片；卡片 → 收起；否则关闭浮层 | 逐层退出 |
| 媒体键 | 播放控制 | 由 SMTC 自动处理 |

### 登录

默认显示**二维码登录**对话框（用手机上的网易云 App 扫码）；对话框里提供「通用登录」入口，
用 `Launcher.LaunchUriAsync` **唤起默认浏览器**打开 `https://music.163.com/`。用户在浏览器
登录后回到应用点「已登录，导入」，应用经 `IBrowserCookieSource` 从浏览器 Cookie 库读出
`MUSIC_U` 及同域字段，存进 `ICredentialStore` 并刷新云端音乐库，与 Android 相同。
**不引入 WebView2**；浏览器 Cookie 库读不出时，二维码登录仍是可用路径。

## 国际化

- `Strings` 类的属性与 Android `Strings.kt` 一一对应（camelCase 改为 PascalCase），
  这样 8 种语言的译文可以直接搬过来。
- 切换语言在运行时生效：替换当前的 `Strings` 实例，然后各页面调用 `Bindings.Update()`。
- M1 只做 zh-CN，M3 补齐 8 种语言。**界面文字一律不许写死。**

## 里程碑

### M0 · 验证原型

目的是先证明风险最高的五件事能做通；任何一件做不通，都要回到本文重新评估。

| # | 验证项 | 通过标准 |
|---|---|---|
| 1 | Composition 播放器卡片 | Release 构建下，展开和收起流畅无掉帧；触屏拖拽跟手；收起后歌词、队列从可视树里卸载 |
| 2 | 无缝播放 | 用真实 NetEase URL 连续播两首，中间没有空隙；第二首的 URL 是在 `Binding` 事件里才取的；SMTC 显示的元数据正确 |
| 3 | eapi 请求 | 在 UWP 进程里带 cookie 取到每日推荐；确认 `HttpClient` 不会自己附加或吞掉 cookie（需要设 `UseCookies = false`） |
| 4 | 浏览器 Cookie 导入 | 从 Chrome / Edge 的 Cookie 库读到 `MUSIC_U`；`PasswordVault` 能存下完整 cookie |
| 5 | .NET Native Release 构建 | 响应模型的 JSON 解析正常。方案已定为 **Core 自研的只读 `JsonValue`**（零反射、零外部依赖），M0 只需在 Release + .NET Native 下确认解析正常 |
| — | 附带评估（半天） | 「UWP on 现代 .NET」能否带 WinUI 2.8 跑起来。只记录结论，不切换 |

M0 的配套工作：解决方案骨架（✅ 已完成）、Kanesumi.Xaml 的 `Colors` / `Typography` /
`Overrides` 三个资源文件（✅ 已完成）、从 arc-deck 移植 `tools/shot`（未做）。

### M1 · MVP（能日常使用）

登录（二维码 + 浏览器 Cookie 导入）、首页（每日推荐 / 推荐歌单 / 新歌）、歌单详情、搜索（三类）、
播放栏与全屏播放器、队列与 5 种播放模式、歌词、音质降级、播放上报。
Kanesumi.Xaml 的 M1 控件（见 `KANESUMI_XAML.md`）。
`spec/fixtures` 第一批：crypto、quality、queue、lrc，并有 Core 测试覆盖。

### M2 · 与 Android 主干功能对齐

音乐库云同步、专辑与歌手页、私人 FM 与 INFINITY、音质设置、主题（6 色 × 3 模式）、
多选、窄窗口布局（`MetroBottomNav`）、Kanesumi.Xaml 的 M2 控件、dolby / jyeffect 解码实测。

### M3 · 收尾

8 种语言、`QrPair` 被扫端、`ncrust://` 协议、MSIX 发布流程、Android 端测试接入 `spec/fixtures`。

## 版本与发布

- 版本号的唯一来源是 `Package.appxmanifest` 里的 `Identity Version`（`X.Y.Z.0`）。
  About 页读取 `Package.Current.Id.Version`，**不要写死版本常量**。
- Tag 用 `win-vX.Y.Z`；Android 保持现有的 `vX.Y.Z`。
- 发布流程（M3 定稿）：升版本号 → commit `build(windows): 升级至 vX.Y.Z` → Release 打包
  → `gh release create win-vX.Y.Z --draft <msix>` → 负责人冒烟测试后手动发布。

## 提交规范

沿用根目录 `AGENTS.md` 的约定（Conventional Commits，小写类型，中文主题，
一个逻辑单元一个 commit，主动提交）。monorepo 下补充 scope：

| 范围 | 写法 |
|---|---|
| `windows/` 下的改动 | `feat(windows): …`、`fix(windows): …` |
| `spec/` 下的改动 | `docs(spec): …`（夹具也算规格，不另设类型） |
| Android | 保持不带 scope（与现有历史一致） |

同时改了 spec 和一端实现时，scope 写那一端，并在 body 里说明另一端的跟进状态。

## 已知的坑（大多来自 arc-deck 的实测）

- **UWP 项目不能用 `dotnet build`**，必须用 MSBuild；日常开发也用 **Release** 构建。
- **`UseDotNetNativeToolchain` 必须为 true**：否则产物依赖 `Microsoft.NET.CoreRuntime.2.x`
  框架包，而较新的 Windows 默认不装，部署会失败。
- **C# 版本默认是 7.3**：不加 `<LangVersion>10.0</LangVersion>`，写 `is not` 会报 CS8370，
  而且报错位置离真正原因很远。
- **`{ThemeResource}` 引用不存在的键不会报错**，只会静默失效。WinUI 3 的资源键在 WinUI 2
  里不存在。新增资源引用后跑 `ResourceAudit.ps1`。
- **`ControlCornerRadius` / `OverlayCornerRadius` 是框架内建的默认值**，SDK 和 WinUI 2 的资源文件里
  都查不到定义，只能在应用资源层覆盖。
- **不要依赖半透明的系统文字色**：窗口背景可能被材质混合成 `#808080`，这时平台文字色的
  对比度只有 1.00。页面背景要显式设置，文字色用不透明的 token。
- **.NET Native 对反射敏感**：绑定只用 `x:Bind`；JSON **读取**用 `Ncrust.Core.Json.JsonValue`（自研只读 DOM，零反射），**写入**用 `Net.JsonText`。不引入 System.Text.Json / Newtonsoft。
- **`LocalSettings` 单个值有大小上限**：队列、音乐库这类数据放进 `LocalFolder` 的文件里。
- **`rd.xml` 要作为 `Content` 加入项目**：写成 `EmbeddedResource` 会报 ILT0027
  「嵌入清单中不允许的应用程序指令」；完全不加也会报 ILT0027「缺少运行时指令文件」。
- **`dotnet sln add` 不能添加 UWP 项目**：它会到 .NET SDK 目录下找 WindowsXaml 的 targets，找不到就报错。
  解决方案文件手写维护，新增项目时照现有条目补上 `Release|x64` 的映射。
- **截图脚本按标题找窗口时要完全匹配**：arc-deck 的 `ShotWindow.ps1` 用的是「标题包含」，
  而终端窗口的标题里也可能带 "Ncrust"，会截错窗口。要求标题等于 "Ncrust"，
  并且窗口类是 `ApplicationFrameWindow`。
- **UWP 窗口在还原状态下可能截到一整块空白**：实测第一次截图是纯 `#222222`，
  加 `-Max` 最大化后才截到内容。截图前先最大化，或者把窗口激活到前台。
- **应用能力声明**：`internetClient`、`backgroundMediaPlayback`；M3 做 `QrPair` 时再加
  `privateNetworkClientServer`。
