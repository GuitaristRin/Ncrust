# Ncrust 项目开发日志

**项目信息**
- 项目名称：Ncrust——网易云音乐第三方客户端
- 仓库地址：github.com/GuitaristRin/Ncrust
- 版本：v1.0.3
- 开发者：Takahashi_Rinta
- 技术栈：Kotlin + Jetpack Compose + Media3 ExoPlayer + Retrofit + Coil
- 许可证：MIT（保留原 Python 项目 Suxiaoqinx/Netease_url 版权声明）

> **说明**：本日志合并自多份开发记录。4 月 22 日至 29 日为初始开发期，5 月 2 日进行导航兼容性修复，5 月 3 日进行主题、组件与详情页重构，5 月 4 日完成代码拆分、性能优化、歌词动画改进、一键播放功能并发布 v1.0.1/v1.0.2。累计代码量 6058 行（47 个 Kotlin 文件）。

---

## 2026 年 4 月 22 日

### 项目起源与立项# Ncrust 项目开发日志

**项目信息**
- 项目名称：Ncrust——网易云音乐第三方客户端
- 仓库地址：github.com/GuitaristRin/Ncrust
- 版本：v1.0.2
- 开发者：Takahashi_Rinta
- 技术栈：Kotlin + Jetpack Compose + Media3 ExoPlayer + Retrofit + Coil
- 许可证：MIT（保留原 Python 项目 Suxiaoqinx/Netease_url 版权声明）

> **说明**：本日志合并自多份开发记录，统一按 2026 年纪录。4 月 22 日至 29 日为初始开发期，5 月 2 日进行导航兼容性修复，5 月 3 日进行主题、组件与详情页重构，5 月 4 日完成代码拆分、性能优化并发布 v1.0.1/v1.0.2。累计代码量 6058 行（47 个 Kotlin 文件）。

---

## 2026 年 4 月 22 日

### 项目起源与立项
- 项目立项，决定基于原 Python 项目 **Suxiaoqinx/Netease_url** 进行 Rust 重写（CLI 工具）。
- 确定仓库名为 **163CMAnalyser**，CLI 主程序名为 **ncrust-core**。
- 采用 MIT 许可证，同时保留原作者版权声明。
- 交互形态确定为纯 CLI，完全舍弃 WebUI。

### 初次架构设计
- 创建 Rust workspace 结构：
  - `ncrust-lib`（核心库）
  - `ncrust-core`（CLI 二进制）
- 核心模块划分：
  - `crypto`（加密）
  - `api`（API 交互）
  - `auth`（认证）
  - `downloader`（下载）
  - `models`（数据模型）
  - `utils`（工具函数）
- 确定音频质量支持等级：`standard`、`exhigh`、`lossless`、`hires`、`jyeffect`、`sky`、`jymaster`，并建立音质降级链。

---

## 2026 年 4 月 23 日

### Rust 核心库实现

#### 加密模块
- 实现 `crypto/eapi.rs`：eapi 加密算法，采用 AES-128-ECB + PKCS7 填充。
- 实现 `crypto/utils.rs`：MD5 哈希工具函数（后改用 md-5 crate）。
- 加密魔数：`nobody{url_path}use{payload}md5forencrypt`
- AES 密钥：`e82ckenh8dichen8`
- 分隔符：`-36cd479b6b5-`
- 遇到 PKCS7 填充问题，最终手动实现以解决兼容性。

#### HTTP 客户端与 API 模块
- 实现 `api/client.rs`：基于 reqwest 封装 HTTP 客户端，自动附加 Cookie、User-Agent、Referer。
- 实现 `api/endpoints.rs`：定义网易云音乐 API 端点常量。
- 实现业务 API：
  - `api/song.rs`：获取歌曲播放 URL、详情、歌词。
  - `api/search.rs`：搜索歌曲。
  - `api/playlist.rs`：获取歌单详情。
  - `api/album.rs`：获取专辑详情。
  - `api/login.rs`：分析二维码登录原理，预留接口。

#### 数据模型
- `models/song.rs`：Song、Artist、AlbumInfo 结构体。
- `models/playlist.rs`：Playlist 结构体。
- `models/album.rs`：Album 结构体。
- `models/lyric.rs`：Lyric 结构体。
- `models/quality.rs`：Quality 枚举，内含音质降级链。

#### Cookie 管理与认证
- 实现 `auth/cookie.rs`：Cookie 文件读写、格式验证、有效性检查，重要字段为 `MUSIC_U`、`__csrf`。
- Cookie 存储路径：`~/.config/ncrust/cookie.txt`。
- 实现 `auth/qrcode.rs`：二维码登录完整流程（生成 key、轮询状态、提取 Cookie）。

#### 下载模块
- `downloader/quality.rs`：音质降级策略，从高到低依次尝试。
- `downloader/task.rs`：单曲下载任务。
- `downloader/parallel.rs`：批量并发下载。
- `downloader/metadata.rs`：音频标签写入，支持 MP3/FLAC/M4A 格式。

#### 工具模块
- `utils/id_extractor.rs`：从各种 URL 中提取资源 ID。
- `utils/filename.rs`：生成安全文件名。
- `utils/progress.rs`：进度条封装。

#### CLI 命令实现
- 主程序名：`ncrust-core`。
- 子命令：`search`、`info`、`download`、`playlist`、`album`、`config`。
- 基于 clap Derive API 设计命令行参数。
- 搜索结果以表格形式展示，歌曲详情包含歌词，下载过程显示实时进度。

#### 编译与调试
- 依赖版本冲突处理：aes 从 0.9 降级至 0.8。
- 替换 md5 crate 为 md-5。
- 解决编译警告（未使用导入、变量等）。

#### 跨设备文件系统问题
- `std::fs::rename` 在 Linux 上报告 Invalid cross-device link 错误，原因是临时目录 `/tmp` 与下载目录不在同一文件系统。
- 修复方案：改用 `std::fs::copy` + `std::fs::remove_file` 替代 rename。

#### 代码注释规范确立
- 采用半文半白风格，用词精简（之、其、且、即等）。
- 句末加标点，无必要不注释，复杂逻辑方加注释。
- 禁止横幅式注释与 log 性注释（如“终极版”“解决方案”），提倡单行注释。

### Android 客户端启动

#### 技术选型与项目初始化
- 确定 Android GUI 版本名称为 **Ncrust**（弃用中文名）。
- 包名：`com.takahashirinta.ncrust`。
- 测试设备：Xperia 10 VI（骁龙 6 Gen 1，8 GB 内存）。
- 开发环境：Arch Linux + ThinkPad + Android Studio。
- UI 框架：Jetpack Compose；音频引擎：Media3 ExoPlayer；图片加载：Coil；网络：Retrofit + OkHttp。
- 设计目标：纯网络流媒体播放，不提供下载功能，交互参考 Apple Music 运作方式。
- 创建 Android 项目，基于 Material 3；集成 ExoPlayer、Retrofit、OkHttp；实现 eapi 加密（AES-128-ECB，密钥 `e82ckenh8dichen8`）以及 Cookie 管理器（SharedPreferences 持久化）。

#### 性能优化原则
- 针对 60 Hz 屏幕开发，使用 `graphicsLayer` 处理动画（GPU 执行，避免重组）。
- 禁止在动画中修改触发布局重计算的属性。
- 视觉风格采用直角切割（Metro Design）配合 Spotify 色调。

#### 交互模型设计
- 迷你播放栏常驻，置于导航栏上方；点击或上滑手势可触发展开。
- 全屏播放器卡片从底部滑出，动画进度由单一变量 `progress`（0→1）驱动。
- 所有视觉元素（位置、大小、透明度）均为 `progress` 的纯函数。
- 拖拽超过 25% 松手即自动展开，否则回弹。

#### 主界面框架
- 底部导航栏包含首页、库、搜索、用户四个标签。
- 播放队列系统实现 `addToQueue`、`removeFromQueue`、`playFromQueue`。

---

## 2026 年 4 月 24 日

### Rust 侧收尾
- 4 月 23 日各模块基本完成，24 日进行集成调试与注释规范应用。

### Android UI 深化

#### Splash Screen 与性能优化
- 实现预热机制：在 Splash Screen 阶段进行密集计算以预热 JIT。
- 预热阶段分布：
  - 数学运算 800 ms
  - 对象分配 400 ms
  - 字符串操作 400 ms
  - Compose 状态 300 ms
  - 集合操作 300 ms
- 播放器卡片组件在 Splash 中预创建（song 参数为 null），提前完成 JIT 预热。

#### 布局结构演进
- 确定三层图层结构：
  - 底层：主页面内容
  - 中层：全屏/迷你播放卡片
  - 顶层：导航栏
- 导航栏独立于最外层 Box，使用 `Modifier.align(Alignment.BottomCenter)`。
- 卡片通过 `graphicsLayer { translationY }` 控制竖直位置，从导航栏上方滑入。
- 卡片展开时，导航栏通过 `translationY` 向下移出屏幕。

#### 卡顿问题排查与解决
- 现象：动画帧率低，卡片跟随手势滞后。
- 根因：
  1. `animateFloatAsState` 触发不必要的 Compose 重组。
  2. 卡片通过 `if/else` 被销毁重建。
  3. 迷你栏手势挂载位置错误。
- 解决方案：
  1. 全部动画改用 `graphicsLayer` 直接进行底层计算。
  2. 卡片常驻组件树，仅通过 `translationY` 控制可见位置。
  3. 导航栏使用 `alpha` 控制透明度，不再销毁。
  4. 手势从条件渲染组件移至始终存在的外层容器。
- 拖拽灵敏度参数调整为 `totalDragDistancePx = screenH * 0.85f`。

#### 歌词系统
- LRC 解析器：支持 `[mm:ss.xx]` 格式。
- 显示规格：大号粗体（32sp），已播放部分白色，当前句绿色（`#1DB954`），未播放灰色。
- 自动定位：当前句定位到视图的**上黄金分割点**（0.45），每句仅定位一次。
- 用户手动滚动后 5 秒无操作恢复自动定位。
- 歌词区域上下增加渐变融入效果。

#### 封面动画系统
- 封面始终存在，形状与位置由 `graphicsLayer` 变换。
- 迷你模式：56 dp × 56 dp。
- 全屏模式：宽度撑满屏幕，竖直位置居中偏上（Y = `screenH * 0.35f`）。
- 歌词与封面之间通过 `Animatable` 实现 300 ms 平滑切换。
- 封面模式下顶部信息栏可见，切换至歌词时渐隐渐显；下拉按钮始终可见。

#### 进度条设计
- 纯色细线设计：已播放部分绿色（`#1DB954`），未播放灰色（`#404040`），无拖拽圆点。
- 支持点击与拖动跳转。
- 左侧显示当前时间，右侧显示总时长。

#### 触摸事件重大排查
- 问题：收起全屏后主页面无法交互。
- 排查过程：
  1. `PlayerCardOverlay` 的 `fillMaxSize()` 覆盖层拦截了全部触摸事件。
  2. 手势条件判断在稳态时移除了 `pointerInput`，导致无法再次拉起。
  3. 动态高度方案破坏了原有布局。
  4. 手势区域限制方案导致协程作用域不兼容。
- 最终方案：将 `pointerInput` 放置在 PlayerCard 的最外层 Box 上，并始终保留手势处理。

#### 库页面（Library）设计
- 默认标签页设为“库”（`selectedTab = 1`）。
- 内部分类：单曲、专辑、歌单。
- 单曲视图：两列封面墙，正方形封面下方显示歌曲名与歌手名，保留适当间距，`LazyColumn` 可滚动，点击具有弹性动画波纹反馈。
- 专辑、歌单为占位页面（即将推出）。
- 本地存储：`LibraryManager` 基于 SharedPreferences + Gson 实现。
- 全屏播放页添加加号按钮，可将当前歌曲存入曲库。

#### 搜索页面重新设计
- 搜索框为 `singleLine`，回车后收起键盘（`ImeAction.Done`），右侧提供清空按钮（X 图标）。
- 分类标签：单曲、专辑、艺人。
- 单曲搜索结果：每行左侧封面 + 歌曲信息，右侧加号（加入库）与箭头（立即播放）。
- 专辑搜索：封面墙（占位）。
- 艺人搜索：圆形头像列表（占位）。

#### 播放队列设计
- 队列与歌词共享同一过渡动画效果。
- 队列条目：一行一首歌，左侧封面 + 歌曲信息，右侧移除按钮（X）。
- 点击某行直接切换到对应歌曲播放。
- 渐变过渡效果与歌词区块一致（上下渐变）。
- 底部控制栏增加队列按钮（使用 PlaylistPlay 图标）。

#### 音频焦点管理
- 实现 `AudioManager` 请求音频焦点，确保与其他 App（如 Apple Music）互不干扰。
- 初始错误：手动焦点管理与 ExoPlayer 内部管理冲突，后改为由 ExoPlayer 通过 `handleAudioFocus = true` 全权处理。

#### 系统媒体控制
- 实现 `MediaSessionCompat` + MediaStyle 通知，支持锁屏与控制中心媒体控件。
- 通知显示问题：最初通知不显示，排查后发现需要调用 `setMediaSession(sessionToken)`；重要性需设为 `IMPORTANCE_LOW` 并配合 MediaStyle 才能在 Android 11+ 正确展示。
- 封面提取：通过 Coil 加载封面并利用 Palette 提取主色调，设置 `setColor()` 与 `setLargeIcon()`。
- 遇到 `Config#HARDWARE` bitmap 导致 Palette 无法读取像素的崩溃，解决方法是将 bitmap 复制为 `Config.ARGB_8888` 的可读格式。
- 进度条通过 `MediaSession.setPlaybackState()` 的 `setBufferedPosition()` 显示，Android 13+ 系统自动从中读取并绘制进度。

#### 歌词系统补充
- 切歌时重置滚动状态，确保新歌词从起始位置开始自动定位。

#### 播放模式设计
- 列表循环（0）：顺序播放至末尾后返回队列首部。
- 单曲循环（1）：始终播放当前歌曲（通过 `seekTo(0)` 实现）。
- 随机播放（2）：生成 shuffled 索引列表，然后按该列表顺序播放，而非每次随机选曲。

---

## 2026 年 4 月 25 日

### Rust CLI 最终完善（当日细节参见 4.23 日志，25 日主要聚焦 Android）

### Android 功能扩展

#### 网络层重构与专辑/艺人支持
- 专辑详情接口 `GET /api/v1/album/{id}` 测试通过。
- 探索艺人详情 API 过程：
  - `GET /api/artist/detail/{id}` → 404
  - `POST /eapi/artist/detail` → 400（参数错误）
  - `POST /eapi/v1/artist/detail` → 400
  - 最终采用 `GET /api/artist/albums/{id}`，其返回数据中嵌套了 `artist` 对象，可以获取艺人基本信息。
- 艺人热门单曲：因无独立热门歌曲接口，以艺人名进行 type=1 搜索并过滤出该艺人歌曲，作为替代方案。
- 歌单同步：
  - 通过 `POST /eapi/user/playlist` 获取用户创建/收藏的歌单列表。
  - 通过 `POST /eapi/v6/playlist/detail` 获取歌单内歌曲。
  - 用户 UID 通过 `POST /eapi/w/nuser/account/get` 从 `profile.userId` 提取。
- 用户资料：昵称位于 `profile.nickname`，头像位于 `profile.avatarUrl`；`account.userName` 为加密用户名，不可直接使用。

#### 搜索与本地库深化
- 多维度搜索：单曲 `type=1`、专辑 `type=10`、艺人 `type=100`。
- `SearchViewModel` 管理搜索状态，防抖设置为 500 ms。
- `SearchScreen` 使用三标签 TabRow 切换类别。
- 本地曲库存储方案：`LibraryManager` 使用 SharedPreferences + Gson 序列化 `List<SongItem>`。
- 专辑统计：不作为独立实体存储，而是从本地单曲列表中按 `albumId` 去重计数，实现专辑派生。
- 插播与加队列：
  - 插播：插入到当前播放位置的下一首。
  - 加队列：追加到播放列表末尾。
  - 两者添加前均进行去重（`filter { it.id != song.id }`）。

#### 最新 UI 完善
- 禁止横屏模式：`AndroidManifest.xml` 设置 `screenOrientation="portrait"`。
- 修复封面切换动画：添加 `launchedEffect` 驱动平滑过渡。
- 大封面 Y 轴位置开放为用户偏好参数 0.35f。
- 播放列表按钮集成至全屏播放控件。
- 修复进度条数据更新延迟问题，优化 PlaybackService 回调。

---

## 2026 年 4 月 26 日

### 状态持久化——进程被杀恢复

#### 问题现象
- 8 GB RAM 的 Xperia 10 VI 仍然在后台频繁杀死进程，日志出现 `Operation not started: CONTROL_AUDIO` 等系统 LMK 痕迹。
- 尝试添加 `MODIFY_AUDIO_SETTINGS` 权限无效，尝试 `attributionTag` 导致 AAPT 编译错误（需 API 31+）。
- 最终方案：创建 `PlaybackStateManager`，利用 SharedPreferences 持久化当前播放状态。

#### 保存与恢复
- 保存字段：`songId`、`title`、`artist`、`artworkUrl`、`isPlaying`。
- `PlayerViewModel.init()` 读取上述持久化状态进行恢复。
- 歌词恢复：依据保存的 `songId` 重新加载对应歌词文件。
- 封面恢复：根据 `artworkUrl` 重新加载封面图片。

#### 其他后台管理
- 系统媒体服务采用 `MediaSessionService` 而非普通 Service，以符合 Android 14+ 媒体播放规范。
- 明确不再使用手动音频焦点管理，防止与 ExoPlayer 内置机制冲突。

---

## 2026 年 4 月 27 日～28 日

### 首页发现与日推

#### 新歌速递
- API：`GET /api/v1/discovery/new/songs?limit=10&offset={offset}`。
- 实现懒加载，每批拉取 10 首（初版 limit=20 造成流量浪费，改为 10）。
- 滚动到底部自动加载更多。

#### 推荐歌单
- API：`POST /eapi/v1/discovery/recommend/resource`。
- 展示方式：横向滚动，每个封面附播放按钮。
- “私人雷达”歌单固定显示 35 首（API 返回 `trackCount=0`）。

#### 日推歌曲
- 探索历程：
  1. 直接 `curl` 不带加密参数 → 无返回数据。
  2. 在网页版 Network 中观察到请求使用 `weapi/v2/...` 加密。
  3. 尝试 `eapi` 替代路径 `eapi/v2/...` → 获得成功。
- 前端展示：5 行 × N 列横向滑动，每列宽度占屏幕 82%。

#### 登录优化
- 点击“未登录”弹出选项弹窗。
- “浏览器登录”：全屏 WebView 打开网易云音乐登录页，通过 `onPageFinished` 自动提取 Cookie。
- 已知问题：WebView Cookie 提取偶尔失败，未能稳定获取 `MUSIC_U`。
- 保留手动粘贴 Cookie 的降级方案。

---

## 2026 年 4 月 29 日

### 多屏幕比例适配

#### 核心策略
- 以 21:9（Xperia 10 VI，宽度约 360dp）为基线，宽屏设备内容区域限制最大宽度 360dp 居中显示，保留窄屏视觉比例。
- 创建 `ResponsiveContent` 组件，内含 `Box(widthIn = 360.dp)` 包裹内容，在宽度 ≤ 360dp 时撑满，不影响原设备体验。

#### 各页面适配详情
- **HomeScreen**：套用 `ResponsiveContent`，日推横向滚动列改用 `fillParentMaxWidth(0.9f)`。
- **LibraryScreen**：整体包裹 `ResponsiveContent`，网格行继续使用 `weight` 均分。
- **PlaylistDetailScreen**：移除 `screenWidth * 0.5f`，封面改用 `Modifier.weight(0.4f).aspectRatio(1f)`。
- **AlbumDetailScreen**：同上处理。
- **ArtistDetailScreen**、**SongDetailScreen**：外层套 `ResponsiveContent`。
- **全屏播放器 PlayerCard**：不套用宽度限制，封面使用 `fillMaxWidth().aspectRatio(1f)`，动画计算基于实际屏幕宽度，确保全屏播放时卡片撑满、元素位置正确。
- 封面纵向偏移系数调整为 0.3f，用户可后续调整。

#### 编译错误修复
- 处理 `Dp.toPx()` 需要在 density 上下文中调用、浮点运算歧义等问题，将相关数值提前转为 px 变量，消除编译错误。

---

### 播放队列持久化

- 问题：进程被杀死后，歌词、封面可恢复，但播放队列丢失。
- 根源：`playbackQueue` 与 `currentQueueIndex` 仅存于内存，未持久化。
- 方案：扩展 `PlaybackStateManager`，新增 `saveQueue()`、`getQueue()`、`clearQueue()` 方法，利用 Gson 序列化 `List<SongItem>` 存入 SharedPreferences。
- 修改点：
  - `PlaybackStateManager` 增加队列持久化逻辑。
  - `MainScreen` 中增加 `LaunchedEffect` 恢复队列；在 `addToQueue`、`removeFromQueue`、`playFromQueue`、`insertNext`、`appendToQueue` 等函数末尾调用 `saveQueue`。
  - `PlayerViewModel.stopService()` 内调用 `clearQueue` 清除持久化队列。
- 修复过程中处理了函数定义顺序（`generateShuffledIndices` 先于调用）、重复函数定义等编译问题。

---

### 库页面单曲操作修复

- 现象：库中单曲的“插播”和“加入播放列表”按钮点击无反应。
- 原因：`LibrarySongListItem` 缺少 `onInsertNext`、`onAppendToQueue` 参数，点击回调仅为 TODO 注释。同时 `MainActivity` 内的旧版 `LibraryScreen` 未传递这些回调。
- 修复：
  - 更新 `LibrarySongListItem` 签名，增加 `onInsertNext`、`onAppendToQueue` 参数并绑定至对应按钮。
  - 在 `ui/screen/LibraryScreen.kt` 中为 `LibraryScreen` 添加 `onSongInsertNext`、`onSongAppendToQueue` 参数，传递至 `LibrarySongListItem`。
  - `MainActivity` 中调用 `LibraryScreen` 时传入 `insertNext`、`appendToQueue` 函数。
  - 删除 `MainActivity` 内嵌的旧 `LibraryScreen`，使用独立文件版本。

---

### 关于页面与 Markdown 渲染

- 新增 `ui/screen/AboutScreen.kt`：关于页面包含返回按钮、系统返回手势支持，内容区域使用 `ResponsiveContent`。
- 实现简易 Markdown 渲染器 `MarkdownText`（`ui/theme/MarkdownText.kt`），支持标题（#、##、###）、粗体、斜体、行内代码、无序列表、水平分割线、图片。
- 关于页面内容由 `aboutMarkdown` 变量控制，方便后续编辑。
- 在用户页音质调节下方添加“关于 Ncrust”按钮，点击跳转至关于页面。

---

### Splash Screen 视觉优化

- 修改 `SplashScreen`：标题“Ncrust”改为绿色粗体，下方添加白色副标题“A Re-defined Music Player”，底部居中灰色小字“Artwork by Project Arcturius”。
- 实现渐隐效果：预热完成后使用 `Animatable` 将 alpha 从 1f 动画至 0f（400ms，FastOutSlowInEasing），取代原先的闪没。
- 移除 `MainActivity.kt` 中旧的内嵌 `SplashScreen` 定义，统一使用 `ui/screen/SplashScreen.kt`。
- 修复了 `graphicsLayer` import 缺失导致的编译错误。

---

### 应用图标设计

- 设计绿色唱片风格图标：绿色圆形底，中心白色同心圆环、实心内圈、中心绿孔，右侧播放三角形缺口。
- 创建 `res/drawable/ic_launcher.xml`（Vector Drawable）。
- 配置 Adaptive Icon：`res/mipmap-anydpi-v26/ic_launcher.xml`，背景绿色，前景为唱片 vector。
- 解决手机显示白色蒙版问题：通过缩小唱片组（scale 68%）让外层绿色露出一圈。
- 图标加入关于页顶部居中显示，使用 `R.drawable.ic_launcher` 避免 `painterResource` 对 mipmap 的兼容问题。

---

### 其他优化

- 清除部分编译警告：替换弃用的 `PlaylistPlay`、`PlaylistAdd` 为 `AutoMirrored` 版本；移除未使用的函数、属性、参数；移除未使用的 import 等。
- 修复 `AboutScreen` 中图标加载崩溃（mipmap 不支持）改用 drawable。
- 调整系统返回手势：关于页使用 `BackHandler` 支持手势返回。

---

## 2026 年 5 月 2 日

### 迷你播放栏导航兼容性修复

#### 问题描述
- 迷你播放栏（MiniBar）在三键导航模式下位置错位，被系统导航栏遮挡或偏移；手势导航模式下正常。
- 全屏展开时，底部标签栏（NavigationBar）向下避让未完全移出屏幕，在部分设备上残留可见。
- Issue `#1`（UI 错位）被用户 OYinFengO 反馈，设备为 NothingOS Android 16，1080×2412 420dpi，手势模式但仍有底部空隙。

#### 根因分析
- `PlayerCardOverlay` 定位完全依赖屏幕物理高度减去固定值：
  ```kotlin
  val startY = screenHPx - navBarPx - miniBarPx - extraOffset
  ```
  `extraOffset` 被硬编码为 72dp，未扣除系统导航栏高度。
- `enableEdgeToEdge()` 使内容延伸至系统栏后方，但卡片层与标签栏均未适配系统导航栏实际占用空间，三键模式下视觉错位。

#### 修复方案探索
- **尝试 1**：使用 `WindowInsets.navigationBars` 动态获取高度，但在 Compose 内部返回 0，无法使用。
- **尝试 2**：使用 `view.rootWindowInsets` 获取真实高度（手势 68px，三键 135px），但直接减去该高度后两种模式视觉不一致。
- **最终方案**：保留原公式基本结构，将 `extraOffset` 改为 48dp（视觉调优），并从总偏移中直接减去系统导航栏高度，实现模式间同步：
  ```kotlin
  val collapsedOffsetY = screenHeightPx - systemNavBarHeightPx 
                         - navBarHeightPx - miniBarHeightPx - 48.dp.toPx()
  ```
- **标签栏避让修复**：为 `NavigationBar` 的 `translationY` 增加 24dp 余量，确保完全移出屏幕：
  ```kotlin
  translationY = (navBarHeightPx + systemNavBarHeightPx + 24.dp.toPx()) * progress.value
  ```

#### 修改文件
- `MainActivity.kt`：`MainScreen` 中新增系统导航栏高度获取，修改 `collapsedOffsetY` 计算及标签栏动画位移。
- `PlayerCardOverlay` 与 `PlayerCard`：签名改为接收外部计算好的 `collapsedOffsetY` 和 `screenHeightPx`，移除内部冗余的屏幕高度计算。

#### 测试验证
- Xperia 10 VI（21:9，手势 / 三键）：迷你栏位置一致，全屏展开标签栏完全隐藏。
- 模拟器 Pixel 7 Pro（19.5:9，手势 / 三键）：通过。
- 极端分辨率（`adb shell wm size 1080x1920` 强制窄屏）：交互正常，无错位或遮挡。
- 交互性能未下降，动画流畅性保持原有水平。

#### 结论
该修复以最小改动量实现手势与三键导航下迷你栏位置同步，全屏动画标签栏无残留，覆盖主流设备与极端分辨率，兼容性显著提升。

---

## 2026 年 5 月 3 日 — 主题系统、组件统一与详情页重构

> 本日工作在分支 `refactor/theme-and-components` 下完成，涵盖 Phase 1~3。

### Phase 1: 主题系统

#### 1.1 新建文件
| 文件 | 说明 |
|------|------|
| `ui/theme/ThemeManager.kt` | 6 种主题色预设 + 持久化 + `NcrustTheme` |
| `ui/theme/ThemeColorSelector.kt` | 主题色选择器 UI 组件 |

#### 1.2 主题色预设
```
云杉 #1DB954  // 默认
钴蓝 #3B82F6
绯红 #EF4444
琥珀 #F59E0B
堇紫 #8B5CF6
素白 #FFFFFF
```

#### 1.3 全局颜色变更
- 背景统一为 `MaterialTheme.colorScheme.background`（OLED 纯黑 `#000000`）
- 强调色统一为 `MaterialTheme.colorScheme.primary`
- `surface` 统一为 `#1A1A1A`
- 所有硬编码 `Color(0xFF121212)` 已消除
- 所有硬编码 `Color(0xFF1DB954)` 已消除

#### 1.4 修改文件
| 文件 | 修改内容 |
|------|---------|
| `MainActivity.kt` | 删除旧 `NcrustTheme`，改用新版本；`setContent` 中注入主题索引状态；所有背景色引用改为 `MaterialTheme.colorScheme.background`；所有主色引用改为 `MaterialTheme.colorScheme.primary` |
| 用户页 (UserScreen) | 新增“主题色”选择区域，复用 `QualitySelector` 风格 |
| `QualitySelector` | 选中色改为 `MaterialTheme.colorScheme.primary` |

---

### Phase 2: 组件统一 — SongCard

#### 2.1 新建文件
`ui/components/SongCard.kt`：统一曲目卡组件（3 种样式 + 播放按钮）

#### 2.2 SongCard 样式
| 样式 | 封面 | 布局 | 用途 |
|------|------|------|------|
| `LIST` | 48dp | 横排双行 | 库、搜索、主页 |
| `COMPACT` | 40dp | 横排双行 | 详情页、队列 |
| `GRID` | 填充宽度 | 竖排 | 网格展示 |

附带组件 `PlayAllCircleButton`：圆形播放按钮（主题色自适应）。

#### 2.3 删除的旧组件
`HomeSongListItem`、`LibrarySongListItem` (×2 重复)、`SongSearchItem`、`SongGridItem`、`AlbumSongListItem`、`PlaylistSongListItem`、`ArtistSongListItem`。

#### 2.4 修改文件
| 文件 | 修改内容 |
|------|---------|
| `HomeScreen.kt` | 替换为 `SongCard` + `PlayAllCircleButton` |
| `LibraryScreen.kt` | 同上 |
| `SearchScreen` | 替换为 `SongCard` |
| `QueueView` | 替换为 `SongCard` |
| `AlbumDetailScreen.kt` | 替换为 `SongCard` |
| `PlaylistDetailScreen.kt` | 替换为 `SongCard` |
| `ArtistDetailScreen.kt` | 替换为 `SongCard` |

---

### Phase 3: 详情页重构

#### 3.1 新建文件
| 文件 | 说明 |
|------|------|
| `ui/components/DetailScaffold.kt` | 统一详情页模板 + `DetailHeader` |
| `ui/navigation/NavGraph.kt` | 导航图 + `NavRoutes` 路由定义 |

#### 3.2 导航架构
```
NavHost (fade 过渡动画)
├── "home"           → 占位，内容由 MainScreen 填充
├── "album/{id}"     → AlbumDetailScreen
├── "artist/{id}"    → ArtistDetailScreen
├── "playlist/{id}"  → PlaylistDetailScreen
└── "song/{id}"      → SongDetailScreen
```

#### 3.3 关键特性
- **多级跳转**：专辑 → 艺术家 → 专辑，`navController.popBackStack()` 逐级返回
- **fade 动画**：渐入渐出，避免滑动动画在低端设备的性能问题
- **底部导航栏保持可见**：Apple Music 风格
- **播放器卡片始终覆盖**：所有详情页上方可见

#### 3.4 删除的旧状态变量
```kotlin
var selectedSongId
var selectedAlbumId
var selectedArtistId
var selectedPlaylistId
var selectedPlaylistName
var selectedPlaylistCover
// + 旧的 BackHandler 块
```

#### 3.5 新增依赖
```kotlin
implementation("androidx.navigation:navigation-compose:2.8.5")
```

---

### 代码指标（5 月 3 日完成后）
| 指标 | 修改前 | 修改后 | 变化 |
|------|--------|--------|------|
| MainActivity.kt 行数 | ~2400 | ~1900 | -21% |
| 曲目卡组件数 | 7 个独立函数 | 1 个 SongCard | -86% |
| 硬编码绿色引用 | ~25 处 | 0 | -100% |
| 硬编码灰色背景 | ~15 处 | 0 | -100% |
| 详情页状态变量 | 6 个 | 0（NavHost 管理） | -100% |

---

## 2026 年 5 月 4 日 — 代码拆分、性能优化与问题修复

> 本日完成了 `MainActivity.kt` 的彻底拆分，修复了多个动画与布局问题，并发布了 v1.0.1 与 v1.0.2。最终代码总量精准确认为 **6058 行 / 47 个 Kotlin 文件**。

### 一、代码拆分

#### 背景
`MainActivity.kt` 膨胀至约 1800 行，包含 15+ 个 Composable 函数，每次 Claude Code 分析需读取整个文件，消耗大量 token。为降低分析开销并改善代码组织，进行拆分。

#### 拆分结果
| 新文件 | 移入函数 | 行数 |
|-------|---------|------|
| `ui/player/SlimProgressBar.kt` | `SlimProgressBar` | ~40 |
| `ui/player/FullPlayerControls.kt` | `FullPlayerControls` | ~120 |
| `ui/player/LyricsView.kt` | `LyricsView` | ~100 |
| `ui/player/QueueView.kt` | `QueueView` | ~50 |
| `ui/player/PlayerCardOverlay.kt` | `PlayerCardOverlay` | ~40 |
| `ui/player/PlayerCard.kt` | `PlayerCard` | ~300 |
| `ui/screen/SearchScreen.kt` | `SearchScreen` + `SongSearchItem` | ~130 |
| `ui/screen/UserScreen.kt` | `UserScreen` + `QualitySelector` | ~160 |
| `ui/components/AlbumSearchItem.kt` | `AlbumSearchItem` | ~40 |
| `ui/components/ArtistSearchItem.kt` | `ArtistSearchItem` | ~50 |
| `ui/components/LibrarySongListItem.kt` | `LibrarySongListItem` | ~50 |
| `ui/components/LibraryAlbumGridItem.kt` | `LibraryAlbumGridItem` | ~40 |
| `ui/components/SongGridItem.kt` | `SongGridItem` | ~50 |

#### 结果
- `MainActivity.kt` 从 ~1800 行缩减至 537 行，仅保留 `MainActivity`、`formatDuration`、`MainScreen`
- Claude Code 单次分析 token 消耗预计下降约 70%
- 编译问题修复：
  - `LyricsView.kt`：修正 `animateScrollToItem` import 路径
  - `SlimProgressBar.kt`：补充 background import

---

### 二、关键问题修复（5 月 2 日—4 日总结）

#### 2.1 迷你播放栏导航兼容性（已解决）
- **问题**：三键导航模式下迷你栏位置错位，被系统导航栏遮挡
- **根因**：`collapsedOffsetY` 公式中 `fullCardExtraOffsetPx` 硬编码 48dp，未考虑厂商定制 ROM 状态栏高度差异
- **修复**：`fullCardExtraOffsetPx` 改为动态计算 `statusBarHeightDp + 24.dp`
- **参考**：Claude Code 分析发现 48dp = 24dp(状态栏) + 24dp(NavBar 补偿)，ColorOS 状态栏约 60dp 导致补偿不足
- **测试**：Xperia 10 VI 手势/三键均正常，ColorOS 已通过

#### 2.2 全屏展开后标签栏避让不足（已解决）
- **问题**：`NavigationBar` 避让未完全移出屏幕
- **修复**：隐退量改为固定 132dp

#### 2.3 启动首次展开掉帧（已解决）
- **问题**：启动后第一次拉起全屏动画掉帧，后续流畅
- **根因**：
  1. 迷你栏用 `if (progress.value < 0.3f)` 条件渲染，动画期间触发重组
  2. `fullAlpha` 在 Composition 阶段读取 `progress.value`，触发全函数重组
  3. GPU Shader 首次编译在动画路径上
- **修复**（Claude Code）：
  1. 迷你栏改为始终渲染，仅用 `graphicsLayer.alpha` 控制透明度
  2. `fullAlpha` 移入各 `graphicsLayer` lambda，避免 Composition 阶段读取 `progress.value`
  3. Splash 期间通过 `LaunchedEffect` 瞬间展开再收起卡片，预热 GPU Shader
  4. Splash 移除无效的 CPU 预热代码，改为等待 Composition 完成
- **效果**：Xperia 10 VI 上动画明显流畅

#### 2.4 迷你栏图层错位（已解决）
- **问题**：性能优化后迷你栏透明但占位 56dp，全屏时顶推歌曲信息
- **修复**：迷你栏从 `Column` 内移至外层 `Box`，用 `statusBarsPadding()` 定位，不参与 `Column` 布局

#### 2.5 封面动画重构（已完成）
- 封面变换改为基于中心点插值（迷你封面中心 → 全屏封面中心）
- 引入 `lyricAnimProgress` 控制封面在两个状态间的过渡
- 收起/展开动画曲线差异化（`tween(190)` vs `tween(300)`）

#### 2.6 歌单页面闪退（已解决）
- **问题**：v1.0.1 点开任意歌单闪退（Issue `#11`）
- **修复**：紧急修复并发布 v1.0.2

#### 2.7 WebView 登录兼容性（已解决）
- **问题**：小屏设备（NW-A105）无法滚动到用户协议勾选框（Issue `#2`）
- **修复**：调整 WebView 布局与滚动策略

---

### 三、最终代码体积统计（2026-05-04）

```
📊 Ncrust 项目代码行数统计 (纯 Kotlin)

■■■ 按包/目录统计
  auth                      64
  crypto                    0   (合并至 network/crypto)
  library                   105
  lyric                     27
  network                   587
  player                    483
  ui/components             668
  ui/navigation             111
  ui/player (播放卡片)      940
  ui/screen                 1881
  ui/theme                  320
  ui/viewmodel              305
  ui/ResponsiveContent      30
  MainActivity.kt           537
  ─────────────────────────────
  合计                     6058 行 (47 个文件)

各模块占比
  auth                      1.1%
  library                   1.7%
  lyric                     0.4%
  network                   9.7%
  player                    8.0%
  ui/components             11.0%
  ui/navigation             1.8%
  ui/player                 15.5%
  ui/screen                 31.0%
  ui/theme                  5.3%
  ui/viewmodel              5.0%
  ui/root                   0.5%
  MainActivity              8.9%

📄 最大文件 Top 10
  537  MainActivity.kt
  405  ui/player/PlayerCard.kt
  353  ui/screen/UserScreen.kt
  347  player/PlaybackService.kt
  289  ui/screen/HomeScreen.kt
  284  ui/screen/LibraryScreen.kt
  242  ui/screen/SearchScreen.kt
  203  ui/screen/ArtistDetailScreen.kt
  193  ui/player/LyricsView.kt
  183  ui/components/SongCard.kt
```

---

### 四、版本发布记录

| 版本 | 日期 | 内容 |
|------|------|------|
| v0.1.0-beta | 4 月 26 日 | 初始 MVP 版本 |
| v1.0.0 | 4 月 29 日 | 首个正式版，适配多屏，完善功能 |
| v1.0.1 | 5 月 4 日 | 性能大幅优化、主题色系统、尝试修复 ColorOS 错位、WebView 登录修复 |
| v1.0.2 | 5 月 4 日 | 紧急修复歌单闪退、歌词页面重构 |

---

## 代码架构总结（最终状态）

### 分层架构
- **UI 层**：Compose 组件 + 三层图层结构（主页面 → 卡片层 → 导航栏）。
- **状态层**：单一 `Animatable(0f)` 驱动所有播放卡片动画，`graphicsLayer` 实现零重组。
- **播放层**：Media3 ExoPlayer + MediaSessionService，状态持久化。
- **网络层**：Retrofit + OkHttp + Eapi 加密（`crypto/EapiCrypto.kt` 位于 `network/crypto/`）。
- **本地存储**：SharedPreferences + Gson，轻量无数据库。
- **路由层**：Navigation Compose 管理详情页导航。

### 包结构（最终版，47 个文件）
```
com.takahashirinta.ncrust/
├── MainActivity.kt
├── auth/CookieManager.kt
├── library/LibraryManager.kt
├── lyric/LrcParser.kt
├── network/
│   ├── NcmApi.kt
│   ├── PlaylistApi.kt
│   ├── RetrofitClient.kt
│   ├── SearchResponse.kt
│   ├── crypto/EapiCrypto.kt
│   └── model/
│       ├── AlbumDetail.kt
│       ├── ArtistDetail.kt
│       ├── SongDetail.kt
│       └── SongUrlResponse.kt
├── player/
│   ├── PlaybackService.kt
│   ├── PlaybackStateManager.kt
│   └── SongUrlFetcher.kt
└── ui/
    ├── ResponsiveContent.kt
    ├── components/
    │   ├── ArtistSearchItem.kt
    │   ├── DetailScaffold.kt
    │   ├── LibraryAlbumGridItem.kt
    │   ├── LibrarySongListItem.kt
    │   ├── SongCard.kt
    │   └── SongGridItem.kt
    ├── navigation/
    │   └── NavGraph.kt
    ├── player/
    │   ├── FullPlayerControls.kt
    │   ├── LyricsView.kt
    │   ├── PlayerCard.kt
    │   ├── PlayerCardOverlay.kt
    │   ├── QueueView.kt
    │   └── SlimProgressBar.kt
    ├── screen/
    │   ├── AboutScreen.kt
    │   ├── AlbumDetailScreen.kt
    │   ├── AlbumSearchItem.kt      (实际属于搜索功能，但物理位于 ui/screen)
    │   ├── ArtistDetailScreen.kt
    │   ├── HomeScreen.kt
    │   ├── LibraryScreen.kt
    │   ├── PlaylistDetailScreen.kt
    │   ├── SearchScreen.kt
    │   ├── SongDetailScreen.kt
    │   ├── SplashScreen.kt
    │   └── UserScreen.kt
    ├── theme/
    │   ├── MarkdownText.kt
    │   ├── ThemeColorSelector.kt
    │   └── ThemeManager.kt
    └── viewmodel/
        ├── PlayerViewModel.kt
        ├── SearchViewModel.kt
        └── SongViewModel.kt
```

---

## 已知问题与待办

### 未解决
1. 艺人热门单曲为搜索过滤结果，非真正热门歌曲排行。
2. `attributionTag` 警告持续出现在系统日志中，不影响功能但待清理。
3. WebView 登录提取 Cookie 不稳定，偶有失败。
4. 日推等接口当前使用 eapi 替代 weapi，长期存在失效风险。
5. 进度条数据更新偶有延迟。
6. 封面可能因 Coil 缓存策略导致模糊，需优化。
7. 专辑/艺人搜索功能尚未完全实现。
8. 播放队列历史记录待完善。
9. 本地封面缓存机制尚未实现。
10. 少量编译警告残留（如未使用参数等），后续版本再清理。

### 已解决
1. ✅ 播放卡片手势冲突
2. ✅ 通知栏不显示
3. ✅ Palette HARDWARE bitmap 崩溃
4. ✅ 进程被杀后状态丢失
5. ✅ 艺人详情 404
6. ✅ 新歌速递主线程网络异常
7. ✅ 日推 API 不可用
8. ✅ 跨设备 rename 错误
9. ✅ 动画帧率低
10. ✅ 封面切换动画不流畅
11. ✅ 多屏幕比例适配
12. ✅ 播放队列持久化
13. ✅ 库页面插播/加队列按钮失效
14. ✅ 关于页面与 Markdown 渲染
15. ✅ Splash 渐隐与副标题
16. ✅ 应用图标设计
17. ✅ 迷你播放栏导航兼容性
18. ✅ 启动首次展开掉帧
19. ✅ 歌单页面闪退
20. ✅ WebView 小屏设备登录协议显示

---

## 技术债务
- `MainScreen` 仍包含较多播放队列逻辑（约 200 行），可考虑提取 `PlaybackQueueManager`。
- 封面动画中 `screenHeightPx * 0.3f` 等硬编码比例需进一步参数化。
- Walkman / 低端设备性能降级方案尚未实现。
- 单元测试覆盖率低。
- 错误处理与日志机制需标准化。
- weapi 加密长期替代方案需预研。

## 项目关键决策记录
1. CLI 采用 Rust 重写，保留原项目授权，纯命令行交互（已完成，后续独立演进）。
2. Android UI 采用声明式 Compose，追求高性能动画。
3. 播放器交互参照 Apple Music 卡片式设计。
4. 所有动画由单一 `Animatable` 进度值驱动，降低状态复杂度。
5. GPU 优先：所有动画使用 `graphicsLayer`，避免重组。
6. 组件常驻：卡片始终存在于组件树，仅控制位置和透明度，避免销毁重建开销。
7. 三层图层架构保证触摸与视觉独立性。
8. 本地存储使用 SharedPreferences + Gson，无需引入数据库。
9. 专辑数据从本地单曲派生，保持单曲为唯一数据源。
10. 全部 API 加密统一走 `EapiCrypto` 入口。
11. 媒体服务采用 `MediaSessionService`，适配 Android 14+ 新规范。
12. 懒加载分页减少流量与内存压力。
13. 多屏幕适配使用 360dp 基准宽度限制，宽屏居中留白，保持窄屏比例。
14. 播放队列与当前播放状态分开持久化，均用 SharedPreferences。
15. 主题系统采用 6 种预设色，全局颜色引用统一为 MaterialTheme。
16. 详情页统一使用 `DetailScaffold` 模板，导航由 `NavHost` 管理，消除大量手动状态变量。

---

**最后更新**：2026 年 5 月 4 日  
**总开发时长**：约 10 天  
**累计代码量**：6058 行（47 个 Kotlin 文件）

**致谢与参考**
- 原项目：Suxiaoqinx/Netease_url（MIT 许可证）
- 动画参考：Moriafly/SaltPlayerSource（Salt UI）
- 设计参考：Apple Music for Android
- 测试人员：白给小子
- 项目立项，决定基于原 Python 项目 **Suxiaoqinx/Netease_url** 进行 Rust 重写（CLI 工具）。
- 确定仓库名为 **163CMAnalyser**，CLI 主程序名为 **ncrust-core**。
- 采用 MIT 许可证，同时保留原作者版权声明。
- 交互形态确定为纯 CLI，完全舍弃 WebUI。

### 初次架构设计
- 创建 Rust workspace 结构：
  - `ncrust-lib`（核心库）
  - `ncrust-core`（CLI 二进制）
- 核心模块划分：`crypto`、`api`、`auth`、`downloader`、`models`、`utils`。
- 确定音频质量支持等级：`standard`、`exhigh`、`lossless`、`hires`、`jyeffect`、`sky`、`jymaster`，并建立音质降级链。

---

## 2026 年 4 月 23 日

### Rust 核心库实现

#### 加密模块
- 实现 `crypto/eapi.rs`：eapi 加密算法，采用 AES-128-ECB + PKCS7 填充。
- 实现 `crypto/utils.rs`：MD5 哈希工具函数。
- 加密魔数：`nobody{url_path}use{payload}md5forencrypt`，AES 密钥：`e82ckenh8dichen8`，分隔符：`-36cd479b6b5-`。
- 手动实现 PKCS7 填充以解决兼容性问题。

#### HTTP 客户端与 API 模块
- 实现 `api/client.rs`：基于 reqwest 封装 HTTP 客户端，自动附加 Cookie、User-Agent、Referer。
- 实现 `api/endpoints.rs`：定义网易云音乐 API 端点常量。
- 实现业务 API：`api/song.rs`、`api/search.rs`、`api/playlist.rs`、`api/album.rs`、`api/login.rs`。

#### 数据模型
- `models/song.rs`、`models/playlist.rs`、`models/album.rs`、`models/lyric.rs`、`models/quality.rs`。

#### Cookie 管理与认证
- 实现 `auth/cookie.rs`：Cookie 文件读写、格式验证，重要字段为 `MUSIC_U`、`__csrf`。存储路径：`~/.config/ncrust/cookie.txt`。
- 实现 `auth/qrcode.rs`：二维码登录完整流程。

#### 下载模块
- `downloader/quality.rs`：音质降级策略。
- `downloader/task.rs`：单曲下载任务。
- `downloader/parallel.rs`：批量并发下载。
- `downloader/metadata.rs`：音频标签写入，支持 MP3/FLAC/M4A。

#### 工具模块与 CLI 命令
- `utils/id_extractor.rs`、`utils/filename.rs`、`utils/progress.rs`。
- 主程序名 `ncrust-core`，子命令：`search`、`info`、`download`、`playlist`、`album`、`config`，基于 clap Derive API。

#### 跨设备文件系统问题
- `std::fs::rename` 在 Linux 上报告 Invalid cross-device link 错误，改用 `std::fs::copy` + `std::fs::remove_file` 替代。

#### 代码注释规范
- 采用半文半白风格，用词精简。无必要不注释，复杂逻辑方加注释。禁止横幅式注释与 log 性注释。

### Android 客户端启动

#### 技术选型与项目初始化
- 确定 Android GUI 版本名称为 **Ncrust**，包名 `com.takahashirinta.ncrust`。
- 测试设备：Xperia 10 VI（骁龙 6 Gen 1，8 GB 内存）。
- 开发环境：Arch Linux + ThinkPad + Android Studio。
- UI 框架：Jetpack Compose；音频引擎：Media3 ExoPlayer；图片加载：Coil；网络：Retrofit + OkHttp。
- 设计目标：纯网络流媒体播放，交互参考 Apple Music。

#### 性能优化原则
- 针对 60 Hz 屏幕开发，使用 `graphicsLayer` 处理动画（GPU 执行，避免重组）。
- 禁止在动画中修改触发布局重计算的属性。
- 视觉风格采用直角切割（Metro Design）配合 Spotify 色调。

#### 交互模型设计
- 迷你播放栏常驻，置于导航栏上方；点击或上滑触发全屏展开。
- 全屏播放器卡片从底部滑出，动画由单一变量 `progress`（0→1）驱动。
- 所有视觉元素均为 `progress` 的纯函数。拖拽超过 25% 松手即自动展开。

#### 主界面框架
- 底部导航栏：首页、库、搜索、用户。
- 播放队列系统：`addToQueue`、`removeFromQueue`、`playFromQueue`。

---

## 2026 年 4 月 24 日

### Android UI 深化

#### Splash Screen 与性能优化
- Splash 阶段预热 JIT：数学运算 800ms、对象分配 400ms、字符串操作 400ms、Compose 状态 300ms、集合操作 300ms。
- 播放器卡片组件在 Splash 中预创建，提前完成 JIT 预热。

#### 布局结构演进
- 三层图层结构：底层主页面 → 中层播放卡片 → 顶层导航栏。
- 卡片通过 `graphicsLayer { translationY }` 控制竖直位置，导航栏独立于最外层 Box。
- 卡片展开时导航栏通过 `translationY` 向下移出屏幕。

#### 卡顿问题排查与解决
- 现象：动画帧率低，卡片跟随手势滞后。
- 根因：`animateFloatAsState` 触发重组、卡片被 `if/else` 销毁重建、迷你栏手势挂载位置错误。
- 解决：全部改用 `graphicsLayer`、卡片常驻组件树、手势移至外层容器。拖拽灵敏度调整为 `totalDragDistancePx = screenH * 0.85f`。

#### 歌词系统
- LRC 解析器：支持 `[mm:ss.xx]` 格式，大号粗体（32sp），已播放白色、当前句绿色（`#1DB954`）、未播放灰色。
- 自动定位至视图上黄金分割点（0.45），每句仅定位一次。手动滚动后 5 秒恢复自动定位。
- 歌词区域上下增加渐变融入效果。

#### 封面动画与进度条
- 封面由 `graphicsLayer` 变换：迷你 56dp × 56dp，全屏宽度撑满、Y = `screenH * 0.35f`。
- 歌词与封面通过 `Animatable` 300ms 平滑切换。
- 进度条：纯色细线设计，绿色已播放 / 灰色未播放，支持点击与拖动跳转。

#### 触摸事件重大排查
- 问题：收起全屏后主页面无法交互。
- 排查：`PlayerCardOverlay` 的 `fillMaxSize()` 拦截触摸 → 手势条件判断移除 `pointerInput` → 协程作用域不兼容。
- 最终：`pointerInput` 放置在 PlayerCard 最外层 Box 上，始终保留手势处理。

#### 库页面、搜索、播放队列
- 库页面默认标签，内部分单曲/专辑/歌单，`LibraryManager` 基于 SharedPreferences + Gson。
- 搜索页：`singleLine` 搜索框 + `ImeAction.Done`，三标签 TabRow（单曲/专辑/艺人）。
- 播放队列：与歌词共享过渡动画，点击切换歌曲，渐变融入效果。

#### 音频焦点与系统媒体控制
- 由 ExoPlayer `handleAudioFocus = true` 全权处理音频焦点。
- 实现 `MediaSessionCompat` + MediaStyle 通知，`IMPORTANCE_LOW` 配合 MediaStyle。
- Palette HARDWARE bitmap 崩溃：转为 `ARGB_8888` 可读格式解决。

#### 播放模式
- 列表循环（0）、单曲循环（1，`seekTo(0)`）、随机播放（2，生成 shuffled 索引列表顺序播放）。

---

## 2026 年 4 月 25 日

### Android 功能扩展

#### 网络层重构与专辑/艺人支持
- 专辑详情接口 `GET /api/v1/album/{id}` 测试通过。
- 艺人详情：`GET /api/artist/albums/{id}` 返回数据中嵌套 `artist` 对象。热门单曲以艺人名搜索过滤作为替代。
- 歌单同步：`POST /eapi/user/playlist` 获取列表，`POST /eapi/v6/playlist/detail` 获取歌曲。
- 用户 UID 通过 `POST /eapi/w/nuser/account/get` 提取，资料取自 `profile.nickname` / `profile.avatarUrl`。

#### 搜索与本地库深化
- 多维度搜索：单曲 type=1、专辑 type=10、艺人 type=100。`SearchViewModel` 防抖 500ms。
- 本地曲库：`LibraryManager` 使用 SharedPreferences + Gson。专辑从本地单曲 `albumId` 去重派生。
- 插播与加队列均进行去重。

#### UI 完善
- 禁止横屏：`screenOrientation="portrait"`。
- 封面切换动画：`launchedEffect` 驱动平滑过渡。大封面 Y 轴偏 0.35f。

---

## 2026 年 4 月 26 日

### 状态持久化——进程被杀恢复

- 创建 `PlaybackStateManager`，SharedPreferences 持久化当前播放状态（`songId`、`title`、`artist`、`artworkUrl`、`isPlaying`）。
- `PlayerViewModel.init()` 恢复状态，歌词与封面同步恢复。
- 媒体服务采用 `MediaSessionService`，适配 Android 14+。

---

## 2026 年 4 月 27 日～28 日

### 首页发现与日推

- 新歌速递：`GET /api/v1/discovery/new/songs`，懒加载每批 10 首。
- 推荐歌单：`POST /eapi/v1/discovery/recommend/resource`，横向滚动。"私人雷达"固定 35 首。
- 日推歌曲：以 eapi 替代 weapi，5 行 × N 列横向滑动，每列宽度占屏幕 82%。

### 登录优化
- 浏览器登录：全屏 WebView，`onPageFinished` 自动提取 Cookie。保留手动粘贴降级方案。

---

## 2026 年 4 月 29 日

### 多屏幕比例适配

- 以 21:9（Xperia 10 VI，~360dp）为基线，创建 `ResponsiveContent` 组件，宽屏内容限制最大宽度 360dp 居中。
- 各页面套用，全屏播放器 `PlayerCard` 不套宽度限制，封面 `fillMaxWidth().aspectRatio(1f)`。
- 封面纵向偏移系数调整为 0.3f。

### 播放队列持久化

- 扩展 `PlaybackStateManager`，新增 `saveQueue()`、`getQueue()`、`clearQueue()`。
- `MainScreen` 中 `LaunchedEffect` 恢复队列；各队列操作函数末尾调用 `saveQueue`。

### 库页面单曲操作修复

- `LibrarySongListItem` 增加 `onInsertNext`、`onAppendToQueue` 参数并绑定按钮。
- `MainActivity` 中删除内嵌旧 `LibraryScreen`，使用独立文件版本。

### 关于页面与 Markdown 渲染

- 新增 `ui/screen/AboutScreen.kt`，实现简易 Markdown 渲染器 `MarkdownText`。
- 用户页添加"关于 Ncrust"按钮。

### Splash Screen 视觉优化

- 标题绿色粗体 + 白色副标题 "A Re-defined Music Player" + 底部灰色小字 "Artwork by Project Arcturius"。
- `Animatable` 渐隐效果（400ms，FastOutSlowInEasing）。

### 应用图标设计

- 绿色唱片风格图标，Vector Drawable + Adaptive Icon。解决白色蒙版问题（scale 68%）。

---

## 2026 年 5 月 2 日

### 迷你播放栏导航兼容性修复

#### 问题描述
- 迷你播放栏在三键导航模式下被系统导航栏遮挡；全屏展开时标签栏未完全移出屏幕。
- Issue `#1`（UI 错位）被用户 OYinFengO 反馈（NothingOS Android 16，1080×2412 420dpi）。

#### 根因分析
- `extraOffset` 硬编码 72dp，未扣除系统导航栏高度。`enableEdgeToEdge()` 使内容延伸至系统栏后方，但卡片层未适配。

#### 修复方案
- `extraOffset` 改为 48dp，从总偏移中直接减去系统导航栏高度。
- 标签栏 `translationY` 增加 24dp 余量确保完全移出。
- 测试通过 Xperia 10 VI 手势/三键、Pixel 7 Pro 模拟器、极端分辨率。

---

## 2026 年 5 月 3 日 — 主题系统、组件统一与详情页重构

### Phase 1: 主题系统

#### 新建文件
| 文件 | 说明 |
|------|------|
| `ui/theme/ThemeManager.kt` | 6 种主题色预设 + 持久化 + `NcrustTheme` |
| `ui/theme/ThemeColorSelector.kt` | 主题色选择器 UI 组件 |

#### 主题色预设
```
云杉 #1DB954（默认） / 钴蓝 #3B82F6 / 绯红 #EF4444 / 琥珀 #F59E0B / 堇紫 #8B5CF6 / 素白 #FFFFFF
```

#### 全局颜色变更
- 背景统一为 `MaterialTheme.colorScheme.background`（OLED 纯黑 `#000000`），强调色统一为 `primary`，`surface` 统一为 `#1A1A1A`。
- 所有硬编码 `Color(0xFF121212)` 和 `Color(0xFF1DB954)` 已消除。

### Phase 2: 组件统一 — SongCard

- 新建 `ui/components/SongCard.kt`：统一曲目卡组件，支持 `LIST`（48dp 横排）、`COMPACT`（40dp 横排）、`GRID`（竖排网格）三种样式。
- 附带 `PlayAllCircleButton` 组件。
- 删除 7 个旧组件（`HomeSongListItem`、`LibrarySongListItem` ×2、`SongSearchItem`、`SongGridItem`、`AlbumSongListItem`、`PlaylistSongListItem`、`ArtistSongListItem`）。
- 所有页面替换为 `SongCard`。

### Phase 3: 详情页重构

- 新建 `ui/components/DetailScaffold.kt`（统一详情页模板 + `DetailHeader`）和 `ui/navigation/NavGraph.kt`。
- 导航架构：`NavHost`（fade 过渡），路由包括 home、album/{id}、artist/{id}、playlist/{id}、song/{id}。
- 支持多级跳转与逐级返回。删除 6 个旧状态变量。
- 新增依赖 `navigation-compose:2.8.5`。

---

## 2026 年 5 月 4 日 — 代码拆分、性能优化、歌词改进与一键播放

### 一、代码拆分

#### 背景
`MainActivity.kt` 膨胀至约 1800 行，包含 15+ 个 Composable 函数，消耗大量 token。

#### 拆分结果
| 新文件 | 移入函数 | 行数 |
|-------|---------|------|
| `ui/player/SlimProgressBar.kt` | `SlimProgressBar` | ~40 |
| `ui/player/FullPlayerControls.kt` | `FullPlayerControls` | ~120 |
| `ui/player/LyricsView.kt` | `LyricsView` | ~100 |
| `ui/player/QueueView.kt` | `QueueView` | ~50 |
| `ui/player/PlayerCardOverlay.kt` | `PlayerCardOverlay` | ~40 |
| `ui/player/PlayerCard.kt` | `PlayerCard` | ~300 |
| `ui/screen/SearchScreen.kt` | `SearchScreen` + `SongSearchItem` | ~130 |
| `ui/screen/UserScreen.kt` | `UserScreen` + `QualitySelector` | ~160 |
| `ui/components/AlbumSearchItem.kt` | `AlbumSearchItem` | ~40 |
| `ui/components/ArtistSearchItem.kt` | `ArtistSearchItem` | ~50 |
| `ui/components/LibrarySongListItem.kt` | `LibrarySongListItem` | ~50 |
| `ui/components/LibraryAlbumGridItem.kt` | `LibraryAlbumGridItem` | ~40 |
| `ui/components/SongGridItem.kt` | `SongGridItem` | ~50 |

#### 结果
- `MainActivity.kt` 从 ~1800 行缩减至 537 行，仅保留 `MainActivity`、`formatDuration`、`MainScreen`。
- Token 消耗预计下降约 70%。

---

### 二、歌词系统优化（5 月 4 日下午）

#### 2.1 歌词与音乐同步延迟修复
- **问题**：歌词显示比音乐实际播放慢一拍。
- **根因**：`PlaybackService` 每 1000ms 才更新一次播放进度，最大延迟 1 秒。
- **修复**：轮询间隔从 1000ms 缩短至 250ms，最大延迟降到 250ms。

#### 2.2 歌词动画改进
- **问题**：歌词动画不够 Apple Music 风格，当前行与非当前行视觉差异太小。
- **修复**：
  - 过渡动画从 380ms 缩短至 180ms，视觉反应更灵敏。
  - 缩放逻辑反转：激活行保持全尺寸（1.0），非激活行收缩至 82%（原为激活行放大到 105%）。
  - 效果：非激活行随距离缩小，营造 Apple Music 的层次感。
- 所有动画在 `graphicsLayer` 绘制阶段完成，不触发重组，250ms 轮询频率无 CPU 压力。

---

### 三、一键播放功能（5 月 4 日下午）

#### 3.1 功能概述
参考 Apple Music 交互：点击专辑/歌单的播放按钮，弹出操作选择弹窗——"现在播放"清空当前队列直接开播，"插播"在当前曲目后插入全部歌曲。

#### 3.2 新增文件
- `ui/components/PlayAllDialog.kt`：Metro 风格操作弹窗，两行操作（现在播放 / 插播）+ 取消按钮。

#### 3.3 修改文件
| 文件 | 改动 |
|------|------|
| `AlbumDetailScreen.kt` | 新增 `onReplaceAndPlay` / `onInsertNext` 参数；`songItems` 在加载后转换；弹窗控制 `showPlayAllDialog`；`onPlayAll` 传给 `DetailHeader` |
| `PlaylistDetailScreen.kt` | 同上，songs 已是 `List<SongItem>` 无需转换 |
| `NavGraph.kt` | `MainNavGraph` 新增两个回调参数，向下穿透到两个详情页 |
| `MainActivity.kt` | 新增 `replaceQueueAndPlay()`（清空队列、播第一首、展开播放器）和 `insertAllNext()`（去重后插入当前曲目之后）；`MainNavGraph` 接入回调 |
| `DetailScaffold.kt` | 播放按钮从封面下方居中独立行移至右侧信息列底部，右对齐，尺寸从 56dp 缩至 40dp |
| `HomeScreen.kt` / `LibraryScreen.kt`（逻辑调整） | 歌单/专辑卡片上的播放按钮不再直接播放，改为将歌曲存入 `pendingPlayAllSongs`，在 `MainScreen` 顶层弹出 `PlayAllDialog` |

#### 3.4 交互流程
进入专辑/歌单页 → 点击封面旁的播放圆形按钮 → 弹窗出现 → 选"现在播放"清空队列直接开播 / 选"插播"在当前曲目之后插入全部歌曲。

同样适用于首页推荐歌单和库页面的歌单/专辑卡片上的播放按钮。

---

### 四、最终代码体积统计（2026-05-04 15:39）

```
📊 Ncrust 项目代码行数统计 (纯 Kotlin)

■■■ 按包/目录统计
  auth                      64
  library                   105
  lyric                     27
  network                   587
  player                    483
  ui/components             668
  ui/navigation             111
  ui/player (播放卡片)      940
  ui/screen                 1881
  ui/theme                  320
  ui/viewmodel              305
  ui/ResponsiveContent      30
  MainActivity.kt           537
  ─────────────────────────────
  合计                     6058 行 (47 个文件)

各模块占比
  ui/screen                 31.0%
  ui/player                 15.5%
  ui/components             11.0%
  network                   9.7%
  MainActivity              8.9%
  player                    8.0%
  ui/theme                  5.3%
  ui/viewmodel              5.0%
  ui/navigation             1.8%
  library                   1.7%
  auth                      1.1%
  ui/root                   0.5%
  lyric                     0.4%

📄 最大文件 Top 10
  537  MainActivity.kt
  405  ui/player/PlayerCard.kt
  353  ui/screen/UserScreen.kt
  347  player/PlaybackService.kt
  289  ui/screen/HomeScreen.kt
  284  ui/screen/LibraryScreen.kt
  242  ui/screen/SearchScreen.kt
  203  ui/screen/ArtistDetailScreen.kt
  193  ui/player/LyricsView.kt
  183  ui/components/SongCard.kt
```

---

### 五、版本发布记录

| 版本 | 日期 | 内容 |
|------|------|------|
| v0.1.0-beta | 4 月 26 日 | 初始 MVP 版本 |
| v1.0.0 | 4 月 29 日 | 首个正式版，适配多屏，完善功能 |
| v1.0.1 | 5 月 4 日 | 性能大幅优化、主题色系统、尝试修复 ColorOS 错位、WebView 登录修复 |
| v1.0.2 | 5 月 4 日 | 紧急修复歌单闪退、歌词页面重构、歌词同步优化、一键播放功能 |

---

## 代码架构总结

### 分层架构
- **UI 层**：Compose 组件 + 三层图层结构（主页面 → 卡片层 → 导航栏）。
- **状态层**：单一 `Animatable(0f)` 驱动所有播放卡片动画，`graphicsLayer` 实现零重组。
- **播放层**：Media3 ExoPlayer + MediaSessionService，状态持久化，轮询间隔 250ms。
- **网络层**：Retrofit + OkHttp + Eapi 加密。
- **本地存储**：SharedPreferences + Gson，轻量无数据库。
- **路由层**：Navigation Compose 管理详情页导航。

### 包结构（47 个文件）
```
com.takahashirinta.ncrust/
├── MainActivity.kt
├── auth/CookieManager.kt
├── library/LibraryManager.kt
├── lyric/LrcParser.kt
├── network/
│   ├── NcmApi.kt, PlaylistApi.kt, RetrofitClient.kt, SearchResponse.kt
│   ├── crypto/EapiCrypto.kt
│   └── model/{AlbumDetail,ArtistDetail,SongDetail,SongUrlResponse}.kt
├── player/
│   ├── PlaybackService.kt, PlaybackStateManager.kt, SongUrlFetcher.kt
└── ui/
    ├── ResponsiveContent.kt
    ├── components/{ArtistSearchItem,DetailScaffold,LibraryAlbumGridItem,LibrarySongListItem,PlayAllDialog,SongCard,SongGridItem}.kt
    ├── navigation/NavGraph.kt
    ├── player/{FullPlayerControls,LyricsView,PlayerCard,PlayerCardOverlay,QueueView,SlimProgressBar}.kt
    ├── screen/{About,AlbumDetail,AlbumSearchItem,ArtistDetail,Home,Library,PlaylistDetail,Search,SongDetail,Splash,User}Screen.kt
    ├── theme/{MarkdownText,ThemeColorSelector,ThemeManager}.kt
    └── viewmodel/{PlayerViewModel,SearchViewModel,SongViewModel}.kt
```

---

## 已知问题与待办

### 未解决
1. 艺人热门单曲为搜索过滤结果，非真正热门歌曲排行。
2. `attributionTag` 警告持续出现在系统日志中，不影响功能但待清理。
3. WebView 登录提取 Cookie 不稳定，偶有失败。
4. 日推等接口使用 eapi 替代 weapi，长期存在失效风险。
5. 进度条数据更新偶有延迟。
6. 封面可能因 Coil 缓存策略导致模糊。
7. 专辑/艺人搜索功能尚未完全实现。
8. 播放队列历史记录待完善。
9. 本地封面缓存机制尚未实现。
10. 少量编译警告残留。

### 已解决
1. ✅ 播放卡片手势冲突
2. ✅ 通知栏不显示
3. ✅ Palette HARDWARE bitmap 崩溃
4. ✅ 进程被杀后状态丢失
5. ✅ 艺人详情 404
6. ✅ 新歌速递主线程网络异常
7. ✅ 日推 API 不可用
8. ✅ 跨设备 rename 错误
9. ✅ 动画帧率低
10. ✅ 封面切换动画不流畅
11. ✅ 多屏幕比例适配
12. ✅ 播放队列持久化
13. ✅ 库页面插播/加队列按钮失效
14. ✅ 关于页面与 Markdown 渲染
15. ✅ Splash 渐隐与副标题
16. ✅ 应用图标设计
17. ✅ 迷你播放栏导航兼容性
18. ✅ 启动首次展开掉帧
19. ✅ 歌单页面闪退
20. ✅ WebView 小屏设备登录协议显示
21. ✅ 歌词与音乐播放同步延迟
22. ✅ 歌词动画层次感不足
23. ✅ 专辑/歌单一键播放（现在播放 / 插播）

---

## 技术债务
- `MainScreen` 仍包含较多播放队列逻辑（约 200 行），可考虑提取 `PlaybackQueueManager`。
- 封面动画中 `screenHeightPx * 0.3f` 等硬编码比例需进一步参数化。
- Walkman / 低端设备性能降级方案尚未实现。
- 单元测试覆盖率低。
- 错误处理与日志机制需标准化。

## 项目关键决策记录
1. CLI 采用 Rust 重写，保留原项目授权（已完成，后续独立演进）。
2. Android UI 采用声明式 Compose，追求高性能动画。
3. 播放器交互参照 Apple Music 卡片式设计。
4. 所有动画由单一 `Animatable` 进度值驱动，降低状态复杂度。
5. GPU 优先：所有动画使用 `graphicsLayer`，避免重组。
6. 组件常驻：卡片始终存在于组件树，仅控制位置和透明度。
7. 三层图层架构保证触摸与视觉独立性。
8. 本地存储使用 SharedPreferences + Gson，无需数据库。
9. 专辑数据从本地单曲派生，保持单曲为唯一数据源。
10. 全部 API 加密统一走 `EapiCrypto` 入口。
11. 媒体服务采用 `MediaSessionService`，适配 Android 14+。
12. 懒加载分页减少流量与内存压力。
13. 多屏幕适配使用 360dp 基准宽度限制。# Ncrust 项目开发日志

**项目信息**
- 项目名称：Ncrust——网易云音乐第三方客户端
- 仓库地址：github.com/GuitaristRin/Ncrust
- 版本：v1.0.2
- 开发者：Takahashi_Rinta
- 技术栈：Kotlin + Jetpack Compose + Media3 ExoPlayer + Retrofit + Coil
- 许可证：MIT（保留原 Python 项目 Suxiaoqinx/Netease_url 版权声明）

> **说明**：本日志合并自多份开发记录，统一按 2026 年纪录。4 月 22 日至 29 日为初始开发期，5 月 2 日进行导航兼容性修复，5 月 3 日进行主题、组件与详情页重构，5 月 4 日完成代码拆分、性能优化并发布 v1.0.1/v1.0.2。累计代码量 6058 行（47 个 Kotlin 文件）。

---

## 2026 年 4 月 22 日

### 项目起源与立项
- 项目立项，决定基于原 Python 项目 **Suxiaoqinx/Netease_url** 进行 Rust 重写（CLI 工具）。
- 确定仓库名为 **163CMAnalyser**，CLI 主程序名为 **ncrust-core**。
- 采用 MIT 许可证，同时保留原作者版权声明。
- 交互形态确定为纯 CLI，完全舍弃 WebUI。

### 初次架构设计
- 创建 Rust workspace 结构：
  - `ncrust-lib`（核心库）
  - `ncrust-core`（CLI 二进制）
- 核心模块划分：
  - `crypto`（加密）
  - `api`（API 交互）
  - `auth`（认证）
  - `downloader`（下载）
  - `models`（数据模型）
  - `utils`（工具函数）
- 确定音频质量支持等级：`standard`、`exhigh`、`lossless`、`hires`、`jyeffect`、`sky`、`jymaster`，并建立音质降级链。

---

## 2026 年 4 月 23 日

### Rust 核心库实现

#### 加密模块
- 实现 `crypto/eapi.rs`：eapi 加密算法，采用 AES-128-ECB + PKCS7 填充。
- 实现 `crypto/utils.rs`：MD5 哈希工具函数（后改用 md-5 crate）。
- 加密魔数：`nobody{url_path}use{payload}md5forencrypt`
- AES 密钥：`e82ckenh8dichen8`
- 分隔符：`-36cd479b6b5-`
- 遇到 PKCS7 填充问题，最终手动实现以解决兼容性。

#### HTTP 客户端与 API 模块
- 实现 `api/client.rs`：基于 reqwest 封装 HTTP 客户端，自动附加 Cookie、User-Agent、Referer。
- 实现 `api/endpoints.rs`：定义网易云音乐 API 端点常量。
- 实现业务 API：
  - `api/song.rs`：获取歌曲播放 URL、详情、歌词。
  - `api/search.rs`：搜索歌曲。
  - `api/playlist.rs`：获取歌单详情。
  - `api/album.rs`：获取专辑详情。
  - `api/login.rs`：分析二维码登录原理，预留接口。

#### 数据模型
- `models/song.rs`：Song、Artist、AlbumInfo 结构体。
- `models/playlist.rs`：Playlist 结构体。
- `models/album.rs`：Album 结构体。
- `models/lyric.rs`：Lyric 结构体。
- `models/quality.rs`：Quality 枚举，内含音质降级链。

#### Cookie 管理与认证
- 实现 `auth/cookie.rs`：Cookie 文件读写、格式验证、有效性检查，重要字段为 `MUSIC_U`、`__csrf`。
- Cookie 存储路径：`~/.config/ncrust/cookie.txt`。
- 实现 `auth/qrcode.rs`：二维码登录完整流程（生成 key、轮询状态、提取 Cookie）。

#### 下载模块
- `downloader/quality.rs`：音质降级策略，从高到低依次尝试。
- `downloader/task.rs`：单曲下载任务。
- `downloader/parallel.rs`：批量并发下载。
- `downloader/metadata.rs`：音频标签写入，支持 MP3/FLAC/M4A 格式。

#### 工具模块
- `utils/id_extractor.rs`：从各种 URL 中提取资源 ID。
- `utils/filename.rs`：生成安全文件名。
- `utils/progress.rs`：进度条封装。

#### CLI 命令实现
- 主程序名：`ncrust-core`。
- 子命令：`search`、`info`、`download`、`playlist`、`album`、`config`。
- 基于 clap Derive API 设计命令行参数。
- 搜索结果以表格形式展示，歌曲详情包含歌词，下载过程显示实时进度。

#### 编译与调试
- 依赖版本冲突处理：aes 从 0.9 降级至 0.8。
- 替换 md5 crate 为 md-5。
- 解决编译警告（未使用导入、变量等）。

#### 跨设备文件系统问题
- `std::fs::rename` 在 Linux 上报告 Invalid cross-device link 错误，原因是临时目录 `/tmp` 与下载目录不在同一文件系统。
- 修复方案：改用 `std::fs::copy` + `std::fs::remove_file` 替代 rename。

#### 代码注释规范确立
- 采用半文半白风格，用词精简（之、其、且、即等）。
- 句末加标点，无必要不注释，复杂逻辑方加注释。
- 禁止横幅式注释与 log 性注释（如“终极版”“解决方案”），提倡单行注释。

### Android 客户端启动

#### 技术选型与项目初始化
- 确定 Android GUI 版本名称为 **Ncrust**（弃用中文名）。
- 包名：`com.takahashirinta.ncrust`。
- 测试设备：Xperia 10 VI（骁龙 6 Gen 1，8 GB 内存）。
- 开发环境：Arch Linux + ThinkPad + Android Studio。
- UI 框架：Jetpack Compose；音频引擎：Media3 ExoPlayer；图片加载：Coil；网络：Retrofit + OkHttp。
- 设计目标：纯网络流媒体播放，不提供下载功能，交互参考 Apple Music 运作方式。
- 创建 Android 项目，基于 Material 3；集成 ExoPlayer、Retrofit、OkHttp；实现 eapi 加密（AES-128-ECB，密钥 `e82ckenh8dichen8`）以及 Cookie 管理器（SharedPreferences 持久化）。

#### 性能优化原则# Ncrust 项目开发日志

**项目信息**
- 项目名称：Ncrust——网易云音乐第三方客户端
- 仓库地址：github.com/GuitaristRin/Ncrust
- 版本：v1.0.2
- 开发者：Takahashi_Rinta
- 技术栈：Kotlin + Jetpack Compose + Media3 ExoPlayer + Retrofit + Coil
- 许可证：MIT（保留原 Python 项目 Suxiaoqinx/Netease_url 版权声明）

> **说明**：本日志合并自多份开发记录，统一按 2026 年纪录。4 月 22 日至 29 日为初始开发期，5 月 2 日进行导航兼容性修复，5 月 3 日进行主题、组件与详情页重构，5 月 4 日完成代码拆分、性能优化并发布 v1.0.1/v1.0.2。累计代码量 6058 行（47 个 Kotlin 文件）。

---

## 2026 年 4 月 22 日

### 项目起源与立项
- 项目立项，决定基于原 Python 项目 **Suxiaoqinx/Netease_url** 进行 Rust 重写（CLI 工具）。
- 确定仓库名为 **163CMAnalyser**，CLI 主程序名为 **ncrust-core**。
- 采用 MIT 许可证，同时保留原作者版权声明。
- 交互形态确定为纯 CLI，完全舍弃 WebUI。

### 初次架构设计
- 创建 Rust workspace 结构：
  - `ncrust-lib`（核心库）
  - `ncrust-core`（CLI 二进制）
- 核心模块划分：
  - `crypto`（加密）
  - `api`（API 交互）
  - `auth`（认证）
  - `downloader`（下载）
  - `models`（数据模型）
  - `utils`（工具函数）
- 确定音频质量支持等级：`standard`、`exhigh`、`lossless`、`hires`、`jyeffect`、`sky`、`jymaster`，并建立音质降级链。

---

## 2026 年 4 月 23 日

### Rust 核心库实现

#### 加密模块
- 实现 `crypto/eapi.rs`：eapi 加密算法，采用 AES-128-ECB + PKCS7 填充。
- 实现 `crypto/utils.rs`：MD5 哈希工具函数（后改用 md-5 crate）。
- 加密魔数：`nobody{url_path}use{payload}md5forencrypt`
- AES 密钥：`e82ckenh8dichen8`
- 分隔符：`-36cd479b6b5-`
- 遇到 PKCS7 填充问题，最终手动实现以解决兼容性。

#### HTTP 客户端与 API 模块
- 实现 `api/client.rs`：基于 reqwest 封装 HTTP 客户端，自动附加 Cookie、User-Agent、Referer。
- 实现 `api/endpoints.rs`：定义网易云音乐 API 端点常量。
- 实现业务 API：
  - `api/song.rs`：获取歌曲播放 URL、详情、歌词。
  - `api/search.rs`：搜索歌曲。
  - `api/playlist.rs`：获取歌单详情。
  - `api/album.rs`：获取专辑详情。
  - `api/login.rs`：分析二维码登录原理，预留接口。

#### 数据模型
- `models/song.rs`：Song、Artist、AlbumInfo 结构体。
- `models/playlist.rs`：Playlist 结构体。
- `models/album.rs`：Album 结构体。
- `models/lyric.rs`：Lyric 结构体。
- `models/quality.rs`：Quality 枚举，内含音质降级链。

#### Cookie 管理与认证
- 实现 `auth/cookie.rs`：Cookie 文件读写、格式验证、有效性检查，重要字段为 `MUSIC_U`、`__csrf`。
- Cookie 存储路径：`~/.config/ncrust/cookie.txt`。
- 实现 `auth/qrcode.rs`：二维码登录完整流程（生成 key、轮询状态、提取 Cookie）。

#### 下载模块
- `downloader/quality.rs`：音质降级策略，从高到低依次尝试。
- `downloader/task.rs`：单曲下载任务。
- `downloader/parallel.rs`：批量并发下载。
- `downloader/metadata.rs`：音频标签写入，支持 MP3/FLAC/M4A 格式。

#### 工具模块
- `utils/id_extractor.rs`：从各种 URL 中提取资源 ID。
- `utils/filename.rs`：生成安全文件名。
- `utils/progress.rs`：进度条封装。

#### CLI 命令实现
- 主程序名：`ncrust-core`。
- 子命令：`search`、`info`、`download`、`playlist`、`album`、`config`。
- 基于 clap Derive API 设计命令行参数。
- 搜索结果以表格形式展示，歌曲详情包含歌词，下载过程显示实时进度。

#### 编译与调试
- 依赖版本冲突处理：aes 从 0.9 降级至 0.8。
- 替换 md5 crate 为 md-5。
- 解决编译警告（未使用导入、变量等）。

#### 跨设备文件系统问题
- `std::fs::rename` 在 Linux 上报告 Invalid cross-device link 错误，原因是临时目录 `/tmp` 与下载目录不在同一文件系统。
- 修复方案：改用 `std::fs::copy` + `std::fs::remove_file` 替代 rename。

#### 代码注释规范确立
- 采用半文半白风格，用词精简（之、其、且、即等）。
- 句末加标点，无必要不注释，复杂逻辑方加注释。
- 禁止横幅式注释与 log 性注释（如“终极版”“解决方案”），提倡单行注释。

### Android 客户端启动

#### 技术选型与项目初始化
- 确定 Android GUI 版本名称为 **Ncrust**（弃用中文名）。
- 包名：`com.takahashirinta.ncrust`。
- 测试设备：Xperia 10 VI（骁龙 6 Gen 1，8 GB 内存）。
- 开发环境：Arch Linux + ThinkPad + Android Studio。
- UI 框架：Jetpack Compose；音频引擎：Media3 ExoPlayer；图片加载：Coil；网络：Retrofit + OkHttp。
- 设计目标：纯网络流媒体播放，不提供下载功能，交互参考 Apple Music 运作方式。
- 创建 Android 项目，基于 Material 3；集成 ExoPlayer、Retrofit、OkHttp；实现 eapi 加密（AES-128-ECB，密钥 `e82ckenh8dichen8`）以及 Cookie 管理器（SharedPreferences 持久化）。

#### 性能优化原则
- 针对 60 Hz 屏幕开发，使用 `graphicsLayer` 处理动画（GPU 执行，避免重组）。
- 禁止在动画中修改触发布局重计算的属性。
- 视觉风格采用直角切割（Metro Design）配合 Spotify 色调。

#### 交互模型设计
- 迷你播放栏常驻，置于导航栏上方；点击或上滑手势可触发展开。
- 全屏播放器卡片从底部滑出，动画进度由单一变量 `progress`（0→1）驱动。
- 所有视觉元素（位置、大小、透明度）均为 `progress` 的纯函数。
- 拖拽超过 25% 松手即自动展开，否则回弹。

#### 主界面框架
- 底部导航栏包含首页、库、搜索、用户四个标签。
- 播放队列系统实现 `addToQueue`、`removeFromQueue`、`playFromQueue`。

---

## 2026 年 4 月 24 日

### Rust 侧收尾
- 4 月 23 日各模块基本完成，24 日进行集成调试与注释规范应用。

### Android UI 深化

#### Splash Screen 与性能优化
- 实现预热机制：在 Splash Screen 阶段进行密集计算以预热 JIT。
- 预热阶段分布：
  - 数学运算 800 ms
  - 对象分配 400 ms
  - 字符串操作 400 ms
  - Compose 状态 300 ms
  - 集合操作 300 ms
- 播放器卡片组件在 Splash 中预创建（song 参数为 null），提前完成 JIT 预热。

#### 布局结构演进
- 确定三层图层结构：
  - 底层：主页面内容
  - 中层：全屏/迷你播放卡片
  - 顶层：导航栏
- 导航栏独立于最外层 Box，使用 `Modifier.align(Alignment.BottomCenter)`。
- 卡片通过 `graphicsLayer { translationY }` 控制竖直位置，从导航栏上方滑入。
- 卡片展开时，导航栏通过 `translationY` 向下移出屏幕。

#### 卡顿问题排查与解决
- 现象：动画帧率低，卡片跟随手势滞后。
- 根因：
  1. `animateFloatAsState` 触发不必要的 Compose 重组。
  2. 卡片通过 `if/else` 被销毁重建。
  3. 迷你栏手势挂载位置错误。
- 解决方案：
  1. 全部动画改用 `graphicsLayer` 直接进行底层计算。
  2. 卡片常驻组件树，仅通过 `translationY` 控制可见位置。
  3. 导航栏使用 `alpha` 控制透明度，不再销毁。
  4. 手势从条件渲染组件移至始终存在的外层容器。
- 拖拽灵敏度参数调整为 `totalDragDistancePx = screenH * 0.85f`。

#### 歌词系统
- LRC 解析器：支持 `[mm:ss.xx]` 格式。
- 显示规格：大号粗体（32sp），已播放部分白色，当前句绿色（`#1DB954`），未播放灰色。
- 自动定位：当前句定位到视图的**上黄金分割点**（0.45），每句仅定位一次。
- 用户手动滚动后 5 秒无操作恢复自动定位。
- 歌词区域上下增加渐变融入效果。

#### 封面动画系统
- 封面始终存在，形状与位置由 `graphicsLayer` 变换。
- 迷你模式：56 dp × 56 dp。
- 全屏模式：宽度撑满屏幕，竖直位置居中偏上（Y = `screenH * 0.35f`）。
- 歌词与封面之间通过 `Animatable` 实现 300 ms 平滑切换。
- 封面模式下顶部信息栏可见，切换至歌词时渐隐渐显；下拉按钮始终可见。

#### 进度条设计
- 纯色细线设计：已播放部分绿色（`#1DB954`），未播放灰色（`#404040`），无拖拽圆点。
- 支持点击与拖动跳转。
- 左侧显示当前时间，右侧显示总时长。

#### 触摸事件重大排查
- 问题：收起全屏后主页面无法交互。
- 排查过程：
  1. `PlayerCardOverlay` 的 `fillMaxSize()` 覆盖层拦截了全部触摸事件。
  2. 手势条件判断在稳态时移除了 `pointerInput`，导致无法再次拉起。
  3. 动态高度方案破坏了原有布局。
  4. 手势区域限制方案导致协程作用域不兼容。
- 最终方案：将 `pointerInput` 放置在 PlayerCard 的最外层 Box 上，并始终保留手势处理。

#### 库页面（Library）设计
- 默认标签页设为“库”（`selectedTab = 1`）。
- 内部分类：单曲、专辑、歌单。
- 单曲视图：两列封面墙，正方形封面下方显示歌曲名与歌手名，保留适当间距，`LazyColumn` 可滚动，点击具有弹性动画波纹反馈。
- 专辑、歌单为占位页面（即将推出）。
- 本地存储：`LibraryManager` 基于 SharedPreferences + Gson 实现。
- 全屏播放页添加加号按钮，可将当前歌曲存入曲库。

#### 搜索页面重新设计
- 搜索框为 `singleLine`，回车后收起键盘（`ImeAction.Done`），右侧提供清空按钮（X 图标）。
- 分类标签：单曲、专辑、艺人。
- 单曲搜索结果：每行左侧封面 + 歌曲信息，右侧加号（加入库）与箭头（立即播放）。
- 专辑搜索：封面墙（占位）。
- 艺人搜索：圆形头像列表（占位）。

#### 播放队列设计
- 队列与歌词共享同一过渡动画效果。
- 队列条目：一行一首歌，左侧封面 + 歌曲信息，右侧移除按钮（X）。
- 点击某行直接切换到对应歌曲播放。
- 渐变过渡效果与歌词区块一致（上下渐变）。
- 底部控制栏增加队列按钮（使用 PlaylistPlay 图标）。

#### 音频焦点管理
- 实现 `AudioManager` 请求音频焦点，确保与其他 App（如 Apple Music）互不干扰。
- 初始错误：手动焦点管理与 ExoPlayer 内部管理冲突，后改为由 ExoPlayer 通过 `handleAudioFocus = true` 全权处理。

#### 系统媒体控制
- 实现 `MediaSessionCompat` + MediaStyle 通知，支持锁屏与控制中心媒体控件。
- 通知显示问题：最初通知不显示，排查后发现需要调用 `setMediaSession(sessionToken)`；重要性需设为 `IMPORTANCE_LOW` 并配合 MediaStyle 才能在 Android 11+ 正确展示。
- 封面提取：通过 Coil 加载封面并利用 Palette 提取主色调，设置 `setColor()` 与 `setLargeIcon()`。
- 遇到 `Config#HARDWARE` bitmap 导致 Palette 无法读取像素的崩溃，解决方法是将 bitmap 复制为 `Config.ARGB_8888` 的可读格式。
- 进度条通过 `MediaSession.setPlaybackState()` 的 `setBufferedPosition()` 显示，Android 13+ 系统自动从中读取并绘制进度。

#### 歌词系统补充
- 切歌时重置滚动状态，确保新歌词从起始位置开始自动定位。

#### 播放模式设计
- 列表循环（0）：顺序播放至末尾后返回队列首部。
- 单曲循环（1）：始终播放当前歌曲（通过 `seekTo(0)` 实现）。
- 随机播放（2）：生成 shuffled 索引列表，然后按该列表顺序播放，而非每次随机选曲。

---

## 2026 年 4 月 25 日

### Rust CLI 最终完善（当日细节参见 4.23 日志，25 日主要聚焦 Android）

### Android 功能扩展

#### 网络层重构与专辑/艺人支持
- 专辑详情接口 `GET /api/v1/album/{id}` 测试通过。
- 探索艺人详情 API 过程：
  - `GET /api/artist/detail/{id}` → 404
  - `POST /eapi/artist/detail` → 400（参数错误）
  - `POST /eapi/v1/artist/detail` → 400
  - 最终采用 `GET /api/artist/albums/{id}`，其返回数据中嵌套了 `artist` 对象，可以获取艺人基本信息。
- 艺人热门单曲：因无独立热门歌曲接口，以艺人名进行 type=1 搜索并过滤出该艺人歌曲，作为替代方案。
- 歌单同步：
  - 通过 `POST /eapi/user/playlist` 获取用户创建/收藏的歌单列表。
  - 通过 `POST /eapi/v6/playlist/detail` 获取歌单内歌曲。
  - 用户 UID 通过 `POST /eapi/w/nuser/account/get` 从 `profile.userId` 提取。
- 用户资料：昵称位于 `profile.nickname`，头像位于 `profile.avatarUrl`；`account.userName` 为加密用户名，不可直接使用。

#### 搜索与本地库深化
- 多维度搜索：单曲 `type=1`、专辑 `type=10`、艺人 `type=100`。
- `SearchViewModel` 管理搜索状态，防抖设置为 500 ms。
- `SearchScreen` 使用三标签 TabRow 切换类别。
- 本地曲库存储方案：`LibraryManager` 使用 SharedPreferences + Gson 序列化 `List<SongItem>`。
- 专辑统计：不作为独立实体存储，而是从本地单曲列表中按 `albumId` 去重计数，实现专辑派生。
- 插播与加队列：
  - 插播：插入到当前播放位置的下一首。
  - 加队列：追加到播放列表末尾。
  - 两者添加前均进行去重（`filter { it.id != song.id }`）。

#### 最新 UI 完善
- 禁止横屏模式：`AndroidManifest.xml` 设置 `screenOrientation="portrait"`。
- 修复封面切换动画：添加 `launchedEffect` 驱动平滑过渡。
- 大封面 Y 轴位置开放为用户偏好参数 0.35f。
- 播放列表按钮集成至全屏播放控件。
- 修复进度条数据更新延迟问题，优化 PlaybackService 回调。

---

## 2026 年 4 月 26 日

### 状态持久化——进程被杀恢复

#### 问题现象
- 8 GB RAM 的 Xperia 10 VI 仍然在后台频繁杀死进程，日志出现 `Operation not started: CONTROL_AUDIO` 等系统 LMK 痕迹。
- 尝试添加 `MODIFY_AUDIO_SETTINGS` 权限无效，尝试 `attributionTag` 导致 AAPT 编译错误（需 API 31+）。
- 最终方案：创建 `PlaybackStateManager`，利用 SharedPreferences 持久化当前播放状态。

#### 保存与恢复
- 保存字段：`songId`、`title`、`artist`、`artworkUrl`、`isPlaying`。
- `PlayerViewModel.init()` 读取上述持久化状态进行恢复。
- 歌词恢复：依据保存的 `songId` 重新加载对应歌词文件。
- 封面恢复：根据 `artworkUrl` 重新加载封面图片。

#### 其他后台管理
- 系统媒体服务采用 `MediaSessionService` 而非普通 Service，以符合 Android 14+ 媒体播放规范。
- 明确不再使用手动音频焦点管理，防止与 ExoPlayer 内置机制冲突。

---

## 2026 年 4 月 27 日～28 日

### 首页发现与日推

#### 新歌速递
- API：`GET /api/v1/discovery/new/songs?limit=10&offset={offset}`。
- 实现懒加载，每批拉取 10 首（初版 limit=20 造成流量浪费，改为 10）。
- 滚动到底部自动加载更多。

#### 推荐歌单
- API：`POST /eapi/v1/discovery/recommend/resource`。
- 展示方式：横向滚动，每个封面附播放按钮。
- “私人雷达”歌单固定显示 35 首（API 返回 `trackCount=0`）。

#### 日推歌曲
- 探索历程：
  1. 直接 `curl` 不带加密参数 → 无返回数据。
  2. 在网页版 Network 中观察到请求使用 `weapi/v2/...` 加密。
  3. 尝试 `eapi` 替代路径 `eapi/v2/...` → 获得成功。
- 前端展示：5 行 × N 列横向滑动，每列宽度占屏幕 82%。

#### 登录优化
- 点击“未登录”弹出选项弹窗。
- “浏览器登录”：全屏 WebView 打开网易云音乐登录页，通过 `onPageFinished` 自动提取 Cookie。
- 已知问题：WebView Cookie 提取偶尔失败，未能稳定获取 `MUSIC_U`。
- 保留手动粘贴 Cookie 的降级方案。

---

## 2026 年 4 月 29 日

### 多屏幕比例适配

#### 核心策略
- 以 21:9（Xperia 10 VI，宽度约 360dp）为基线，宽屏设备内容区域限制最大宽度 360dp 居中显示，保留窄屏视觉比例。
- 创建 `ResponsiveContent` 组件，内含 `Box(widthIn = 360.dp)` 包裹内容，在宽度 ≤ 360dp 时撑满，不影响原设备体验。

#### 各页面适配详情
- **HomeScreen**：套用 `ResponsiveContent`，日推横向滚动列改用 `fillParentMaxWidth(0.9f)`。
- **LibraryScreen**：整体包裹 `ResponsiveContent`，网格行继续使用 `weight` 均分。
- **PlaylistDetailScreen**：移除 `screenWidth * 0.5f`，封面改用 `Modifier.weight(0.4f).aspectRatio(1f)`。
- **AlbumDetailScreen**：同上处理。
- **ArtistDetailScreen**、**SongDetailScreen**：外层套 `ResponsiveContent`。
- **全屏播放器 PlayerCard**：不套用宽度限制，封面使用 `fillMaxWidth().aspectRatio(1f)`，动画计算基于实际屏幕宽度，确保全屏播放时卡片撑满、元素位置正确。
- 封面纵向偏移系数调整为 0.3f，用户可后续调整。

#### 编译错误修复
- 处理 `Dp.toPx()` 需要在 density 上下文中调用、浮点运算歧义等问题，将相关数值提前转为 px 变量，消除编译错误。

---

### 播放队列持久化

- 问题：进程被杀死后，歌词、封面可恢复，但播放队列丢失。
- 根源：`playbackQueue` 与 `currentQueueIndex` 仅存于内存，未持久化。
- 方案：扩展 `PlaybackStateManager`，新增 `saveQueue()`、`getQueue()`、`clearQueue()` 方法，利用 Gson 序列化 `List<SongItem>` 存入 SharedPreferences。
- 修改点：
  - `PlaybackStateManager` 增加队列持久化逻辑。
  - `MainScreen` 中增加 `LaunchedEffect` 恢复队列；在 `addToQueue`、`removeFromQueue`、`playFromQueue`、`insertNext`、`appendToQueue` 等函数末尾调用 `saveQueue`。
  - `PlayerViewModel.stopService()` 内调用 `clearQueue` 清除持久化队列。
- 修复过程中处理了函数定义顺序（`generateShuffledIndices` 先于调用）、重复函数定义等编译问题。

---

### 库页面单曲操作修复

- 现象：库中单曲的“插播”和“加入播放列表”按钮点击无反应。
- 原因：`LibrarySongListItem` 缺少 `onInsertNext`、`onAppendToQueue` 参数，点击回调仅为 TODO 注释。同时 `MainActivity` 内的旧版 `LibraryScreen` 未传递这些回调。
- 修复：
  - 更新 `LibrarySongListItem` 签名，增加 `onInsertNext`、`onAppendToQueue` 参数并绑定至对应按钮。
  - 在 `ui/screen/LibraryScreen.kt` 中为 `LibraryScreen` 添加 `onSongInsertNext`、`onSongAppendToQueue` 参数，传递至 `LibrarySongListItem`。
  - `MainActivity` 中调用 `LibraryScreen` 时传入 `insertNext`、`appendToQueue` 函数。
  - 删除 `MainActivity` 内嵌的旧 `LibraryScreen`，使用独立文件版本。

---

### 关于页面与 Markdown 渲染

- 新增 `ui/screen/AboutScreen.kt`：关于页面包含返回按钮、系统返回手势支持，内容区域使用 `ResponsiveContent`。
- 实现简易 Markdown 渲染器 `MarkdownText`（`ui/theme/MarkdownText.kt`），支持标题（#、##、###）、粗体、斜体、行内代码、无序列表、水平分割线、图片。
- 关于页面内容由 `aboutMarkdown` 变量控制，方便后续编辑。
- 在用户页音质调节下方添加“关于 Ncrust”按钮，点击跳转至关于页面。

---

### Splash Screen 视觉优化

- 修改 `SplashScreen`：标题“Ncrust”改为绿色粗体，下方添加白色副标题“A Re-defined Music Player”，底部居中灰色小字“Artwork by Project Arcturius”。
- 实现渐隐效果：预热完成后使用 `Animatable` 将 alpha 从 1f 动画至 0f（400ms，FastOutSlowInEasing），取代原先的闪没。
- 移除 `MainActivity.kt` 中旧的内嵌 `SplashScreen` 定义，统一使用 `ui/screen/SplashScreen.kt`。
- 修复了 `graphicsLayer` import 缺失导致的编译错误。

---

### 应用图标设计

- 设计绿色唱片风格图标：绿色圆形底，中心白色同心圆环、实心内圈、中心绿孔，右侧播放三角形缺口。
- 创建 `res/drawable/ic_launcher.xml`（Vector Drawable）。
- 配置 Adaptive Icon：`res/mipmap-anydpi-v26/ic_launcher.xml`，背景绿色，前景为唱片 vector。
- 解决手机显示白色蒙版问题：通过缩小唱片组（scale 68%）让外层绿色露出一圈。
- 图标加入关于页顶部居中显示，使用 `R.drawable.ic_launcher` 避免 `painterResource` 对 mipmap 的兼容问题。

---

### 其他优化

- 清除部分编译警告：替换弃用的 `PlaylistPlay`、`PlaylistAdd` 为 `AutoMirrored` 版本；移除未使用的函数、属性、参数；移除未使用的 import 等。
- 修复 `AboutScreen` 中图标加载崩溃（mipmap 不支持）改用 drawable。
- 调整系统返回手势：关于页使用 `BackHandler` 支持手势返回。

---

## 2026 年 5 月 2 日

### 迷你播放栏导航兼容性修复

#### 问题描述
- 迷你播放栏（MiniBar）在三键导航模式下位置错位，被系统导航栏遮挡或偏移；手势导航模式下正常。
- 全屏展开时，底部标签栏（NavigationBar）向下避让未完全移出屏幕，在部分设备上残留可见。
- Issue `#1`（UI 错位）被用户 OYinFengO 反馈，设备为 NothingOS Android 16，1080×2412 420dpi，手势模式但仍有底部空隙。

#### 根因分析
- `PlayerCardOverlay` 定位完全依赖屏幕物理高度减去固定值：
  ```kotlin
  val startY = screenHPx - navBarPx - miniBarPx - extraOffset
  ```
  `extraOffset` 被硬编码为 72dp，未扣除系统导航栏高度。
- `enableEdgeToEdge()` 使内容延伸至系统栏后方，但卡片层与标签栏均未适配系统导航栏实际占用空间，三键模式下视觉错位。

#### 修复方案探索
- **尝试 1**：使用 `WindowInsets.navigationBars` 动态获取高度，但在 Compose 内部返回 0，无法使用。
- **尝试 2**：使用 `view.rootWindowInsets` 获取真实高度（手势 68px，三键 135px），但直接减去该高度后两种模式视觉不一致。
- **最终方案**：保留原公式基本结构，将 `extraOffset` 改为 48dp（视觉调优），并从总偏移中直接减去系统导航栏高度，实现模式间同步：
  ```kotlin
  val collapsedOffsetY = screenHeightPx - systemNavBarHeightPx 
                         - navBarHeightPx - miniBarHeightPx - 48.dp.toPx()
  ```
- **标签栏避让修复**：为 `NavigationBar` 的 `translationY` 增加 24dp 余量，确保完全移出屏幕：
  ```kotlin
  translationY = (navBarHeightPx + systemNavBarHeightPx + 24.dp.toPx()) * progress.value
  ```

#### 修改文件
- `MainActivity.kt`：`MainScreen` 中新增系统导航栏高度获取，修改 `collapsedOffsetY` 计算及标签栏动画位移。
- `PlayerCardOverlay` 与 `PlayerCard`：签名改为接收外部计算好的 `collapsedOffsetY` 和 `screenHeightPx`，移除内部冗余的屏幕高度计算。

#### 测试验证
- Xperia 10 VI（21:9，手势 / 三键）：迷你栏位置一致，全屏展开标签栏完全隐藏。
- 模拟器 Pixel 7 Pro（19.5:9，手势 / 三键）：通过。
- 极端分辨率（`adb shell wm size 1080x1920` 强制窄屏）：交互正常，无错位或遮挡。
- 交互性能未下降，动画流畅性保持原有水平。

#### 结论
该修复以最小改动量实现手势与三键导航下迷你栏位置同步，全屏动画标签栏无残留，覆盖主流设备与极端分辨率，兼容性显著提升。

---

## 2026 年 5 月 3 日 — 主题系统、组件统一与详情页重构

> 本日工作在分支 `refactor/theme-and-components` 下完成，涵盖 Phase 1~3。

### Phase 1: 主题系统

#### 1.1 新建文件
| 文件 | 说明 |
|------|------|
| `ui/theme/ThemeManager.kt` | 6 种主题色预设 + 持久化 + `NcrustTheme` |
| `ui/theme/ThemeColorSelector.kt` | 主题色选择器 UI 组件 |

#### 1.2 主题色预设
```
云杉 #1DB954  // 默认
钴蓝 #3B82F6
绯红 #EF4444
琥珀 #F59E0B
堇紫 #8B5CF6
素白 #FFFFFF
```

#### 1.3 全局颜色变更
- 背景统一为 `MaterialTheme.colorScheme.background`（OLED 纯黑 `#000000`）
- 强调色统一为 `MaterialTheme.colorScheme.primary`
- `surface` 统一为 `#1A1A1A`
- 所有硬编码 `Color(0xFF121212)` 已消除
- 所有硬编码 `Color(0xFF1DB954)` 已消除

#### 1.4 修改文件
| 文件 | 修改内容 |
|------|---------|
| `MainActivity.kt` | 删除旧 `NcrustTheme`，改用新版本；`setContent` 中注入主题索引状态；所有背景色引用改为 `MaterialTheme.colorScheme.background`；所有主色引用改为 `MaterialTheme.colorScheme.primary` |
| 用户页 (UserScreen) | 新增“主题色”选择区域，复用 `QualitySelector` 风格 |
| `QualitySelector` | 选中色改为 `MaterialTheme.colorScheme.primary` |

---

### Phase 2: 组件统一 — SongCard

#### 2.1 新建文件
`ui/components/SongCard.kt`：统一曲目卡组件（3 种样式 + 播放按钮）

#### 2.2 SongCard 样式
| 样式 | 封面 | 布局 | 用途 |
|------|------|------|------|
| `LIST` | 48dp | 横排双行 | 库、搜索、主页 |
| `COMPACT` | 40dp | 横排双行 | 详情页、队列 |
| `GRID` | 填充宽度 | 竖排 | 网格展示 |

附带组件 `PlayAllCircleButton`：圆形播放按钮（主题色自适应）。

#### 2.3 删除的旧组件
`HomeSongListItem`、`LibrarySongListItem` (×2 重复)、`SongSearchItem`、`SongGridItem`、`AlbumSongListItem`、`PlaylistSongListItem`、`ArtistSongListItem`。

#### 2.4 修改文件
| 文件 | 修改内容 |
|------|---------|
| `HomeScreen.kt` | 替换为 `SongCard` + `PlayAllCircleButton` |
| `LibraryScreen.kt` | 同上 |
| `SearchScreen` | 替换为 `SongCard` |
| `QueueView` | 替换为 `SongCard` |
| `AlbumDetailScreen.kt` | 替换为 `SongCard` |
| `PlaylistDetailScreen.kt` | 替换为 `SongCard` |
| `ArtistDetailScreen.kt` | 替换为 `SongCard` |

---

### Phase 3: 详情页重构

#### 3.1 新建文件
| 文件 | 说明 |
|------|------|
| `ui/components/DetailScaffold.kt` | 统一详情页模板 + `DetailHeader` |
| `ui/navigation/NavGraph.kt` | 导航图 + `NavRoutes` 路由定义 |

#### 3.2 导航架构
```
NavHost (fade 过渡动画)
├── "home"           → 占位，内容由 MainScreen 填充
├── "album/{id}"     → AlbumDetailScreen
├── "artist/{id}"    → ArtistDetailScreen
├── "playlist/{id}"  → PlaylistDetailScreen
└── "song/{id}"      → SongDetailScreen
```

#### 3.3 关键特性
- **多级跳转**：专辑 → 艺术家 → 专辑，`navController.popBackStack()` 逐级返回
- **fade 动画**：渐入渐出，避免滑动动画在低端设备的性能问题
- **底部导航栏保持可见**：Apple Music 风格
- **播放器卡片始终覆盖**：所有详情页上方可见

#### 3.4 删除的旧状态变量
```kotlin
var selectedSongId
var selectedAlbumId
var selectedArtistId
var selectedPlaylistId
var selectedPlaylistName
var selectedPlaylistCover
// + 旧的 BackHandler 块
```

#### 3.5 新增依赖
```kotlin
implementation("androidx.navigation:navigation-compose:2.8.5")
```

---

### 代码指标（5 月 3 日完成后）
| 指标 | 修改前 | 修改后 | 变化 |
|------|--------|--------|------|
| MainActivity.kt 行数 | ~2400 | ~1900 | -21% |
| 曲目卡组件数 | 7 个独立函数 | 1 个 SongCard | -86% |
| 硬编码绿色引用 | ~25 处 | 0 | -100% |
| 硬编码灰色背景 | ~15 处 | 0 | -100% |
| 详情页状态变量 | 6 个 | 0（NavHost 管理） | -100% |

---

## 2026 年 5 月 4 日 — 代码拆分、性能优化与问题修复

> 本日完成了 `MainActivity.kt` 的彻底拆分，修复了多个动画与布局问题，并发布了 v1.0.1 与 v1.0.2。最终代码总量精准确认为 **6058 行 / 47 个 Kotlin 文件**。

### 一、代码拆分

#### 背景
`MainActivity.kt` 膨胀至约 1800 行，包含 15+ 个 Composable 函数，每次 Claude Code 分析需读取整个文件，消耗大量 token。为降低分析开销并改善代码组织，进行拆分。

#### 拆分结果
| 新文件 | 移入函数 | 行数 |
|-------|---------|------|
| `ui/player/SlimProgressBar.kt` | `SlimProgressBar` | ~40 |
| `ui/player/FullPlayerControls.kt` | `FullPlayerControls` | ~120 |
| `ui/player/LyricsView.kt` | `LyricsView` | ~100 |
| `ui/player/QueueView.kt` | `QueueView` | ~50 |
| `ui/player/PlayerCardOverlay.kt` | `PlayerCardOverlay` | ~40 |
| `ui/player/PlayerCard.kt` | `PlayerCard` | ~300 |
| `ui/screen/SearchScreen.kt` | `SearchScreen` + `SongSearchItem` | ~130 |
| `ui/screen/UserScreen.kt` | `UserScreen` + `QualitySelector` | ~160 |
| `ui/components/AlbumSearchItem.kt` | `AlbumSearchItem` | ~40 |
| `ui/components/ArtistSearchItem.kt` | `ArtistSearchItem` | ~50 |
| `ui/components/LibrarySongListItem.kt` | `LibrarySongListItem` | ~50 |
| `ui/components/LibraryAlbumGridItem.kt` | `LibraryAlbumGridItem` | ~40 |
| `ui/components/SongGridItem.kt` | `SongGridItem` | ~50 |

#### 结果
- `MainActivity.kt` 从 ~1800 行缩减至 537 行，仅保留 `MainActivity`、`formatDuration`、`MainScreen`
- Claude Code 单次分析 token 消耗预计下降约 70%
- 编译问题修复：
  - `LyricsView.kt`：修正 `animateScrollToItem` import 路径
  - `SlimProgressBar.kt`：补充 background import

---

### 二、关键问题修复（5 月 2 日—4 日总结）

#### 2.1 迷你播放栏导航兼容性（已解决）
- **问题**：三键导航模式下迷你栏位置错位，被系统导航栏遮挡
- **根因**：`collapsedOffsetY` 公式中 `fullCardExtraOffsetPx` 硬编码 48dp，未考虑厂商定制 ROM 状态栏高度差异
- **修复**：`fullCardExtraOffsetPx` 改为动态计算 `statusBarHeightDp + 24.dp`
- **参考**：Claude Code 分析发现 48dp = 24dp(状态栏) + 24dp(NavBar 补偿)，ColorOS 状态栏约 60dp 导致补偿不足
- **测试**：Xperia 10 VI 手势/三键均正常，ColorOS 已通过

#### 2.2 全屏展开后标签栏避让不足（已解决）
- **问题**：`NavigationBar` 避让未完全移出屏幕
- **修复**：隐退量改为固定 132dp

#### 2.3 启动首次展开掉帧（已解决）
- **问题**：启动后第一次拉起全屏动画掉帧，后续流畅
- **根因**：
  1. 迷你栏用 `if (progress.value < 0.3f)` 条件渲染，动画期间触发重组
  2. `fullAlpha` 在 Composition 阶段读取 `progress.value`，触发全函数重组
  3. GPU Shader 首次编译在动画路径上
- **修复**（Claude Code）：
  1. 迷你栏改为始终渲染，仅用 `graphicsLayer.alpha` 控制透明度
  2. `fullAlpha` 移入各 `graphicsLayer` lambda，避免 Composition 阶段读取 `progress.value`
  3. Splash 期间通过 `LaunchedEffect` 瞬间展开再收起卡片，预热 GPU Shader
  4. Splash 移除无效的 CPU 预热代码，改为等待 Composition 完成
- **效果**：Xperia 10 VI 上动画明显流畅

#### 2.4 迷你栏图层错位（已解决）
- **问题**：性能优化后迷你栏透明但占位 56dp，全屏时顶推歌曲信息
- **修复**：迷你栏从 `Column` 内移至外层 `Box`，用 `statusBarsPadding()` 定位，不参与 `Column` 布局

#### 2.5 封面动画重构（已完成）
- 封面变换改为基于中心点插值（迷你封面中心 → 全屏封面中心）
- 引入 `lyricAnimProgress` 控制封面在两个状态间的过渡
- 收起/展开动画曲线差异化（`tween(190)` vs `tween(300)`）

#### 2.6 歌单页面闪退（已解决）
- **问题**：v1.0.1 点开任意歌单闪退（Issue `#11`）
- **修复**：紧急修复并发布 v1.0.2

#### 2.7 WebView 登录兼容性（已解决）
- **问题**：小屏设备（NW-A105）无法滚动到用户协议勾选框（Issue `#2`）
- **修复**：调整 WebView 布局与滚动策略

---

## 2026 年 5 月 4 日 16:22 — 切歌崩溃与后台播放中断修复

### 问题描述
- 切歌时应用闪退，随后无法再次打开，陷入崩溃循环。
- 后台播放一段时间后出现音爆并停止播放（与社区 Issue 报告一致）。

### 根因分析

#### Bug 1：LazyColumn 重复 key 崩溃（主要崩溃）
- **位置**：`LyricsView.kt:126`
- **原因**：LazyColumn 使用 `line.timeMs` 作为 item key。实际网易云 LRC 文件中存在两条非空歌词行共享 `timeMs = 0` 的情况（如并行翻译、前奏重叠标记），导致 Compose 抛出 `Key "0" was already used`。
- **崩溃循环**：崩溃前播放的歌曲 ID 被 `PlaybackStateManager` 持久化，每次启动应用都会重新加载该歌词并再次触发相同崩溃。

#### Bug 2：后台播放停止
- **位置**：`PlaybackService.onDestroy()`
- **原因**：`onDestroy()` 中将 `onProgressUpdate`、`onPlaybackEnded`、`onIsPlayingChanged` 等静态回调置 null。服务因 `stopSelf()` 销毁后若重新创建，ViewModel 注册的回调已丢失，自动切歌和进度追踪静默失效。

#### Bug 3：快速切歌时音频异常
- **位置**：`PlayerViewModel.playSong()`
- **原因**：连续快速切歌时，前一次 `launch` 的 URL 获取协程尚未完成，新的协程已启动。旧的协程返回后覆盖当前歌曲 URL，导致音频不连续或音爆。

### 修复方案

#### Fix 1 — LyricsView.kt
```kotlin
// 修改前
itemsIndexed(lyrics, key = { _, line -> line.timeMs }) { index, line -> ... }

// 修改后
itemsIndexed(lyrics, key = { index, _ -> index }) { index, line -> ... }
```
使用稳定且唯一的索引作为 key，消除重复 timeMs 导致的崩溃。

#### Fix 2 — PlaybackService.kt
```kotlin
// onDestroy() 中删除以下三行
// onProgressUpdate = null
// onPlaybackEnded = null
// onIsPlayingChanged = null
```
静态回调由 ViewModel 的 `init` 注册，应由 ViewModel 的 `onCleared()` 负责清空。Service 销毁重建时回调必须存活。

#### Fix 3 — PlayerViewModel.kt
```kotlin
// playSong() 方法开头新增
playJob?.cancel()
playJob = viewModelScope.launch(Dispatchers.IO) { ... }
```
取消前次未完成的播放协程，确保只有最新请求生效。

### 修改文件
| 文件 | 改动 |
|------|------|
| `ui/player/LyricsView.kt` | LazyColumn key 从 `line.timeMs` 改为 `index` |
| `player/PlaybackService.kt` | `onDestroy()` 不再置空静态回调 |
| `ui/viewmodel/PlayerViewModel.kt` | `playSong()` 新增协程取消逻辑 |

### 编译验证
- `./gradlew assembleDebug` 编译通过，无新增警告。
### 三、最终代码体积统计（2026-05-04）

```
📊 Ncrust 项目代码行数统计 (纯 Kotlin)

■■■ 按包/目录统计
  auth                      64
  crypto                    0   (合并至 network/crypto)
  library                   105
  lyric                     27
  network                   587
  player                    483
  ui/components             668
  ui/navigation             111
  ui/player (播放卡片)      940
  ui/screen                 1881
  ui/theme                  320
  ui/viewmodel              305
  ui/ResponsiveContent      30
  MainActivity.kt           537
  ─────────────────────────────
  合计                     6058 行 (47 个文件)

各模块占比
  auth                      1.1%
  library                   1.7%
  lyric                     0.4%
  network                   9.7%
  player                    8.0%
  ui/components             11.0%
  ui/navigation             1.8%
  ui/player                 15.5%
  ui/screen                 31.0%
  ui/theme                  5.3%
  ui/viewmodel              5.0%
  ui/root                   0.5%
  MainActivity              8.9%

📄 最大文件 Top 10
  537  MainActivity.kt
  405  ui/player/PlayerCard.kt
  353  ui/screen/UserScreen.kt
  347  player/PlaybackService.kt
  289  ui/screen/HomeScreen.kt
  284  ui/screen/LibraryScreen.kt
  242  ui/screen/SearchScreen.kt
  203  ui/screen/ArtistDetailScreen.kt
  193  ui/player/LyricsView.kt
  183  ui/components/SongCard.kt
```

---

### 四、版本发布记录

| 版本 | 日期 | 内容 |
|------|------|------|
| v0.1.0-beta | 4 月 26 日 | 初始 MVP 版本 |
| v1.0.0 | 4 月 29 日 | 首个正式版，适配多屏，完善功能 |
| v1.0.1 | 5 月 4 日 | 性能大幅优化、主题色系统、尝试修复 ColorOS 错位、WebView 登录修复 |
| v1.0.2 | 5 月 4 日 | 紧急修复歌单闪退、歌词页面重构 |

---

## 代码架构总结（最终状态）

### 分层架构
- **UI 层**：Compose 组件 + 三层图层结构（主页面 → 卡片层 → 导航栏）。
- **状态层**：单一 `Animatable(0f)` 驱动所有播放卡片动画，`graphicsLayer` 实现零重组。
- **播放层**：Media3 ExoPlayer + MediaSessionService，状态持久化。
- **网络层**：Retrofit + OkHttp + Eapi 加密（`crypto/EapiCrypto.kt` 位于 `network/crypto/`）。
- **本地存储**：SharedPreferences + Gson，轻量无数据库。
- **路由层**：Navigation Compose 管理详情页导航。

### 包结构（最终版，47 个文件）
```
com.takahashirinta.ncrust/
├── MainActivity.kt
├── auth/CookieManager.kt
├── library/LibraryManager.kt
├── lyric/LrcParser.kt
├── network/
│   ├── NcmApi.kt
│   ├── PlaylistApi.kt
│   ├── RetrofitClient.kt
│   ├── SearchResponse.kt
│   ├── crypto/EapiCrypto.kt
│   └── model/
│       ├── AlbumDetail.kt
│       ├── ArtistDetail.kt
│       ├── SongDetail.kt
│       └── SongUrlResponse.kt
├── player/
│   ├── PlaybackService.kt
│   ├── PlaybackStateManager.kt
│   └── SongUrlFetcher.kt
└── ui/
    ├── ResponsiveContent.kt
    ├── components/
    │   ├── ArtistSearchItem.kt
    │   ├── DetailScaffold.kt
    │   ├── LibraryAlbumGridItem.kt
    │   ├── LibrarySongListItem.kt
    │   ├── SongCard.kt
    │   └── SongGridItem.kt
    ├── navigation/
    │   └── NavGraph.kt
    ├── player/
    │   ├── FullPlayerControls.kt
    │   ├── LyricsView.kt
    │   ├── PlayerCard.kt
    │   ├── PlayerCardOverlay.kt
    │   ├── QueueView.kt
    │   └── SlimProgressBar.kt
    ├── screen/
    │   ├── AboutScreen.kt
    │   ├── AlbumDetailScreen.kt
    │   ├── AlbumSearchItem.kt      (实际属于搜索功能，但物理位于 ui/screen)
    │   ├── ArtistDetailScreen.kt
    │   ├── HomeScreen.kt
    │   ├── LibraryScreen.kt
    │   ├── PlaylistDetailScreen.kt
    │   ├── SearchScreen.kt
    │   ├── SongDetailScreen.kt
    │   ├── SplashScreen.kt
    │   └── UserScreen.kt
    ├── theme/
    │   ├── MarkdownText.kt
    │   ├── ThemeColorSelector.kt
    │   └── ThemeManager.kt
    └── viewmodel/
        ├── PlayerViewModel.kt
        ├── SearchViewModel.kt
        └── SongViewModel.kt
```

---

## 已知问题与待办

### 未解决
1. 艺人热门单曲为搜索过滤结果，非真正热门歌曲排行。
2. `attributionTag` 警告持续出现在系统日志中，不影响功能但待清理。
3. WebView 登录提取 Cookie 不稳定，偶有失败。
4. 日推等接口当前使用 eapi 替代 weapi，长期存在失效风险。
5. 进度条数据更新偶有延迟。
6. 封面可能因 Coil 缓存策略导致模糊，需优化。
7. 专辑/艺人搜索功能尚未完全实现。
8. 播放队列历史记录待完善。
9. 本地封面缓存机制尚未实现。
10. 少量编译警告残留（如未使用参数等），后续版本再清理。

### 已解决
1. ✅ 播放卡片手势冲突
2. ✅ 通知栏不显示
3. ✅ Palette HARDWARE bitmap 崩溃
4. ✅ 进程被杀后状态丢失
5. ✅ 艺人详情 404
6. ✅ 新歌速递主线程网络异常
7. ✅ 日推 API 不可用
8. ✅ 跨设备 rename 错误
9. ✅ 动画帧率低
10. ✅ 封面切换动画不流畅
11. ✅ 多屏幕比例适配
12. ✅ 播放队列持久化
13. ✅ 库页面插播/加队列按钮失效
14. ✅ 关于页面与 Markdown 渲染
15. ✅ Splash 渐隐与副标题
16. ✅ 应用图标设计
17. ✅ 迷你播放栏导航兼容性
18. ✅ 启动首次展开掉帧
19. ✅ 歌单页面闪退
20. ✅ WebView 小屏设备登录协议显示

---

## 技术债务
- `MainScreen` 仍包含较多播放队列逻辑（约 200 行），可考虑提取 `PlaybackQueueManager`。
- 封面动画中 `screenHeightPx * 0.3f` 等硬编码比例需进一步参数化。
- Walkman / 低端设备性能降级方案尚未实现。
- 单元测试覆盖率低。
- 错误处理与日志机制需标准化。
- weapi 加密长期替代方案需预研。

## 项目关键决策记录
1. CLI 采用 Rust 重写，保留原项目授权，纯命令行交互（已完成，后续独立演进）。
2. Android UI 采用声明式 Compose，追求高性能动画。
3. 播放器交互参照 Apple Music 卡片式设计。
4. 所有动画由单一 `Animatable` 进度值驱动，降低状态复杂度。
5. GPU 优先：所有动画使用 `graphicsLayer`，避免重组。
6. 组件常驻：卡片始终存在于组件树，仅控制位置和透明度，避免销毁重建开销。
7. 三层图层架构保证触摸与视觉独立性。
8. 本地存储使用 SharedPreferences + Gson，无需引入数据库。
9. 专辑数据从本地单曲派生，保持单曲为唯一数据源。
10. 全部 API 加密统一走 `EapiCrypto` 入口。
11. 媒体服务采用 `MediaSessionService`，适配 Android 14+ 新规范。
12. 懒加载分页减少流量与内存压力。
13. 多屏幕适配使用 360dp 基准宽度限制，宽屏居中留白，保持窄屏比例。
14. 播放队列与当前播放状态分开持久化，均用 SharedPreferences。
15. 主题系统采用 6 种预设色，全局颜色引用统一为 MaterialTheme。
16. 详情页统一使用 `DetailScaffold` 模板，导航由 `NavHost` 管理，消除大量手动状态变量。

---

**最后更新**：2026 年 5 月 4 日  
**总开发时长**：约 10 天  
**累计代码量**：6058 行（47 个 Kotlin 文件）

**致谢与参考**
- 原项目：Suxiaoqinx/Netease_url（MIT 许可证）
- 动画参考：Moriafly/SaltPlayerSource（Salt UI）
- 设计参考：Apple Music for Android
- 测试人员：白给小子
- 针对 60 Hz 屏幕开发，使用 `graphicsLayer` 处理动画（GPU 执行，避免重组）。
- 禁止在动画中修改触发布局重计算的属性。
- 视觉风格采用直角切割（Metro Design）配合 Spotify 色调。

#### 交互模型设计
- 迷你播放栏常驻，置于导航栏上方；点击或上滑手势可触发展开。
- 全屏播放器卡片从底部滑出，动画进度由单一变量 `progress`（0→1）驱动。
- 所有视觉元素（位置、大小、透明度）均为 `progress` 的纯函数。
- 拖拽超过 25% 松手即自动展开，否则回弹。

#### 主界面框架
- 底部导航栏包含首页、库、搜索、用户四个标签。
- 播放队列系统实现 `addToQueue`、`removeFromQueue`、`playFromQueue`。

---

## 2026 年 4 月 24 日

### Rust 侧收尾
- 4 月 23 日各模块基本完成，24 日进行集成调试与注释规范应用。

### Android UI 深化

#### Splash Screen 与性能优化
- 实现预热机制：在 Splash Screen 阶段进行密集计算以预热 JIT。
- 预热阶段分布：
  - 数学运算 800 ms
  - 对象分配 400 ms
  - 字符串操作 400 ms
  - Compose 状态 300 ms
  - 集合操作 300 ms
- 播放器卡片组件在 Splash 中预创建（song 参数为 null），提前完成 JIT 预热。

#### 布局结构演进
- 确定三层图层结构：
  - 底层：主页面内容
  - 中层：全屏/迷你播放卡片
  - 顶层：导航栏
- 导航栏独立于最外层 Box，使用 `Modifier.align(Alignment.BottomCenter)`。
- 卡片通过 `graphicsLayer { translationY }` 控制竖直位置，从导航栏上方滑入。
- 卡片展开时，导航栏通过 `translationY` 向下移出屏幕。

#### 卡顿问题排查与解决
- 现象：动画帧率低，卡片跟随手势滞后。
- 根因：
  1. `animateFloatAsState` 触发不必要的 Compose 重组。
  2. 卡片通过 `if/else` 被销毁重建。
  3. 迷你栏手势挂载位置错误。
- 解决方案：
  1. 全部动画改用 `graphicsLayer` 直接进行底层计算。
  2. 卡片常驻组件树，仅通过 `translationY` 控制可见位置。
  3. 导航栏使用 `alpha` 控制透明度，不再销毁。
  4. 手势从条件渲染组件移至始终存在的外层容器。
- 拖拽灵敏度参数调整为 `totalDragDistancePx = screenH * 0.85f`。

#### 歌词系统
- LRC 解析器：支持 `[mm:ss.xx]` 格式。
- 显示规格：大号粗体（32sp），已播放部分白色，当前句绿色（`#1DB954`），未播放灰色。
- 自动定位：当前句定位到视图的**上黄金分割点**（0.45），每句仅定位一次。
- 用户手动滚动后 5 秒无操作恢复自动定位。
- 歌词区域上下增加渐变融入效果。

#### 封面动画系统
- 封面始终存在，形状与位置由 `graphicsLayer` 变换。
- 迷你模式：56 dp × 56 dp。
- 全屏模式：宽度撑满屏幕，竖直位置居中偏上（Y = `screenH * 0.35f`）。
- 歌词与封面之间通过 `Animatable` 实现 300 ms 平滑切换。
- 封面模式下顶部信息栏可见，切换至歌词时渐隐渐显；下拉按钮始终可见。

#### 进度条设计
- 纯色细线设计：已播放部分绿色（`#1DB954`），未播放灰色（`#404040`），无拖拽圆点。
- 支持点击与拖动跳转。
- 左侧显示当前时间，右侧显示总时长。

#### 触摸事件重大排查
- 问题：收起全屏后主页面无法交互。
- 排查过程：
  1. `PlayerCardOverlay` 的 `fillMaxSize()` 覆盖层拦截了全部触摸事件。
  2. 手势条件判断在稳态时移除了 `pointerInput`，导致无法再次拉起。
  3. 动态高度方案破坏了原有布局。
  4. 手势区域限制方案导致协程作用域不兼容。
- 最终方案：将 `pointerInput` 放置在 PlayerCard 的最外层 Box 上，并始终保留手势处理。

#### 库页面（Library）设计
- 默认标签页设为“库”（`selectedTab = 1`）。
- 内部分类：单曲、专辑、歌单。
- 单曲视图：两列封面墙，正方形封面下方显示歌曲名与歌手名，保留适当间距，`LazyColumn` 可滚动，点击具有弹性动画波纹反馈。
- 专辑、歌单为占位页面（即将推出）。
- 本地存储：`LibraryManager` 基于 SharedPreferences + Gson 实现。
- 全屏播放页添加加号按钮，可将当前歌曲存入曲库。

#### 搜索页面重新设计
- 搜索框为 `singleLine`，回车后收起键盘（`ImeAction.Done`），右侧提供清空按钮（X 图标）。
- 分类标签：单曲、专辑、艺人。
- 单曲搜索结果：每行左侧封面 + 歌曲信息，右侧加号（加入库）与箭头（立即播放）。
- 专辑搜索：封面墙（占位）。
- 艺人搜索：圆形头像列表（占位）。

#### 播放队列设计
- 队列与歌词共享同一过渡动画效果。
- 队列条目：一行一首歌，左侧封面 + 歌曲信息，右侧移除按钮（X）。
- 点击某行直接切换到对应歌曲播放。
- 渐变过渡效果与歌词区块一致（上下渐变）。
- 底部控制栏增加队列按钮（使用 PlaylistPlay 图标）。

#### 音频焦点管理
- 实现 `AudioManager` 请求音频焦点，确保与其他 App（如 Apple Music）互不干扰。
- 初始错误：手动焦点管理与 ExoPlayer 内部管理冲突，后改为由 ExoPlayer 通过 `handleAudioFocus = true` 全权处理。

#### 系统媒体控制
- 实现 `MediaSessionCompat` + MediaStyle 通知，支持锁屏与控制中心媒体控件。
- 通知显示问题：最初通知不显示，排查后发现需要调用 `setMediaSession(sessionToken)`；重要性需设为 `IMPORTANCE_LOW` 并配合 MediaStyle 才能在 Android 11+ 正确展示。
- 封面提取：通过 Coil 加载封面并利用 Palette 提取主色调，设置 `setColor()` 与 `setLargeIcon()`。
- 遇到 `Config#HARDWARE` bitmap 导致 Palette 无法读取像素的崩溃，解决方法是将 bitmap 复制为 `Config.ARGB_8888` 的可读格式。
- 进度条通过 `MediaSession.setPlaybackState()` 的 `setBufferedPosition()` 显示，Android 13+ 系统自动从中读取并绘制进度。

#### 歌词系统补充
- 切歌时重置滚动状态，确保新歌词从起始位置开始自动定位。

#### 播放模式设计
- 列表循环（0）：顺序播放至末尾后返回队列首部。
- 单曲循环（1）：始终播放当前歌曲（通过 `seekTo(0)` 实现）。
- 随机播放（2）：生成 shuffled 索引列表，然后按该列表顺序播放，而非每次随机选曲。

---

## 2026 年 4 月 25 日

### Rust CLI 最终完善（当日细节参见 4.23 日志，25 日主要聚焦 Android）

### Android 功能扩展

#### 网络层重构与专辑/艺人支持
- 专辑详情接口 `GET /api/v1/album/{id}` 测试通过。
- 探索艺人详情 API 过程：
  - `GET /api/artist/detail/{id}` → 404
  - `POST /eapi/artist/detail` → 400（参数错误）
  - `POST /eapi/v1/artist/detail` → 400
  - 最终采用 `GET /api/artist/albums/{id}`，其返回数据中嵌套了 `artist` 对象，可以获取艺人基本信息。
- 艺人热门单曲：因无独立热门歌曲接口，以艺人名进行 type=1 搜索并过滤出该艺人歌曲，作为替代方案。
- 歌单同步：
  - 通过 `POST /eapi/user/playlist` 获取用户创建/收藏的歌单列表。
  - 通过 `POST /eapi/v6/playlist/detail` 获取歌单内歌曲。
  - 用户 UID 通过 `POST /eapi/w/nuser/account/get` 从 `profile.userId` 提取。
- 用户资料：昵称位于 `profile.nickname`，头像位于 `profile.avatarUrl`；`account.userName` 为加密用户名，不可直接使用。

#### 搜索与本地库深化
- 多维度搜索：单曲 `type=1`、专辑 `type=10`、艺人 `type=100`。
- `SearchViewModel` 管理搜索状态，防抖设置为 500 ms。
- `SearchScreen` 使用三标签 TabRow 切换类别。
- 本地曲库存储方案：`LibraryManager` 使用 SharedPreferences + Gson 序列化 `List<SongItem>`。
- 专辑统计：不作为独立实体存储，而是从本地单曲列表中按 `albumId` 去重计数，实现专辑派生。
- 插播与加队列：
  - 插播：插入到当前播放位置的下一首。
  - 加队列：追加到播放列表末尾。
  - 两者添加前均进行去重（`filter { it.id != song.id }`）。

#### 最新 UI 完善
- 禁止横屏模式：`AndroidManifest.xml` 设置 `screenOrientation="portrait"`。
- 修复封面切换动画：添加 `launchedEffect` 驱动平滑过渡。
- 大封面 Y 轴位置开放为用户偏好参数 0.35f。
- 播放列表按钮集成至全屏播放控件。
- 修复进度条数据更新延迟问题，优化 PlaybackService 回调。

---

## 2026 年 4 月 26 日

### 状态持久化——进程被杀恢复

#### 问题现象
- 8 GB RAM 的 Xperia 10 VI 仍然在后台频繁杀死进程，日志出现 `Operation not started: CONTROL_AUDIO` 等系统 LMK 痕迹。
- 尝试添加 `MODIFY_AUDIO_SETTINGS` 权限无效，尝试 `attributionTag` 导致 AAPT 编译错误（需 API 31+）。
- 最终方案：创建 `PlaybackStateManager`，利用 SharedPreferences 持久化当前播放状态。

#### 保存与恢复
- 保存字段：`songId`、`title`、`artist`、`artworkUrl`、`isPlaying`。
- `PlayerViewModel.init()` 读取上述持久化状态进行恢复。
- 歌词恢复：依据保存的 `songId` 重新加载对应歌词文件。
- 封面恢复：根据 `artworkUrl` 重新加载封面图片。

#### 其他后台管理
- 系统媒体服务采用 `MediaSessionService` 而非普通 Service，以符合 Android 14+ 媒体播放规范。
- 明确不再使用手动音频焦点管理，防止与 ExoPlayer 内置机制冲突。

---

## 2026 年 4 月 27 日～28 日

### 首页发现与日推

#### 新歌速递
- API：`GET /api/v1/discovery/new/songs?limit=10&offset={offset}`。
- 实现懒加载，每批拉取 10 首（初版 limit=20 造成流量浪费，改为 10）。
- 滚动到底部自动加载更多。

#### 推荐歌单
- API：`POST /eapi/v1/discovery/recommend/resource`。
- 展示方式：横向滚动，每个封面附播放按钮。
- “私人雷达”歌单固定显示 35 首（API 返回 `trackCount=0`）。

#### 日推歌曲
- 探索历程：
  1. 直接 `curl` 不带加密参数 → 无返回数据。
  2. 在网页版 Network 中观察到请求使用 `weapi/v2/...` 加密。
  3. 尝试 `eapi` 替代路径 `eapi/v2/...` → 获得成功。
- 前端展示：5 行 × N 列横向滑动，每列宽度占屏幕 82%。

#### 登录优化
- 点击“未登录”弹出选项弹窗。
- “浏览器登录”：全屏 WebView 打开网易云音乐登录页，通过 `onPageFinished` 自动提取 Cookie。
- 已知问题：WebView Cookie 提取偶尔失败，未能稳定获取 `MUSIC_U`。
- 保留手动粘贴 Cookie 的降级方案。

---

## 2026 年 4 月 29 日

### 多屏幕比例适配

#### 核心策略
- 以 21:9（Xperia 10 VI，宽度约 360dp）为基线，宽屏设备内容区域限制最大宽度 360dp 居中显示，保留窄屏视觉比例。
- 创建 `ResponsiveContent` 组件，内含 `Box(widthIn = 360.dp)` 包裹内容，在宽度 ≤ 360dp 时撑满，不影响原设备体验。

#### 各页面适配详情
- **HomeScreen**：套用 `ResponsiveContent`，日推横向滚动列改用 `fillParentMaxWidth(0.9f)`。
- **LibraryScreen**：整体包裹 `ResponsiveContent`，网格行继续使用 `weight` 均分。
- **PlaylistDetailScreen**：移除 `screenWidth * 0.5f`，封面改用 `Modifier.weight(0.4f).aspectRatio(1f)`。
- **AlbumDetailScreen**：同上处理。
- **ArtistDetailScreen**、**SongDetailScreen**：外层套 `ResponsiveContent`。
- **全屏播放器 PlayerCard**：不套用宽度限制，封面使用 `fillMaxWidth().aspectRatio(1f)`，动画计算基于实际屏幕宽度，确保全屏播放时卡片撑满、元素位置正确。
- 封面纵向偏移系数调整为 0.3f，用户可后续调整。

#### 编译错误修复
- 处理 `Dp.toPx()` 需要在 density 上下文中调用、浮点运算歧义等问题，将相关数值提前转为 px 变量，消除编译错误。

---

### 播放队列持久化

- 问题：进程被杀死后，歌词、封面可恢复，但播放队列丢失。
- 根源：`playbackQueue` 与 `currentQueueIndex` 仅存于内存，未持久化。
- 方案：扩展 `PlaybackStateManager`，新增 `saveQueue()`、`getQueue()`、`clearQueue()` 方法，利用 Gson 序列化 `List<SongItem>` 存入 SharedPreferences。
- 修改点：
  - `PlaybackStateManager` 增加队列持久化逻辑。
  - `MainScreen` 中增加 `LaunchedEffect` 恢复队列；在 `addToQueue`、`removeFromQueue`、`playFromQueue`、`insertNext`、`appendToQueue` 等函数末尾调用 `saveQueue`。
  - `PlayerViewModel.stopService()` 内调用 `clearQueue` 清除持久化队列。
- 修复过程中处理了函数定义顺序（`generateShuffledIndices` 先于调用）、重复函数定义等编译问题。

---

### 库页面单曲操作修复

- 现象：库中单曲的“插播”和“加入播放列表”按钮点击无反应。
- 原因：`LibrarySongListItem` 缺少 `onInsertNext`、`onAppendToQueue` 参数，点击回调仅为 TODO 注释。同时 `MainActivity` 内的旧版 `LibraryScreen` 未传递这些回调。
- 修复：
  - 更新 `LibrarySongListItem` 签名，增加 `onInsertNext`、`onAppendToQueue` 参数并绑定至对应按钮。
  - 在 `ui/screen/LibraryScreen.kt` 中为 `LibraryScreen` 添加 `onSongInsertNext`、`onSongAppendToQueue` 参数，传递至 `LibrarySongListItem`。
  - `MainActivity` 中调用 `LibraryScreen` 时传入 `insertNext`、`appendToQueue` 函数。
  - 删除 `MainActivity` 内嵌的旧 `LibraryScreen`，使用独立文件版本。

---

### 关于页面与 Markdown 渲染

- 新增 `ui/screen/AboutScreen.kt`：关于页面包含返回按钮、系统返回手势支持，内容区域使用 `ResponsiveContent`。
- 实现简易 Markdown 渲染器 `MarkdownText`（`ui/theme/MarkdownText.kt`），支持标题（#、##、###）、粗体、斜体、行内代码、无序列表、水平分割线、图片。
- 关于页面内容由 `aboutMarkdown` 变量控制，方便后续编辑。
- 在用户页音质调节下方添加“关于 Ncrust”按钮，点击跳转至关于页面。

---

### Splash Screen 视觉优化

- 修改 `SplashScreen`：标题“Ncrust”改为绿色粗体，下方添加白色副标题“A Re-defined Music Player”，底部居中灰色小字“Artwork by Project Arcturius”。
- 实现渐隐效果：预热完成后使用 `Animatable` 将 alpha 从 1f 动画至 0f（400ms，FastOutSlowInEasing），取代原先的闪没。
- 移除 `MainActivity.kt` 中旧的内嵌 `SplashScreen` 定义，统一使用 `ui/screen/SplashScreen.kt`。
- 修复了 `graphicsLayer` import 缺失导致的编译错误。

---

### 应用图标设计

- 设计绿色唱片风格图标：绿色圆形底，中心白色同心圆环、实心内圈、中心绿孔，右侧播放三角形缺口。
- 创建 `res/drawable/ic_launcher.xml`（Vector Drawable）。
- 配置 Adaptive Icon：`res/mipmap-anydpi-v26/ic_launcher.xml`，背景绿色，前景为唱片 vector。
- 解决手机显示白色蒙版问题：通过缩小唱片组（scale 68%）让外层绿色露出一圈。
- 图标加入关于页顶部居中显示，使用 `R.drawable.ic_launcher` 避免 `painterResource` 对 mipmap 的兼容问题。

---

### 其他优化

- 清除部分编译警告：替换弃用的 `PlaylistPlay`、`PlaylistAdd` 为 `AutoMirrored` 版本；移除未使用的函数、属性、参数；移除未使用的 import 等。
- 修复 `AboutScreen` 中图标加载崩溃（mipmap 不支持）改用 drawable。
- 调整系统返回手势：关于页使用 `BackHandler` 支持手势返回。

---

## 2026 年 5 月 2 日

### 迷你播放栏导航兼容性修复

#### 问题描述
- 迷你播放栏（MiniBar）在三键导航模式下位置错位，被系统导航栏遮挡或偏移；手势导航模式下正常。
- 全屏展开时，底部标签栏（NavigationBar）向下避让未完全移出屏幕，在部分设备上残留可见。
- Issue `#1`（UI 错位）被用户 OYinFengO 反馈，设备为 NothingOS Android 16，1080×2412 420dpi，手势模式但仍有底部空隙。

#### 根因分析
- `PlayerCardOverlay` 定位完全依赖屏幕物理高度减去固定值：
  ```kotlin
  val startY = screenHPx - navBarPx - miniBarPx - extraOffset
  ```
  `extraOffset` 被硬编码为 72dp，未扣除系统导航栏高度。
- `enableEdgeToEdge()` 使内容延伸至系统栏后方，但卡片层与标签栏均未适配系统导航栏实际占用空间，三键模式下视觉错位。

#### 修复方案探索
- **尝试 1**：使用 `WindowInsets.navigationBars` 动态获取高度，但在 Compose 内部返回 0，无法使用。
- **尝试 2**：使用 `view.rootWindowInsets` 获取真实高度（手势 68px，三键 135px），但直接减去该高度后两种模式视觉不一致。
- **最终方案**：保留原公式基本结构，将 `extraOffset` 改为 48dp（视觉调优），并从总偏移中直接减去系统导航栏高度，实现模式间同步：
  ```kotlin
  val collapsedOffsetY = screenHeightPx - systemNavBarHeightPx 
                         - navBarHeightPx - miniBarHeightPx - 48.dp.toPx()
  ```
- **标签栏避让修复**：为 `NavigationBar` 的 `translationY` 增加 24dp 余量，确保完全移出屏幕：
  ```kotlin
  translationY = (navBarHeightPx + systemNavBarHeightPx + 24.dp.toPx()) * progress.value
  ```

#### 修改文件
- `MainActivity.kt`：`MainScreen` 中新增系统导航栏高度获取，修改 `collapsedOffsetY` 计算及标签栏动画位移。
- `PlayerCardOverlay` 与 `PlayerCard`：签名改为接收外部计算好的 `collapsedOffsetY` 和 `screenHeightPx`，移除内部冗余的屏幕高度计算。

#### 测试验证
- Xperia 10 VI（21:9，手势 / 三键）：迷你栏位置一致，全屏展开标签栏完全隐藏。
- 模拟器 Pixel 7 Pro（19.5:9，手势 / 三键）：通过。
- 极端分辨率（`adb shell wm size 1080x1920` 强制窄屏）：交互正常，无错位或遮挡。
- 交互性能未下降，动画流畅性保持原有水平。

#### 结论
该修复以最小改动量实现手势与三键导航下迷你栏位置同步，全屏动画标签栏无残留，覆盖主流设备与极端分辨率，兼容性显著提升。

---

## 2026 年 5 月 3 日 — 主题系统、组件统一与详情页重构

> 本日工作在分支 `refactor/theme-and-components` 下完成，涵盖 Phase 1~3。

### Phase 1: 主题系统

#### 1.1 新建文件
| 文件 | 说明 |
|------|------|
| `ui/theme/ThemeManager.kt` | 6 种主题色预设 + 持久化 + `NcrustTheme` |
| `ui/theme/ThemeColorSelector.kt` | 主题色选择器 UI 组件 |

#### 1.2 主题色预设
```
云杉 #1DB954  // 默认
钴蓝 #3B82F6
绯红 #EF4444
琥珀 #F59E0B
堇紫 #8B5CF6
素白 #FFFFFF
```

#### 1.3 全局颜色变更
- 背景统一为 `MaterialTheme.colorScheme.background`（OLED 纯黑 `#000000`）
- 强调色统一为 `MaterialTheme.colorScheme.primary`
- `surface` 统一为 `#1A1A1A`
- 所有硬编码 `Color(0xFF121212)` 已消除
- 所有硬编码 `Color(0xFF1DB954)` 已消除

#### 1.4 修改文件
| 文件 | 修改内容 |
|------|---------|
| `MainActivity.kt` | 删除旧 `NcrustTheme`，改用新版本；`setContent` 中注入主题索引状态；所有背景色引用改为 `MaterialTheme.colorScheme.background`；所有主色引用改为 `MaterialTheme.colorScheme.primary` |
| 用户页 (UserScreen) | 新增“主题色”选择区域，复用 `QualitySelector` 风格 |
| `QualitySelector` | 选中色改为 `MaterialTheme.colorScheme.primary` |

---

### Phase 2: 组件统一 — SongCard

#### 2.1 新建文件
`ui/components/SongCard.kt`：统一曲目卡组件（3 种样式 + 播放按钮）

#### 2.2 SongCard 样式
| 样式 | 封面 | 布局 | 用途 |
|------|------|------|------|
| `LIST` | 48dp | 横排双行 | 库、搜索、主页 |
| `COMPACT` | 40dp | 横排双行 | 详情页、队列 |
| `GRID` | 填充宽度 | 竖排 | 网格展示 |

附带组件 `PlayAllCircleButton`：圆形播放按钮（主题色自适应）。

#### 2.3 删除的旧组件
`HomeSongListItem`、`LibrarySongListItem` (×2 重复)、`SongSearchItem`、`SongGridItem`、`AlbumSongListItem`、`PlaylistSongListItem`、`ArtistSongListItem`。

#### 2.4 修改文件
| 文件 | 修改内容 |
|------|---------|
| `HomeScreen.kt` | 替换为 `SongCard` + `PlayAllCircleButton` |
| `LibraryScreen.kt` | 同上 |
| `SearchScreen` | 替换为 `SongCard` |
| `QueueView` | 替换为 `SongCard` |
| `AlbumDetailScreen.kt` | 替换为 `SongCard` |
| `PlaylistDetailScreen.kt` | 替换为 `SongCard` |
| `ArtistDetailScreen.kt` | 替换为 `SongCard` |

---

### Phase 3: 详情页重构

#### 3.1 新建文件
| 文件 | 说明 |
|------|------|
| `ui/components/DetailScaffold.kt` | 统一详情页模板 + `DetailHeader` |
| `ui/navigation/NavGraph.kt` | 导航图 + `NavRoutes` 路由定义 |

#### 3.2 导航架构
```
NavHost (fade 过渡动画)
├── "home"           → 占位，内容由 MainScreen 填充
├── "album/{id}"     → AlbumDetailScreen
├── "artist/{id}"    → ArtistDetailScreen
├── "playlist/{id}"  → PlaylistDetailScreen
└── "song/{id}"      → SongDetailScreen
```

#### 3.3 关键特性
- **多级跳转**：专辑 → 艺术家 → 专辑，`navController.popBackStack()` 逐级返回
- **fade 动画**：渐入渐出，避免滑动动画在低端设备的性能问题
- **底部导航栏保持可见**：Apple Music 风格
- **播放器卡片始终覆盖**：所有详情页上方可见

#### 3.4 删除的旧状态变量
```kotlin
var selectedSongId
var selectedAlbumId
var selectedArtistId
var selectedPlaylistId
var selectedPlaylistName
var selectedPlaylistCover
// + 旧的 BackHandler 块
```

#### 3.5 新增依赖
```kotlin
implementation("androidx.navigation:navigation-compose:2.8.5")
```

---

### 代码指标（5 月 3 日完成后）
| 指标 | 修改前 | 修改后 | 变化 |
|------|--------|--------|------|
| MainActivity.kt 行数 | ~2400 | ~1900 | -21% |
| 曲目卡组件数 | 7 个独立函数 | 1 个 SongCard | -86% |
| 硬编码绿色引用 | ~25 处 | 0 | -100% |
| 硬编码灰色背景 | ~15 处 | 0 | -100% |
| 详情页状态变量 | 6 个 | 0（NavHost 管理） | -100% |

---

## 2026 年 5 月 4 日 — 代码拆分、性能优化与问题修复

> 本日完成了 `MainActivity.kt` 的彻底拆分，修复了多个动画与布局问题，并发布了 v1.0.1 与 v1.0.2。最终代码总量精准确认为 **6058 行 / 47 个 Kotlin 文件**。

### 一、代码拆分

#### 背景
`MainActivity.kt` 膨胀至约 1800 行，包含 15+ 个 Composable 函数，每次 Claude Code 分析需读取整个文件，消耗大量 token。为降低分析开销并改善代码组织，进行拆分。

#### 拆分结果
| 新文件 | 移入函数 | 行数 |
|-------|---------|------|
| `ui/player/SlimProgressBar.kt` | `SlimProgressBar` | ~40 |
| `ui/player/FullPlayerControls.kt` | `FullPlayerControls` | ~120 |
| `ui/player/LyricsView.kt` | `LyricsView` | ~100 |
| `ui/player/QueueView.kt` | `QueueView` | ~50 |
| `ui/player/PlayerCardOverlay.kt` | `PlayerCardOverlay` | ~40 |
| `ui/player/PlayerCard.kt` | `PlayerCard` | ~300 |
| `ui/screen/SearchScreen.kt` | `SearchScreen` + `SongSearchItem` | ~130 |
| `ui/screen/UserScreen.kt` | `UserScreen` + `QualitySelector` | ~160 |
| `ui/components/AlbumSearchItem.kt` | `AlbumSearchItem` | ~40 |
| `ui/components/ArtistSearchItem.kt` | `ArtistSearchItem` | ~50 |
| `ui/components/LibrarySongListItem.kt` | `LibrarySongListItem` | ~50 |
| `ui/components/LibraryAlbumGridItem.kt` | `LibraryAlbumGridItem` | ~40 |
| `ui/components/SongGridItem.kt` | `SongGridItem` | ~50 |

#### 结果
- `MainActivity.kt` 从 ~1800 行缩减至 537 行，仅保留 `MainActivity`、`formatDuration`、`MainScreen`
- Claude Code 单次分析 token 消耗预计下降约 70%
- 编译问题修复：
  - `LyricsView.kt`：修正 `animateScrollToItem` import 路径
  - `SlimProgressBar.kt`：补充 background import

---

### 二、关键问题修复（5 月 2 日—4 日总结）

#### 2.1 迷你播放栏导航兼容性（已解决）
- **问题**：三键导航模式下迷你栏位置错位，被系统导航栏遮挡
- **根因**：`collapsedOffsetY` 公式中 `fullCardExtraOffsetPx` 硬编码 48dp，未考虑厂商定制 ROM 状态栏高度差异
- **修复**：`fullCardExtraOffsetPx` 改为动态计算 `statusBarHeightDp + 24.dp`
- **参考**：Claude Code 分析发现 48dp = 24dp(状态栏) + 24dp(NavBar 补偿)，ColorOS 状态栏约 60dp 导致补偿不足
- **测试**：Xperia 10 VI 手势/三键均正常，ColorOS 已通过

#### 2.2 全屏展开后标签栏避让不足（已解决）
- **问题**：`NavigationBar` 避让未完全移出屏幕
- **修复**：隐退量改为固定 132dp

#### 2.3 启动首次展开掉帧（已解决）
- **问题**：启动后第一次拉起全屏动画掉帧，后续流畅
- **根因**：
  1. 迷你栏用 `if (progress.value < 0.3f)` 条件渲染，动画期间触发重组
  2. `fullAlpha` 在 Composition 阶段读取 `progress.value`，触发全函数重组
  3. GPU Shader 首次编译在动画路径上
- **修复**（Claude Code）：
  1. 迷你栏改为始终渲染，仅用 `graphicsLayer.alpha` 控制透明度
  2. `fullAlpha` 移入各 `graphicsLayer` lambda，避免 Composition 阶段读取 `progress.value`
  3. Splash 期间通过 `LaunchedEffect` 瞬间展开再收起卡片，预热 GPU Shader
  4. Splash 移除无效的 CPU 预热代码，改为等待 Composition 完成
- **效果**：Xperia 10 VI 上动画明显流畅

#### 2.4 迷你栏图层错位（已解决）
- **问题**：性能优化后迷你栏透明但占位 56dp，全屏时顶推歌曲信息
- **修复**：迷你栏从 `Column` 内移至外层 `Box`，用 `statusBarsPadding()` 定位，不参与 `Column` 布局

#### 2.5 封面动画重构（已完成）
- 封面变换改为基于中心点插值（迷你封面中心 → 全屏封面中心）
- 引入 `lyricAnimProgress` 控制封面在两个状态间的过渡
- 收起/展开动画曲线差异化（`tween(190)` vs `tween(300)`）

#### 2.6 歌单页面闪退（已解决）
- **问题**：v1.0.1 点开任意歌单闪退（Issue `#11`）
- **修复**：紧急修复并发布 v1.0.2

#### 2.7 WebView 登录兼容性（已解决）
- **问题**：小屏设备（NW-A105）无法滚动到用户协议勾选框（Issue `#2`）
- **修复**：调整 WebView 布局与滚动策略

---
## 2026 年 5 月 5 日 — 迷你播放栏点击拉起、动画曲线优化与触摸冲突修复

> 本日针对迷你播放栏交互、动画曲线及全屏触摸冲突进行三项改进，并修复了收起按钮失效问题。版本号更新至 v1.0.3。

### 一、迷你播放栏点击拉起

#### 需求
迷你播放栏此前仅支持上滑手势拉起全屏播放器，用户期望点击迷你栏即可一步展开（复用现有的 `progress` 动画机制，非新增行为）。

#### 实现
- `PlayerCard.kt` 迷你播放栏 Surface 增加 `clickable`，点击时若 `progress < 0.5f` 则触发 `progress.animateTo(1f)` 展开动画。
- 播放/暂停、下一首按钮位于 Surface 子层级，子元素消费点击事件后不会上传至 Surface，按钮行为不受影响。
- 涟漪效果通过 `indication = null` + `MutableInteractionSource()` 去除，保持 Metro Design 无视觉反馈的简洁风格。

---

### 二、动画曲线优化

#### 背景
原有展开/收起动画统一使用 `FastOutSlowInEasing` + 250ms，缺乏方向差异化，展开时缺乏 Apple Music 标志性的"快射慢落"质感。

#### 方案
| 操作 | 曲线 | 持续时长 | 效果 |
|------|------|---------|------|
| 展开（点击/上滑松手） | `CubicBezierEasing(0.2f, 0f, 0f, 1f)` | 400ms | 极强 ease-out，开头迅速加速，尾段大幅减速收口 |
| 折叠（按钮关闭/下滑松手） | `FastOutSlowInEasing` | 260ms | 利落收起，无拖沓感 |

#### 修改文件
- `PlayerCard.kt`：`onDragEnd` 中根据目标值选择曲线与时长。
- `MainActivity.kt`：`expandCard()` 和 `collapseCard()` 同步更新曲线参数；新增 `CubicBezierEasing` 与 `FastOutSlowInEasing` import。

---

### 三、拖拽阈值方向感知

#### 需求
- 从收起状态往上拖：超过 50% 自动完成展开，否则弹回。
- 从全屏状态往下拖：下滑超过 25%（即 `progress < 0.75`）自动收起，否则弹回。

#### 实现
- `PlayerCard.kt` 的 `pointerInput` 块内新增 `var dragStartProgress = 0f`，在 `onDragStart` 记录起始位置。
- `onDragEnd` 内依据 `dragStartProgress` 判断起点状态，应用对应阈值。
- 原阈值 25% 对展开过于敏感（轻微上滑即全屏），改为 50% 后刻意性更强，减少误触。

---

### 四、全屏播放器收起按钮触摸冲突修复

#### 问题演化
全屏播放器右上角收起按钮（向下箭头）点击无反应。排查经历三个阶段：

1. **尝试 1**：将迷你栏控件设为 `enabled = miniBarEnabled`（`progress < 0.1f` 时启用）。失败——Compose 中 `enabled = false` 的节点仍然存在于 modifier 链，参与 hit test 并消费触摸事件。

2. **尝试 2**：用 `Modifier.then(if(miniBarEnabled) Modifier.clickable(...) else Modifier)` 完全移除 clickable 节点；用 `if(miniBarEnabled) IconButton(...) else Spacer(96.dp)` 将按钮移出 Composition。依旧失败——迷你播放栏的 Surface 在外层 Box 中声明于 Column 之后，z 序高于 Column 内的收起按钮，无论其内部子元素是否存在，Surface 的布局区域始终拦截触摸。

3. **最终方案**：将收起按钮从 Column 标题栏内移出，作为外层 Box 的最后一个子元素独立放置（z 序最高）。视觉位置保持不变（`statusBarsPadding()` + `height(56.dp)` + `Alignment.CenterEnd` + `padding(end=8.dp)`），原标题栏 `end padding` 同步调整为 56dp（8dp 间距 + 48dp 按钮宽度）以维持歌名区域宽度。

#### 关键教训
Compose Box 中的 hit test 按声明顺序从后向前遍历（后声明 = z 序更高 = 优先命中）。控制 `enabled` 或条件移除子元素无法改变容器本身的 z 序拦截。需要拦截触摸的元素必须置于最高 z 序。

---

### 五、性能

- 去除涟漪动画（`indication = null`）消除了 `InteractionSource` 每帧状态驱动开销，设备上轻微掉帧问题得到改善。
- `derivedStateOf` 确保迷你栏状态切换仅在阈值穿越时触发重组，不增加帧级开销。

### 修改文件汇总

| 文件 | 改动 |
|------|------|
| `ui/player/PlayerCard.kt` | 迷你栏点击拉起 + 涟漪去除；拖拽阈值方向感知；收起按钮移至外层 Box 最高 z 序；迷你栏控件条件渲染 |
| `MainActivity.kt` | `expandCard()`/`collapseCard()` 曲线参数更新；新增 easing import |

## 2026 年 5 月 6 日 — 后端接口预留与项目重构修复

> 本日对后端接口进行了集中梳理与统一，增设新接口以预留后续 FM、日推等功能的入口。过程中经历了重构导致的功能回退，最终通过回滚原始代码并仅追加新接口的方式恢复稳定性。

### 一、后端接口整理与统一

#### 背景
现有网络层存在两套独立调用路径：Retrofit FormUrlEncoded POST（搜索、歌词、歌曲/专辑详情）与 eapi AES 加密 POST（日推、歌单、用户信息、播放 URL）。调用方分散在 ViewModel 和 UI 层，部分发现页数据直接在 HomeScreen 内联实现。

#### 新增接口（PlaylistApi.kt）

| 方法 | 端点 | 用途 |
|------|------|------|
| `getDailyRecommendSongs()` | `eapi/v2/discovery/recommend/songs` | 每日推荐歌曲 |
| `getRecommendPlaylists()` | `eapi/v1/discovery/recommend/resource` | 推荐歌单列表 |
| `getTopSongs(limit, offset)` | `api/v1/discovery/new/songs` | 新歌速递 |
| `getPersonalFm()` | `eapi/v1/radio/get` | 私人 FM |
| `fmTrash(songId)` | `eapi/radio/trash/add` | FM 垃圾桶 |

#### 新增接口（NcmApi.kt）

| 方法 | 端点 | 用途 |
|------|------|------|
| `getUserDetail(uid)` | `api/v1/user/detail/{uid}` | 公开用户详情 |
| `getPersonalized(limit)` | `api/personalized` | 推荐歌单（首页） |
| `getNewAlbums(area, limit, offset)` | `api/album/new` | 新碟上架 |

#### 新增数据模型
- `network/model/DiscoveryModels.kt`：`UserDetailResponse`、`PersonalizedResponse`、`NewAlbumsResponse` 等。
- `PlaylistCard` 内部数据类（供 `PlaylistApi` 与 `HomeScreen` 共用）。

#### 关键决策
- 所有新增接口仅作预留，不改变现有调用逻辑。`HomeScreen` 仍使用原有内联 eapi 调用，后续可逐步迁移。
- 新接口均放置在 `PlaylistApi`（eapi 加密路径）或 `NcmApi`（明文 Retrofit 路径），与原有架构保持一致。
- 不引入 Go 代理等外部依赖，纯 Kotlin 实现。

### 二、重构回退与修复

#### 问题描述
在统一后端接口过程中，最初的修改方案错误地将 `NcmApi` 中的表单 POST 接口改为了 GET + Query 参数，并删除了 `PlaylistApi.kt`，导致：
- 搜索、歌词、歌曲详情等核心端点全部返回 404
- 用户资料和歌单无法加载（UID 获取失败）
- `HomeScreen` 中日推、推荐歌单、新歌速递接口路径不存在
- 引入的 Go 代理服务 `ncrust-api/` 引入额外的部署复杂度，且未解决根本问题

#### 修复流程
1. 通过 `git checkout HEAD` 将全部改动文件恢复至原始状态。
2. 删除重构时新增的 `ApiModels.kt`（避免类重复）。
3. 仅在原始代码基础上追加新接口，保留原有两条调用路径的完整性。

#### 编译验证
`./gradlew assembleDebug` 通过，核心功能恢复。

### 三、问题总结

| 问题 | 根因 | 解决 |
|------|------|------|
| 搜索/歌词 404 | 原 `POST api/cloudsearch/pc` 改为 `GET api/search`，端点不存在 | 恢复原始代码 |
| 用户信息缺失 | 原 eapi 路径被明文 API 替代，登录态丢失 | 恢复 `PlaylistApi` 和 `eapiPost()` |
| 首页空白 | 日推/推荐歌单/新歌速递改为不存在的明文路径 | 恢复原始内联调用 |
| 接口碎片化 | 新接口与旧调用方不匹配，HomeScreen 未同步更新 | 新接口仅预留，不改变现有调用 |

### 四、当前架构状态

保留两条独立调用路径，新增接口分别归入对应路径：

- **路径 A**（Retrofit FormUrlEncoded POST）：搜索、歌词、歌曲详情、专辑/歌手、新碟上架、用户详情
- **路径 B**（eapi AES 加密 POST）：日推、推荐歌单、歌单详情、用户信息、播放 URL、私人 FM、FM 垃圾桶

调用方保持原状不变，后续可按需将 HomeScreen 等逐步迁移至 `PlaylistApi` 统一入口。

---

## 2026 年 5 月 7 日 — 全屏播放器动画重构、性能优化与 UI 调整

> 本日对全屏播放器三状态切换动画进行了彻底重构以消除性能问题，调整了多处 UI 组件的尺寸与比例，并修复了标题过长导致的错位问题。随后对搜索页卡片、详情页顶栏进行了统一优化。

### 一、全屏播放器三状态过渡优化

#### 初始方案与性能问题
三状态（a=大封面无歌词、b=小封面有歌词、c=小封面列表）之间切换最初使用 `AnimatedContent` + `AnimatedVisibility`，导致每帧触发完整 recompose，Xperia 10 VI 上严重掉帧。

#### 最终方案：零重组模式
参照迷你播放栏的 `graphicsLayer` 零重组模式重新设计：
- 移除 `AnimatedContent` 和 `AnimatedVisibility`
- LyricsView、QueueView、大封面信息全部始终保持在 Composition 中
- 所有可见性（alpha）和位置（translationX）计算移入 `graphicsLayer {}`（draw 阶段），动画帧内零 recompose
- `lyricAnimProgress: Animatable<Float>` 驱动封面缩放 + 内容淡入淡出
- `queueSlideProgress: Animatable<Float>` 驱动歌词↔列表横滑

#### 三种切换动画行为
| 切换方向 | 动画效果 |
|---------|---------|
| a↔b（大封面↔歌词） | 封面缩放移动，歌词 alpha 随 lyricAnimProgress 淡入，大封面信息淡出 |
| a↔c（大封面↔列表） | 同上，queueSlideProgress 直接 snapTo，无横滑 |
| b↔c（歌词↔列表） | lyricAnimProgress 保持 1，queueSlideProgress 0↔1 驱动横滑（歌词左出，列表右入） |

#### 大封面信息布局修复
大封面歌曲信息从原 `if (!showLyrics && !showQueue)` 控制 Composition 成员资格，改为 `Alignment.BottomStart` overlay 在内容 Box 内，始终存在但 alpha 由 `1f - lyricAnimProgress.value` 控制，消除 layout 跳变。

### 二、小封面标题过长滚动

- **问题**：歌曲名过长时在小封面模式下换行，导致顶栏错位。
- **修复**：顶栏 Text(s.name) 加 `maxLines = 1` + `overflow = TextOverflow.Clip`，配合 `Modifier.basicMarquee()` 自动横滚（延迟 2s、间隔 2.5s、速度 48dp/s）。

### 三、单曲卡片与详情页顶栏调整

#### SongCard 尺寸统一
- COMPACT 封面 40dp → 56dp，LIST 封面 48dp → 56dp，消除两档混乱
- 垂直 padding 8dp → 10dp
- COMPACT 标题字号 bodyMedium → bodyLarge（与 LIST 统一）

#### 搜索页卡片放大
- 单曲封面 56dp → 72dp
- 专辑封面 56dp → 72dp
- 艺人头像 56dp → 72dp

#### DetailScaffold 顶栏收敛
- TopAppBar 替换为自定义 Row，固定高度 48dp（原 Material3 默认 64dp）
- DetailHeader 封面比例 0.4f → 0.33f，信息区 0.6f → 0.67f
- 各处 Spacer 收紧（标题后 8→6dp，infoLine 间距 4→3dp，播放按钮前 12→8dp）

#### 顶栏融入状态栏
- 原 `statusBarsPadding()` 加在 Surface 上导致状态栏背后空白
- 改为 Surface 从 y=0 开始绘制覆盖状态栏区域，内部用 `Spacer(Modifier.windowInsetsTopHeight(WindowInsets.statusBars))` 将内容推至状态栏下方，顶栏背景与状态栏完全连通


### 三、最终代码体积统计（2026-05-04）

```
📊 Ncrust 项目代码行数统计 (纯 Kotlin)

■■■ 按包/目录统计
  auth                      64
  crypto                    0   (合并至 network/crypto)
  library                   105
  lyric                     27
  network                   587
  player                    483
  ui/components             668
  ui/navigation             111
  ui/player (播放卡片)      940
  ui/screen                 1881
  ui/theme                  320
  ui/viewmodel              305
  ui/ResponsiveContent      30
  MainActivity.kt           537
  ─────────────────────────────
  合计                     6058 行 (47 个文件)

各模块占比
  auth                      1.1%
  library                   1.7%
  lyric                     0.4%
  network                   9.7%
  player                    8.0%
  ui/components             11.0%
  ui/navigation             1.8%
  ui/player                 15.5%
  ui/screen                 31.0%
  ui/theme                  5.3%
  ui/viewmodel              5.0%
  ui/root                   0.5%
  MainActivity              8.9%

📄 最大文件 Top 10
  537  MainActivity.kt
  405  ui/player/PlayerCard.kt
  353  ui/screen/UserScreen.kt
  347  player/PlaybackService.kt
  289  ui/screen/HomeScreen.kt
  284  ui/screen/LibraryScreen.kt
  242  ui/screen/SearchScreen.kt
  203  ui/screen/ArtistDetailScreen.kt
  193  ui/player/LyricsView.kt
  183  ui/components/SongCard.kt
```

---

### 四、版本发布记录

| 版本 | 日期 | 内容 |
|------|------|------|
| v0.1.0-beta | 4 月 26 日 | 初始 MVP 版本 |
| v1.0.0 | 4 月 29 日 | 首个正式版，适配多屏，完善功能 |
| v1.0.1 | 5 月 4 日 | 性能大幅优化、主题色系统、尝试修复 ColorOS 错位、WebView 登录修复 |
| v1.0.2 | 5 月 4 日 | 紧急修复歌单闪退、歌词页面重构 |

---

## 代码架构总结（最终状态）

### 分层架构
- **UI 层**：Compose 组件 + 三层图层结构（主页面 → 卡片层 → 导航栏）。
- **状态层**：单一 `Animatable(0f)` 驱动所有播放卡片动画，`graphicsLayer` 实现零重组。
- **播放层**：Media3 ExoPlayer + MediaSessionService，状态持久化。
- **网络层**：Retrofit + OkHttp + Eapi 加密（`crypto/EapiCrypto.kt` 位于 `network/crypto/`）。
- **本地存储**：SharedPreferences + Gson，轻量无数据库。
- **路由层**：Navigation Compose 管理详情页导航。

### 包结构（最终版，47 个文件）
```
com.takahashirinta.ncrust/
├── MainActivity.kt
├── auth/CookieManager.kt
├── library/LibraryManager.kt
├── lyric/LrcParser.kt
├── network/
│   ├── NcmApi.kt
│   ├── PlaylistApi.kt
│   ├── RetrofitClient.kt
│   ├── SearchResponse.kt
│   ├── crypto/EapiCrypto.kt
│   └── model/
│       ├── AlbumDetail.kt
│       ├── ArtistDetail.kt
│       ├── SongDetail.kt
│       └── SongUrlResponse.kt
├── player/
│   ├── PlaybackService.kt
│   ├── PlaybackStateManager.kt
│   └── SongUrlFetcher.kt
└── ui/
    ├── ResponsiveContent.kt
    ├── components/
    │   ├── ArtistSearchItem.kt
    │   ├── DetailScaffold.kt
    │   ├── LibraryAlbumGridItem.kt
    │   ├── LibrarySongListItem.kt
    │   ├── SongCard.kt
    │   └── SongGridItem.kt
    ├── navigation/
    │   └── NavGraph.kt
    ├── player/
    │   ├── FullPlayerControls.kt
    │   ├── LyricsView.kt
    │   ├── PlayerCard.kt
    │   ├── PlayerCardOverlay.kt
    │   ├── QueueView.kt
    │   └── SlimProgressBar.kt
    ├── screen/
    │   ├── AboutScreen.kt
    │   ├── AlbumDetailScreen.kt
    │   ├── AlbumSearchItem.kt      (实际属于搜索功能，但物理位于 ui/screen)
    │   ├── ArtistDetailScreen.kt
    │   ├── HomeScreen.kt
    │   ├── LibraryScreen.kt
    │   ├── PlaylistDetailScreen.kt
    │   ├── SearchScreen.kt
    │   ├── SongDetailScreen.kt
    │   ├── SplashScreen.kt
    │   └── UserScreen.kt
    ├── theme/
    │   ├── MarkdownText.kt
    │   ├── ThemeColorSelector.kt
    │   └── ThemeManager.kt
    └── viewmodel/
        ├── PlayerViewModel.kt
        ├── SearchViewModel.kt
        └── SongViewModel.kt
```

---


## 2026 年 5 月 16 日 — 全屏播放器触摸事件隔离

> 修复全屏播放器与主页面之间的触摸事件穿透问题。设计基于两个稳态（progress=0 完全收起、progress=1 完全展开）进行隔离，确保展开时播放器内部交互（歌词点击/滑动、下滑收起）正常，后方主页面（SongCard、导航栏）完全不响应。

### 一、问题定位

#### 根本原因
`PlayerCardOverlay` 使用 `graphicsLayer { translationY }` 进行位置动画，该修饰符**仅影响渲染，不影响 hit test 边界**。无论播放器视觉上处于何处，layout 坐标始终覆盖全屏，Scaffold 里的 `SongCard.clickable` 因此始终可被触发。

#### Compose 兄弟节点 Main pass 处理顺序
Box 内兄弟节点的 Main pass 处理顺序为**低 z-order 先于高 z-order**（背景先于前景）。`Scaffold`（z 最低）在 `PlayerCardOverlay` 之前处理触摸事件，意味着在 `PlayerCardOverlay` 内部任何消费逻辑运行之前，`SongCard.clickable` 已完成响应。因此无论在 `PlayerCardOverlay` 内部如何尝试拦截 Main pass 事件，均无法阻止 `Scaffold` 已先行触发的 click。

#### 歌词点击切歌根因
`LyricsView` 的每行 `clickable { onSeekToMs(line.timeMs) }` 本身正确。问题在于 `Scaffold` 的 `SongCard.clickable`（更低 z）在 Main pass 中更早收到并处理了 DOWN 事件，触发了 `playSongItem` → 切歌，而非歌词 seek。

### 二、修复方案

#### 核心：graphicsLayer alpha 隔离

`graphicsLayer { alpha = 0f }` 是 Compose 规范保证：alpha 为 0 的子树**既不渲染，也完全移出 hit test**。将 `Scaffold` 和 `NavigationBar` 包裹在一个 wrapper Box 内，通过 `graphicsLayer` 在展开稳态将整个子树的 alpha 置零，使其彻底退出触摸事件路由，而 `PlayerCardOverlay` 位于此 wrapper **之外**，不受影响。

```kotlin
// MainActivity.kt — MainScreen Box 顶层结构
Box(Modifier.fillMaxSize().background(...)) {
    // alpha=0 时整个子树移出 hit test，draw-phase only，不触发重组
    Box(
        modifier = Modifier
            .fillMaxSize()
            .graphicsLayer { alpha = if (progress.value > 0.99f) 0f else 1f }
    ) {
        Scaffold { ... }          // 主页面内容
        NavigationBar { ... }     // 底部导航栏
    }

    PlayerCardOverlay(...)        // 在 wrapper 外，始终可交互
    SongMenuSheet(...)            // 在 wrapper 外，展开状态下仍可弹出
}
```

#### 滑动卡住问题修复（PlayerCard）

初始实现使用 `then(if (isExpanded) Modifier.pointerInput(...) else Modifier)`，当 progress 在滑动中跌破 0.99 时 modifier 链发生变化，`detectVerticalDragGestures` 协程因链位移重启，拖拽手势丢失，进度卡在中间值。

**修复**：将 PlayerCard 外层 Box 的事件消费 modifier 改为**常驻**（`pointerInput(Unit)`），在协程内读取 `progress.value` 判断是否消费，不触发 recompose，不引起链位移。

#### 事件路由对比

| 操作 | progress=1 时 Scaffold alpha | 歌词/播放器交互 | Scaffold SongCard |
|---|---|---|---|
| 点击歌词行 | 0（移出 hit test） | ✅ LyricsView.clickable 正常 seek | ❌ 不参与 hit test |
| 歌词滚动 | 0 | ✅ LazyColumn 正常滚动 | ❌ 不参与 hit test |
| 下滑收起播放器 | 0 | ✅ drag detector 正常收起 | ❌ 不参与 hit test |
| progress=0 收起稳态 | 1（正常） | 不覆盖主页面 | ✅ 正常响应 |

### 三、修改文件汇总

| 文件 | 变更内容 |
|---|---|
| `MainActivity.kt` | `Scaffold` + `NavigationBar` 包裹进 `graphicsLayer { alpha }` wrapper Box；`PlayerCardOverlay`/`PlayAllDialog`/`SongMenuSheet` 保留在 wrapper 外，始终可交互 |

> `ui/player/PlayerCard.kt` 无需修改——原有 `detectVerticalDragGestures` 常驻在外层 Box，LazyColumn（inner）赢得竖直拖拽，标题栏/控制区（无竞争 inner handler）交给外层 drag detector，逻辑本已正确。

---

## 已知问题与待办

### 未解决
1. 艺人热门单曲为搜索过滤结果，非真正热门歌曲排行。
2. `attributionTag` 警告持续出现在系统日志中，不影响功能但待清理。
3. WebView 登录提取 Cookie 不稳定，偶有失败。
4. 日推等接口当前使用 eapi 替代 weapi，长期存在失效风险。
5. 进度条数据更新偶有延迟。
6. 封面可能因 Coil 缓存策略导致模糊，需优化。
7. 专辑/艺人搜索功能尚未完全实现。
8. 播放队列历史记录待完善。
9. 本地封面缓存机制尚未实现。
10. 少量编译警告残留（如未使用参数等），后续版本再清理。

### 已解决
1. ✅ 播放卡片手势冲突
2. ✅ 通知栏不显示
3. ✅ Palette HARDWARE bitmap 崩溃
4. ✅ 进程被杀后状态丢失
5. ✅ 艺人详情 404
6. ✅ 新歌速递主线程网络异常
7. ✅ 日推 API 不可用
8. ✅ 跨设备 rename 错误
9. ✅ 动画帧率低
10. ✅ 封面切换动画不流畅
11. ✅ 多屏幕比例适配
12. ✅ 播放队列持久化
13. ✅ 库页面插播/加队列按钮失效
14. ✅ 关于页面与 Markdown 渲染
15. ✅ Splash 渐隐与副标题
16. ✅ 应用图标设计
17. ✅ 迷你播放栏导航兼容性
18. ✅ 启动首次展开掉帧
19. ✅ 歌单页面闪退
20. ✅ WebView 小屏设备登录协议显示
21. ✅ 迷你播放栏点击拉起全屏
22. ✅ 动画曲线 Apple Music 风格优化
23. ✅ 拖拽阈值方向感知（50%/25%）
24. ✅ 全屏收起按钮触摸冲突

---

## 技术债务
- `MainScreen` 仍包含较多播放队列逻辑（约 200 行），可考虑提取 `PlaybackQueueManager`。
- 封面动画中 `screenHeightPx * 0.3f` 等硬编码比例需进一步参数化。
- Walkman / 低端设备性能降级方案尚未实现。
- 单元测试覆盖率低。
- 错误处理与日志机制需标准化。
- weapi 加密长期替代方案需预研。

## 项目关键决策记录
1. CLI 采用 Rust 重写，保留原项目授权，纯命令行交互（已完成，后续独立演进）。
2. Android UI 采用声明式 Compose，追求高性能动画。
3. 播放器交互参照 Apple Music 卡片式设计。
4. 所有动画由单一 `Animatable` 进度值驱动，降低状态复杂度。
5. GPU 优先：所有动画使用 `graphicsLayer`，避免重组。
6. 组件常驻：卡片始终存在于组件树，仅控制位置和透明度，避免销毁重建开销。
7. 三层图层架构保证触摸与视觉独立性。
8. 本地存储使用 SharedPreferences + Gson，无需引入数据库。
9. 专辑数据从本地单曲派生，保持单曲为唯一数据源。
10. 全部 API 加密统一走 `EapiCrypto` 入口。
11. 媒体服务采用 `MediaSessionService`，适配 Android 14+ 新规范。
12. 懒加载分页减少流量与内存压力。
13. 多屏幕适配使用 360dp 基准宽度限制，宽屏居中留白，保持窄屏比例。
14. 播放队列与当前播放状态分开持久化，均用 SharedPreferences。
15. 主题系统采用 6 种预设色，全局颜色引用统一为 MaterialTheme。
16. 详情页统一使用 `DetailScaffold` 模板，导航由 `NavHost` 管理，消除大量手动状态变量。

---

**最后更新**：2026 年 5 月 4 日  
**总开发时长**：约 10 天  
**累计代码量**：6058 行（47 个 Kotlin 文件）

**致谢与参考**
- 原项目：Suxiaoqinx/Netease_url（MIT 许可证）
- 动画参考：Moriafly/SaltPlayerSource（Salt UI）
- 设计参考：Apple Music for Android
- 测试人员：白给小子

14. 播放队列与当前播放状态分开持久化。
15. 主题系统采用 6 种预设色，全局颜色引用统一为 MaterialTheme。
16. 详情页统一使用 `DetailScaffold` 模板，导航由 `NavHost` 管理。
17. 歌词轮询间隔 250ms，动画过渡 180ms，非激活行缩放至 82%。
18. 一键播放统一通过 `PlayAllDialog` 弹窗，专辑页和歌单页按钮移至右侧信息列。

---

**最后更新**：2026 年 5 月 7 日  
**当前版本**：v1.0.3  
**开发总时长**：约 12 天

**致谢与参考**
- 原项目：Suxiaoqinx/Netease_url（MIT 许可证）
- 动画参考：Moriafly/SaltPlayerSource（Salt UI）
- 设计参考：Apple Music for Android
- 测试人员：白给小子

---

## 2026 年 5 月 16 日

### 指针事件隔离与 z 序修复

#### 问题背景
全屏播放器三态（大封面 / 歌词 / 队列）之间存在指针事件穿透：歌词模式下可触发队列 SongCard；全屏/迷你稳定态之间互相干扰。SongMenuSheet 底部抽屉在修改导航栏布局后被遮挡下沉。

#### NavigationBar 图层迁移
- 将 `NavigationBar` 从 `Scaffold` 内部移至外层 `Box`，赋予 `zIndex(1.5f)`，使其在迷你播放栏（PlayerCardOverlay `zIndex=1f`）之上渲染。
- `PlayerCardOverlay` 改为外层 `Box` 第一个子节点，利用 Compose 主通道同组合顺序（第一子节点先处理）保证迷你栏优先拦截事件。
- **SongMenuSheet z 序修复**：NavigationBar 提升至 `zIndex=1.5f` 后，原本同组合顺序靠后的 SongMenuSheet（默认 `zIndex=0`）被压到 NavigationBar 之下导致遮挡。用 `Box(Modifier.fillMaxSize().zIndex(2f))` 包裹 SongMenuSheet，使其始终渲染于最顶层。

#### 全屏播放器三态隔离
- **根因**：`LyricsView` 和 `QueueView` 两个面板均为 `fillMaxSize()`，共享屏幕空间；`SongCard` 使用 `combinedClickable`，其内部 `awaitFirstDown(requireUnconsumed = false)` 即便事件已被消费仍会触发，任何基于消费标志的拦截方案均无效。
- **修复方案**：将歌词面板的 `graphicsLayer { translationX }` 从 `±W/3` 改为 `±screenWidthPx`，确保非激活状态时面板完全移出屏幕布局边界，彻底消除命中测试重叠。过渡动画同步改为全宽横划，两个面板在任何中间状态均无重叠（`q=0.5` 时恰好在 `x=W/2` 相切）。
- **LyricsView `enabled` 参数**：增加 `enabled: Boolean`，控制 `userScrollEnabled`（禁用 `LazyColumn` 滚动手势）和逐行 `detectTapGestures`（禁用歌词定位点击），由 `PlayerCard` 中 `derivedStateOf { lyricAnim > 0.5f && queueSlide < 0.5f }` 驱动，阈值穿越处各触发至多一次重组。

稳定态隔离验证：

| 模式 | LyricsView translationX | QueueView translationX | 可交互 |
|---|---|---|---|
| 歌词（q=0） | 0（屏幕内） | +W（屏幕外） | 仅 LyricsView |
| 队列（q=1） | −W（屏幕外） | 0（屏幕内） | 仅 QueueView |
| 大封面（q=0, lyricAnim=0） | 0（屏幕内，enabled=false） | +W（屏幕外） | 均不可交互 |

### 启动恢复播放修复

**问题**：应用启动时 `PlaybackStateManager` 恢复上次曲目元数据，但 ExoPlayer 无媒体加载，导致迷你播放栏显示时长为 0、歌词可见但无法播放。

**修复**（`PlayerViewModel.kt`）：
- `init` 中强制 `isPlaying.value = false`，避免恢复状态误报正在播放。
- `togglePlayPause()` 新增前置判断：若 `duration.value == 0L` 且存在有效 `currentSongId`，自动调用 `playSong()` 重新拉取 URL 并开始播放，而非发送无效 resume 指令。

### 音质档位扩展（3 → 5）

将音质偏好从 3 档扩展为 5 档，对应 NetEase API level：

| 显示标签 | API level |
|---|---|
| 压缩 | standard（128 kbps） |
| 较好 | higher（192 kbps） |
| 更好 | exhigh（320 kbps） |
| 无损 | lossless（FLAC） |
| 高解析 | hires（Hi-Res FLAC） |

- **`UserScreen.kt`**：`qualityOptions` 扩展为 5 项，WLAN 默认档改为 3（无损），移动数据默认档保持 1（较好）。
- **`PlayerViewModel.kt`**：`qualityApiLevels` / `qualityDisplayLabels` 同步扩展；新增 `currentQualityLabel: MutableStateFlow<String>` 在 `init` 时从 SharedPreferences 读取，在每次 `playSong()` 时更新。

### 全屏播放器音质徽章

在 `FullPlayerControls` 进度条上方时间行（`currentPosition` ↔ `duration`）居中位置，新增音质显示徽章：

- 样式：灰色（`#2A2A2A`）纯直角矩形背景，强调色文字，字号 11sp，SemiBold。
- 内容：实时反映当前播放音质标签（`currentQualityLabel` 从 PlayerViewModel 收集）。
- 样式：灰色（`#2A2A2A`）纯直角矩形背景，强调色文字，字号 11sp，SemiBold。
- 内容：实时反映当前播放音质标签（`currentQualityLabel` 从 PlayerViewModel 收集）。
- 参数链：`FullPlayerControls.onNavigateToUser` → `PlayerCard.onNavigateToUser` → `PlayerCardOverlay.onNavigateToUser` → `MainScreen` 内联 lambda。

### 音质徽章点击行为修正

**问题**：全屏播放器展开（`progress=1`）时点击徽章，`selectedTab` 已更新但用户页被播放器覆盖，视觉上毫无反应。

**修复**（`MainActivity.kt`，`onNavigateToUser` lambda）：
- 点击后同时触发播放器收起动画：`progress.animateTo(0f, tween(260, easing = FastOutSlowInEasing))`
- 切换标签页与动画并发执行；收起后用户页自然可见。

### 音质显示实际值修正

**问题**：`currentQualityLabel` 初始化时取用户请求档位，而 NetEase API 可能静默降级（如无版权时 lossless → standard）。徽章显示的是"请求值"而非"实际值"，误导用户。

**修复**（`SongUrlFetcher.kt` + `PlayerViewModel.kt`）：
- `SongUrlFetcher.fetch()` 从 API 响应 JSON 读取 `"level"` 字段，以 `SongUrlResult(url, actualLevel)` 返回实际音质。
- `PlayerViewModel.playSong()` 改用 `result.actualLevel` 查找显示标签并更新 `currentQualityLabel`，徽章始终反映 API 实际返回的音质档位。

---

**最后更新**：2026 年 5 月 16 日  
**当前版本**：v1.0.4  
**开发总时长**：约 13 天
