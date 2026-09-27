# AGENTS.md —— Ncrust for Windows

本文件是 `windows/` 目录下的权威指南，面向在此工作的编码 agent（Claude Code、Codex 等）和人。
仓库级约定（提交规范、「一个逻辑单元一个 commit」等）见根目录 `AGENTS.md`；根文件里
Android 专属的章节不适用于这里。

**状态**（2026-09-27）：**M1 进行中，已可日常点播。** 包版本 0.1.2.0。

- `Ncrust.Core`：协议、加密、全部端点（含相似歌曲）、队列状态机、INFINITY 续播取数（`InfinityFeeder`）、
  音质阶梯、缓存与持久化、云收藏库、10 段均衡器（DSP + 预设），Core 测试 230 个通过。
- `Ncrust.App`：标准汉堡菜单外壳（`NavigationView`）+ 自定义标题栏、独立登录层（WebView2 + 二维码）、
  `PlaybackEngine`（滑动窗口 / MediaBinder / 降级 / 上报 / 均衡器 / INFINITY 与私人 FM 续播）、
  Groove 式传输栏与 Composition 播放器层（卡片右栏：歌词 / 播放队列）、首页（含私人 FM 卡）、搜索页、
  专辑 / 歌手 / 歌单详情页、音乐库页、设置页与 10 段均衡器子页面；歌曲与专辑 / 歌单磁贴的右键菜单。
- `Ncrust.Audio`：均衡器音效组件（`IBasicAudioEffect`，挂在 `MediaPlayer` 上）。
- `Kanesumi.Xaml`：token、缓动、按钮 / 列表 / 进度环样式、NavigationView 资源覆盖、`KanesumiAccent`（强调色跟随系统）、
  `MetroLyricsPanel`。

与 Android 主干功能已基本对齐（见「Android → Windows 映射」）；验收记录见 `docs/PLAN.md`。
下一步：搜索历史入口、窄窗口的歌词 / 队列、多选批量操作、8 种语言。

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
| 平台控件优先 | **能用 WinUI / 平台原生控件的就用原生**（NavigationView、Pivot、Slider、ContentDialog…），只做资源键级的 Kanesumi 覆盖；不再自绘替代品 | 负责人实测自绘 Tab 行体验差（2026-09-26）。原生控件自带键盘、UIA、触屏手势与系统一致的交互 |
| 强调色 | **跟随 Windows 系统强调色**（设置 → 个性化 → 颜色，Windows 10 / 11 都有）；读不到时回落内置云杉 `#1DB954`。平台控件直接用 SystemAccentColor，Kanesumi 的 `KPrimaryBrush` 由 `KanesumiAccent.FollowSystem` 同步并实时跟随；`KOnPrimaryBrush` 按亮度选黑 / 白 | 负责人要求。应用图标的绿色是品牌色，不随强调色变 |
| 外壳导航 | **标准汉堡菜单**：WinUI 2 `NavigationView`（自适应展开 / 紧凑 / 最小），不用自绘 ListView 侧栏或窄窗底部导航 | 负责人要求标准汉堡菜单；参考 Groove Music。平台控件自带自适应、返回按钮、键盘与 UIA |
| WinUI 2 样式版本 | `XamlControlsResources ControlsResourcesVersion="Version1"` | Windows 10 / Groove 一代的直角样式；Version2 是 Windows 11 圆角 + 中灰圆角内容面板，与 Kanesumi 冲突 |
| 按钮 | **微软原生样式**：主操作 `AccentButtonStyle`，次要操作平台默认 `Button`，图标按钮 `AppBarButton`（`LabelPosition=Collapsed`，同 Groove 播放栏），播放卡片的播放键是强调色按钮。Kanesumi 的 `Metro*ButtonStyle` 不再使用 | 负责人（2026-09-27）：按钮不用 Ncrust Android 的设计，用微软的 |
| 磁贴间距 | 桌面横向 20 / 纵向 28、磁贴 176（`MetroGridTileStyle`），**有意偏离** tokens 的 `gridSpacing = 2` | 2 的拼贴缝在手机上是 Kanesumi 的拼贴感，桌面一整面墙显得太紧张（负责人反馈，2026-09-27） |
| 图标 | 界面图标用 **Segoe MDL2 Assets**（显式指定 `FontFamily`）；应用图标是整块绿底唱片纹 | Groove 同源的原生图标字体；不打包 Material Icons（原 KANESUMI_XAML 的设想已撤回） |
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

**改了 `Package.appxmanifest`（包括构建自动写入的音效类注册）就要提高版本号**，否则重新注册报
`0x80073CFB`；卸载重装可以绕过，但会清掉 LocalSettings（登录态、音量、均衡器设置都没了）。
注册后第一次启动偶尔没反应，再启动一次即可。

界面验证工具：M0 时从 arc-deck `uwp/tools/shot/` 移植到 `windows/tools/shot/`
（`ShotWindow`、`Uia`、`Verify`、`Contrast`、`ResourceAudit` 等）。移植时注意「已知的坑」里
关于截图脚本的两条。

## 目录结构

```
windows/
├── AGENTS.md
├── Ncrust.Windows.sln          # 手写维护（dotnet sln 无法添加 UWP 项目），仅 Release|x64
├── docs/
│   ├── KANESUMI_XAML.md
│   └── PLAN.md                 # 施工单与设备验收记录
├── src/
│   ├── Ncrust.Core/            # netstandard2.0 —— 协议与业务，不依赖 WinRT 和 UI
│   ├── Kanesumi.Xaml/          # UWP 类库 —— token、样式、自定义控件；不依赖 Ncrust
│   ├── Ncrust.Audio/           # Windows 运行时组件 —— 音效（均衡器），只引用 Ncrust.Core
│   └── Ncrust.App/             # UWP 应用 —— 页面、播放引擎、平台集成
├── tests/
│   └── Ncrust.Core.Tests/      # net9.0 + xUnit，直接读取 ../../spec/
└── tools/
    ├── shot/Shot.ps1           # 按固定尺寸截 Ncrust 窗口，可先点击 / 按键（DPI aware）
    └── icons/MakeIcons.ps1     # 生成应用图标（整块绿底唱片纹，33 张 scale / targetsize 资源）
```

应用资源的合并顺序（`App.xaml`，顺序不能反）：

1. `XamlControlsResources ControlsResourcesVersion="Version1"`（WinUI 2，Windows 10 直角样式）；
2. `ms-appx:///Kanesumi.Xaml/Themes/Kanesumi.xaml`（token、样式、平台覆盖：圆角归零、NavigationView；不覆盖系统强调色）；
3. `Resources/Templates.xaml`（带 `x:Class`，共用列表项模板：歌曲行 / 专辑磁贴 / 歌单磁贴 / 歌手行 / 搜索建议）。

Kanesumi 必须排在 WinUI 之后，否则覆盖不生效；模板字典排最后，模板里的 `StaticResource` 在实例化时才解析。

依赖方向（不得反向）：

```
Ncrust.App ──► Kanesumi.Xaml
    │
    ├────────► Ncrust.Audio ──┐
    │                         ▼
    └────────────────► Ncrust.Core ◄── Ncrust.Core.Tests ──► spec/fixtures
```

`Ncrust.Audio` 必须是独立的 Windows 运行时组件（winmdobj）：`MediaPlayer.AddAudioEffect` 按
activatable class id 激活音效，类要在 winmd 里可见。构建会自动把类注册进应用清单
（`Ncrust.Audio.EqualizerEffect`，宿主 `Ncrust.dll`），不用手写 `Extensions`。

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
| `Audio/` | 10 段均衡器：`EqualizerBands`（31 Hz ~ 16 kHz，Q 1.41，±12 dB）、`BiquadCoefficients`（RBJ peaking）、`EqualizerProcessor`（原地处理、参数快照无锁切换）、内置 / 命名预设、`EqualizerStore`（设置键 `eq_*` + `eq_presets.json`） | —— Android 没有 |
| `Platform/` | 接口：`ISettingsStore`、`IFileStore`、`ICredentialStore`、`ICodecProbe`、`INetworkInfo` | —— |

规则：Core 里的 `await` 一律 `ConfigureAwait(false)`，不假设有 UI 线程；由 App 负责切回 Dispatcher。

### Ncrust.App

| 目录 | 内容 |
|---|---|
| `Shell/` | `ShellPage`（`NavigationView` + 内容 Frame + 播放栏 + 播放器层 + 登录层）、自定义标题栏、全局快捷键；`AppTheme`（明暗模式 + 标题栏按钮配色） |
| `Pages/` | Home、Search、Library、Album、Artist、Playlist（后三者共用 `DetailHeader`）、Settings（账户 / 音质 / 播放 / 音效 / 外观 / 缓存 / 关于，对应 Android 的「我的」页与 About）、Equalizer；`SongActions`（歌曲 / 磁贴右键菜单、收藏、转到歌手 / 专辑） |
| `Player/` | `PlayerHost`（Composition 驱动的展开层 + 传输栏）、`LyricsController`（取词、2Hz 之间的位置外推，喂 `MetroLyricsPanel`）、`QueuePresenter`（三分区队列视图） |
| `Playback/` | `PlaybackEngine`：把 `PlaybackQueue` 的决定落到 `MediaPlaybackList` 上；`MediaBinder` 的绑定处理 |
| `Login/` | `LoginWindow`（独立登录层）：内嵌 WebView2 登录 + 二维码（`QrLoginClient` + 本地渲染） |
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
| WebView 登录 | 独立登录窗口：内嵌 WebView2（主）+ 二维码（辅助） | Android 同款 WebView 路径；**浏览器 Cookie 导入已证伪**（App-Bound Encryption，见「已知的坑」） |
| 二维码登录（宽屏默认） | 二维码登录（**桌面默认**），用 ZXing.Net 渲染 | 同一套 weapi 轮询：每 2s 一次，最多 150 次；800 过期 / 802 已扫码 / 803 成功 |
| `QrPair`：手机扫码，把 cookie 交给平板 | M3：Windows 作为被扫端（`QrPairServer`） | 需要 `privateNetworkClientServer` 能力；UDP 广播在 AppContainer 里的表现必须实测 |
| `QrScannerScreen` / `QrAuthorizeScreen` | 不移植 | Windows 不做扫码端 |
| 电池白名单、屏幕方向策略 | 不移植 | —— |
| 剪贴板链接检测 | M3：`ncrust://` 协议激活；搜索框粘贴 NetEase 链接时识别 | 桌面上自动读剪贴板太打扰 |
| `SongDetailScreen` | 不移植 | Android 上本来就进不去 |
| `SongMenuSheet` | 右键 / 长按 / Shift+F10 `MenuFlyout`（`SongActions`） | 菜单项与 Android 一致；「分享」在桌面上是复制链接 |
| `PlayAllDialog` | 详情页页头的「全部播放 / 插播 / 最后播放」按钮；磁贴右键菜单 | 桌面上不弹对话框，少一次点击 |
| `startFm` / `launchInfinity` | `PlaybackHost.StartFmAsync` / 引擎队尾续播（`InfinityFeeder`） | 队尾提前续播，停在队尾时取回后接着播 |
| `ThemeManager`（6 色 × 3 模式） | 强调色跟随系统（`KanesumiAccent`）+ 3 种明暗模式（`AppTheme`） | 不做 6 色预设 |
| `UserScreen`（「我的」）+ `AboutScreen` | `SettingsPage` | 见「设置页与均衡器」 |
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
   （0 = 迷你栏，1 = 展开卡片）、`Fullscreen`（0 = 卡片，1 = 真全屏）。卡片上滑、迷你栏随动上移与淡出、
   封面从迷你栏缩略图变到卡片封面位再铺满窗口、各层透明度，全部是引用这个 PropertySet 的 `ExpressionAnimation`。
   （Android 的 `lyricAnimProgress` / `queueSlideProgress` 没有对应标量：卡片里没有大封面 ↔ 歌词的切换，
   歌词 / 队列用原生 `Pivot`。）
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

- 内容延伸进标题栏（`ExtendViewIntoTitleBar`），标题栏高度取 `CoreApplicationViewTitleBar.Height`
  （通常 32），`AppTitleBar`（应用图标 + Ncrust，失焦变淡）用 `Window.SetTitleBar` 作拖动区；
  左边距随 NavigationView 显示模式调整，给返回 / 汉堡按钮让位（最小模式两个按钮都在这一行）。
  系统标题栏按钮背景透明，前景白色。
- **标题栏区域的输入会被系统拿去拖动窗口**：播放器层、登录层等覆盖层要让出标题栏高度
  （`ShellPage.ApplyTitleBarHeight`），否则覆盖在这一条上的按钮点不到。
- 最小尺寸 360 × 500（`SetPreferredMinSize` 上限是 500 × 500）。
- 页面背景**显式设为** token 的 `background`，不依赖系统材质（arc-deck 实测：窗口背景会被混合成中灰）。

### 布局（参考 Groove Music / Apple Music）

```
┌──────────────────────────────────────────────────────────────┐
│ ←  ▣ Ncrust                              标题栏（拖动区）  ─ □ ✕ │
├──────────┬───────────────────────────────────────────────────┤
│ ≡        │  首页                                     页头 34px │
│ [搜索  ] │  ┌ 登录网易云音乐 ……………………………… [登录] ┐（未登录时）│
│▌首页     │  每日推荐                          [▷ 全部播放]     │
│ 我的音乐 │  ■ 歌名 / 歌手 ………………………………………… 3:47          │
│ 音乐库   │  推荐歌单   ■■■■■■（磁贴间距 2）                    │
│          │  新歌速递   ────────                                │
│ 👤 昵称  │                                                    │
├──────────┴───────────────────────────────────────────────────┤
│■■■■■■│ 歌名   │  ⟳  ⏮  ⏯  ⏭                 │ 🔊  词  ⌃        │
│■■■■■■│ 歌手   │  0:42 ━━━━━━○────────── 3:51 │                  │
└──────────────────────────────────────────────────────────────┘
```

- **导航**：WinUI 2 `NavigationView`（标准汉堡菜单），按窗口宽度自适应：
  ≥ 1008 展开（面板宽 240）、600 ~ 1008 紧凑（只剩图标）、< 600 最小（只剩汉堡按钮，点开浮出面板）。
  面板：顶部搜索框（Groove）→ 首页 → 分组标题「我的音乐」（Apple Music 的资料库分组）→ 音乐库；
  底部账户项：未登录显示「登录」并打开登录层，登录后显示昵称与头像、进入设置页；
  面板最下方是 NavigationView 自带的「设置」。
  返回按钮常驻，随 `Frame.CanGoBack` 启用；返回后菜单高亮与当前页同步，
  搜索结果 / 详情页等不对应菜单项的页面清掉高亮。
- **播放栏**：外壳第二行固定 72，页面内容区在它上方，**不会被播放栏遮挡**，
  因此不再需要 Android 那种 `BottomOverlayInset` 底部内边距（页面底部留 24 即可）。
  详见下节「传输栏」。
- **窄窗口（< 600）**：导航退化为汉堡按钮（不另做底部导航），传输栏退化为 Android 迷你栏形态。
- 首页：未登录时顶部是登录提示卡；任何分区为空整块隐藏；每日推荐带「全部播放」。

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

1. **传输栏**（`Progress=0, Fullscreen=0`）：底栏，高 72，封面 72 贴左。参考 Groove 的正在播放栏：
   - 曲目信息（点击展开卡片）；
   - 中间：模式按钮（列表循环 → 单曲循环 → 随机 → 顺序播完即停 → 相似无限）、
     上一首 / 播放 / 下一首，下方是可拖动进度条 + 已播 / 总时长。**拖动时只在松手后 seek**
     （避免反复跳转让流媒体卡顿），键盘方向键 / 单击轨道立即跳转，拖动中不被 2Hz 进度拉回；
   - 右侧：音量浮层（静音 + 0~100 滑块）、歌词、播放队列、展开。
   - 音量与播放模式存 LocalSettings（`volume` / `play_mode`）并在启动时恢复
     （Android 不持久化模式，桌面按 Groove 习惯记住）。
   - 窄窗口（< 600）：退化为 Android 迷你栏——顶边 2px 细进度条（对应 `SlimProgressBar`），
     只留播放 / 下一首与展开（`AdaptiveTrigger`）。
2. **展开卡片**（`Progress=1, Fullscreen=0`）：覆盖标题栏以下的整个窗口（含导航面板）。
   - **整张卡片从底部上滑**（`Translation.Y = (H - 72) × (1 - Progress)`），**迷你栏随卡片同步上移**、
     在前 40% 淡出 —— 同 Android 卡片整体上滑、迷你栏在卡片顶部淡出。不要做成迷你栏钉在底部、卡片原地淡入。
   - **卡片有自己的一套信息与控件**（对应 Android `FullPlayerControls`），不沿用迷你栏：大标题、
     歌手 / 专辑链接（点了收起并转到对应页）、进度条、模式 / 上一首 / 播放 / 下一首 / 收藏、内联音量。
     两套控件由同一组引擎回调同步。
   - **卡片里没有「只有封面」的状态，也没有进入它的按钮**（负责人要求，2026-09-27）。
   - **构图**（参考 Apple Music / Groove 的「正在播放」）：宽窗口左右两栏作为一个整体（`Stage`，宽度显式给定）
     在卡片里居中。左栏以封面宽度为一条竖轴 —— 封面、标题 / 歌手 / 专辑、进度条（时间在两端下方）、
     传输行（模式与收藏贴两端，上一首 / 播放 / 下一首居中）、音量，全部与封面左右对齐；右栏歌词 / 播放队列
     （原生 `Pivot`）与左栏等高、页签上沿对齐封面上沿。封面尽量大：受左右留白 56、栏间距 64 与
     「信息 + 控件」实测高度约束，240 ~ 560；右栏 280 ~ 560。歌词字号宽窗口 28 / 38（译文 17 / 24）。
     窄窗口上中下三段：顶部 64 小封面 + 信息、中间歌词 / 队列、底部控件（无音量行），歌词 22 / 30。
   - 封面位（`CoverSlot`）只占位，封面仍是那张唯一元素，形变终点取封面位的布局位置。
   进入：点封面或曲目信息、「词」/ 队列按钮（切到对应页）、展开按钮、`Ctrl+L`、触屏上拉。
   退出：`Esc` / 左上角收起按钮 / 触屏下拉。
3. **真全屏**（`Progress=1, Fullscreen=1`）：**系统全屏**（`TryEnterFullScreenMode`，隐藏任务栏与标题栏），
   封面按屏幕短边铺满、居中，卡片内容淡出；右上角只留退出按钮（闲置自动隐藏待做）。这是唯一的纯封面状态，
   **只能由 `F11` 进入**（卡片里不放全屏按钮）。退出：`Esc` / `F11` / 退出按钮 / 双击任意处；
   从系统层面退出全屏（Win+Shift+Enter 等）时跟着回卡片。全屏时外壳隐藏自绘标题，覆盖层不再让出标题栏高度。

**命中测试**：XAML 命中测试按布局位置、不认合成变换。所以迷你栏按「底部」布局（上移只是视觉）、
卡片按「铺满」布局（下移只是视觉），每个状态只让看得见的那一层接收点击：收起 → 迷你栏，
展开 → 卡片，全屏 → 全屏操作层。卡片在表达式接管前保持 `Collapsed`，否则首帧会整块盖住界面。
   `Esc` 的层级：全屏 → 回卡片；卡片 → 收起迷你栏。


### 列表与操作

| 操作 | 方式 |
|---|---|
| 播放一首歌 | 单击（当前实现）；双击 / 悬停 ▶ 待做。「替换队列并播放」还是「插入播放」，以 Android 对应页面的现行行为为准：首页、搜索、音乐库都是 `playSongItem`（`PlaybackHost.PlaySong`，插到当前曲之后播放，不替换队列）；「全部播放」按钮才替换队列（`PlaybackHost.PlayAll`） |
| 歌曲菜单 | 右键、触屏长按、Shift+F10 或菜单键 → `MenuFlyout`：播放、插播、最后播放、加入库 / 移除收藏、转到歌手、转到专辑、复制链接（与 `SongMenuSheet` 一致）；音乐库另有「重试同步」，队列里另有「从队列移除」 |
| 专辑 / 歌单磁贴菜单 | 右键 → 播放（替换队列）/ 插播 / 最后播放；音乐库专辑另有「取消收藏」 |
| 轻提示 | 插播、收藏、复制链接等操作后在播放栏上方显示约 2 秒（`AppShell.Notice`，对应 Android Toast） |
| 多选 | Ctrl / Shift 多选，批量「下一首播放 / 加入队列」（M2） |
| 返回 | 详情页左上角悬浮箭头；Alt+←、鼠标侧键、焦点不在输入框时的 Backspace |
| 搜索 | 导航面板顶部的搜索框（窄窗口先展开面板），Ctrl+F 聚焦。输入停顿 **500ms（防抖不能去掉）** 后下拉即时建议（前 8 首歌，选中即播放）；回车进入搜索页，用平台原生 `Pivot` 分歌曲 / 专辑 / 歌手三类（Groove「我的音乐」同款），三类并发请求。下拉项带 40 小封面。搜索框为空时下拉显示搜索记录：搜过的关键词（桌面新增，Android 只记条目）+ 从搜索里点过的歌曲 / 专辑 / 歌手，末尾「清除搜索记录」 |

### 快捷键

| 键 | 作用 | 条件 |
|---|---|---|
| Space | 播放 / 暂停 | 焦点不在文本输入框或按钮上 |
| Ctrl+P | 播放 / 暂停 | Groove Music 的快捷键 |
| Ctrl+← / Ctrl+→ | 上一首 / 下一首 | |
| Ctrl+F | 聚焦搜索框 | 最小 / 紧凑模式先展开面板 |
| Ctrl+L | 展开 / 收起播放器 | |
| F11 | 进入 / 退出真全屏（系统全屏、纯封面）；收起状态下先展开 | 唯一入口 |
| Esc | 关闭登录层；全屏 → 回卡片；卡片 → 收起 | 逐层退出 |
| Alt+← / 鼠标侧键 | 后退 | |
| 媒体键 | 播放控制 | 由 SMTC 自动处理 |

快捷键挂在 `ShellPage.KeyboardAccelerators` 上，`KeyboardAcceleratorPlacementMode = Hidden`（不弹按键提示）。

### 设置页与均衡器

设置页（`Pages/SettingsPage`）移植 Android `UserScreen` 的内容，按 Windows 设置应用的分组排版，
用平台原生控件（`ComboBox` / `ToggleSwitch` / `RadioButton` / `ContentDialog`）：

| 分组 | 内容 | 键 / 来源 |
|---|---|---|
| 账户 | 头像、昵称、UID；登出（确认）/ 登录 | `AppServices.SignOut` → `SessionChanged` |
| 音质 | 不计费网络 / 按流量计费的网络，各 7 档 | `wifi_quality` / `mobile_quality`（同 Android） |
| 播放 | 无缝播放、歌词翻译 | `gapless_playback` / `lyrics_translation` |
| 音效 | 均衡器入口，显示当前预设名（手动调过显示「自定义」） | → `EqualizerPage` |
| 外观 | 跟随系统 / 深色 / 浅色；强调色色块 + 打开 `ms-settings:colors` | `theme_mode`（`SYSTEM` / `DARK` / `LIGHT`，同 Android） |
| 存储与缓存 | 占用大小；清除（确认）：`LocalCache`、`TempState`、`AC\INetCache` 与歌词缓存 | 不动登录态与队列 |
| 关于 | 版本号（`Package.Current.Id.Version`）、GitHub | —— |

明暗模式设在根 `Frame.RequestedTheme` 上（`AppTheme`），切换即时生效，同时重设标题栏按钮配色。
强调色不提供应用内选择：Windows 已有全局强调色，应用跟随它（见「立项决策」）。

**均衡器**（`Pages/EqualizerPage`，桌面独有）：开关、预设下拉、前级 + 10 个竖向滑块（±12 dB，步进 0.5）。

- 内置预设 9 个（平直、流行、摇滚、爵士、古典、电子、人声、低音增强、高音增强），不可删；
  用户预设可命名保存（≤ 24 字，不能与内置同名，同名用户预设直接覆盖）与删除，存 `eq_presets.json`。
- 手动拖动滑块后，只要仍与所选预设一致就保留预设名，否则显示「自定义」；选预设会顺手打开均衡器。
- 改动即时生效：页面写 `EqualizerStore` 并调 `PlaybackEngine.ApplyEqualizer`，引擎把参数写进
  与音效共享的 `PropertySet`，`EqualizerEffect` 在 `MapChanged` 里重算系数（快照整体替换，音频线程不加锁）。
- `AddAudioEffect` 以 `optional: true` 挂载：音效加载失败时照常播放，设置页与均衡器页提示「当前系统不支持挂载音效」。
- 音效只接受 32 位浮点 PCM（44.1 / 48 / 88.2 / 96 / 192 kHz，单 / 双声道）；Q 值与频点见 `EqualizerBands`。

### 登录

**M0 #4 实测：浏览器 Cookie 导入不可行**（Chrome / Edge 的 App-Bound Encryption，前缀 `v20`；
DB 运行中被锁）。因此改用**独立登录窗口**，内含两个入口：

1. **内嵌 WebView2 登录（默认）**：`Microsoft.UI.Xaml.Controls.WebView2`（WinUI 2.8，需
   `Microsoft.Web.WebView2` 包与 Evergreen 运行时）导航到 `https://music.163.com/#/login`；
   登录后用 `CoreWebView2.CookieManager.GetCookiesAsync("https://music.163.com")` 读
   `MUSIC_U`（HttpOnly 也能读），拼出会话 cookie。与 Android 的 WebView 登录等价。
2. **二维码登录（辅助）**：`QrLoginClient` 申请 unikey（带 chainId），本地用 QRCoder 渲染
   （服务端 `qrimg` 优先），每 2s 轮询、最多 150 次；803 从 Set-Cookie 取会话 cookie。

两条路径成功后走同一处：存 `ICredentialStore`（PasswordVault）→ `NcmHttp.Cookie` → 刷新云端
音乐库。窗口为覆盖整个应用的**独立登录层**（左上角关闭；将来也可做成系统级二级窗口）。
`IBrowserCookieSource` 已删除。

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
| 4 | WebView2 登录（登录窗口） | 内嵌 WebView2 读到 `MUSIC_U`（HttpOnly 亦可）；`PasswordVault` 能存下完整 cookie。二维码作为辅助路径 |
| 5 | .NET Native Release 构建 | 响应模型的 JSON 解析正常。方案已定为 **Core 自研的只读 `JsonValue`**（零反射、零外部依赖），M0 只需在 Release + .NET Native 下确认解析正常 |
| — | 附带评估（半天） | 「UWP on 现代 .NET」能否带 WinUI 2.8 跑起来。只记录结论，不切换 |

M0 的配套工作：解决方案骨架（✅ 已完成）、Kanesumi.Xaml 的 `Colors` / `Typography` /
`Overrides` 三个资源文件（✅ 已完成）、从 arc-deck 移植 `tools/shot`（未做）。

### M1 · MVP（能日常使用）

登录（WebView2 + 二维码，独立窗口）、首页（每日推荐 / 推荐歌单 / 新歌）、歌单详情、搜索（三类）、
播放栏与全屏播放器、队列与 5 种播放模式、歌词、音质降级、播放上报。
Kanesumi.Xaml 的 M1 控件（见 `KANESUMI_XAML.md`）。
`spec/fixtures` 第一批：crypto、quality、queue、lrc，并有 Core 测试覆盖。

### M2 · 与 Android 主干功能对齐

音乐库云同步、专辑与歌手页、私人 FM 与 INFINITY、音质设置（✅）、明暗 3 模式（✅，强调色跟随系统）、
多选、窄窗口细节（导航已由 NavigationView 最小模式覆盖）、Kanesumi.Xaml 的 M2 控件、dolby / jyeffect 解码实测。

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
- **本地注册必须用 `bin\Release\ilc\AppxManifest.xml`**（.NET Native 布局）。注册
  `bin\Release\AppxManifest.xml`（IL 布局）会在激活期直接崩溃，而且**不产生任何托管日志**
  （App 构造函数都没跑到），极其难查。启动命令与日志路径见「构建与测试」。
- **Composition 的表达式/动画类型必须与目标属性一致**：`Scale` 是 `Vector3`，用标量表达式
  会在运行期抛 `ArgumentException: The expression output does not match animating property type`
  （UI 线程未处理异常 → 整进程崩溃）。凡是 `StartAnimation(name, expression)` 都要确认返回类型；
  合成初始化最好包 try/catch 降级。
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
- **浏览器 Cookie 读不了**：Chrome / Edge 127+ 用 App-Bound Encryption（cookie 前缀 `v20`），
  第三方（含 full-trust 进程）拿不到明文；且浏览器运行时 Cookie DB 被锁。不要再尝试从浏览器
  导入登录态（M0 #4 本机实测，DPAPI `v10` 已罕见）。
- **`LocalSettings` 单个值有大小上限**：队列、音乐库这类数据放进 `LocalFolder` 的文件里。
- **`rd.xml` 要作为 `Content` 加入项目**：写成 `EmbeddedResource` 会报 ILT0027
  「嵌入清单中不允许的应用程序指令」；完全不加也会报 ILT0027「缺少运行时指令文件」。
- **`dotnet sln add` 不能添加 UWP 项目**：它会到 .NET SDK 目录下找 WindowsXaml 的 targets，找不到就报错。
  解决方案文件手写维护，新增项目时照现有条目补上 `Release|x64` 的映射。
- **截图脚本按标题找窗口时要完全匹配**：arc-deck 的 `ShotWindow.ps1` 用的是「标题包含」，
  而终端窗口的标题里也可能带 "Ncrust"，会截错窗口。要求标题等于 "Ncrust"，
  并且窗口类是 `ApplicationFrameWindow`。
- **UWP 窗口在还原状态下可能截到一整块空白**：实测第一次截图是纯 `#222222`，
  窗口只有 514x359，最大化请求也不一定生效。用 `tools/shot/Shot.ps1`（`SetWindowPos` 摆成固定尺寸再截）。
- **MediaPlayer / MediaPlaybackList 的事件在后台线程触发**：回调里直接改 XAML 会
  `RPC_E_WRONG_THREAD` 崩溃。`PlaybackEngine` 统一封送到 UI 线程（`CoreDispatcher.RunAsync`），
  公开事件都在 UI 线程触发；`MediaBinder.Binding` 仍在后台，只能读闭包捕获的不可变参数。
  封送后的 `CurrentItemChanged` 可能已过时（列表又切走了），处理前先核对 `_list.CurrentItem`。
- **`IsHitTestVisible="False"` 对整棵子树生效**：想让覆盖层「空白处穿透、子元素可点」，
  用 `Background="{x:Null}"`（null 背景不接收命中），不要用 `Transparent`，也不要关父元素命中测试。
  反过来，`Opacity=0` 的元素照样接收点击，隐藏时要一并关掉命中测试。
- **Composition 动画参数的求值顺序**：`KanesumiEasing.Standard(_compositor)` 这类工厂在传参时就会执行；
  合成初始化失败（`_compositor == null`）的降级路径里要先判空再创建缓动。
- **编辑工具会把 `\uXXXX` 转义写成私用区字符**：图标码点在源码里要保持 `"\uE768"` 这样的转义，
  提交前确认没有混进看不见的字符。
- **快捷键 Space**：`KeyboardAccelerator` 会先于文本输入触发；焦点在 `TextBox` / `AutoSuggestBox` /
  按钮上时不要 `Handled`，否则打不了空格、按不了按钮。
- **UI 线程上不许对异步调用 `.Wait()` / `.Result`**：`BitmapImage.SetSourceAsync` 这类要回到 UI 线程
  完成的操作，在 UI 线程上同步等待会死锁 —— 实测打开登录层后整个应用无响应、WebView2 一片空白。
- **字符串不能直接 `x:Bind` 到 `Image.Source`**：空串转 ImageSource 会抛
  `ArgumentException: The value cannot be converted to type ImageSource` 并带崩进程（无封面的专辑 / 歌手）。
  图片一律经 `DisplayFormat.Cover(url, decodePx)`（Core `CoverUrls` + 空值保护 + 按显示尺寸解码）。
- **`BitmapImage` 只有挂到可视树里的 `Image` 上才会下载**：只 `new` 出来等 `ImageOpened` 永远等不到。
  需要「解码完再替换」时用隐藏的预加载 `Image`（见 `PlayerHost.CoverPreloader`）。
- **XAML 会跳过渲染 `Opacity="0"` 的元素**：想用 Composition 表达式驱动透明度的元素，XAML 里的
  Opacity 要保持 1，初值交给表达式。
- **`TransformToVisual` 会把 Composition `Translation` 算进去**（开了 `SetIsTranslationEnabled` 的元素）：
  播放卡片相对 Root 量封面位，收起时量到的是「平移到屏幕外的卡片里」的位置，形变终点跟着错，
  展开时封面先落到右下再跳回（实测）。量「动画终点」时相对不受该平移影响的祖先（卡片自身）取。
- **`AppBarButton.Flyout` 会自带子菜单箭头**：独立的图标键挂浮层时被挤成小图标 +「›」。改用
  `FlyoutBase.AttachedFlyout`，点击时 `ShowAttachedFlyout`。
- **键盘焦点框画在最上层**：覆盖层（播放卡片）展开时焦点若留在下面的页面，焦点框会透过覆盖层显示。
  展开时把焦点移进覆盖层，收起时移回。
- **XAML 命中测试不认 Composition 变换**：`Scale` / `Translation` 只改视觉，点击区域仍是布局位置。
  用 Composition 缩放显示的大元素要关掉命中测试，另放点击区。
- **应用运行时不能编译**：本地注册指向 `bin\Release\ilc`，应用开着时文件被占用，.NET Native 编译报
  `ilc.exe` 退出码 3004。先关应用再构建。
- **模拟键盘输入会被中文输入法转换**：截图脚本 `-Keys` 输入英文会变成拼音候选（"jay" → 「叫阿姨」）；
  验证时用数字，或先切到英文输入。
- **包清单变了而版本号没变，`Add-AppxPackage -Register` 报 `0x80073CFB`**（同版本包已存在且内容不同）。
  新增 Windows 运行时组件时构建会改清单（写入 activatable class），同样要升版本。见「构建、测试与运行」。
- **音效组件的缓冲区**：`AudioFrame.LockBuffer` 拿到的内存要经 `IMemoryBufferByteAccess`（COM 接口，
  `unsafe` + `AllowUnsafeBlocks`）取指针；`EqualizerEffect` 先 `Marshal.Copy` 到 float 数组、处理完再拷回。
  `DiscardQueuedFrames`（seek / 换歌）时要清掉滤波器历史，否则上一段的尾音会带进新位置。
- **`git rm` 暂存的删除会混进下一个 commit**：分多个 commit 提交时，先确认 `git status` 里没有不属于
  本单元的已暂存删除；混进去了用 `git reset --soft` 拆开重提。
- **Bash 工具的 heredoc 会吞掉反斜杠**：写含 `\` 的脚本（正则、Windows 路径、`\uXXXX`）用编辑工具，
  或写成文件再执行。
- **跟随另一块元素高度时小心布局循环**：播放卡片右栏要与左栏等高，若左栏元素是拉伸对齐、右栏跨行，
  给右栏设高度会撑高行 → 左栏跟着变高 → 右栏再变高，抛 `LayoutCycleException`（启动即崩，实测）。
  左栏元素改为顶端对齐（高度只看自身内容），右栏总占高取整后不超过左栏。
- **不要重新 `StartAnimation` 正在运行的表达式来「更新参数」**：替换那一帧属性会露出静态值
  （播放卡片的封面按原始大尺寸、布局原点闪一下，实测）。表达式只启动一次，随布局变化的数值放进
  `CompositionPropertySet` 当参数，改参数即可。
- **启动恢复期间不能落盘会话**：恢复播放模式会调 `SetMode`，那时 `RestoreAsync` 还没读文件、队列是空的，
  存下去就把上次的队列覆盖成空。引擎在 `ShowRestored` / `PlayCurrent` 之前忽略 `SaveState`。
- **Write 工具仍会把 `\uXXXX` 转成私用区字符**：C# 里写图标码点后用脚本扫一遍 U+E000–U+F8FF
  （或改用 `Glyphs` 里 `(char)0xE768` 的写法）。
- **应用能力声明**：`internetClient`、`backgroundMediaPlayback`；M3 做 `QrPair` 时再加
  `privateNetworkClientServer`。
