# Ncrust 演进路线图

> 本文件是 Ncrust 长期改造的规划文档，供"日后计划性完成"。每个阶段可独立提交、独立验收、独立回滚。
> 创建于 2026-07-30。维护方式：随阶段推进在对应章节标注状态（☐ 待办 / ☐ 进行中 / ☑ 完成）。
> **不维护进度日志**--进度看 git log，本文件只保留路线与原则。

---

## 一、设计哲学：Ncrust Style = 三位一体

Ncrust 的视觉血统不是"纯 Metro"，而是三个支柱的耦合：

| 支柱 | 含义 | 不可破坏的具象 |
|---|---|---|
| **Metro 骨架** | 直角、无阴影、信息优先、OLED 纯黑、Pivot 风导航 | 任何位置不得出现圆角、elevation、shadow；强调色单一且硬编码 |
| **无边框（Groove）气质** | list flush-to-edge、裸图标浮动、大字页头、tile wall 紧密间距 | SongCard 左侧 0dp 贴边；DetailScaffold 无 TopAppBar Surface；返回箭头裸图标 + 半透明黑方块；34sp 页头 |
| **GPU 零重组** | 动画走 `graphicsLayer` 单 progress 驱动，不触发重组 | 播放器卡片的所有动画值只在 `graphicsLayer {}` 块内读取；禁用 `animateFloatAsState` 驱动布局 |

**核心约束**：三支柱不是叠加，是耦合。任何改造都不能为了"纯 Metro"而破坏 Groove（例如引入圆角指示器、卡片背板、分隔线），也不能为了"纯 Metro"而破坏零重组（例如把 graphicsLayer 动画改回 state 驱动）。

**血统不纯的根源**：当前视觉层已经 Metro + Groove，但**组件骨架层**和**主题系统层**仍是 MD3。`ThemeManager.kt:68` 的 `MaterialTheme(colorScheme = darkColorScheme(...))` 是 MD3 的根--只要它在，全项目的颜色、字号、触控反馈就都来自 MD3 基础设施。

---

## 二、现状诊断

| 层 | 状态 | 关键证据 |
|---|---|---|
| 视觉层 | ☑ 已 Metro + Groove | 无 elevation/shadow、RectangleShape 普遍、Groove 页头、flush-to-edge |
| 组件层 | ⚠ 夹生 | `NavigationBar`、`TabRow`×3、`Surface`、`IconButton`、`TextField`、`CircularProgressIndicator` 全是 M3 |
| 主题层 | ☒ 仍 MD3 | `ThemeManager.kt:68` `MaterialTheme(colorScheme = darkColorScheme(...))`；40+ typography 引用、30+ colorScheme 引用 |
| 性能层 | ⚠ 部分 | R8 + Baseline Profile + zero-recomposition 已做；图片缓存/内存响应/线程模型未做 |

**MD3 残留清单（按严重度）：**

| 级 | 位置 | 性质 |
|---|---|---|
| P0 | `MainActivity.kt:750-795` NavigationBar + NavigationBarItem | M3 底部导航，圆角指示器 + ripple + 80dp 容器 |
| P0 | `ThemeManager.kt:68-76` MaterialTheme(colorScheme=...) | 主题骨架是 MD3 |
| P1 | `SearchScreen.kt:284`、`LibraryScreen.kt:117-134`、`ArtistDetailScreen.kt:154-160` TabRow+Tab | M3 选项卡，下划线 + ripple |
| P1 | `SongCard.kt:176`、`LibraryAlbumGridItem.kt:44` CircleShape | 圆形按钮是 FAB 残留 |
| P2 | `PlayAllDialog.kt:29`、`PlayerCard.kt:409` Surface | M3 容器，自带 tonalElevation 调色 |
| P2 | 全项目 40+ 处 `MaterialTheme.typography.*` | 依赖 MD3 排版规范 |
| P3 | 全项目 30+ 处 `MaterialTheme.colorScheme.primary` | 颜色来源是 MD3 colorScheme |
| P3 | 15+ 处 M3 IconButton、4 处 CircularProgressIndicator、1 处 TextField | M3 原子组件 |

**性能现状清单：**

| 项 | 状态 | 说明 |
|---|---|---|
| R8 minify + shrinkResources | ☑ 已做 | `build.gradle.kts:45-46` |
| Baseline Profile | ☑ 已做 | `profileinstaller:1.4.0` + `baseline-prof.txt` |
| 播放器卡片 zero-recomposition | ☑ 已做 | graphicsLayer 单 progress 驱动 |
| 图片缓存全局配置 | ☒ 未做 | 用 Coil 默认（内存缓存 25% 堆），无自定义 ImageLoader |
| onTrimMemory 响应 | ☒ 未做 | 内存紧张时不清缓存 |
| ContentCache 上限 | ☒ 未做 | 进程内无界 dict，低端机风险 |
| material-icons-extended 全量 | ☑ 待评估 | 影响 DEX 体积，但 R8 应已裁剪 |
| 线程模型 | ⚠ 粗放 | 全用 Dispatchers.IO，未区分 CPU/IO |
| largeHeap | ☑ 未开 | 保持不开，倒逼内存可控 |

---

## 三、改造原则

1. **三不破坏**：Groove flush-to-edge 不破坏、播放器卡片 graphicsLayer 动画不破坏、OLED 纯黑不破坏。
2. **支点优先**：主题系统重构是支点，做完后 typography/colorScheme 引用批量迁移，其余阶段自动收益。
3. **可独立验收**：每个阶段是一个 `refactor:` 或 `perf:` 提交，可单独回滚。不合并多阶段。
4. **不引入新依赖**：自定义实现替代 M3，不加 Compose 之外的库。`material-icons-extended` 可评估裁剪但不替换。
5. **性能与 UI 并行**：UI 改造（阶段 A-E）和性能优化（阶段 F-J）不冲突，可同时推进。但同一提交内不混做两类。
6. **Metro 直角优先于"纯 Metro 指示器"**：不为追求 Pivot 标识而引入会破坏 Groove 的视觉元素（如卡片背板、分隔线）。指示器用 2px 顶部细线或纯色变，不用容器。
7. **低端机基准**：所有性能验收以低端机（4GB RAM / 4 核 / Android 8+）为准绳，不以旗舰机为准。

---

## 四、UI 血统纯化路线图

### 阶段 A：主题系统重构（支点）  ☐ 待办

**目标**：去掉 `MaterialTheme(colorScheme = ...)` 包裹，用自定义 `CompositionLocal` 提供颜色与排版。

**做法**：
- 新增 `ui/theme/NcrustColors.kt`：`data class NcrustColors(primary, background, surface, surfaceVariant, onBackground, onSurface, onSurfaceVariant)`，由 `LocalNcrustColors`（CompositionLocal）提供。
- 新增 `ui/theme/NcrustTypography.kt`：`data class NcrustTypography(pageTitle, titleLarge, titleMedium, bodyLarge, bodyMedium, bodySmall, labelSmall)`，字号比 MD3 紧 10-15% 行高，由 `LocalNcrustTypography` 提供。
- 重写 `ThemeManager.kt` 的 `NcrustTheme`：不再调用 `MaterialTheme`，改为 `CompositionLocalProvider(LocalNcrustColors provides ..., LocalNcrustTypography provides ...) { content() }`。
- 全项目批量替换：`MaterialTheme.colorScheme.X` -> `LocalNcrustColors.current.X`；`MaterialTheme.typography.X` -> `LocalNcrustTypography.current.X`。

**涉及文件**：`ui/theme/ThemeManager.kt`（重写）+ 全项目 70+ 引用点。

**验收**：
- `grep -r "MaterialTheme.colorScheme" app/src/main` 为 0
- `grep -r "MaterialTheme.typography" app/src/main` 为 0
- `grep -r "MaterialTheme" app/src/main` 仅剩 `ui/theme/` 内部（若有的话）
- 6 种主题色切换正常（云杉/钴蓝/绯红/琥珀/堇紫/素白）
- OLED 纯黑背景不变
- 所有屏幕排版无错位

**风险**：排版字号微调可能破坏 Groove 页头与 tile 间距。需逐屏幕目检。

**不做**：不引入自定义 `TextStyle` 动画系统；不替换字体（保持系统无衬线）。

---

### 阶段 B：底部导航重写（Pivot 风）  ☐ 待办

**目标**：弃用 M3 `NavigationBar` + `NavigationBarItem`，改为 Pivot 风--顶部 2px 细线指示器 + 纯文字色变，无 ripple、无圆角、无 80dp 容器。

**做法**：
- 新增 `ui/components/NcrustPivotNav.kt`：`Row` + 4 个 `NcrustPivotTab`。
- `NcrustPivotTab`：`Column`（图标 24dp + 文字 12sp），顶部 2px 指示器用 `Box` + `Modifier.height(2.dp).width(24.dp).background(primary)`，仅选中态显示。无 `clickable` 的 ripple（用 `Modifier.clickable(indication = null)`）。
- 高度 56dp（与迷你播放栏对齐，遵循 `BottomOverlayInset`）。
- 保留 `MainActivity.kt:754-756` 的 `graphicsLayer { translationY = navBarHideOffset * progress.value }` 隐藏动画（这是零重组的，不能动）。
- 触控区 48dp 最小（用 `Modifier.minimumInteractiveComponentSize` 或手动 padding 保证）。

**涉及文件**：`MainActivity.kt:747-795`（替换 NavigationBar 块）+ 新增 `ui/components/NcrustPivotNav.kt`。

**验收**：
- 点击 tab 无 ripple 涟漪
- 选中态：图标 + 文字变 `primary`，顶部 2px 细线显示
- 未选中态：图标 + 文字 `onSurfaceVariant`
- 与迷你播放栏（56dp）对齐，无错位
- `graphicsLayer` 隐藏动画保留
- 触控区 ≥ 48dp

**风险**：`NavigationBarItem` 自带触控区比裸 `Box` 大，需手动保证 48dp。

---

### 阶段 C：Tab 组件重写  ☐ 待办

**目标**：弃用 M3 `TabRow` + `Tab`，改为 Metro 风--顶部 2px 细线指示器 + 纯文字色变，无下划线 ripple。

**做法**：
- 新增 `ui/components/NcrustTabRow.kt`：`Row` + N 个 `NcrustTab`。指示器用 `Box` + `Modifier.fillMaxWidth().height(2.dp).background(primary)` 跟随选中项（用 `Modifier.layoutId` + `onGloballyPositioned` 定位）。
- `NcrustTab`：纯文字 14sp，选中态 `primary` + 字重 Medium，未选中 `onSurfaceVariant` + 字重 Regular。无 ripple。
- 替换三处：`SearchScreen.kt:284`、`LibraryScreen.kt:117-134`、`ArtistDetailScreen.kt:154-160`。

**涉及文件**：3 个屏幕 + 新增 `ui/components/NcrustTabRow.kt`。

**验收**：
- 三处 Tab 视觉一致：顶部 2px 细线、纯文字色变、无 ripple
- 切换流畅，指示器滑动用 `tween(200, MetroDefault)`（Sokuou 系统）
- 无下划线、无背板

---

### 阶段 D：M3 原子替换  ☐ 待办

**目标**：替换所有 M3 容器与原子组件为自定义实现。

**逐项**：

| M3 组件 | 替换为 | 涉及位置 |
|---|---|---|
| `Surface` | `Box` + `Modifier.background(color)` | `PlayAllDialog.kt:29`、`PlayerCard.kt:409`、`SongMenuSheet.kt:73` |
| `IconButton` | `Box` + `Modifier.clickable(indication = null)`（复用 `DialogButton` 模式） | 15+ 处 |
| `Button` | 自定义 `Box` + `background(primary)` + `clickable` | `DetailScaffold.kt:88` |
| `CircularProgressIndicator` | 自定义 `Canvas` 旋转圆环，或 `LinearProgressIndicator` 横条（更 Metro） | `HomeScreen.kt:144`、`LibraryScreen.kt:240`、`DetailScaffold.kt:79`、`SearchScreen.kt:132` |
| `TextField` | `BasicTextField` + 自定义装饰（`SearchScreen.kt:116`） | `SearchScreen.kt:116-149` |
| `TabRow`/`Tab` | 阶段 C 已处理 | — |

**验收**：
- `grep -r "material3.Surface\|material3.IconButton\|material3.Button\|material3.CircularProgressIndicator\|material3.TextField" app/src/main` 为 0
- 所有交互无 ripple（除非有意保留，如长按菜单）
- 触控区全部 ≥ 48dp
- 加载指示器视觉与 Metro 一致（直角横条或细线圆环）

**风险**：`BasicTextField` 的光标、选区、输入法交互需要手动处理，比 `TextField` 复杂。建议先在 `SearchScreen` 单点验证。

---

### 阶段 E：圆形按钮去圆  ☐ 待办

**目标**：清除 `CircleShape` 残留（播放器进度条旋钮除外）。

**做法**：
- `SongCard.kt:176` `PlayAllCircleButton`：改为 48×48dp 直角方块，`background(primary)`，图标居中。或直接裸图标（若位置已在卡片上）。
- `LibraryAlbumGridItem.kt:44`：同改。
- `SlimProgressBar.kt` 的滑块旋钮：保留直角细线（2dp 宽，全进度条高），不圆形。

**验收**：
- `grep -r "CircleShape" app/src/main` 为 0
- 播放按钮视觉与 Metro 一致（直角方块或裸图标）

---

## 五、性能优化路线图（低端机专项）

### 阶段 F：图片缓存策略  ☐ 待办

**现状**：Coil 用默认 `ImageLoader`（内存缓存 = 25% 可用堆），无全局配置。低端机 4GB 堆约 96MB 内存缓存，列表滚动时图片解码会挤压音频缓冲。

**目标**：自定义 `ImageLoader`，按设备分级配置。

**做法**：
- 新增 `NcrustApplication.kt`（若不存在），实现 `ImageLoaderFactory`。
- 内存缓存：低端机（< 4GB RAM）16MB，中端 24MB，高端 32MB。用 `ActivityManager.MemoryInfo` 判档。
- 磁盘缓存：统一 100MB（`DiskCache.Builder().maxSizeBytes(100L * 1024 * 1024)`）。
- 列表缩略图统一 `size(256, 256)`：`SongCard`、`LibrarySongListItem`、`AlbumSearchItem`、`ArtistSearchItem`、`LibraryAlbumGridItem`。
- 详情页大图统一 `size(512, 512)`：`DetailHeader`、`PlayerCard` 全屏封面。
- `crossfade(false)` 或 `crossfade(0)`：Metro 不需要淡入，直接显示更利落。
- `PlaybackService.kt:221` 通知栏封面已是 512，保持。

**涉及文件**：新增 `NcrustApplication.kt`（或在 `MainActivity` 里 `Application` 注册）+ 修改 `AndroidManifest.xml` 的 `android:name` + 调整所有 `AsyncImage` 调用的 `size`。

**验收**：
- 低端机列表快速滚动不卡顿（> 45fps）
- 内存缓存峰值 ≤ 24MB（用 `Profiler` 看）
- 图片无淡入，直接出现（Metro 利落感）
- 离线打开已访问页面，图片从磁盘缓存秒开

**风险**：`size(256,256)` 可能让封面在小尺寸时糊。需目检 SongCard 72dp 封面清晰度，必要时调到 288。

---

### 阶段 G：内存压力响应  ☐ 待办

**现状**：无 `onTrimMemory`。低端机内存紧张时 ContentCache 与图片缓存不会主动释放，依赖系统 kill。

**目标**：分级响应内存压力，避免 OOM 与后台被杀。

**做法**：
- `NcrustApplication.onTrimMemory(level)`：
  - `TRIM_MEMORY_RUNNING_MODERATE`：清空 `ContentCache` 全部快照。
  - `TRIM_MEMORY_RUNNING_LOW`：清空 `ContentCache` + Coil 内存缓存。
  - `TRIM_MEMORY_COMPLETE`：停止新图片加载请求，`PlaybackService` 通知栏封面降级到 256。
- `ContentCache` 加 LRU 上限：默认 32 项，超过按 LRU 淘汰。避免无界增长。
- `LibraryManager` 的 `savedSongs` 若超过 5000 项，考虑分页加载（当前全量在内存）。

**涉及文件**：`NcrustApplication.kt` + `cache/ContentCache.kt`（加 LRU）。

**验收**：
- 模拟内存压力（Android Profiler -> Force GC -> 内存填充），ContentCache 被清空
- 回到已访问页面，从网络重新加载（Crossfade 平滑过渡，不闪烁）
- 无 OOM crash

---

### 阶段 H：重组削峰  ☐ 待办

**现状**：播放器卡片已 zero-recomposition，但其他屏幕未优化。`SongCard` 等列表项在快速滚动时可能重组。

**目标**：列表滚动时单帧重组 < 5 次/项。

**做法**：
- `SongItem` data class 加 `@Immutable`（若字段都是不可变）。
- `SongCard` 的 lambda 参数加 `@Stable` 或改为 `(song: SongItem) -> Unit` 函数引用而非 lambda。
- 所有 `LazyColumn` 的 `items` 加 `key = { it.id }`（部分已有，全量检查）。
- 派生状态用 `derivedStateOf`：如 `selectedTab == 0` 这类频繁读取的布尔。
- 避免在 `LazyColumn` 的 `items` 块里 `remember` 大对象。
- 用 Layout Inspector 的 Recomposition Counts 验证。

**涉及文件**：`ui/components/SongCard.kt`、`ui/components/LibrarySongListItem.kt`、`ui/components/LibraryAlbumGridItem.kt`、各 `LazyColumn` 调用处。

**验收**：
- Layout Inspector 显示快速滚动时 SongCard 重组计数 = 0（复用）或 1（新项进入）
- 低端机列表滚动 60fps（中端 90fps）

---

### 阶段 I：DEX 体积与冷启动  ☐ 待办

**现状**：R8 + Baseline Profile 已做。`material-icons-extended` 全量打入（R8 应裁剪，但 keep 规则可能过宽）。

**目标**：APK ≤ 8MB，冷启动 ≤ 800ms（低端机）。

**做法**：
- 评估 `material-icons-extended` 实际使用图标数（grep `Icons.Default.` / `Icons.Outlined.` / `Icons.Filled.`）。若 < 50 个，考虑：
  - 方案 A：保留 extended，确认 R8 已裁剪未用图标（`shrinkResources` 应处理）。
  - 方案 B：迁移到 `material-icons-core` + 自定义 SVG 图标（工作量大，不推荐）。
  - **推荐方案 A**，仅清理 `proguard-rules.pro` 里对图标类的过宽 keep。
- 检查 `proguard-rules.pro`：Gson model 的 keep 是否过度（`-keep class com.takahashirinta.ncrust.network.** { *; }` 可缩到具体 model 类）。
- `AppWarmup` 已预加载关键类，扩展到首屏 SongCard / AsyncImage 相关类。
- 首屏关键路径：`MainActivity` -> `MainScreen` -> `HomeScreen` -> `SongCard`，确保这些类在 Baseline Profile 内。

**涉及文件**：`app/proguard-rules.pro`、`baseline-prof.txt`、`warmup/AppWarmup.kt`。

**验收**：
- APK release 体积 ≤ 8MB
- 低端机（4 核 4GB）冷启动 ≤ 800ms（`adb shell am start -W`）
- 首屏无 JIT 卡顿（前 30s 无掉帧）

---

### 阶段 J：线程模型梳理  ☐ 待办

**现状**：全用 `Dispatchers.IO`，未区分 CPU 密集与 IO 密集。Gson JSON 解析在 IO 线程（CPU 密集），可能抢占网络线程。

**目标**：无 ANR，主线程帧时间 < 16ms。

**做法**：
- 网络请求：`Dispatchers.IO`（保持）。
- JSON 解析（Gson / `JSONObject`）：切到 `Dispatchers.Default`（CPU 密集）。
- 图片解码：Coil 默认（IO，保持）。
- SharedPreferences 读写：`Dispatchers.IO`（保持，但写用 `apply()` 不用 `commit()`）。
- `PlaybackStateManager` 序列化队列：Gson 在 `Dispatchers.Default`，写文件在 `Dispatchers.IO`。
- 避免在主线程做任何 Gson 解析（grep `Gson().fromJson` 在 composable 内的调用）。

**涉及文件**：`network/PlaylistApi.kt`（JSON 解析切 Default）、`player/PlaybackStateManager.kt`、其他 `withContext(Dispatchers.IO)` 调用 JSON 解析处。

**验收**：
- 严格模式（`StrictMode.ThreadPolicy`）无违规
- 长列表加载时主线程帧时间 < 16ms
- 无 ANR（Play Console ANR 率 < 0.5%）

---

## 六、资源调配清单

| 资源 | 当前 | 目标 | 控制点 |
|---|---|---|---|
| 图片内存缓存 | 默认 25% 堆 | 低端 16MB / 中端 24MB / 高端 32MB | `NcrustApplication` ImageLoader |
| 图片磁盘缓存 | 默认 250MB | 100MB | 同上 |
| ContentCache | 无界 | LRU 32 项 | `cache/ContentCache.kt` |
| 播放队列 | 全量内存 | 保持（典型 < 1000 项） | — |
| LibraryManager | 全量内存 | 保持（< 5000 项时分页） | — |
| 线程池 | IO 共享 | IO + Default 分工 | 各 `withContext` 调用 |
| DEX | R8 已裁剪 | ≤ 8MB | proguard + icons 评估 |
| largeHeap | 不开 | 不开 | — |

---

## 七、验收标准（全局）

**UI 血统**：
- `grep -r "MaterialTheme" app/src/main` 仅剩 `ui/theme/` 内部
- `grep -r "material3\." app/src/main` 仅剩必要 import（如 `LocalTextStyle`）
- `grep -r "CircleShape\|RoundedCornerShape" app/src/main` 为 0
- `grep -r "elevation\|shadow" app/src/main` 为 0
- 所有交互无 ripple（除非有意保留）
- 三支柱（Metro/Groove/零重组）视觉与行为完整

**性能**：
- 低端机（4 核 4GB Android 8+）列表滚动 ≥ 60fps
- 冷启动 ≤ 800ms
- 内存峰值 ≤ 150MB
- 无 OOM、无 ANR
- 播放器卡片重组计数 = 0（Layout Inspector）

---

## 八、不做清单

- **不引入新依赖**：自定义实现替代 M3，不加 Compose 之外的库。
- **不动播放器卡片的 graphicsLayer 动画**：那些是手调的，CLAUDE.md 已警告。
- **不破坏 Groove flush-to-edge**：SongCard 左侧 0dp、DetailScaffold 无 TopAppBar、返回箭头裸图标。
- **不为"纯 Metro"引入圆角**：Metro 本身就是直角，Ncrust 不用圆角。
- **不替换 Gson**：保持 JSON 序列化，不引入 Protobuf/Moshi。
- **不引入 Room**：保持 SharedPreferences + Gson。
- **不批量重写已工作的代码**：只改 MD3 残留与性能瓶颈，不顺手重构。
- **不合并多阶段提交**：每阶段独立 commit，便于回滚与 review。
- **不维护进度日志文件**：进度看 git log，本文件只保留路线与原则。

---

## 九、提交节奏建议

| 阶段 | 建议提交类型 | 估计工作量 |
|---|---|---|
| A 主题系统 | `refactor:` 主题系统去 MD3 骨架，改用 CompositionLocal | 大（影响 70+ 引用） |
| B 底部导航 | `refactor:` 底部导航改 Pivot 风，去 M3 NavigationBar | 中 |
| C Tab 组件 | `refactor:` TabRow 改 Metro 顶部细线指示器 | 中 |
| D M3 原子 | `refactor:` 去除 M3 Surface/IconButton/Button/Progress/TextField | 中（分 2-3 提交） |
| E 圆形按钮 | `refactor:` 播放按钮去 CircleShape，改直角方块 | 小 |
| F 图片缓存 | `perf:` Coil 分级缓存与尺寸约束，低端机内存削峰 | 中 |
| G 内存响应 | `perf:` onTrimMemory 分级响应 + ContentCache LRU | 中 |
| H 重组削峰 | `perf:` 列表项 stable 化与 LazyColumn key 化 | 中 |
| I DEX 冷启动 | `perf:` proguard 收紧与 baseline profile 扩展 | 小 |
| J 线程模型 | `perf:` IO/Default 分工，JSON 解析切 Default | 小 |

**建议顺序**：A -> F -> B -> G -> C -> H -> D -> I -> E -> J

A 先做（支点），F 紧随（低端机立刻受益），B-G-C 改 UI 同时推性能，D-E 收尾 UI，I-J 最后打磨。

---

## 十、附：关键文件索引

| 文件 | 作用 | 涉及阶段 |
|---|---|---|
| `ui/theme/ThemeManager.kt` | 主题入口，待重写 | A |
| `MainActivity.kt:747-795` | 底部导航，待替换 | B |
| `ui/components/SongCard.kt` | 列表项，待 stable 化 + 去 CircleShape | E、H |
| `ui/player/PlayerCard.kt` | 播放器卡片，graphicsLayer 不动 | —（保护区） |
| `cache/ContentCache.kt` | 内存快照，待加 LRU | G |
| `player/PlaybackService.kt` | 播放服务，通知栏封面待降级 | G |
| `warmup/AppWarmup.kt` | 预加载，待扩展 | I |
| `network/PlaylistApi.kt` | JSON 解析，待切 Default | J |
| `app/proguard-rules.pro` | R8 规则，待收紧 | I |
| `baseline-prof.txt` | AOT 预编译，待扩展 | I |
| `app/build.gradle.kts` | 依赖与 R8 配置 | I |
