# Kanesumi.Xaml —— Kanesumi 的 XAML 扇区

> 正典：Ether monorepo `KANESUMI_DESIGN.md`。数值来源：`Kanesumi-sec-a`（Android / Compose 扇区）
> 与 `spec/design/tokens.json`。本文只定义**移植方式与落差**，不复述设计语言本身。

## 定位

Kanesumi 在 Android 上是「Compose 之上、Material 之下」的一层：文本、布局、手势、
无障碍交给 Compose，Kanesumi 负责设计语言与组件。**Kanesumi.Xaml 是同一层在 UWP 上的
对应物**：文本、IME、滚动、虚拟化、键盘导航、UI 自动化交给 XAML 框架，
Kanesumi.Xaml 负责 token、样式、少量自定义控件。

目标是在**语言层面**与 Android 端同源（直角、token 色 / 字 / 动效、控件手感同一套），
**但 Windows 允许做新设计**：桌面端以美观为先，布局与视觉可以明显不同于 Android 手机版，
不追求逐像素对齐。Kanesumi.Xaml 提供 token 与基础控件，Windows 在其上自行演进。

孵化位置：`windows/src/Kanesumi.Xaml/`（UWP 类库）。等 arc-deck 等其他 UWP 项目需要时，
再抽到 Kanesumi 仓库 —— 与当年 Metro 组件先在 Ncrust 里长成、再迁入 Kanesumi-sec-a 的路径一致。
**Kanesumi.Xaml 不得引用 Ncrust 的任何代码。**

## 移植原则

1. **优先照搬 sec-a / `tokens.json`**（dp / sp 与有效像素 1:1）。但 **Windows 专属的桌面数值
   可以另设**，写进 `windows/` 自己的文档，不进 `tokens.json`（spec 只收录已落地值的原则不变）。
   同一个控件如果要为桌面重新设计视觉，先改本文的对照表再写代码。
2. **优先「平台控件 + 重写样式/模板」，其次才是自定义控件。** 平台控件自带键盘导航、
   UIA 模式、虚拟化、高对比度适配，重写模板保留这些；只有平台没有对应物时才写
   `Control` 派生类。
3. **动画只走独立动画或 Composition。** 允许的：`Opacity`、`RenderTransform`、
   `Projection` 上的 Storyboard（由合成线程执行），以及 `Windows.UI.Composition`。
   **禁止依赖动画**（`Width` / `Height` / `Margin` 等，`EnableDependentAnimation`
   永远不开）。这是 Android「只在 `graphicsLayer` 里读动画值」的 XAML 版。
4. **颜色全部来自 Kanesumi token，且不透明（`pressTint` 等叠加色除外）。**
   不引用 `SystemControl*` 等系统画刷，不依赖亚克力或半透明底。
   arc-deck 实测过：窗口背景在材质混合后是 `#808080` 中灰，平台半透明文字色对比度只有 1.00。
   **例外：强调色**跟随 Windows 系统强调色（见下「运行时主题」），平台控件直接用 SystemAccentColor。
5. **圆角全局归零。** `ControlCornerRadius`、`OverlayCornerRadius` 在应用资源层覆盖为 `0`；
   模板里写死的 `CornerRadius` 也要逐个查。唯一例外同 sec-a：进度环端帽保留圆头。
6. **无阴影、无 Reveal、无亚克力。** Flyout / Menu / Dialog 的默认阴影关掉。
7. **只用 WinUI 2 / Windows SDK 里真实存在的资源键。** `{ThemeResource}` 引用不存在的键
   **不会报错**，只会静默失效。WinUI 3 的键（如 `TextFillColorSecondaryBrush`、
   `BodyStrongTextBlockStyle`）在 WinUI 2 里不存在。新增资源引用后跑
   `ResourceAudit.ps1`（从 arc-deck `uwp/tools/shot/` 移植）。

## 资源结构

```
Kanesumi.Xaml/
├── Themes/
│   ├── Generic.xaml          # 合并入口；自定义控件的默认样式
│   ├── Colors.xaml           # ThemeDictionaries: Dark / Light，全部来自 tokens.json
│   ├── Typography.xaml       # TextBlock 样式：语义轴 + 尺度轴
│   ├── Overrides.xaml        # CornerRadius 归零、FocusVisual、滚动条等平台覆盖
│   └── Controls/*.xaml       # 按控件族分文件：Buttons / Misc / Navigation
├── Motion/
│   ├── KanesumiEasing.cs     # tokens.json easing → CubicBezierEasingFunction / KeySpline
│   └── KanesumiMotion.cs     # tokens.json motion → 时长常量 + 常用 Composition 动画工厂
├── Controls/                 # 自定义控件（见下表「自定义」列）
└── KanesumiAccent.cs         # 强调色跟随 Windows：FollowSystem / Apply
```

**平台控件优先**（负责人 2026-09-26 决定）：能用 WinUI / 平台原生控件的就用原生，只做资源键级覆盖，
不再自绘替代品。已撤掉的自绘件：ListView 侧栏（→ NavigationView）、ListView Tab 行（→ Pivot）。

**运行时主题**：明暗 3 模式（系统 / 深 / 浅）；强调色**跟随 Windows**。

- 明暗：设置根元素的 `RequestedTheme`，`Colors.xaml` 的 ThemeDictionaries 自动切换；
  「跟随系统」监听 `UISettings.ColorValuesChanged`。（尚未实现，目前固定深色。）
- 强调色：**不覆盖** `SystemAccentColor`，平台控件（NavigationView 选中条、Pivot 下划线、焦点、滑块、
  文本框）直接跟随「设置 → 个性化 → 颜色」里的强调色（Windows 10 / 11 都有）。
  `Colors.xaml` 里的 `KPrimaryBrush` 是**单一共享的 `SolidColorBrush` 实例**，`KanesumiAccent.FollowSystem`
  在启动时把它设成 `UISettings.GetColorValue(UIColorType.Accent)`，并订阅 `ColorValuesChanged` 实时跟随；
  读不到时回落内置云杉 `#1DB954`。`KOnPrimaryBrush` 按相对亮度（>0.5）选黑 / 白。
  原计划的「6 强调色预设」在 Windows 上不再做（系统强调色本身就可选任意颜色）。
- 标题栏按钮颜色随主题同步。

**WinUI 2 样式版本**：应用以 `XamlControlsResources ControlsResourcesVersion="Version1"` 合并 WinUI 2，
即 Windows 10 / Groove 一代的直角样式。Version2（Windows 11）自带圆角与浮起的中灰内容面板，
与 Kanesumi 的直角、纯黑底冲突；Version1 再配上本库的资源覆盖即可。

**字体**：跟随系统 UI 字体（与 Android 一致，不打包字体）。
**图标**：用 **Segoe MDL2 Assets**，在 `FontIcon` 上显式指定 `FontFamily`（Windows 11 上默认的
`SymbolThemeFontFamily` 是 Segoe Fluent Icons，个别码点语义不同）。Windows 端以 Groove Music 为参考，
用系统原生图标字体；原设想的「打包 Material Icons 与 Android 统一字形」已撤回。

## 交互反馈：MetroIndication 的 XAML 版

sec-a：按下时叠加 `pressTint` 矩形，alpha 0→1 用 100ms `metroCubic`，松手 1→0 用 200ms。
无圆角、无涟漪。

XAML：可点击控件的模板里放一个铺满的 `Rectangle x:Name="PressTint"`
（`Fill = KPressTintBrush`，`Opacity = 0`），用 `VisualStateManager` 在 `Pressed` / `Normal`
之间以 `VisualTransition` 驱动 `Opacity`（独立动画）。

**桌面新增的状态**（Android 没有，需回写正典）：

| 状态 | 规则 |
|---|---|
| 悬停 PointerOver | 同一个 `PressTint`，`Opacity = 0.5`，进入 100ms / 离开 200ms，曲线同上 |
| 键盘焦点 | 用平台 FocusVisual，改为直角：`FocusVisualPrimaryBrush = onBackground`、`FocusVisualSecondaryBrush = background`，粗细 2 / 1，`FocusVisualMargin = 0` |
| 选中（多选等） | `surfaceVariant` 底 + 左侧 3px `primary` 竖条（沿用 SelectorFlyout 的选中标记） |

## 控件对照表

「基底」列：**样式** = 重写平台控件的 Style / Template；**自定义** = 新写 `Control` 派生类。

### 层 0 · core

| sec-a | Kanesumi.Xaml | 基底 | 关键数值 | 阶段 |
|---|---|---|---|---|
| `MetroTheme` / `MetroColors` | `Colors.xaml` + `KanesumiTheme` | 资源 | 见 tokens `color` | M1 |
| `MetroTypography` | `Typography.xaml`：`PageHeadingTextStyle` … `BodySmallTextStyle` | 样式 | 见 tokens `typography`，每个样式都设 `LineHeight` + `LineStackingStrategy=BlockLineHeight` | M1 |
| `MetroText` / `MetroIcon` | `TextBlock` 样式 / `FontIcon`（Segoe MDL2 Assets） | 样式 | 图标 24，导航图标 22 | M1 |
| `MetroIndication` | 模板部件 `PressTint` + VisualStates | 样式约定 | 见上节 | M1 |
| `MetroInsets` / `MetroBottomStack` | 不移植。Windows 没有刘海或手势条；Ncrust 的播放栏占外壳独立的一行，内容区不被遮挡，不需要底部叠层内边距 | — | — | — |

### 层 1 · anim

| sec-a | Kanesumi.Xaml | 说明 |
|---|---|---|
| `UwpEasing` / `MetroDefault` / `MetroCubic` | `KanesumiEasing` | Quadratic 与 Cubic EaseOut 都能**精确**表示为三次贝塞尔（见 tokens `easing`），不必做近似 |
| `SokuouPresets` / `SokuouTweens` | `KanesumiMotion` | 只移植 tween 预设。**弹簧预设不移植**：Ncrust 不用弹簧 |
| `mapRange` / `mapRangeClamped` | Composition 表达式里的 `Lerp` / `Clamp` | — |
| `MetroFling` / `MetroScroll` / `metroViewConfiguration` | **不移植** | Android 上是为了把 Compose 的滚动手感调成 Metro 味；UWP 的 `ScrollViewer` 本身就是 Metro 手感的源头 |

### 层 3 · controls

| sec-a | Kanesumi.Xaml | 基底 | 关键数值 / 行为 | 阶段 |
|---|---|---|---|---|
| `MetroSurface` | `Border` + `KSurfaceBrush`；可点击时用 `MetroSurfaceButtonStyle` | 样式 | 直角、无边框 | M1 |
| `MetroButton` | `MetroButtonStyle`（主）/ `MetroSecondaryButtonStyle` / `MetroGhostButtonStyle` | 样式 · `Button` | 内边距 16×12，图标 18 + 间距 8；禁用时整体 alpha 0.4；正文 `body` | M1 |
| `MetroIconButton` | `MetroIconButtonStyle` | 样式 · `Button` | 默认 48×48，无背板；可按场景覆盖为 40 / 44（对应 FullPlayerControls 的 compact） | M1 |
| `MetroListRow` | `MetroListViewItemStyle` + 行模板约定 | 样式 · `ListViewItem` | 左 0 右 16、上下 8；前后元素间距 12；标题 `body`，副标题 `caption` + `onSurfaceVariant`。**关掉** WinUI 2 的圆角选中指示条与项圆角（具体属性以 WinUI 2.8 的 `ListViewItem` 模板为准，写样式前先查模板源码） | M1 |
| `MetroDivider` | `MetroDividerStyle`（`Rectangle` 高 1） | 样式 | `divider` 色；只做水平方向 | M1 |
| `MetroTabRow` | **平台原生 `Pivot`** | 原生（不覆盖） | sec-a 的 MetroTabRow 本身就是模仿 UWP Pivot 的，在 Windows 上直接用 Pivot（Groove「我的音乐」同款）：选中下划线跟随系统强调色，键盘 / 触屏滑动 / UIA 由平台提供。**修订**：曾用重写 ListViewItem 的 `MetroTabRowStyle` 自绘，负责人实测体验差，已删除 | M1 ✅ |
| `MetroProgressIndicator` | `MetroProgressRing` | 自定义 | 36 / 描边 3，270° 弧，圆头端帽，Composition `RotationAngle` 线性 1s 一圈。不重写平台 `ProgressRing`：WinUI 2 的实现是 Lottie 动画，改不动弧形 | M1 |
| `MetroTextField` | `MetroTextBoxStyle`；搜索框用 `MetroAutoSuggestBoxStyle` | 样式 · `TextBox` / `AutoSuggestBox` | `surfaceVariant` 底，内边距 12，无边框、无聚焦下划线、无圆角；占位符 `onSurfaceVariant`。光标色若平台不可配则接受默认 | M1 |
| `MetroSwitch` | `MetroToggleSwitchStyle` | 样式 · `ToggleSwitch` | 轨道 52×28 直角，滑块 22 宽、内边距 3；关态轨道 `onSurfaceVariant@0.45` + `divider` 描边，开态 `primary`；滑块恒为 `onPrimary`；220ms `metroCubic`。拖拽释放判定用平台逻辑（sec-a 是「位移 < 15% 视为点击」），M2 实测后再决定是否需要自绘 | M2 |
| `MetroSelectorFlyout` | `MetroComboBoxStyle` | 样式 · `ComboBox` | sec-a 这个控件**本来就是照 UWP ComboBox 移植的**（打开时选中项落在锚点原位），在这里回归原生。项高 44，最大 320×400（`MaxDropDownHeight`）；选中项左侧 3px `primary` 竖条，未选中项透明占位，保证文字左缘不跳 | M2 |
| `MetroDropdownMenu` | `MetroMenuFlyoutPresenterStyle` + `MetroMenuFlyoutItemStyle` | 样式 · `MenuFlyout` | `surfaceVariant` 底，最大宽 220，项内边距 16×12，前后元素间距 12；直角、无阴影。打开动画（sec-a：180ms 淡入 + scaleY 0.92→1）平台自带，M2 评估能否替换，不能就接受平台动画 | M2 |
| `MetroDialog` | `MetroContentDialogStyle` | 样式 · `ContentDialog` | 宽 280，`surface` 底，直角，默认无按钮行（菜单列式：一列直角行 + 分隔线）。保留 ContentDialog 的遮罩层、焦点陷阱与 Esc 关闭 | M2 |
| `MetroBottomSheet` | **不移植** | — | 桌面的歌曲菜单用右键 / 长按 `MenuFlyout`，与窗口宽度无关 | — |
| `MetroLyricsPanel` | `MetroLyricsPanel` | 自定义 | 见下节 | M1 |
| `MetroDrawer` / `MetroChatInputBar` | **不移植** | — | Ncrust 用不到 | — |

### 层 2 · structure

| sec-a | Kanesumi.Xaml | 基底 | 关键数值 / 行为 | 阶段 |
|---|---|---|---|---|
| `MetroSidebar` | **WinUI 2 `NavigationView`** + `Themes/Controls/Navigation.xaml` 资源覆盖 | 样式（只覆盖资源键，不重写模板） | 标准汉堡菜单，自适应展开 / 紧凑 / 最小；面板宽 240（sec-a 默认值）。覆盖：展开面板与页面同为 background、浮出面板用 surface（都不透明，不用亚克力）；内容区无边框、无圆角、透明；悬停 / 按下用 pressTint（悬停 0.5 倍）替代 Reveal；选中无底色，只有 primary 竖条；文字不透明。**修订**：原计划自绘 ListView 侧栏并「不用 NavigationView」，理由是 arc-deck 里的圆角中灰内容区 —— 那是 WinUI 2 **Version2** 样式；改用 `ControlsResourcesVersion="Version1"` + 资源覆盖即可解决，而负责人要求的是标准汉堡菜单。旧的 `MetroSidebarStyle`（ListView）已随 Lists.xaml 删除 | M1 ✅ |
| `MetroBottomNav` | **不移植** | — | 窄窗口由 NavigationView 最小模式（汉堡按钮 + 浮出面板）覆盖，不另做底部导航 | — |
| `MetroDetailScaffold` | `MetroDetailScaffold` | 自定义 | 三态（加载 / 错误 / 内容）交叉淡化；内容入场：淡入 + 上移 12，220ms `metroDefault`；`HasCachedContent = true` 时跳过加载态（对应 Ncrust 的 ContentCache 模式） | M1 |
| `MetroTopScrim` | `MetroDetailScaffold` 的模板部件 | — | 高 120，黑色 alpha 0.55 → 0 渐变；悬浮返回按钮 48，默认无按压反馈 | M1 |
| `MetroShell` / `MetroAppBar` | **不移植** | — | 外壳由 Ncrust.App 的 `ShellPage` 实现；Ncrust 用 34px 页头，不用顶栏 | — |

## MetroLyricsPanel（XAML 版）

行为照搬 sec-a 与 Ncrust `LyricsView`：

- 左对齐，32 / 42 粗体；翻译行 20 / 26。当前行 `primary`，已唱行 `#99FFFFFF`，未唱行 `#66888888`
  （颜色离散切换，跨行时一次性翻转）。
- **缩放连续**：一个共享标量 `SmoothIndex` 放在 `CompositionPropertySet` 里，跨行时用 180ms
  `fastOutSlowIn` 从旧行号滑到新行号。每一行的 Visual 绑定同一个 `ExpressionAnimation`：
  `Scale = Lerp(1, 0.82, Clamp(Abs(index - P.SmoothIndex) / 1.8, 0, 1))`，缩放中心在左侧中点。
  这就是 sec-a「每行 `graphicsLayer` 读同一个 `Animatable`」的合成线程版，**逐帧零 UI 线程开销**。
- 当前行滚动到视口 36% 高处；用户手动滚动后暂停自动跟随 5s；面板出现时直接跳到当前行，
  不做滚动动画；点击某行跳转播放进度。
- 歌词从无到有、或切歌时，整个面板 `Opacity` 0→1，400ms `standard`。**淡入状态从歌词数据派生**，
  不靠一次性事件触发（sec-a `2bfd1cc` 修过「透明度卡在 0」的问题）。
- 提供外部强制定位入口（对应 sec-a `f2f315e`）：播放器展开时调用，把滚动位置拉回当前行。
- 当前行文本通过 UIA LiveRegion 播报，只在跨行时更新。

## 验收方式

每个控件落地时：

1. 深色 / 浅色 × 至少两个强调色（云杉、素白）截图，与 Android 同控件并排对比。
2. `Contrast.ps1` 核对文字对比度（从 arc-deck 移植）。
3. 键盘可达：Tab 能聚焦到，Enter / Space 能触发，焦点框是直角。
4. `ResourceAudit.ps1` 零未定义键。
