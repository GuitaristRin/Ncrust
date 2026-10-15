# Ncrust for Windows —— 性能与功耗审计（只出报告，不改代码）

- 审计对象：`windows/`（`Ncrust.App` / `Ncrust.Core` / `Ncrust.Audio` / `Kanesumi.Xaml`），
  基线 `Package.appxmanifest` 版本 **1.0.1.0**（`windows/src/Ncrust.App/Package.appxmanifest:17`）。
- 本报告**没有修改任何产品代码**，唯一新增文件就是本文件。
- 本轮**没有跑任何实机测量**（本工作区没有部署好的应用、没有登录态、也不允许动登录 cookie），
  所以所有「收益」都是**定性等级 + 理由**，不是实测数字。测法与判据逐条写清，谁都能复跑。
- 报告里的行号以本工作区（基于 master 的 worktree）的当前内容为准；代码前进后请按 `文件:行` 附近的内容复核。

## 怎么读这份报告

**收益等级**（定性）：

| 等级 | 判据 |
|---|---|
| 高 | 影响「每一次交互 / 每一首歌 / 每一次启动」，或者直接决定进程能不能静止下来（省电） |
| 中 | 影响某类操作（某一页、某次滚动、换歌瞬态），可感知但不总是发生 |
| 低 | 单次开销在毫秒级 / 每首歌一次 / 只在设置页里，属于顺手清理 |

**风险等级**（改动风险，不是问题本身的严重度）：

| 等级 | 判据 |
|---|---|
| 低 | 改动局部、可回滚、无交互/语义变化 |
| 中 | 会动到交互手感、布局或队列/播放语义，需要回归测试或负责人试听试用 |
| 高 | 触及「已知的坑」（Composition 类型、布局循环、UI 线程死锁、.NET Native 反射）或用户可见行为 |

**验证分级**（全文逐条标注，不混装）：

- **【已确认】**：代码里读出来的事实，可以按 `文件:行` 自己复核。
- **【推测】**：还没在实机上验证过的判断（含开销量级、帧影响、CPU 占比）。条目末尾都写了怎么证实/证伪。
- **【需你判断】**：属于用户/负责人决策的事（清晰度取舍、桌面端是否要记住播放模式之类）。

**测法编号**（总表「实机测量方法」列引用；脚本见文末「测量步骤草案」）：

| 编号 | 手段 |
|---|---|
| M1 | 任务管理器（Win11 进程页）→「电源使用情况」/「电源使用趋势」列，按状态各采 5 分钟 |
| M2 | `powercfg /energy /duration 60`（需管理员）→ `energy-report.html`，看「电源请求」「CPU 利用率」 |
| M3 | `powercfg /srumutil /output <csv> /csv`（需管理员）→ SRUM 里每进程的 Energy Usage / 后台 CPU 时间 |
| M4 | `powercfg /batteryreport`（本机有电池：`Win32_Battery` 计数 = 1）→ 整体核对，不区分进程 |
| M5 | WPR `-start XAMLAppResponsiveness`（XAML Frame Analysis 数据）→ WPA 逐帧看 UI/合成线程与 missed vblank |
| M6 | WPR `-start CPU.light -start GPU -start FileIO -start Network` → WPA；本机缺 WPA 时用 `tracerpt` 兜底 |
| M7 | perfmon：`\Process(Ncrust)\% Processor Time`、`Working Set - Private`、`IO Write Bytes/sec`、`\Energy Meter(*)\Power`、`\Processor Information(_Total)\% Processor Utility` |
| M8 | VS 2022 诊断工具（CPU / 内存 / 事件）+ XAML 实时可视化树、实时属性资源管理器。**本报告没有跑过这一项**：本机只装了 Build Tools，没有 `devenv.exe`（见第七节） |
| M9 | 任务管理器 / 资源监视器：进程树（`msedgewebview2.exe` 是否退出）、GPU 引擎、线程数、句柄数 |
| M10 | 手工 A/B：同一场景下改前/改后各采一次 M7 的 5 分钟均值（门控类改动的最小验证法） |

代码走查时我用过的静态检查（可复跑）：`grep` 全仓找 `DispatcherTimer` / `CompositionTarget` / `VisibilityChanged` /
`x:Load` / `x:DeferLoadStrategy` / `x:Phase` / `LayoutUpdated` / `NavigationCacheMode` / `static event`。

---

## 一、总表

> 每条编号对应下面「逐条说明」里的同一编号（高收益项展开写）。
>
> **标注约定**：「问题」一列里，凡未写【推测】的都是**代码级事实**（可照 `文件:行` 复核，走查时已确认）；
> 写了【推测】的是「由代码推断、但开销量级还没实测」的判断。收益一列凡带「**推测**」的同样是待实测项。
> 逐条说明里的【已确认】/【推测】是同一套分级。

| 编号 | 类别 | 问题（一句话） | 证据（文件:行） | 预估收益 | 风险 | 实机测量方法 | 改法草图 |
|---|---|---|---|---|---|---|---|
| W-01 | 功耗/后台 | 进度 ticker 是 500ms `DispatcherTimer`，**从引擎初始化起永久运行**：暂停、空队列、窗口最小化时都在 tick，每秒 2 次读播放会话并驱动一批 XAML 写入 | `Playback/PlaybackEngine.cs:41`、`:194-195`、`:687-698`；`Player/PlayerHost.xaml.cs:173`、`:491-515` | **高**：进程永远醒着，空闲/暂停时也占 UI 线程与合成器 | 低 | M1 + M7（空闲 5 分钟 vs 播暂停 5 分钟） | ticker 只在「有媒体且未暂停」时运行；暂停/停止/无队列时 `Stop()`，恢复播放再 `Start()`。若上报仍需要心跳，用 5s 的慢速心跳替代 500ms 周期 |
| W-02 | 功耗/后台 | 全仓**没有任何窗口可见性 / 激活状态门控**（无 `VisibilityChanged`，只有 `Window.Activated` 改标题透明度）；最小化后 XAML 侧照旧更新 | 全仓 `grep VisibilityChanged` 无命中；`Shell/ShellPage.xaml.cs:175-176`；`App.xaml.cs:37`、`:132-136`；`Playback/PlaybackEngine.cs:194-195` | **高**：与 W-01 叠加，最小化后台播放期间白做整个 UI 层的活 | 低 | M1 + M7（最小化播放 5 分钟 vs 可见播放 5 分钟）；M9 看线程唤醒 | `Window.Current.VisibilityChanged` 里置一个 `IsBackground` 标志；后台时停 ticker 的 UI 侧派发（进度仍每 5s 更新一次内部状态用于上报），隐藏/收起时不写滑块与文本 |
| W-03 | 功耗/后台 | 歌词边界计时器密集段落可到 **16ms 一响**（≈60Hz `DispatcherTimer`），每次还可能触发一次带缓动的 `ChangeView` 滚动 | `Player/LyricsController.cs:183-201`（`Math.Min(1000, Math.Max(16, next - now))`）、`:203-207`；`Kanesumi.Xaml/Controls/MetroLyricsPanel.cs:404-420` | 中：只在歌词行很密时发生；展开卡片看歌词时最费 | 低 | M7（采样期间停留在歌词页的密集段落）；M5 看每帧 UI 线程耗时 | 下限从 16ms 提到 ~50ms（跨行视觉上仍是「到点就换」）；把「滚动」与「换色」分开——滚动只在跨过若干行或 250ms 节流后才做一次 |
| W-04 | 功耗/后台 | 均衡器音效**无论开关都挂在音频链路上**（`AddAudioEffect(optional:true)` 在启动时无条件调用），而它声明的受支持格式只有 32 位浮点 PCM；【推测】降级/转换开销是否真实存在未实测 | `Playback/PlaybackEngine.cs:151-163`；`PlaybackHost.cs:239`；`Ncrust.Audio/EqualizerEffect.cs:29-46`、`:65-71`；`Ncrust.Core/Audio/EqualizerProcessor.cs:23-33` | 中（**推测**）：关闭时 `ProcessFrame` 已提前返回（不碰缓冲），但管线可能仍按 float32 解码+转换 | 中 | M10：均衡器关 vs 开各采一次 M7 的进程 CPU；M5 看音频线程（`Ncrust` 的 MMDevAPI/Audio 线程）CPU | 关闭均衡器时不挂音效（`AddAudioEffect` 只在 enabled 时调用，切换时重挂）；**前提是先实测确认「不挂」真的更省**，否则不改 |
| W-05 | 流畅度/帧 | 首页的 `ListView`/`GridView` 被 `ScrollViewer > StackPanel` 包住 → 纵向测量空间无限，**UI 虚拟化失效**（框架行为，需 M8 确认实际容器数），每日推荐 + 新歌（约 60 行）+ 10 张歌单磁贴一次性实例化 | `Pages/HomePage.xaml:15-16`、`:74-80`、`:118-130`、`:136-142` | **高**：首帧、滚动、内存三处都吃亏 | 中 | M5（首页滚动 + 首帧帧时间）；M8 实时可视化树看实际容器数；M7 看 `Working Set - Private` 与 GPU 内存 | 用 `ListView` + `Header`（详情页已经是这个模式，见 `AlbumPage.xaml:22-24`）或 `ItemsRepeater` 承载三个分区；每个分区自己的 `ListView` 需要显式高度，改成单个外层 `ListView` 承载「页头 + 分区头 + 行」最省事 |
| W-06 | 流畅度/帧 | 播放卡片的**重子树没有 `x:Load` 门控**（AGENTS.md 的「`Progress < 0.05` 卸载」并未实现）：收起时卡片内仍是完整可视树，且 2Hz 进度**照样写卡片里的滑块与两个时间文本** | `Player/PlayerHost.xaml:75-78`、`:158-165`、`:283-336`；`Player/PlayerHost.xaml.cs:163`、`:723-734`、`:491-515`；`Kanesumi.Xaml/Motion/KanesumiMotion.cs:38`（`PlayerHeavyChildrenMinProgress` 定义但全仓无引用）；全仓 `grep "x:Load"` 无命中 | **高**：每一秒 4 次无谓布局/绘制提交（迷你栏状态占绝大多数时间） | 中 | M8 实时可视化树：收起状态下数卡片子树元素数；M5 收起静止时的帧时间（应有大量「无变化却提交」的帧）；M7 | 给 `CardContent`（或其内部的 `ControlsPanel`/`SideTabs`）加 `x:Load` 绑到 `_expanded`；`OnProgressChanged` 里在 `!_expanded` 时跳过 `CardSeekSlider`/`CardPositionText`/`CardDurationText`，展开瞬间补一次全量刷新 |
| W-07 | 流畅度/帧 | `Card.LayoutUpdated` 里做 `TransformToVisual` 量尺寸、并可能写 `SideTabs.Height`（写高度又会触发下一轮布局）；这个处理函数**每次布局都会跑** | `Player/PlayerHost.xaml.cs:75-90`、`:84`、`:277-294`、`:306-319`、`:321-384` | 中~**高**：动画/窗口缩放期间每帧一次树遍历 + 一次可能的再布局 | 中 | M5（展开/收起动画期间的布局次数与帧时间）；M6 看 UI 线程中的 `Measure/Arrange` 占比 | 只在 `Root.SizeChanged` / 卡片尺寸变化时重算，用 `SizeChanged` 代替 `LayoutUpdated`；或在 `LayoutUpdated` 里先比对 `Root.ActualWidth/Height` 与上次值，未变直接返回；`SyncSideHeight` 的写入放到 `Dispatcher.RunAsync(Low)` 里做，避免在布局回调内再布局 |
| W-08 | 流畅度/帧 | 列表项模板**没有 `x:Phase`**，且封面是 `x:Bind` 调 `DisplayFormat.Cover(...)` 每次新建 `BitmapImage`：容器每次被回收复用都会重新建图、重新解码 | `Resources/Templates.xaml:24-29`、`:47-60`、`:64-78`、`:81-95`、`:98-112`、`:115-129`、`:132-152`、`:166-210`、`:230-276`；`Resources/Templates.xaml.cs:112-121`；全仓 `grep "x:Phase"` 无命中 | 中：滚动的顿挫主要来自图片解码落在 UI 线程 | 低 | M5（快速滚动列表时的长帧与 `BitmapImage` 解码栈）；M9 看 GPU 内存 | 给图片加 `x:Phase="1"`（文字先出、图片后到）；加一个 `Url → ImageSource` 的 LRU 缓存（例如 200 条）供模板复用，滚动回去不再解码 |
| W-09 | 流畅度/帧 | 歌词面板换歌/切翻译时**整棵重建**（每行一个 `StackPanel` + 1~2 个 `TextBlock` + 每行一个 `SizeChanged` 订阅 + 每行一个表达式动画） | `Kanesumi.Xaml/Controls/MetroLyricsPanel.cs:149-180`、`:227-271`；`Player/LyricsController.cs:104-161` | 中：换歌瞬态卡顿（100 行歌词 ≈ 200~300 个元素） | 中 | M5 换歌瞬间的 UI 线程长帧；M8 看歌词面板元素数 | 行容器池化复用（行数变化时只增删差值）；颜色用 Composition 表达式或共享样式切，不再整表 `ApplyState`；`SetLines` 只在歌词真的变了时调用（现在翻译开关变化也会重建） |
| W-10 | 流畅度/帧 | 队列视图每次队列变化都**整表重建** `ObservableCollection`（展开着队列时每换一首歌重建 N 行） | `Player/QueuePresenter.cs:113-120`、`:143-186`；`Player/PlayerHost.xaml.cs:175-176` | 低~中：只在队列页可见时重建 | 低 | M8 看队列 ListView 容器数变化；M5 换歌时切到队列页的帧时间 | 只更新「当前曲」标记与新增/删除项，不 `Clear()` 重灌；把行对象做成可复用（改 `IsCurrent` 后 `INotifyPropertyChanged`） |
| W-11 | 流畅度/帧 | 【推测】`MetroProgressRing` 在 `OnApplyTemplate` 里启动**永久旋转动画**，控件 `Collapsed` 时也不例外（代码事实：动画无条件启动；成本多小未实测） | `Kanesumi.Xaml/Controls/MetroProgressRing.cs:26-43`；`Pages/HomePage.xaml:147-151`、`Pages/SearchPage.xaml:71-76`、`Pages/LibraryPage.xaml:135-139` | 低（**推测**：合成器上一条标量动画，成本很小，但它在每个页面各有一条） | 低 | M7（加载态结束后的空闲 CPU/GPU）；M9 GPU 引擎 | 在 `SizeChanged`/可见性变化时启停动画；或加载态用 `x:Load` 让控件整体卸载（顺带解决 W-06 的同类问题） |
| W-12 | 启动 | 首次触碰 `AppServices` 任意成员会**触发全部静态字段初始化**，其中 `CreateHttp()` 会同步读 `PasswordVault`（凭据管理器 COM 调用）；而这个触碰发生在 `App.OnLaunched` 里的 `AppTheme.ApplySaved`（**首帧之前**） | `App.xaml.cs:99-123`（`:108`）；`AppServices.cs:20-30`；`AppServices.cs:90-100`；`Platform/PasswordVaultCredentialStore.cs:16-28`；`Platform/LocalSettingsStore.cs:13-14` | 中：冷启动首帧路径上的同步凭据读 + 十几个对象构造 | 中 | M6（`FileIO` + `CPU` profile 看启动期同步调用）；M8 事件视图看首帧前的耗时；`boot.log` 时间戳（`App.xaml.cs:40-41`） | 把 `Http` 改成惰性构造（`Lazy<T>`），`Cookie` 从 `PasswordVault` 的读取挪到首个网络请求前或 `OnLoaded` 之后；`AppTheme.ApplySaved` 只读 `LocalSettings`，不要经由 `AppServices` |
| W-13 | 启动 | 启动诊断日志每次 `Trace` 往 **3 个位置**同步 `File.AppendAllText`（5 次 Trace = 最多 15 次文件打开）；播放器布局诊断还会在动画期间往同一处写 | `App.xaml.cs:18`、`:36`、`:40-71`、`:114-122`；`Player/PlayerHost.xaml.cs:342-353` | 低：单次几毫秒，但全在 UI 线程且在首帧路径上 | 低 | M6（`FileIO` profile 看进程启动阶段的写次数）；直接数 `boot.log` 行数 | 只在 Debug 构建保留三落点，Release 只写 `LocalFolder` 一处；或者改成内存缓冲、启动完成后一次性落盘 |
| W-14 | 启动 | .NET Native 运行时指令用 `<Assembly Name="*Application*" Dynamic="Required All"/>`，**给全部应用程序集留全量反射元数据** | `Properties/Default.rd.xml:8` | 低~中（**推测**：主要影响包体积与模块加载，对首帧的影响需实测） | 中 | M6（启动期 CPU/FileIO）；对比两次发布的 `Ncrust.dll`/`.ni` 体积 | 按实际用到反射的类型收紧（当前方案刻意零反射，只有异常消息等少数路径需要）；收紧后必须回归「Release + .NET Native」全流程，反射裁剪失败的报错很晚才出现 |
| W-15 | 网络 | 云音乐库刷新**没有 in-flight 去重**：启动时 `ShellPage.OnLoaded` 拉一次，进音乐库页时 `LibraryPage.OnNavigatedTo` 又拉一次（各含 uid + 红心 ids + 批量歌曲 + 收藏专辑四次往返） | `Shell/ShellPage.xaml.cs:73-74`、`:94-110`；`Pages/LibraryPage.xaml.cs:62-69`、`:71-93`；`Core/Library/LibraryManager.cs:258-338` | 中：重复请求 + 重复落盘，冷启动与首次进库都能感知 | 低 | M6（`Network` profile 数请求个数，或用抓包看 `eapi` 调用序列） | 在 `LibraryManager` 里加「最近一次刷新完成时间 + 在飞任务」两个字段，短窗口（例如 30s）内复用结果；或者让 `LibraryPage` 只在超过窗口时才刷新 |
| W-16 | 网络 | 一次「收藏」= 3 次网络往返（like + `GetProfileAsync` 取 uid + 拉全量红心 id 做校验）**+ 2 次整库落盘**；uid 明明已经在 `ContentCache.UserProfile` 里还要重新请求 | `Core/Library/LibraryManager.cs:412-431`、`:433-445`、`:501-547`；`Core/Api/AccountApi.cs:56-57`；`Core/Cache/ContentCache.cs:34` | 中：每次红心都是几秒级延迟与多余流量；失败重试还会放大 | 低 | M6（`Network`）数一次红心操作的请求数；M7 看 `IO Write Bytes/sec` | uid 优先读缓存（缓存没有才请求）；校验改成「乐观更新 + 下次云端刷新时对账」（`_pending` 机制已经在了）；两次落盘合并成一次（等校验结果一起写） |
| W-17 | 网络 | 「预载下一首」是按**滑动窗口制**触发：当前曲一开始播，下一首就进 `MediaPlaybackList`（URL 在 `Binding` 里就取），而不是文档写的「最后 60 秒」；`PlaybackSessionState.PreloadCandidate()` 全仓无人调用（死代码），`MaxPrefetchTime` 在代码里根本不存在 | `Playback/PlaybackEngine.cs:457-483`、`:373-432`；`Core/Playback/PlaybackSessionState.cs:53-67`；`windows/AGENTS.md:223` | 中：连续播放时网络一直有下一首的取链与系统预缓冲（**推测**：`MediaPlaybackList` 会提前打开下一项） | 低 | M6（`Network` profile：观察播放中的持续流量与请求时刻）；对比「无缝播放」开关打开/关闭的流量曲线 | 先把文档与代码对齐（要么实现 60s 窗口、要么改文档）；省电方向上可让预载在「计费网络」时退到窗口尾部触发（`INetworkInfo.IsMetered` 已有） |
| W-18 | 网络 | 登录层每次打开都 `new NcmHttp()`（新的 `HttpClient` + handler + 连接池），用完不释放；主网关的 `HttpClient` 是复用得很好的单例 | `Login/LoginPage.xaml.cs:26`、`:41`；对比 `AppServices.cs:30`、`Core/Net/NcmHttp.cs:32-52` | 低：每次登录一份连接池与 TLS 会话 | 低 | M9 看句柄/连接数在反复开关登录层后的走势；M6 数握手 | 二维码登录复用 `AppServices.Http`（`QrLoginClient` 本来就是拿 `NcmHttp` 构造的），或在登录层卸载时 `Dispose()` |
| W-19 | 功耗/后台 | 登录层关闭时**不停止二维码轮询**：`_pollTimer` 只在成功/过期/超次时 `Stop()`，`CloseRequested` 路径没有；关掉登录层后最多还会每 2s 请求一次，最长 150 次 | `Login/LoginPage.xaml.cs:43`、`:149`、`:197-244`、`:273`；`Shell/ShellPage.xaml.cs:516-529` | 中：关掉登录层后 5 分钟里的持续后台请求（用户以为已经关掉了） | 低 | M6（`Network` profile：打开登录层→切到扫码→关闭→观察是否还有周期请求） | `LoginPage` 加 `Unloaded` 处理（或 `CloseLogin()` 里显式调一个 `Stop()`），在轮询处一并停表 |
| W-20 | 功耗/后台 | 【推测】WebView2 是否随登录层关闭退出未验证；如果它常驻，就是一个后台常驻的浏览器进程（代码事实：没有任何 `Dispose`/`Close` 调用） | `Login/LoginPage.xaml.cs:53-74`、`:252-269`；无 `Dispose`/`Close` 调用 | 中（**推测**） | 低 | M9 进程树：打开登录层→关掉→看 `msedgewebview2.exe` 是否消失 | `CloseLogin()` 里显式释放 WebView2；或把登录窗口做成真正的二级窗口 |
| W-21 | 内存 | 【推测】量级：磁贴/行封面按「逻辑尺寸 ×2」解码（`DecodePixelType.Logical`，代码事实），配合 W-05 的「首页不虚拟化」，首页可能同时持有数十 MB 位图 | `Resources/Templates.xaml.cs:112-121`；`Resources/Templates.xaml:28`（144）、`:52`（352）、`:176`（112）、`:255`（80）；`Pages/HomePage.xaml:74-142` | 中（**推测，量级按公式估算**：200% 缩放下 352 逻辑 px → 704² × 4B ≈ 2.0MB/张；10 张磁贴 ≈ 20MB，60 行 × 144px ≈ 20MB） | 低 | M9 任务管理器内存/G 内存；M8 诊断工具的内存快照（按类型看 `BitmapImage`/纹理） | 先修 W-05；再按实际显示尺寸下调 `decodePx`（磁贴 176 显示 → 352 已是最低可接受的 2 倍）；长列表里让 `x:Phase` 延后取图 |
| W-22 | 内存 | 静态事件订阅**从不退订**（`AppServices.SessionChanged`、`SongActions.LibraryChanged`、`LoginLauncher.LaunchRequested`、`SearchSuggestion.RemoveRequested`、`PlayerHost.LyricsSettingsChanged`、`CoreWindow.PointerPressed`），页面被 `NavigationCacheMode` 长期缓存并持有整棵可视树与列表数据 | `Shell/ShellPage.xaml.cs:46-49`；`Pages/HomePage.xaml.cs:28`；`Pages/LibraryPage.xaml.cs:50-59`；`Pages/SettingsPage.xaml.cs:38`；`Player/PlayerHost.xaml.cs:167-168`；`Pages/HomePage.xaml:6`、`LibraryPage.xaml:6`、`SearchPage.xaml:6`、`SettingsPage.xaml:5` | 中：三个缓存页（含详情页未缓存）叠加后常驻内存不小；重复触发 lambda 也会执行两遍 | 低 | M8 内存快照（看缓存页对象与可视树是否留存）；M9 看内存随「首页↔音乐库」来回切换是否阶梯上涨 | 页面加 `Unloaded`/`OnNavigatedFrom` 退订，或改成弱事件；`LibraryPage`/`HomePage` 订阅前先退订一次（幂等） |
| W-23 | 存储/IO | 播放状态**每次换歌、每次队列编辑都全量序列化整个队列**再写盘（`CreateFile(ReplaceExisting)` + `WriteTextAsync`），120 首的队列每首歌几十 KB 写放大 | `Playback/PlaybackEngine.cs:565-575`、`:727-737`；`Playback/PlaybackHost.cs:130`、`:142`、`:218`；`Core/Playback/PlaybackStateStore.cs:38-42`；`Core/Playback/PlaybackSessionState.cs:69-70`；`Platform/LocalFileStore.cs:28-33` | 中：换歌瞬态 + 无谓的 SSD 写入 | 低 | M7（`IO Write Bytes/sec` 在换歌时的尖峰）；M6（`FileIO` profile） | 加 1~2s 防抖（`SaveState` 合并写）；只在「队列/索引真的变了」时写；`WriteTextAsync` 用 `CreationCollisionOption.OpenIfExists` 减少文件重建 |
| W-24 | 存储/IO | 搜索历史每次展示做 **4 次整文件读 + 4 次 JSON 解析同一文件**（四类各读一遍），搜索框获得焦点/清空输入都会触发 | `Shell/ShellPage.xaml.cs:420-431`、`:381-387`、`:389-418`；`Core/Search/SearchHistory.cs:80-90`、`:166-178`、`:185-202`、`:204-245` | 低~中：每次聚焦搜索框 4 次 IO；机械盘/低端设备更明显 | 低 | M7（搜索框聚焦前后的 `IO Read Bytes/sec`）；M6（`FileIO`） | 内存镜像 + 一次读入（`LyricsCache`/`LibraryManager` 已经是这个模式）；读路径只解析一次 |

---

## 二、逐条说明（高收益项展开）

### W-01 / W-02 常驻 ticker 与「没有后台概念」

**证据（已确认）**

- `PlaybackEngine._ticker`：`Interval = 500ms`，`Initialize()` 里无条件 `Start()`，全仓**没有一处 `Stop()`**
  （`Playback/PlaybackEngine.cs:41`、`:194-195`）。
- tick 里做的事：读两次 `MediaPlaybackSession`（COM 属性）、写 `_session.UpdateProgress`、
  触发 `ProgressChanged`、跑上报判定（`Playback/PlaybackEngine.cs:687-698`）。
- `ProgressChanged` 的订阅者直接改 XAML：`SlimProgress.Value`、两个时间文本、两个 `Slider.Value`
  （`Player/PlayerHost.xaml.cs:173`、`:491-515`）。
- 窗口可见性：全仓 `grep VisibilityChanged` 无命中；`Window.Activated` 只用来淡标题
  （`Shell/ShellPage.xaml.cs:175-176`）；`Suspending` 处理函数只 `deferral.Complete()`，什么都不做
  （`App.xaml.cs:132-136`）。清单声明了 `backgroundMediaPlayback`
  （`Ncrust.App/Package.appxmanifest:63`），所以最小化后进程**不会被挂起**，ticker 会一直跑。

**为什么算高收益**：这不是「一次操作贵」，而是「进程永远不静止」。用户把窗口最小化去干别的事、
或者暂停后离开，Ncrust 仍然每秒醒 2 次、写 6~8 个 XAML 属性、触发一轮布局失效。
省电优化的第一刀应该砍在这里。

**测量（M1 + M7 + M10）**

1. 三个状态各 5 分钟：A 空队列（不播放）；B 播放中暂停；C 播放中最小化；D 播放中窗口可见。
2. 每个状态采 `\Process(Ncrust)\% Processor Time`、`IO Write Bytes/sec`、`\Energy Meter(*)\Power`、
   `\Processor Information(_Total)\% Processor Utility`，看 A/B 与 C/D 的差。
3. 预期（**假设，待证实**）：A/B 应当是「接近 0 的平线」，现在会看到 2Hz 的锯齿状尖峰。

**改法草图**

- ticker 生命周期绑到「有媒体 + 未暂停」：`OnPlaybackStateChanged` 里 `Playing → Start()`、
  `Paused/None → Stop()`；`Stop()` 时补一次收尾上报判定。
- 加 `IsBackground`（`VisibilityChanged` 里维护）：后台时 ticker 只更新内部状态与上报判定，
  **不派发** `ProgressChanged`（或在后台把派发降到 5s 一次）；回到前台立刻全量刷新一次 UI。
- 注意：`OnProgressChanged` 里的 `_dragSlider` 逻辑依赖 2Hz 回传（`Player/PlayerHost.xaml.cs:500-503`），
  门控不能让「拖动中不被拉回」这个行为失效。

### W-05 首页失去虚拟化

**证据（结构已确认，后果待实测）**：`HomePage.xaml:15-16` 是 `ScrollViewer > StackPanel`，第 74/118/136 行的
`ListView`/`GridView` 都在里面。UWP 的 `ListView` 只有在拿到「有限高度」时才虚拟化
（它依赖自己的内部 `ScrollViewer`）；包在纵向 `StackPanel` 里时测量高度是无限的，
按框架语义所有项都会被实例化；`ItemsWrapGrid`（`HomePage.xaml:126-128`）同理。
**「实际容器数 = 数据条数」这一步我没有实机验证**，判据见 H-2。

**数据量（已确认）**：每日推荐一整份列表、推荐歌单 10 条（`Core/Api/DiscoveryApi.cs:42`）、
新歌 30 条（`Pages/HomePage.xaml.cs:118`）→ 约 60 个 72px 行 + 10 张 176px 磁贴，
每行/每贴各带一张 `BitmapImage`（W-21）。

**测量（M5 + M8）**：展开「首页」后看首帧与滚动帧时间；在实时可视化树里数 `ListViewItem`/`GridViewItem`
的实际数量（不虚拟化时 ≈ 数据条数）。

**改法草图**：把外层换成单个 `ListView`，把「页头 / 每日推荐标题 / 每日推荐行 / 推荐歌单标题 / 磁贴 / 新歌标题 / 新歌行」
作为分段数据项交给它（详情页的 `ListView.Header` 已经是同思路，见 `Pages/AlbumPage.xaml:14-25`）。
另一个更小的改法：给三个分区自己的 `ListView` 显式高度（`Height` 或外层 `Grid` 行高），
但滚动条会变成三个，视觉上要先问负责人。

### W-06 播放卡片重子树没有门控

**证据（已确认）**

- `Card.Visibility` 在合成初始化后就一直是 `Visible`（`Player/PlayerHost.xaml.cs:163`），
  收起只是把它平移到屏幕外；`ApplyHitTesting` 只切 `IsHitTestVisible`
  （`Player/PlayerHost.xaml.cs:723-734`）。
- 卡片里包含右侧 `Pivot`（歌词 + 队列）、`CardSeekSlider`、音量行、一整套传输按钮
  （`Player/PlayerHost.xaml:158-165`、`:283-336`）。
- 全仓 `grep "x:Load"`、`grep "x:DeferLoadStrategy"` **零命中**；
  `KanesumiMotion.PlayerHeavyChildrenMinProgress = 0.05f` 定义了但没有任何引用
  （`Kanesumi.Xaml/Motion/KanesumiMotion.cs:38`）。
- 收起状态下 `OnProgressChanged` 仍然写 `CardSeekSlider.Value`、`CardPositionText`、
  `CardDurationText`（`Player/PlayerHost.xaml.cs:491-515`，其中 `Card*` 两行在 508-512）。

**文档不一致（顺带记录）**：`windows/AGENTS.md` 的「动画架构」第 4 条把 `x:Load` 门控写成已定架构，
而 `windows/docs/PLAN.md:142` 把它列在「未做」。两处文档对同一件事说法不同，代码站在 PLAN 那一边。
本任务不改文档，留给派工者决定。

**测量（M8 + M5）**：收起状态下用实时可视化树数 `PlayerHost` 子树元素数；
在 M5 里看「静止不动」时的帧序列是否仍有周期性提交（2Hz 节奏）。

**改法草图**：给 `CardContent` 加 `x:Load`（绑到展开状态），展开动画**开始前**就设为 true（避免第一帧没内容）；
`OnProgressChanged` 里未展开时只更新迷你栏那一套控件，展开时补一次全量。

### W-07 `LayoutUpdated` 里的测量与赋值

**证据（已确认）**：`Player/PlayerHost.xaml.cs:75-90` 在 `Card.LayoutUpdated` 里：

1. 首次布局后调 `ApplyCardLayout()`；
2. 每次都调 `SyncSideHeight()`（`:277-294`，内部可能写 `SideTabs.Height`，写高度会再次触发布局）；
3. 每次都调 `CoverSlotRect()`（`:306-319`，`TransformToVisual(...).TransformPoint(...)` 走树），
   矩形有变化就 `RebuildExpressions()`（`:321-384`，会写 `CoverImage.Width/Height`，又是一次布局失效）。

**风险点（已知的坑）**：代码注释里明确写了这里曾经撞出 `LayoutCycleException`（`:284-286`），
所以任何改动都要保留「右栏总占高 ≤ 左栏」这条约束。

**测量（M5 + M6）**：展开/收起动画期间，看 UI 线程上 `Measure/Arrange`（或 XAML 的 layout pass）
次数与单帧耗时；窗口连续缩放时更容易看出。

**改法草图**：把「量封面位 + 同步右栏高度」从 `LayoutUpdated` 挪到 `SizeChanged` + 一次性测量；
`LayoutUpdated` 里只留「尺寸没变就直接 return」的短路。`RebuildExpressions` 已经在做参数化，
不需要重新 `StartAnimation`（`:386-414` 的注释说明了为什么），这一点保持。

### W-12 启动路径上的同步 IO

**证据（已确认）**：`App.OnLaunched`（`App.xaml.cs:99-123`）第一件事是
`AppTheme.ApplySaved(rootFrame)`（`:108`）→ `AppServices.Settings.GetString(...)`
（`Shell/AppTheme.cs:34`）→ 这是对 `AppServices` 的**首次静态访问**，于是 C# 按声明顺序初始化全部静态字段
（`AppServices.cs:20-66`）：`Settings` → `Files` → `Credentials` → … → `Http = CreateHttp()`（`:30`）→
`CreateHttp()` 里 `Credentials.GetCookie()`（`:93`）→ `PasswordVault.Retrieve(...)`
（`Platform/PasswordVaultCredentialStore.cs:20-21`，同步 COM 调用）。
这些全部发生在 `Window.Current.Activate()`（`App.xaml.cs:121`）之前。

**测量（M6 + M8）**：启动期（`Process Start` → 首帧）看 CPU 采样里 `PasswordVault`/`Credential`
相关栈与文件/注册表活动；也可以直接对比 `boot.log` 时间戳
（`App.xaml.cs:40-41` 已经打了 5 个点）与首帧出现的时刻。

**改法草图**：`Http` 改成 `Lazy<NcmHttp>`；`AppTheme.ApplySaved` 直接读 `ApplicationData.Current.LocalSettings`
而不经过 `AppServices`；凭据读取放到 `ShellPage.OnLoaded` 的异步路径里（`AppServices.SetCookie`/`Http.Cookie`
是可变属性，晚一点设没问题，但要注意「界面先显示未登录、随后切成已登录」的闪烁——这属于可见行为改变，
建议先用 `boot.log` 量一下到底值不值得改）。

---

## 三、建议执行顺序（收益/风险排序，前 10）

排序原则：先做「不改变任何用户可见行为、只去掉白干的活」的项；凡是需要负责人试听/试用的往后放。

| 顺序 | 项 | 为什么这个位置 | 依赖 |
|---|---|---|---|
| 1 | **W-01 + W-02** | 收益最高、风险最低、改动最局部；直接决定进程能不能静止。做完就能用 M1 看到「电源使用情况」变化 | 无 |
| 2 | **W-05** 首页虚拟化 | 收益高（首帧/滚动/内存三处）；风险中但改动模式在仓库里已有先例（`ListView.Header`） | 无 |
| 3 | **W-06** 卡片重子树门控 | 收益高；风险中（展开第一帧不能空、焦点与命中测试不能坏） | 建议在 W-07 之前做，因为门控会减少 W-07 的触发面 |
| 4 | **W-07** `LayoutUpdated` 瘦身 | 帧时间的主要来源之一；改了 W-06 之后再量更准 | W-06 |
| 5 | **W-08** `x:Phase` + 图片缓存 | 滚动顿挫的常见根因；风险低；`DecodePixelType.Logical` 的用法保持不动即可 | 无 |
| 6 | **W-23** 状态落盘防抖 | 每次换歌都在写整队 JSON；风险低；与播放语义无关（只是写盘时机） | 无 |
| 7 | **W-03** 歌词计时器下限 | 只影响歌词页；16ms 下限在密集歌词里等于 60Hz 定时器 | 无 |
| 8 | **W-12** 启动同步 IO | 首帧路径上的同步凭据读；风险中（登录态时序） | 无 |
| 9 | **W-15 + W-16** 网络去重与 uid 缓存 | 直接减少往返与写盘；风险低；`_pending` 对账机制已存在 | 无 |
| 10 | **W-04** 均衡器链路（先量后改） | 省电相关但**必须实测确认**再动；改错会影响音质与播放 | 先跑 M10 |

不在前 10 但值得排进计划：W-09（歌词面板池化）、W-10（队列增量更新）、W-19（关登录层停轮询，
是「关掉了还在跑」的正确性问题）、W-21（图片尺寸）、W-24（搜索历史读放大）、W-18（登录层连接池）、
W-13（诊断日志），后三项都是风险低、可以顺手做掉的。

> 排序说明：W-04、W-17、W-21 涉及**取舍**（省电 vs 音质 / 衔接稳定性 / 封面清晰度），
> 它们不是「收益低」而是「先要你拍板」，见第九节。

---

## 四、代码里已确认的事实

不涉及收益判断，只是走查结论（可复核）。

**播放 / 后台**

- F-1 ticker 500ms、启动即 `Start()`、全仓无 `Stop()`（`Playback/PlaybackEngine.cs:41`、`:194-195`）。
- F-2 清单声明 `backgroundMediaPlayback`（`Package.appxmanifest:63`），最小化不进入挂起。
- F-3 `Suspending` 只完成 deferral，不落盘、不暂停（`App.xaml.cs:132-136`）。队列是每次变化就落盘的
  （`Playback/PlaybackHost.cs:130` 等），所以「挂起时丢队列」这件事不成立。
- F-4 全仓没有 `CompositionTarget.Rendering`；XAML 侧的常驻定时器只有三处：播放 ticker、歌词边界计时器、
  轻提示 2s 计时器（`Shell/ShellPage.xaml.cs:28`，用完即 `Stop()`）。
- F-5 歌词外推是「睡到下一行时间戳」，`Interval` 下限 16ms、上限 1000ms
  （`Player/LyricsController.cs:191-199`）；`Active == false`（卡片收起或不在歌词页）时不启动
  （`Player/LyricsController.cs:186-189`、`Player/PlayerHost.xaml.cs:802-812`）。这一条设计是**对的**。
- F-6 队列视图只在可见时重建（`Player/QueuePresenter.cs:94-120`），不可见时只标脏。这条也是**对的**。
- F-7 播放卡片的所有形变都走 Composition 表达式 / `ScalarKeyFrameAnimation`，没有 Storyboard 改布局属性
  （`Player/PlayerHost.xaml.cs:148-150`、`:399-413`、`:779-789`）。符合「GPU 零重组」的意图。
- F-8 `MetroLyricsPanel` 的缩放/淡入走 Composition（`MetroLyricsPanel.cs:171-179`、`:251-266`），
  但换行时的颜色/定位仍是 XAML 属性（`ApplyState` / `ChangeView`）。

**网络**

- F-9 `HttpClient` 复用是好的：`AppServices.Http` 单例（`AppServices.cs:30`），`NcmHttp` 内部一个
  `HttpClient`（`Core/Net/NcmHttp.cs:32-45`），`UseCookies=false`（`:47-52`）。`PlaylistApi` 自己 new 的
  `SongApi` 共用同一个 `NcmHttp`（`Core/Api/PlaylistApi.cs:24`），不会多开连接池。
- F-10 每次 eapi 请求做一次 JSON 写 + AES-128-ECB 加密 + MD5 签名（`Core/Net/NcmHttp.cs:63-64`、
  `Core/Net/Crypto/EapiCrypto.cs`），weapi 还要一次裸 RSA（`WeapiCrypto.cs`）。量级是微秒~毫秒/请求，
  只出现在登录与相似歌曲回退路径上，**不建议**为它做优化。
- F-11 歌单详情用 `trackIds` + 批量 `song/detail` 补齐，批大小 500（`Core/Api/PlaylistApi.cs:16`、`:133-141`）。
- F-12 首页三块是 `Task.WhenAll` 并发（`Pages/HomePage.xaml.cs:65`），不是串行。
- F-13 启动期网络请求：首页 3 个 + 云库刷新 4 个 + 资料 1 个 + 更新检查 1 个（`ShellPage.xaml.cs:59-74`）。
- F-14 JSON 解析自研 DOM（`Core/Json/JsonValue.cs:39-86`，每个对象一张 `Dictionary`），解析发生在
  `ConfigureAwait(false)` 之后的线程池线程上，不在 UI 线程（Api 层统一如此）。**推测**：大响应
  （1000 首歌单）会产生可观的 LOH 分配，进而给 UI 线程带来 GC 暂停；这一点需要 M6/M8 证实。

**存储**

- F-15 大块数据走文件、小设置走 `LocalSettings`，与文档一致（`Platform/LocalFileStore.cs:9-12`、
  `Platform/LocalSettingsStore.cs:6-10`）。
- F-16 `PlaybackStateStore.SaveAsync` 每次全量序列化（`Core/Playback/PlaybackStateStore.cs:38-42`）。
- F-17 `LyricsCache` / `LibraryManager` 有内存镜像（`Core/Lyrics/LyricsCache.cs:44`、
  `Core/Library/LibraryManager.cs:71-75`），`SearchHistory` **没有**（每次整读整写，`SearchHistory.cs:185-245`）。
- F-18 `SettingsPage` 的缓存大小统计与清缓存是 `Task.Run` 里做的（`Pages/SettingsPage.xaml.cs:223-227`、
  `:248-254`），不阻塞 UI 线程。

**UI 结构**

- F-19 详情页：专辑 / 歌单用 `ListView.Header` 承载页头（`AlbumPage.xaml:14-25`、`PlaylistPage.xaml:14-25`），
  歌手页的页头固定在 `Grid` 行里、列表仍在有限高度行内（`ArtistPage.xaml:14-43`）—— 这三页的虚拟化结构没被破坏。
  但它们**都没有** `NavigationCacheMode`（`AlbumPage.xaml:1-7`、`ArtistPage.xaml:1-13`、`PlaylistPage.xaml:1-7`），
  所以每次进入都重建页面对象，而且**即使命中 `ContentCache` 也照样发一次刷新请求**
  （`Pages/AlbumPage.xaml.cs:44-51`、`Pages/ArtistPage.xaml.cs:35-42`、`Pages/PlaylistPage.xaml.cs:29-37`）。
  这是「重复请求」的另一处来源（与 W-15 同源，不在前 10 里，因为详情页是用户主动导航）。
- F-20 有 `NavigationCacheMode` 的页：Home / Search / Library = `Enabled`，Settings = `Required`
  （`HomePage.xaml:6`、`SearchPage.xaml:6`、`LibraryPage.xaml:6`、`SettingsPage.xaml:5`）。
- F-21 模板全部是 `x:Bind`（编译期绑定），没有任何 `{Binding}`（`Resources/Templates.xaml:28`、`:34`、`:52` 等）。
- F-22 四处 `Pivot`（播放卡片右栏、搜索页、音乐库页、歌手页）都是平台控件，
  未选择的页签内容是否已实例化**未验证**（见下面 H-5）。
- F-23 全仓无 `ItemsRepeater`、无 `IncrementalLoadingCollection`；分页靠
  `ContainerContentChanging`（`Pages/LibraryPage.xaml.cs:145-176`），这个用法是对的（虚拟化保留时才触发）。
- F-24 全仓**没有 `InfoBar`**；轻提示是 `ShellPage` 的 `NoticeHost`（`Shell/ShellPage.xaml:119-135`，
  与 `NavigationView` 同在 `Grid.Row=0`、`VerticalAlignment=Bottom`），它不参与相邻元素的排布，
  所以文案长短不会让页面重排；弹层一律用平台 `ContentDialog`
  （`Player/PlayerHost.xaml.cs:842-853`、`Pages/EqualizerPage.xaml.cs:266-291`、`Pages/SettingsPage.xaml.cs:87-99`）。
  唯一的小开销是每次提示都新建一个 `Storyboard`（`Shell/ShellPage.xaml.cs:130-153`），量级可忽略。
- F-25 Kanesumi 的按钮按下反馈是 `Opacity` Storyboard（`Kanesumi.Xaml/Themes/Controls/Buttons.xaml:27-51`），
  属独立动画，不占 UI 线程逐帧；全仓 `Kanesumi.Xaml` 里没有其它 Storyboard、
  也没有 `EnableDependentAnimation`（`grep` 已确认）。

**文档与代码不一致（只报告，本任务不改）**

- D-1 `windows/AGENTS.md:223` 说预载窗口靠 `MaxPrefetchTime` 对齐 60s —— 代码里没有这个 API 调用，
  `PreloadCandidate()` 是死代码（F 见 W-17）。
- D-2 `windows/AGENTS.md`「动画架构」第 4 条的 `x:Load` 门控、`:250-252` 的触屏拖拽 25% 阈值、
  `:319` 的 `COVER_HOLD_MS` 在代码里都没有实现；对应的常量
  （`KanesumiMotion.cs:37-39`）定义了但无引用。`PLAN.md:141-144` 把它们列为未完成。
- D-3 本报告只做了代码走查与静态检查，`windows/docs/KANESUMI_XAML.md` 没有逐条核对（不在审计面内）。

---

## 五、需要实机才能确认的假设

每条都写了「怎么证实/证伪」，都不影响 W-01/W-02/W-05/W-06 这些「行为性」结论（那些只看代码就成立）。

| 编号 | 假设 | 关联项 | 怎么证实/证伪 |
|---|---|---|---|
| H-1 | 空闲/暂停状态下进程仍有可测的 CPU 占用与电源使用量（ticker 的代价可见） | W-01、W-02 | M1：A/B/C 三状态各 5 分钟，比较「电源使用情况」与 `\Energy Meter(*)\Power` 均值；M7 看 `% Processor Time` 的波形是否 2Hz 锯齿 |
| H-2 | 首页的容器数量等于数据条数（真的没有虚拟化） | W-05 | M8 实时可视化树数 `ListViewItem` 个数；或临时把新歌改 200 条看内存/首帧是否线性恶化 |
| H-3 | 收起状态下播放卡片的 2Hz 写入会造成可测的帧提交/布局 | W-06、W-07 | M5：收起静止 30 秒，看是否有周期性 composed frame；M8 数元素数 |
| H-4 | 均衡器「挂着但关闭」比「根本不挂」更费（float32 管线 / 格式协商） | W-04 | M10：同一首歌、同一音量，均衡器关闭 vs 卸载音效（需要一版临时构建）各采 5 分钟 M7；也看 M2 的「电源请求」与进程 CPU |
| H-5 | 未选中的 `PivotItem` 内容未被实例化（影响卡片右栏与搜索页的实际元素数） | W-06、W-11 | M8 实时可视化树：切到「歌词」页签时看「播放队列」的 `ListView` 是否已在树上 |
| H-6 | 密集歌词段落会让边界计时器跑到 ~60Hz | W-03 | M6 + 「线程时间」看 `Ncrust` 主线程在歌词页每秒被唤醒次数；或 M7 看 `% Processor Time` 的尖峰密度 |
| H-7 | `MediaPlaybackList` 会在当前曲一开始播就预取下一首（网络/无线一直在忙） | W-17 | M6 `Network` profile 看播放中的持续流量与请求时刻；关掉「无缝播放」对比曲线 |
| H-8 | WebView2 进程随登录层关闭而退出 | W-20 | M9 进程树：打开登录层（网页登录）→ 关闭 → 观察 `msedgewebview2.exe` |
| H-9 | 首页内存里同时存在数十张解码后的位图（量级 ≈ 40MB @200%） | W-21 | M9 任务管理器内存 / M8 内存快照按类型统计；对比「只在首页停 1 分钟」与「滚动到最底」的差值 |
| H-10 | 缓存页（Home/Search/Library）离树后仍持有已实例化的可视树与位图 | W-22 | M8 内存快照 A/B：一次访问首页 vs 首页→音乐库→首页来回 10 次，看内存是否阶梯上涨（真泄漏上涨不回头） |
| H-11 | 大响应（1000 首歌单）的 DOM 解析会造成可感知的 GC 暂停 | F-14 | M8 诊断工具的 GC/内存视图；或 M6 看 GC 事件与 UI 线程停顿的对齐关系 |
| H-12 | `\Energy Meter(*)\Power` 在本机能稳定读出整机功耗（用于 W-04 的 A/B） | W-04 | 先跑一次 M7 的采样脚本，确认计数器有非零值且随负载变化 |

---

## 六、测量步骤草案（PowerShell，只写在报告里，不新建可执行文件）

> 说明：
> - 全部输出写到 `$env:TEMP\perf-audit\`，**不落在仓库里**。
> - 采样本机（Windows 11 build 26200，笔记本，有电池）实测过的可用计数器：
>   `\Energy Meter(*)\Power`、`\Power Meter(*)\Power`、`\Processor Information(_Total)\% Processor Utility`、
>   `\Process(*)\% Processor Time`（`Get-Counter` 语法已验证）。
> - **不要**在这些脚本里使用真实登录 cookie；需要登录态的场景（云库、红心）由人工点击完成，
>   脚本只负责采样。
> - 本机 `wpr.exe` 可用（`C:\Windows\system32\wpr.exe`，CoreSystem 版），
>   **但没有 WPA（`wpa.exe`/`xperf.exe` 都不在）**，只有 `tracerpt.exe`。
>   要看得懂 ETL 的帧分析表，需要另外装「Windows Performance Toolkit」（VS 安装器里勾
>   「Windows Performance Toolkit」组件，或独立的 Windows SDK）。这一点会直接决定 M5 能不能用。

### 0. 准备：启动被测应用（沿用 `windows/AGENTS.md` 的命令，人工执行）

```powershell
# 需要先开启 Windows 开发者模式。构建产物路径见 windows/AGENTS.md。
Add-AppxPackage -Register windows\src\Ncrust.App\bin\Release\ilc\AppxManifest.xml
explorer.exe "shell:AppsFolder\TakahashiRinta.Ncrust_98kk3q0vty278!App"

# 启动时间点（本报告作者没有执行；由执行者跑）
Get-Content "$env:LOCALAPPDATA\Packages\TakahashiRinta.Ncrust_98kk3q0vty278\LocalState\boot.log"
```

### 1. perfmon 采样（M7）—— 最通用的「前后对比」工具

```powershell
$out = Join-Path $env:TEMP 'perf-audit'
New-Item -ItemType Directory -Force -Path $out | Out-Null

# 进程不在运行时，带实例名的计数器会整组报错 —— 先确认进程存在
Get-Process Ncrust -ErrorAction Stop | Select-Object Id, ProcessName

# 按进程名筛选（Packaged App 的进程名就是 Ncrust.exe）
$counters = @(
  '\Process(Ncrust)\% Processor Time',
  '\Process(Ncrust)\Working Set - Private',
  '\Process(Ncrust)\IO Read Bytes/sec',
  '\Process(Ncrust)\IO Write Bytes/sec',
  '\Process(Ncrust)\Thread Count',
  '\Process(Ncrust)\Handle Count',
  '\Processor Information(_Total)\% Processor Utility',
  '\Energy Meter(*)\Power'
)

# 场景：先手工把应用摆成待测状态（空闲 / 暂停最小化 / 播放最小化），再跑这一段
$samples = Get-Counter -Counter $counters -SampleInterval 2 -MaxSamples 150   # 约 5 分钟
$samples.CounterSamples |
  Group-Object Path |
  ForEach-Object {
    [pscustomobject]@{
      Counter = $_.Name
      Avg     = [math]::Round(($_.Group | Measure-Object CookedValue -Average).Average, 3)
      Max     = [math]::Round(($_.Group | Measure-Object CookedValue -Maximum).Maximum, 3)
    }
  } | Format-Table -AutoSize

# 存原始样本，便于事后画曲线
# 若 Export-Counter 报「无法解释计数器路径」，跳过这一步即可：上面的均值表已够做 A/B
$samples | Export-Counter -Path (Join-Path $out 'idle-5min.blg') -Force
```

判据（**假设值，先按自己机器建立基线**）：空闲/暂停状态下 `% Processor Time` 均值应当接近 0；
如果看到固定的 2Hz 锯齿，W-01 就成立。

### 2. 系统电源视角（M2 / M3 / M4，均需管理员）

```powershell
# 60 秒能耗报告：看「电源请求」里是谁在阻止系统进入低功耗，以及每进程 CPU
# 默认把 energy-report.html 写到**当前目录**，所以先切到输出目录
Set-Location "$env:TEMP\perf-audit"
powercfg /energy /duration 60

# 当前有哪些电源请求（哪个进程在持有显示/系统请求）
powercfg /requests

# SRUM：每进程的能耗估算（Energy Usage）与后台 CPU 时间。
# 注意：本机 `powercfg /?` 只列出了 /SRUMUTIL，没有列出 /output 与 /csv；
# 网上文档里的 `powercfg /srumutil /output x.csv /csv` 请先按本机帮助确认参数再拼。
powercfg /srumutil

# 电池报告（整机对账用，不区分进程）
powercfg /batteryreport /output "$env:TEMP\perf-audit\battery.html"
```

### 3. WPR 抓帧与 CPU（M5 / M6）

```powershell
# 先确认 profile 名（本机输出里确认存在：CPU / GPU / Power / FileIO / Network /
# XAMLActivity / XAMLAppResponsiveness / DotNet / ReferenceSet / XAMLHTMLAppMemoryAnalysis）
wpr -profiles

# 帧分析：等应用起来并进入待测交互后执行
wpr -start XAMLAppResponsiveness
# ... 在这里做：展开/收起播放卡 10 次、首页滚动 10 秒、换歌 5 次 ...
wpr -stop "$env:TEMP\perf-audit\xaml.etl"

# CPU / GPU / 文件 / 网络（多 profile 用多个 -start，语法见 wpr -help start）
wpr -start CPU.light -start GPU -start FileIO -start Network
# ... 同上一段交互 ...
wpr -stop "$env:TEMP\perf-audit\cpu.etl"
```

没有 WPA 时的兜底（只能拿到统计，拿不到帧分析表）：

```powershell
# ETL → CSV + 摘要（不需要额外安装）
tracerpt "$env:TEMP\perf-audit\cpu.etl" -o "$env:TEMP\perf-audit\cpu.csv" -of CSV -summary "$env:TEMP\perf-audit\cpu-summary.txt"
Get-Content "$env:TEMP\perf-audit\cpu-summary.txt" | Select-Object -First 60
```

> 我**没有**跑过上面任何一条 WPR 命令（本工作区没有部署好的应用，也就没有可采样的场景）。
> 命令形态已按本机 `wpr -help start` 与 `wpr -profiles` 核对。

### 4. 任务管理器（M1）人工检查清单

1. 「进程」页展开 Ncrust 的进程树，确认有几个进程（W-20：`msedgewebview2.exe` 是否在列）。
2. 打开「电源使用情况」「电源使用趋势」两列，记录三状态（空闲 / 暂停最小化 / 播放最小化）各 5 分钟的值。
3. 「详细信息」页看 GPU 引擎与「专用 GPU 内存」在收起状态下的变化（W-06 / W-11）。
4. 三次「首页 ↔ 音乐库」来回切换，观察内存是否阶梯上涨（H-10）。

### 5. Visual Studio 诊断工具（M8，需要另装 VS 2022）

本轮**无法执行**（本机只有 Build Server，没有 `devenv.exe`）。装好后：

1. 以「调试 → 附加到进程」附加 `Ncrust.exe`（UWP 需要「调试 → 其他调试目标 → 附加到进程」里选托管 + 本机）。
2. 性能探查器：CPU 使用率（看 `OnTick`/`OnProgressChanged`/`LayoutUpdated` 的占比）、
   内存使用率（快照对比：打开首页 / 来回切换 / 关掉登录层）。
3. XAML 实时可视化树：数收起状态下的卡片子树元素数（W-06）、首页容器数（W-05）、
   未选中 `PivotItem` 是否已实例化（H-5）。
4. 实时属性资源管理器：看 `PlayerHost` 元素上的 `Width/Height/Translation` 在静止时是否仍被写（W-07）。

---

## 七、本机工具现状（影响可执行的测量项）

| 工具 | 状态 | 影响 |
|---|---|---|
| `wpr.exe` 10.0.26100（CoreSystem） | 有 | M5/M6 可以抓 ETL |
| `wpa.exe` / `xperf.exe` | **没有** | ETL 只能靠 `tracerpt` 出统计；帧分析表需要装 Windows Performance Toolkit |
| `perfmon.exe` / `Get-Counter` | 有 | M7 立即可用 |
| `powercfg.exe` | 有（`/energy`、`/srumutil` 需管理员） | M2/M3/M4 可用 |
| `tracerpt.exe` | 有 | ETL 兜底解析 |
| VS 2022（`devenv.exe`） | **没有**（只有 BuildTools） | M8 的 CPU/内存/XAML 可视化树全套需要另装 |
| 电池 | 有（`Win32_Battery` = 1） | M1「电源使用情况」、M3 SRUM、M4 batteryreport 都有意义 |
| 操作系统 | Windows 11 家庭版 build 26200 | 任务管理器有「电源使用情况/趋势」列 |

---

## 八、没做的 / 没验证的

- **没有跑任何实机测量**：本工作区没有部署好的应用、没有登录态，任务书也禁止使用真实 cookie。
  所有「收益」是定性判断，量级性的说法（内存几十 MB、CPU 占比）都标了【推测】并给了测法。
- **没有构建**：没跑 MSBuild（`.NET Native` 构建需要独占应用目录、本任务不需要产物）。
  因此「代码能编译」这件事本轮也没验证 —— 但本任务没有改动任何代码，编译状态不受影响。
- **没有核对 Kanesumi.Xaml 的全部主题/样式文件**（`Themes/Controls/Buttons.xaml` 的按下动画是
  Opacity Storyboard，属独立动画，已确认；`Typography.xaml`/`Colors.xaml`/`Navigation.xaml` 只做了
  关键点抽查，没有逐行审）。
- **没有审计 `windows/tests/`**（Core 单测不参与运行时性能；`DynamicBassProcessor` 只有单测引用，
  应用侧没有接线，属于「已实现的未用代码」，不在性能审计的改动范围）。
- **`dolby` / `jyeffect` 在 Windows 上能否解码**这一条 `PLAN.md:171` 的待办与功耗/流畅度都有关
  （解码不了会走降级重试，每次失败都是一次网络往返 + 重建条目），本轮**没有实测**，
  也不在审计面里，只在此提示它与 W-17 的预载开销会叠加。
- **`spec/` 没有改动**，本次审计没有发现需要改规格的地方。

## 九、需要你（负责人）拍板的点

1. **W-04 均衡器链路**：是否允许「关闭均衡器时干脆不挂音效」？
   代价是切换开关时音频管线要重建（可能有极短静音/爆音风险），收益是省电。
   **建议**：先按 M10 实测，确认收益真实存在再决定（这条我给不出「测过」的结论）。
2. **W-21 封面解码尺寸**：`AGENTS.md` 明确要求封面「任何状态下都清晰」。
   如果为了内存把预载封面降码，需要你接受「全屏状态下略微糊」——**这是取舍，不是 bug**。
3. **W-17 预载时机**：是否要在「计费网络」上把预载推迟到曲尾？
   代价是无缝衔接在弱网下可能失败（现在窗口制的衔接更稳）。
4. **W-05 首页改造后的滚动视觉**：单个 `ListView` 承载全部分区会改变滚动条行为与「分区吸顶」观感，
   属于桌面端美术决策。
5. **是否装 Windows Performance Toolkit / VS 2022**：决定 M5/M8 能不能用。
   不装也能做（M1/M7 + `tracerpt`），但帧分析只能看统计，看不到逐帧归因。

## 十、下一步

按第三节的顺序从 **W-01 + W-02** 开始：先在实机上跑第六节第 1 步的 perfmon 采样，
拿到「空闲 / 暂停最小化 / 播放最小化」三组基线（这一步不改代码，10 分钟就能做完），
再决定 ticker 门控的粒度（是「完全停」还是「降到 5s 心跳」）——用实测数字定这个粒度，
不要凭感觉选。
