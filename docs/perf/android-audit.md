# Ncrust Android 性能与功耗审计（真机测量前的代码级审计）

审计对象：本仓库 `master` 工作区（`versionName = 1.3.2` / `versionCode = 7`，见 `app/build.gradle.kts:35-36`）。
审计方式：**只读代码**。本次没有跑 App、没有跑 benchmark、没有真机数据。
因此下面所有「预估收益」都是**定性等级 + 理由**，不是实测数字；所有涉及运行时行为的判断按三级标注：

- **已验证（静态）**：能在代码里逐行指出，且不需要运行时即可确认。
- **推测**：代码上说得通，但运行时行为（调度、系统回收、第三方库内部实现）未在真机确认。
- **无法替你验证**：只能由你（有真机、有账号、有目标机型）判断的部分。

判决口径：`高/中/低` 是「收益 × 确定性」，不是「改起来爽不爽」。

最近合入的相关改动（`git log --oneline -15`）已读过：`bf1a8be`（收藏改走 xeapi）、`336f9cd`（待同步收藏方向）、`98d1fc5`（队列索引以在播歌曲为准）、`293eedd`（整列播放/插播/追加）。它们把网络与队列语义修对了，但也**新增了两处成本面**：写路径的往返数（N2）与「整列播放」的全量详情拉取 + 全量入队落盘（N4）。

---

## 1. 总表

> 「真机怎么量」一列里的命令都是可在设备上直接跑的形式；`$PKG` = `com.takahashirinta.ncrust`，`$NCRUST` = 仓库根。
> 「改法草图」只写方向，不写实现——本任务不改产品代码。

| 编号 | 类别 | 问题（一句话） | 证据（文件:行） | 预估收益 | 风险 | 真机怎么量 | 改法草图 |
|---|---|---|---|---|---|---|---|
| A1 | 功耗 | 进度 ticker 是 `while(isActive){ delay(500) }`，**暂停时照样 2 Hz 唤醒主线程**，且与「有没有 UI 订阅 / 有没有在播」无关 | `app/src/main/java/com/takahashirinta/ncrust/player/PlaybackService.kt:735-749`（ticker）、`:275`（onCreate 里启动）、`:740-741`（每 500 ms 调 `onProgressUpdate` + `updatePlaybackState`）、`:677`/`:691-708`（每 900 ms 一次 MediaSession binder 写） | 高：暂停后设备被固定 2 次/秒唤醒，永不进入深空闲；后台/熄屏长听场景直接进 batterystats | 低：只需在「未播放」或「无订阅者」时挂起循环；**唯一坑**是暂停瞬间要补发一次最后位置，否则进度条停在旧值 | 暂停播放 → 熄屏 10 min：`adb shell dumpsys batterystats --reset`；等 10 min；`adb shell dumpsys batterystats $PKG \| grep -Ei "Wakeups\|wake_lock\|Time spent"`；对照「在播 10 min」同口径。Perfetto 抓 `sched/sched_wakeup` + `power/cpu_idle`，看主线程 Runnable 周期 | ticker 循环里把「`player.isPlaying`」扩成「`isPlaying && 有订阅者`」，并在 `onIsPlayingChanged(false)` 时主动补发一次 `(currentPosition, duration)`；`progressJob` 保持单实例 |
| A2 | 功耗 | `C.WAKE_MODE_NETWORK` + `DefaultLoadControl(30 s, 60 s, 2.5 s, 15 s)`：缓冲窗口大、Wi-Fi 锁与网络唤醒的持有时长没在真机确认过 | `PlaybackService.kt:157`（wake mode）、`:161-170`（LoadControl）、`app/src/main/AndroidManifest.xml:9`（WAKE_LOCK） | 中：蜂窝下 30~60 s 预读=持续拉流；锁是否在暂停后释放决定「暂停是否还漏电」 | 中：调小缓冲牺牲弱网容忍（注释记录了「播一点断一点」的原始动机）；换 wake mode 影响熄屏稳定性。**必须真机对照** | 播放中/暂停中各跑一次：`adb shell dumpsys power \| grep -iA4 "Wake Locks"`；`adb shell dumpsys wifi \| grep -i wifilock`；`adb shell dumpsys batterystats $PKG \| grep -iE "network\|mobile"`。Perfetto `power` 轨道 + `linux.sys_stats` 的流量计数 | 分场景给 LoadControl（Wi-Fi 保持 30/60；蜂窝降到 ~15/30 并单独暴露开关）；`WAKE_MODE_NETWORK` 先测「熄屏 30 min 有无欠载」，再决定是否降为 `WAKE_MODE_LOCAL` |
| A3 | 功耗 | 每首歌切歌都会把**整个**歌词缓存 map（≤200 条、含完整 LRC 文本）重新 Gson 序列化 + 整文件写盘 | `app/src/main/java/com/takahashirinta/ncrust/lyric/LyricsCache.kt:57-73`（`:68-70` 全量 `putString` + `apply`）、`:30`（MAX_ENTRIES=200）、`:39-51`（内存镜像）；写入点 `ui/viewmodel/PlayerViewModel.kt:566` | 中：单次是「序列化 ~百 KB + SharedPreferences 全量重写 XML」，随缓存变满而变贵；长听（1 首/3 min）也够频繁 | 低：改成 per-song key 或分片写；淘汰语义（≤200、按 timestamp）要保留 | Perfetto 抓 `disk`/`f2fs` + `sched`，切 20 首看切歌瞬间的写 IO 段；`adb shell dumpsys batterystats $PKG` 前后对比 | 每首歌一个 prefs key（`lrc_<songId>`）+ 一份「键→时间戳」索引；淘汰时只删对应 key，不做整表重写 |
| A4 | 功耗 | `LibraryManager` 任何收藏变更都把 4 个 key（含**整个** `liked_ids`）全量重写；`refreshFromCloud` 无 in-flight 去重，冷启动 warmup / 进收藏页 / 登录各触发一次，每次 5 个网络往返 | `app/src/main/java/com/takahashirinta/ncrust/library/LibraryManager.kt:136-145`（scheduleFlush）、`:147-170`（flushToDisk 全量 4 键）、`:382-445`（refreshFromCloud）、调用点 `warmup/AppWarmup.kt:137-139`、`ui/screen/LibraryScreen.kt:158-169`、`MainActivity.kt:1117-1121` | 中高：登录用户冷启动固定多打 5 个 eapi 往返 ×2~3 次（warmup + 收藏页），并伴随整表落盘 | 低：加 in-flight 合并 + 短 TTL 即可；注意刷新完成后 UI 靠调用方 `reloadLocal()`，改成「合并后回调一次」要保持语义 | `adb logcat -c` → 冷启动进收藏页 → `adb logcat -d -s LibraryManager:I \| grep -c "likedIds="`（应≈1，现在可能 2~3）；`adb shell dumpsys batterystats $PKG \| grep -i network` 前后差 | `refreshFromCloud` 外层加 `Mutex`/`inFlight` + 「N 秒内已刷新则跳过」；`flushToDisk` 改成只写变化的那一个 key |
| A5 | 功耗 | `SongUrlFetcher.fetch` 逐档串行重试，最坏一档一首 **6 个阻塞 POST**；无版权/无订阅的歌每次都要走完整阶梯（失败不会被记住） | `app/src/main/java/com/takahashirinta/ncrust/player/SongUrlFetcher.kt:36-85`（`:49-78` 串行 for + 每档一次 `eapiPost`）；调用点 `PlayerViewModel.kt:313-359`、`:420-506`（预载同路径） | 中：批量播无版权歌单（如整列播放碰到灰歌）时是「每首 4~6 次往返」，蜂窝下明显 | 低：与既有 `PreloadCacheEntry.requestedLevel` 同构，加一个「负缓存」（songId@level 失败 N 分钟内不再试） | `adb logcat -c` → 播一张含灰歌的歌单 → `adb logcat -d -s SongUrlFetcher:D \| grep -c "eapi response"`；`adb shell dumpsys batterystats $PKG` 网络字节前后差 | 增加「负结果短 TTL 缓存」（songId@level → 失败时间戳，5 min）；阶梯命中 `code!=200` 的档位直接跳过，不再重试同一档 |
| A6 | 功耗 | 播放上报每首歌起一个裸线程 + 一次 HTTP；无批量、无退避 | `player/PlayReporter.kt:69-77`（`thread(name="ncrust-weblog")`）；触发点 `PlayerViewModel.kt:110-129`、`:130-138` | 低：一首歌一次，量级可接受；只是线程模型粗糙 | 低：改用现成的 IO scope/队列即可 | `adb shell dumpsys batterystats $PKG` 网络计数；`adb logcat -d -s PlayReporter:D \| tail` | 复用 `Dispatchers.IO` + 单线程队列；失败丢弃（现状即如此），不做重试 |
| A7 | 功耗 | 扫码登录轮询（2 s × 150 次 = 5 min）与配对 UDP 广播在**用户触发时**才存在 | `ui/components/QrLoginDialog.kt:88-89`；`auth/QrPairServer.kt:41-68` | 低：只在登录/配对会话中存在，且是功能本身 | 低：超时/取消路径已有 | `adb shell dumpsys batterystats $PKG` 配网期间网络计数；确认对话框关闭后 `adb shell dumpsys netstats` 无新增 | 保持现状；把「150 次」与「用户可见倒计时」对齐即可（体验项，非性能项） |
| F1 | 帧 | **AGENTS.md 写的「progress < 0.05 释放重子树」在代码里已不存在**：歌词面板 / 队列面板 / 控件面板**常挂载**，折叠态只是 `graphicsLayer` alpha=0 | 代码：`ui/player/PlayerCard.kt:163-169`（注释明说「常挂载（无 gate）」）、`:395-451`（控件）、`:477-490`（LyricsView）、`:554-564`（QueueView）、`:722-728`（窄屏控件挂载）；文档：`AGENTS.md`「PlayerCard collapse gating」 | 中：折叠态任何内部状态变化都会真执行（跨行→LazyColumn 滚动动画、缓冲→逐帧脉冲），只是看不见 | **高**：注释解释过 `if (expandedEnough)` 会把构造成本压到首次展开那一帧；且 AGENTS.md 明令不要批量重构卡片动画。**先测量，不要先动手** | `adb shell dumpsys gfxinfo $PKG reset` → 折叠态静止 30 s → `adb shell dumpsys gfxinfo $PKG framestats`（看是否有持续帧）；Perfetto `gfx`/`view` 轨道看折叠态有无 `drawFrame`/layout | 不做结构重构。若要回收，只对「歌词自动滚动」与「缓冲脉冲」加**基于可见性的运行门控**（见 F2），不碰 mount/unmount |
| F2 | 帧 | 三种动画在**不可见**时照常跑：歌名无限跑马灯、歌词跨行自动滚动、进度条缓冲脉冲 | 跑马灯 `PlayerCard.kt:668-674`（`iterations = Int.MAX_VALUE`、`Immediately`）；歌词自动滚动 `../Kanesumi-sec-a/kanesumi-controls/src/main/java/io/github/takahashirinta/kanesumi/controls/MetroLyricsPanel.kt:194-205` + `PlayerCard.kt:483`（`isVisible = showLyrics`，折叠时仍为 true）；缓冲脉冲 `ui/player/SlimProgressBar.kt:100`、`:150-162`、`:213-227` | 中：缓冲脉冲会让一个不可见 Canvas 逐帧 invalidate（60 fps），跑马灯是常驻无限动画，歌词每跨行在不可见时做一次滚动动画 | 低：只加「可见性/展开度」门控，不改动画参数与观感 | `adb shell dumpsys gfxinfo $PKG framestats` 在折叠态取两次快照，帧数应停止增长；Perfetto `gfx` 轨道比对折叠/展开态的 `drawFrame` 密度 | 跑马灯：`iterations` 收敛或按 `progress > 0.5` 才启用；歌词 `isVisible` 改为 `showLyrics && cardExpanded`；缓冲脉冲随「所在面板 alpha>0」启动 |
| F3 | 帧 | 列表行封面统一请求 640²，而 LIST 行只显示 72 dp（≈216 px @3x）；AppWarmup 又用 320 px 解码同一 URL | `network/CoverUrls.kt:16`（small=640）、`:19`（large=1080）；`ui/components/SongCard.kt:60-63`（72 dp）、`:79`（`CoverUrls.small`）；`warmup/AppWarmup.kt:41-44`（COVER_PX=320）、`:112`（URL 走 `small`=640）、`:125`（解码 size 320） | 低中：滚动时的流量与磁盘缓存体积浪费数倍（解码本身受布局尺寸约束，代价主要在传输与缓存上） | 低：多档 URL 会让既有磁盘缓存全部 miss 一次（一次性回填成本） | `benchmark/run_benchmark.sh scroll`（FrameTimingMetric）+ `adb shell dumpsys meminfo $PKG` 的 Bitmap 峰值；Perfetto `network`/`disk` 轨道看 `decodeBitmap` 段 | 给 `CoverUrls` 加「按显示尺寸取档」（列表行 320、tile 640、播放器 1080）；warmup 的请求尺寸与显示端一致，别再 320 解码 640 的图 |
| F4 | 帧 | 帧率基线只覆盖「窄屏 + LIST」一种形态：滚动基准取「可见高度最大的 scrollable」，宽屏网格/折叠态无基线 | `benchmark/src/main/java/com/takahashirinta/ncrust/benchmark/HomeScrollBenchmark.kt:60-83`；`benchmark/run_benchmark.sh:127-136` | 低：这是**测量能力**缺口，不是线上问题；但会让 F1/F2 的改动无法验证 | 无 | `benchmark/run_benchmark.sh all`；平板/折叠机型上补跑 scroll，看是否仍找到主列表 | 增加「宽屏/网格」变体或按设备过滤的 benchmark；结果落 `benchmark/output/<device>/`（已 gitignore） |
| S1 | 启动 | `baseline-prof.txt` 是**手写 class-only 列表**，含 5 条指向已迁出仓库的 `ui/anim/sokuou/*` **死条目**；仓库没有 `:baselineprofile` 模块，方法级 AOT profile 从未生成过 | `app/src/main/baseline-prof.txt:7-9`（自述 class-only）、`:83-87`（`ui/anim/sokuou/*`，该目录在本仓库已不存在，见 `AGENTS.md`「former in-repo `ui/anim/sokuou/` directory no longer exists」）；`settings.gradle.kts:26-27`（只有 `:app` + `:benchmark`）；`benchmark/build.gradle.kts:3-5`（`com.android.test`，非 profile 生成器）；`app/build.gradle.kts:131-134` | 高：冷启动/首屏 JIT 抖动是最大且最好量化的杠杆；class-only 只做 verify，拿不到 AOT 编译收益 | 中：新增模块 + 采样脚本；要 guard 住外部库类名（Kanesumi 类要进 profile）；生成产物替换手写文件需回归启动闪退 | `benchmark/run_benchmark.sh startup`（改前/改后各 8 次迭代，同机同热状态）；`adb shell cmd package compile -m speed-profile -f $PKG` 后再跑一次对照；Perfetto `dalvik` + `sched` 看 JIT/编译活动 | ①删死条目；②接 `:baselineprofile` 模块（macrobenchmark 采样冷启动 + 首页滚动 + 播放器展开三条路径）生成方法级 profile，产物直接替换手写文件；③profile 里显式加入 Kanesumi/Coil/OkHttp 的热方法 |
| S2 | 启动 | 首帧路径上的同步读盘：`RetrofitClient.init()` 在 `AppWarmup.start()` **之前**读 `ncrust_prefs`，而 AppWarmup 的「阶段零」正是为了把 prefs 解析挪到 IO 线程；`setContent` 里又同步读 3 个 prefs | `MainActivity.kt:106`（`RetrofitClient.init`）、`:109`（`AppWarmup.start`）、`:112-120`（主题/语言 prefs，在 composition 里同步读，取值函数见 `ui/theme/ThemeManager.kt:42,58`、`ui/i18n/LanguageManager.kt:28`）；`network/RetrofitClient.kt:44-48` → `auth/CookieManager.kt:10-12,18-20`（首次 `getSharedPreferences` 同步解析 XML）；`warmup/AppWarmup.kt:62-70` | 低中：注释自己写了「eMMC 上单文件 30~100 ms」；顺序不保证时这笔就落在主线程 | 低：调整初始化顺序即可，不改变任何语义 | Perfetto `sched` 看主线程在 `bindApplication → firstFrame` 之间的 IO 阻塞段；`benchmark/run_benchmark.sh startup` 的 P50/P90 | 把「prefs 预热」提到 `Application.onCreate`（或让 `RetrofitClient.init` 接已加载的 cookie），并把主题/语言初值从磁盘读改为「预热后注入」 |
| S3 | 启动 | R8 keep 面过宽：`network.**` 与 `lyric.**` 全量 keep，抵消部分 minify 收益 | `app/proguard-rules.pro:30-33`；**为什么这么写**：`player/PlaybackStateManager.kt:31-52` 记录了 v1.3.2 首版 release 启动闪退的根因 | 低中：DEX 体积与冷启动类加载；但收益远小于风险 | **高**：删 keep 会重演 Gson 泛型擦除闪退。**非首批**，且必须真机回归 | 改后必须：`./gradlew :app:assembleRelease` → 装机 → **冷启动 + 恢复队列 + 收藏页 + 歌词**四条路径全过；`benchmark/run_benchmark.sh startup` 对照 | 只把「含泛型字段的持久化模型」从 `**` 收窄到具体类（`PlaybackStateManager.PlaybackSession`、`SongItem`、`AlbumInfo`、`CachedLyrics`），其余靠「匿名 `TypeToken` 携带元素类型」的既有约定；一次只收一类并复跑闪退回归 |
| S4 | 启动 | Splash 双闸门：最短驻留 350 ms + warmup 最长 3 s（warmup 期间网络/解码/磁盘齐上） | `ui/screen/SplashScreen.kt:27-36`、`:73`（MIN_DWELL_MS=350）；`warmup/AppWarmup.kt:40`（TIMEOUT_MS=3000）、`:75-132`（3 个并发网络 + 最多 18 张封面预取 + 收藏刷新） | 中：这是「首帧到可用」的体验预算；慢网下 user 会盯着 splash 最久 3.26 s | 中：缩短超时会牺牲「首屏就是完成态」，需要真机看首屏是否开始闪 loader | `benchmark/run_benchmark.sh startup` + 录屏数 splash 驻留帧；Perfetto 抓冷启动全程，看 `AppWarmup` 三请求的完成时刻 | 让「首帧可交互」与「数据就绪」解耦：splash 只等**首页三条数据**（不等封面预取），封面在首帧后继续后台预取 |
| N1 | 网络 | 存在**两套** OkHttpClient（`plainClient` 与 Retrofit 的 `api` 各自 `Builder()` 新建 → 各自连接池/调度器/DNS 缓存），短链解析还每次新建第三个 | `network/RetrofitClient.kt:58-63`（plainClient）、`:77-97`（api 的 client，独立池）、`:69-75`（antiCheatClient 走 `plainClient.newBuilder()`，**推测**共用池）；`MainActivity.kt:1024-1027`（短链 HEAD 临时 client） | 中：同一域名多池=多套 TCP/TLS 与 DNS 查询；`antiCheat` 的 3 s 超时 client 也可能新建连接 | 低：合并为一个 client + 一个 CookieInterceptor；**注意** eapi 系列请求是逐请求显式设 `Cookie`/`UA`，合并时不能让 interceptor 覆盖显式头 | `adb shell dumpsys netstats detail` 或用 OkHttp `EventListener` 数 connect 次数（改前后各一次冷启动+进收藏页）；Perfetto `network packet` 计数 | 全应用一个 `OkHttpClient`（含 `ConnectionPool`、Dispatcher 上限、`retryOnConnectionFailure`），超时靠 `newBuilder().callTimeout()` 派生；短链解析复用同一 client |
| N2 | 网络 | 一次「点红心」最多 **5 个往返**：易盾 token → xeapi 写入 →（解密失败还要再刷公钥并重试，+1~2）→ 读回校验（`getCurrentUserId` + 红心歌单 id + 歌单详情 = 3 个） | `network/RetrofitClient.kt:209-223`（xeapiPost + 失败重取公钥重试）、`:301-322`（易盾 token，每次现取不缓存）；`network/XeapiState.kt:108-114`、`:116-184`（公钥 12 h 兜底 TTL）；`library/LibraryManager.kt:213-226`（pushLikeBlocking）、`:237-248`（verifyLikeState → `getCurrentUserId` + `getLikedTrackIds`）；`network/PlaylistApi.kt:542-573`（getLikedTrackIds 内部又是 2 个请求：红心歌单 id + 歌单详情） | 中：写入本身是低频操作，但待同步重试是**顺序循环**（`LibraryManager.kt:258-265`），积压 N 条就线性放大 5N | 中：读回校验是「不冒充已同步」的核心语义，**不能删**；易盾不缓存是协议决定（`spec/api/xeapi.md` §5） | `adb logcat -c` → 点 1 次红心 → `adb logcat -d -s PlaylistApi:I LibraryManager:I`，数 `likeSong(xeapi)`/`pushLike` 行；`adb shell dumpsys batterystats $PKG \| grep -i network` 前后差 | ①`verifyLikeState` 复用刚写回的结果或延后到批量末尾一次校验；②同一会话内合并多次 like 的读回；③待同步重试加并发上限与指数退避（当前是顺序阻塞） |
| N3 | 网络 | `HomeScreen` 每次重新进入 tab（距上次预热 >15 s）都会重打首页 3 个接口；详情页命中内存缓存也必然再刷新（设计如此） | `MainActivity.kt:1298-1323`（`when(selectedTab)` 条件挂载 → 切走即 dispose，`LaunchedEffect(Unit)` 会重跑）、`ui/screen/HomeScreen.kt:158-164`（`isHomeFresh()` 才跳过）、`cache/ContentCache.kt:30-38`（15 s 新鲜度窗口） | 低中：tab 来回切是高频动作；蜂窝下每次 3 个 eapi 往返 | 低：把窗口按「同一会话内已刷新」而不是纯时间窗；注意别把用户主动下拉刷新也吃掉 | `adb logcat -c` → 首页→搜索→首页→搜索→首页 → `adb logcat -d -s PlaylistApi:D \| grep -c discovery`；batterystats 网络计数 | `ContentCache` 增加「本次进程内是否已从网络刷新过」标记（时间窗只作兜底），或把 Home 的三条请求改成「进 tab 时只在内容为空/过期时刷新 + 手动刷新按钮强制」 |
| N4 | 网络 | 「整列播放 / 插播全部 / 追加全部」（`293eedd` 新功能）会**一次性把全量详情拉齐**（按 500/批顺序），随后整份队列被 Gson 序列化并整表落盘 | `library/LibraryManager.kt:474-532`（`LIKED_FETCH_BATCH_SIZE=500`、`loadAllLikedSongs`、`assembleLikedSongs` 顺序批次）、`:537-553`；`ui/screen/LibraryScreen.kt:89-119`（`startPlayAll` 期间按钮禁用）；`MainActivity.kt:1360-1362`（`onPlayAllSongs`）/`:925-947`（`insertAllNext`）/`:949-962`（`appendAllToQueue`）→ 都调 `persistSession()`；落盘路径 `player/PlaybackStateManager.kt:136-168` | 中高：三千首红心 = 6 个顺序大请求 + 一份几乎全量的 `List<SongItem>` 常驻；之后**每次队列变更**（插播/追加/拖拽）都重新序列化整份队列并重写 prefs | 低中：不能退回「懒加载」式入队就丢掉「整列播放立刻可点」的语义？其实不会——取链本来就是播放时才做；但队列落盘格式被 `PlaybackSessionStoreTest` 锁住，改存储要兼容旧键 | `adb logcat -c` → 点「播放全部」→ `adb logcat -d -s PlaylistApi:D \| grep -c "song/detail"`（应≈⌈N/500⌉）+ 计时；Perfetto `disk`/`sched` 看序列化与写盘；`adb shell dumpsys meminfo $PKG` 前后 Java Heap | ①详情批次与「可播放」解耦：入队先带 id/标题，取链仍走播放时的 `SongUrlFetcher`（现有模型）；②会话落盘对大队列改「引用式」（记来源歌单 id + 顺序）或只存 id + 最小元数据，保持旧键可读 |
| M1 | 内存 | 封面位图：每首歌走 1024² 解码 + `copy(ARGB_8888)`（≈4 MB/张，算术估算），预载路径最多再留 4 张，另有 Palette 直方图 | `PlaybackService.kt:116`（预载 map）、`:591-617`（`:598` size 1024、`:603` copy、`:607-610` 保留 4 张）、`:619-661`（`:631` size 1024、`:638` copy、`:646-655` Palette）；`PlaybackService.kt:78-79`（当前位图） | 中：进程常驻 4~5 张 1024² 位图（≈16~20 MB 估算），低端机（Coil 内存缓存仅 16 MB，见 `NcrustApplication.kt:53-57`）里这是最大单块 | 中：锁屏/任务栏封面清晰度是用户可见品质；降到 512 会糊（代码注释里有实测结论） | `adb shell dumpsys meminfo $PKG`（看 `Graphics`/`Java Heap`/`Bitmaps`）+ 切歌 20 首后再看；`adb shell dumpsys meminfo $PKG -d` 看 Objects | 目标尺寸取 `min(1024, 屏幕短边)`；预载保留 1~2 张（够无缝）+ 只在需要时保留 current；Palette 用 128 px 缩略图（`HomeScreen.kt:540-558` 已有 128 px + `allowHardware(false)` 的现成做法） |
| M2 | 内存 | 详情页 LRU 按**条数 32** 封顶而不是按字节：一个歌单可上千首，32 个大歌单的对象图无上限 | `cache/ContentCache.kt:41-43`（`LruCache<Long, List<SongItem>>(32)`）；数据规模见 `library/LibraryManager.kt:474-477`（单曲详情批次上界 500）、`:537-553`（分页追加） | 中：反复进出大歌单/大专辑是常见路径；对象图为 `SongItem` + 嵌套 model | 低：改成「总条目数上限」或按估算字节封顶；注意首页三个字段**不在** LRU 内（`ContentCache.kt:24-26`），不要一起改 | `adb shell dumpsys meminfo $PKG` 在「进 6 个大歌单」前后的 Java Heap 差；`adb shell dumpsys meminfo $PKG -d` 的 Activity/View/Objects | `LruCache` 的 `sizeOf` 返回条目数（列表长度），上限设「总条目数」；或对超大列表只缓存前 N 首 + 标记为部分 |
| M3 | 内存 | 静态回调把 ViewModel / 组合作用域 lambda 挂在 `PlaybackService` 的 companion 上；`onTrimMemory` 不清播放器位图 | `PlaybackService.kt:96-111`（companion 回调 + `instance`）、`:832-834`（明确「不在 onDestroy 清回调」）；`PlayerViewModel.kt:110-143`（注册）、`:718-727`（onCleared 清理）；`MainActivity.kt:670-678`（MainScreen 注册的 lambda 捕获组合状态）；`NcrustApplication.kt:60-77`（trim 只清 ContentCache + Coil） | 低：`onCleared` 有清理，MainScreen 的 lambda 是覆盖式替换；`instance` 是 Service 生命周期内自持 | 低：改成 `WeakReference` 或注册表要小心「服务重启后回调丢失」（注释里有明确理由） | `adb shell dumpsys meminfo $PKG`：反复进后台/回前台 + 旋转（Activity 重建）后看 Java Heap 是否单调上涨；`adb shell dumpsys activity processes \| grep -i ncrust` | 保持现有清理契约，只把「大位图」纳入 `onTrimMemory`（清 `preloadedArtworkBitmaps`，保留 current） |
| M4 | 内存 | 常驻内存的整表 JSON：歌词缓存 map（≤200 条完整 LRC）、播放会话队列（几百首 `SongItem`）；会话落盘的 Gson 序列化在 Default 上跑（几百首 50 ms+，注释自述） | `lyric/LyricsCache.kt:30`、`:36-51`（内存镜像）；`player/PlaybackStateManager.kt:79-85`（200 ms debounce）、`:148-168`（`:158-164` 序列化 + 落盘）、`:174-175`（注释自述 50 ms+） | 低：量级可控，属于「长期驻留」而非泄漏 | 低 | `adb shell dumpsys meminfo $PKG` 冷启动后 vs 播完 50 首后 | 会话落盘只写「队列 + 索引 + 模式」；`shuffledIndices` 可重建（`MainActivity.kt:383-389` 已有 `generateShuffledIndices`），不必每次随会话写盘 |

---

## 2. 按「收益 / 风险」排序的前 10 项

排序原则：**先做「收益确定、改动面小、不碰用户可见品质」的**；凡是需要真机基线才能定量的（A2、F1）排后面；凡是高风险且会重演历史事故的（S3）非首批。

| 顺序 | 编号 | 为什么排这里 | 验收方式（真机） |
|---|---|---|---|
| 1 | S1 | 收益最高、可量化、与业务逻辑零耦合（只加 profile 与清理死条目）。冷启动是用户每次都能感知的路径 | `run_benchmark.sh startup` P50/P90 对照（8 iter，同机同热状态） |
| 2 | A1 | 收益高（暂停后不再 2 Hz 唤醒）、风险低、代码面极小。是「更省电」最直接的一条 | 暂停熄屏 10 min 的 `dumpsys batterystats` 对照 + Perfetto `sched_wakeup`/`cpu_idle` |
| 3 | A4 | 登录用户冷启动固定多打 5~15 个网络往返 + 整表落盘；in-flight 去重是纯加法 | logcat 计数 + batterystats 网络字节对照 |
| 4 | A3 | 每首歌一次全量写盘；改成 per-song key 后语义不变 | Perfetto `disk` 段 + 切 20 首的 IO 对照 |
| 5 | N4 | 「整列播放/插播全部/追加全部」的上千首全量拉取 + 全量队列反复落盘，是**新功能引入的**成本面；容量随红心规模线性增长 | `logcat -s PlaylistApi:D` 数 `song/detail` 次数与耗时；Perfetto `disk` + `dumpsys meminfo` 对照 |
| 6 | F2 | 只加「可见性门控」，不碰动画参数（避开 AGENTS 明令的批量重构）；折叠态无效绘制/动画直接消失 | `dumpsys gfxinfo framestats` 折叠态帧数增长；Perfetto `gfx` 密度对照 |
| 7 | N1 | 两套连接池合并，收益稳定；风险点是 header 覆盖语义，靠「显式头优先」保住 | OkHttp `EventListener`/Perfetto `network` 的 connect 次数对照（冷启动 + 进收藏页） |
| 8 | M1 | 低端机内存最大单块；但要平衡「锁屏封面清晰度」这个用户可见品质 | `dumpsys meminfo` 的 Graphics/Java Heap + 切歌 20 首后对照 + 目视任务栏封面 |
| 9 | M2 | LRU 按字节/条目封顶，改动局部；影响「反复进出大歌单」的机型 | `dumpsys meminfo` 进 6 个大歌单前后 |
| 10 | A2 | **必须先量再改**：改缓冲策略会动弱网体验，属于用户可感知取舍 | 蜂窝/弱网下：熄屏播放 30 min 的 batterystats + 卡顿次数（Perfetto `gfx`/`audio` + logcat 的 `AudioSink error`） |

**不进前 10、但要在报告里留档的：** F1（折叠门控——高风险，先按 F2 打薄，拿到真机基线后再评估）、S3（R8 keep 收窄——高风险，必须真机回归闪退）、S2（首帧同步读盘的收益取决于设备存储速度，先看 Perfetto 再决定）、N2/A5（网络路径，等 N1 合并后再叠加）、F3/N3/A6/A7/M3/M4/F4（低收益或纯测量能力）。

---

## 3. 代码里已确认的事实（不需要真机）

1. **高频状态的读取位置是对的**：全仓 `progress.value` 的读取点只有 `graphicsLayer { }`、`derivedStateOf`、`snapshotFlow` 与手势协程（`MainActivity.kt:1002,1006,1464,1515`、`PlayerCard.kt:159,162,183,263,276,293,311,343,350,352,374,384,385,599…880`、`PlayerCardOverlay.kt:41`）——没有在组合阶段读它。GPU 零重组模式在这一层成立。
2. **`position`/`duration`/`progress` 三个 StateFlow 的订阅点都在叶子**：`FullPlayerControls.kt:384-407`（PositionText / DurationText / QualityLabel 各自 collect）、`SlimProgressBar.kt:69-72`（`progressState.value` 只在 Canvas draw 阶段读）；`PlayerViewModel.kt:110-129` 是唯一的写入点（2 Hz）。
3. **`PlayerCard` 折叠门控不存在**：`PlayerCard.kt:163-169` 的注释明确记录「展开态子树常挂载（无 gate）」，与 `AGENTS.md` 的「below `progress = 0.05`, heavy children are disposed」**矛盾**。以代码为准，`AGENTS.md` 这一条需要更新（本次未改，属文档债）。
4. **进度 ticker 确实与「有没有在播」「有没有订阅者」都无关**：`PlaybackService.kt:737-748` 的循环里只有 `if (player.isPlaying)` 决定**是否回调**，`delay(500)` 无条件执行；`onProgressUpdate` 为 null 时照样空转。
5. **MediaSession 的更新已经收敛过**：state 有 900 ms 最小间隔（`PlaybackService.kt:677`、`:691-708`），metadata 只在 title/artist/duration/封面 URL/**位图引用**变化时重发（`:713-732`），通知只在 `onIsPlayingChanged`/`applyNowPlaying`/封面加载完成时重建（`:214`、`:584`、`:642`、`:653`）——**不是**每个 tick 一次。这一项不需要优化。
6. **通知每次重建都会重新构造 3 个 PendingIntent 并带上大图位图**（`PlaybackService.kt:796-812`），但调用频率是「每首歌 2~3 次」，量级可接受。
7. **gzip 与 HTTP/2 不用动**：全仓没有任何 `Accept-Encoding`/`protocols`/`Cache(` 配置（grep 结果为空），即由 OkHttp 默认行为提供透明 gzip 与 TLS ALPN HTTP/2；也不需要为此加代码。
8. **列表的 `key` 与模型稳定性基本到位**：所有 `Lazy*` 的 `items/itemsIndexed` 都带 `key`（`HomeScreen.kt:242,266,276,293`、`LibraryScreen.kt:279,336,388`、`SearchScreen.kt:228,280,315,390,440,497`、`AlbumDetailScreen.kt:172`、`ArtistDetailScreen.kt:175,195`、`PlaylistDetailScreen.kt:92`、`QueueView.kt:353`）；`SongItem`、`PlaylistApi.*`、model 包均已 `@Immutable`（`network/SearchResponse.kt:18-41`、`library/LibraryManager.kt:558`、`lyric/LrcParser.kt:5` 等）。**「列表稳定性」这条不需要批量改造。**
9. **歌词面板的每帧工作量受控**：`LyricsView.kt:104-128` 是「睡到下一行时间戳再写一次」而非逐帧轮询；`MetroLyricsPanel.kt:112-114` 用 `derivedStateOf` 二分定位当前行，`:259-263` 的缩放只在 `graphicsLayer` 里读 `smoothCurrentIndex.value`。残留问题只有 F2 的「不可见时仍跑」。
10. **`baseline-prof.txt` 的 5 条 `ui/anim/sokuou/*` 条目是死引用**（`app/src/main/baseline-prof.txt:83-87`：`SokuouKt`/`UwpEasing`/`UwpEasingKt`/`MetroScrollKt`/`MetroFlingBehavior`）：该目录已迁到外部 Kanesumi（`AGENTS.md` 明确记载，包名现为 `io.github.takahashirinta.kanesumi.anim.sokuou`），本仓库不存在这些类。
11. **`SongUrlFetcher` 把带签名的完整播放 URL 写进 logcat**（`SongUrlFetcher.kt:56`、`:71`），release 也打；`PlaybackService.playUrl` 的注释明确说过「取链 URL 带签名参数，不能进 logcat」——两处口径不一致（安全/日志噪声，属事实记录）。
12. **`LibraryManager.saveSongs(...)` 目前没有调用点**（`LibraryManager.kt:299`，全仓仅定义）；它内部是「N 首各一次 pushLike」（`:312`），一旦被接上会直接放大 N2 的往返数。
13. **`ContentCache` 不落盘**（`ContentCache.kt:9-21`），落盘的是 `LibraryManager`/`LyricsCache`/`PlaybackStateManager`/`SearchHistoryManager`——写盘审计只需盯这四个 + 各 `SharedPreferences.apply()`（`PlaybackStateManager.kt:93-105`、`LyricsCache.kt:68-70`、`LibraryManager.kt:162-169`、`auth/CookieManager.kt:14-20`、`ui/theme/ThemeManager.kt:42-70`、`ui/i18n/LanguageManager.kt:28-40`）。
14. **release 不挂 `HttpLoggingInterceptor`**（`RetrofitClient.kt:80-85`，`BuildConfig.DEBUG` 才挂）；`AndroidManifest.xml:33-35` 的 `<profileable android:shell="true">` 让 release 包也能被 perfetto 抓方法级采样（测量便利，不是性能问题）。
15. **`NcrustApplication.onTrimMemory` 在 `TRIM_MEMORY_RUNNING_MODERATE` 就清空 `ContentCache`**（`NcrustApplication.kt:60-70`）：低内存机型上前台期间的一次 trim 会让首页/详情页回到「无缓存→重新请求」路径（**推测**会带来重复网络，收益/代价需真机看）。
16. **「整列播放/插播全部/追加全部」的完整路径（`293eedd` 新功能，逐行核对过）**：`LibraryScreen.kt:95-119`（`startPlayAll`，期间按钮禁用）→ `LibraryManager.loadAllLikedSongs`（`LibraryManager.kt:492-532`，命中缓存的部分跳过，其余按 **500/批顺序**拉）→ `PlayAllDialog`（`MainActivity.kt:1444-1451`）→ `replaceQueueAndPlay`/`insertAllNext`/`appendAllToQueue`（`MainActivity.kt:892-962`）→ `persistSession()`（`MainActivity.kt:393-405`）→ `PlaybackStateManager.saveSession`（`PlaybackStateManager.kt:136-168`，200 ms debounce 后 Gson 序列化**整份队列**并写 `SharedPreferences`）。**队列里装的是全量 `SongItem` 对象**，不是 id 引用。

---

## 4. 需要真机才能确认的假设（与上一节严格分开）

| # | 假设 | 为什么代码上看不出来 | 用什么测量定论 |
|---|---|---|---|
| H1 | 暂停后 ExoPlayer 的 partial wake lock 与 Wi-Fi lock 会释放（因此熄屏暂停不漏电），漏电主要来自 A1 的 ticker | `WAKE_MODE_NETWORK` 的加解锁在 media3 内部（`WakeLockManager`），本仓库看不到；本机无 JDK/JADX 可反编译 AAR 验证 | `adb shell dumpsys power \| grep -iA4 "Wake Locks"` 与 `adb shell dumpsys wifi \| grep -i wifilock`，在「播放中 / 暂停中」各取一次；配合 batterystats 的 `wake_lock` 计时 |
| H2 | 停掉暂停态 ticker 能省下的电量占比 | 省电幅度取决于机型/调度器，代码里无法推断 | 同机同电量区间的「暂停熄屏 10 min」batterystats 对照（改动前后），看 `Estimated power use` 与 `Wakeups` |
| H3 | 折叠态「常挂载」子树的真实每帧成本 | 代码注释说「每帧 1-3 ms」，是作者当时的观测，未在本次审计中复现；Compose 的 layer alpha=0 是否跳过 draw 也取决于版本 | `adb shell dumpsys gfxinfo $PKG framestats`（折叠静止 30 s 两次快照）+ Perfetto `gfx` 轨道；`Compose:recompose`/`draw` slice 密度 |
| H4 | Coil 的内存缓存 key 含请求尺寸 → AppWarmup 用 320 px 预取的条目**不会**被首屏 480 px 的 tile 命中（磁盘缓存仍命中） | 取决于 Coil 2.6 的 `MemoryCacheKey` 组成，本仓库无源码 | 冷启动首帧用 Perfetto `gfx`/`disk` 看是否仍有 `decodeBitmap`；或临时开 Coil logger 观察 memory cache hit/miss（验证用，不入产品代码） |
| H5 | `Palette.generate {}` 的回调不在主线程（因此不阻塞 UI） | androidx.palette 1.0.0 的线程策略在库内部 | Perfetto `sched` 看 Palette 相关 slice 落在哪个线程；或 `StrictMode` 线程策略临时打开（仅本地验证） |
| H6 | `antiCheatClient`（`plainClient.newBuilder()`）与 `plainClient` 共用连接池与调度器 | OkHttp 的 `newBuilder()` 语义（本机无 JDK 无法反编译确认） | 合并 N1 时用 OkHttp `EventListener` 数 connect 次数即可间接定论 |
| H7 | 方法级 baseline profile 能给冷启动带来可量化收益（以及多少） | 只有真机能测 AOT/verify 差异 | `run_benchmark.sh startup`（改前后各 8 iter）+ `adb shell cmd package compile -m speed-profile -f $PKG` 对照 |
| H8 | 列表封面从 640 降到按显示尺寸，滚动帧率与蜂窝流量都有可见改善 | 解码受布局尺寸约束，真正省的是传输与磁盘缓存体积；帧率影响要真机测 | `run_benchmark.sh scroll` 的 FrameTimingMetric + batterystats 网络字节 + Perfetto `network`/`disk` |
| H9 | 收窄 R8 keep（S3）不会重演 Gson 泛型闪退 | 需要 release 包在真机上跑恢复队列/收藏/歌词四路径 | `assembleRelease` + 装机 + 冷启动 + 会话恢复 + 收藏页 + 歌词；崩溃日志（`:9-10` 已保留行号） |
| H10 | `ContentCache` 的 LRU-32 在真实歌单规模下占多少堆内存 | 取决于用户歌单规模，无法静态算 | `adb shell dumpsys meminfo $PKG`（Java Heap）在「进 6 个大歌单」前后对照 |
| H11 | `onTrimMemory` 清缓存导致的「重复网络」在低端机上真的发生 | 依赖系统 trim 时机 | `adb shell dumpsys meminfo $PKG` 与 logcat 的 `PlaylistApi` 请求计数，在低内存机型上前台跑一段 |
| H12 | HomeScrollBenchmark 在宽屏/折叠形态下能否找到主列表并给出可信帧率 | 依赖设备形态与 a11y 树 | `benchmark/run_benchmark.sh scroll` 在目标大屏设备上跑，看是否 fail 在「未找到首页纵向列表」 |
| H13 | 2 Hz ticker 在低端机主线程（snapshot 广播）的实际开销 | 与机型 CPU/调度相关 | Perfetto `sched` + `gfx`：主线程 500 ms 周期 Runnable 的时长分布；对照暂停态 |

**无法替你验证的部分**（只能由你判断）：
- 「更流畅 / 更舒适」的主观标准（Kanesumi 设计的手感阈值、动画时长偏好）。
- 目标机型分布（低端 4 GB 机 vs 旗舰）——它决定 M1/M2 与 A2 的优先级顺序。
- 弱网缓冲策略的取舍（A2）：宁可多缓冲还是宁可多断，是产品判断。
- 是否接受「锁屏封面降清晰度」换取内存（M1）。

---

## 5. 真机测量脚本草案（bash）

> 用途：**基线采集 → 改动 → 复测**三段式，命令原样可用；`$DEVICE` 用 `-s` 指定设备时可自行在 `ADB` 变量里加。
> 不新建可执行文件——把下面整段贴进终端或存成临时脚本即可。

```bash
#!/usr/bin/env bash
# Ncrust Android 功耗/帧率对照测量草案
# 用法:
#   bash perf_measure.sh base     # 改动前：基线
#   bash perf_measure.sh opt      # 改动后：复测（必须同机、同网络、同亮度、电量 20~80%）
# 约定:
#   - 被测包必须是 release 且已登录（见 benchmark/run_benchmark.sh 的说明）
#   - 每轮之间不要清应用数据；Coil 磁盘缓存与 ART profile 要保持同一热度
set -euo pipefail

PKG="com.takahashirinta.ncrust"
BENCH_PKG="$PKG.benchmark"
LABEL="${1:?用法: perf_measure.sh <base|opt>}"
NCRUST="$(cd "$(dirname "$0")" && pwd)"   # 若贴进终端跑，改成仓库根绝对路径
OUT="$NCRUST/benchmark/output/audit-$LABEL"
ADB="${ADB_BINARY:-adb}"
mkdir -p "$OUT"

echo "== 0. 前置检查（两台设备/两次运行必须一致） =="
$ADB shell pm list packages | grep -q "$PKG" || { echo "!! 未安装 $PKG"; exit 1; }
if $ADB shell dumpsys package "$PKG" | grep -q "debuggable=true"; then
  echo "!! 警告: 设备上是 debuggable 版本, 数字偏慢, 先跑 benchmark/run_benchmark.sh 的 install 流程"
fi
$ADB shell dumpsys battery | grep -E "level|status" | tee "$OUT/battery.txt"
$ADB shell dumpsys thermalservice | grep -iE "status|temperature" | head -5 | tee "$OUT/thermal.txt"
echo "（人工确认: 关自动亮度并固定亮度; 同一 Wi-Fi; 不插电; 电量 20~80%）"

# ---------- 1. 基线：冷启动 + 滚动 + 播放器展开 ----------
echo "== 1. macrobenchmark（startup/scroll/expand） =="
"$NCRUST/benchmark/run_benchmark.sh" all | tee "$OUT/benchmark-console.txt"
# 结果与 Perfetto trace 在设备上，pull 回来归档
$ADB pull "/storage/emulated/0/Android/media/$BENCH_PKG/" "$OUT/traces/" || true

# ---------- 2. 帧：折叠态静止 / 展开动画 ----------
echo "== 2. gfxinfo：先 reset，做固定动作后再取 framestats =="
$ADB shell dumpsys gfxinfo "$PKG" reset
echo "   → 手动：进首页、切到首页 tab、折叠播放器，静止 30 s（不要摸屏幕）"
read -r -p "   静止 30s 完成后按回车继续..." _
$ADB shell dumpsys gfxinfo "$PKG" framestats > "$OUT/frames-idle-collapsed.txt"
$ADB shell dumpsys gfxinfo "$PKG" reset
echo "   → 手动：拉起播放器→收起，重复 10 次（测展开/收起动画）"
read -r -p "   完成后按回车继续..." _
$ADB shell dumpsys gfxinfo "$PKG" framestats > "$OUT/frames-expand-collapse.txt"

# ---------- 3. 内存 ----------
echo "== 3. meminfo：冷启动后 / 播放 20 首后 =="
$ADB shell dumpsys meminfo "$PKG" > "$OUT/meminfo-cold.txt"
echo "   → 手动：连播 20 首（每首听 5 s 就切）"
read -r -p "   完成后按回车继续..." _
$ADB shell dumpsys meminfo "$PKG" > "$OUT/meminfo-after-20songs.txt"

# ---------- 4. 功耗：在播 / 暂停 两个 10 min 窗口 ----------
echo "== 4. 功耗窗口（每个窗口 10 min，全程熄屏） =="
power_window() {
  local name="$1" manual="$2"
  echo "--- $name ---"
  $ADB shell dumpsys batterystats --reset >/dev/null
  echo "   → 手动：$manual"
  read -r -p "   准备好后按回车开始 10 min 计时（随后请熄屏）..." _
  $ADB shell input keyevent KEYCODE_SLEEP   # 熄屏
  sleep 600
  $ADB shell input keyevent KEYCODE_WAKEUP
  $ADB shell dumpsys batterystats "$PKG" > "$OUT/batt-$name.txt"
  $ADB shell dumpsys power  | grep -iA4 "Wake Locks" > "$OUT/wakelocks-$name.txt" || true
  $ADB shell dumpsys wifi   | grep -i wifilock      > "$OUT/wifilocks-$name.txt" || true
}
power_window "playing" "开始播放一首常听歌，进入播放器（保持播放），然后按回车"
power_window "paused"  "在上一步的基础上，点暂停（服务仍在，通知栏还在），然后按回车"

# ---------- 5. 网络往返计数（收藏写路径 / 首页重复请求） ----------
echo "== 5. 网络往返计数 =="
$ADB logcat -c
echo "   → 手动：进收藏页 → 点 1 次红心 → 退出重进收藏页"
read -r -p "   完成后按回车继续..." _
$ADB logcat -d -s PlaylistApi:I LibraryManager:I > "$OUT/like-roundtrips.txt" || true
echo "   计数: likeSong(xeapi)=$(grep -c 'likeSong(xeapi)' "$OUT/like-roundtrips.txt" || true)  \
pushLike=$(grep -c 'pushLike' "$OUT/like-roundtrips.txt" || true)  \
refreshFromCloud=$(grep -c 'likedIds=' "$OUT/like-roundtrips.txt" || true)"

$ADB logcat -c
echo "   → 手动：首页→搜索→首页→搜索→首页（每个 tab 停 3 s）"
read -r -p "   完成后按回车继续..." _
$ADB logcat -d -s PlaylistApi:D > "$OUT/home-repeat-requests.txt" || true
echo "   discovery 请求数: $(grep -c 'discovery' "$OUT/home-repeat-requests.txt" || true)"

# ---------- 6. Perfetto：一条覆盖帧/调度/功耗/IO/网络的 trace ----------
echo "== 6. Perfetto trace（10 min，覆盖一次完整播放 + 切歌 + 进收藏页） =="
cat > /tmp/ncrust.cfg <<'CFG'
buffers { size_kb: 65536 fill_policy: RING_BUFFER }
data_sources {
  config {
    name: "linux.ftrace"
    ftrace_config {
      ftrace_events: "sched/sched_switch"
      ftrace_events: "sched/sched_wakeup"
      ftrace_events: "sched/sched_waking"
      ftrace_events: "power/cpu_frequency"
      ftrace_events: "power/cpu_idle"
      ftrace_events: "power/suspend_resume"
      ftrace_events: "binder/binder_transaction"
      atrace_categories: "gfx"
      atrace_categories: "view"
      atrace_categories: "wm"
      atrace_categories: "am"
      atrace_categories: "sm"
      atrace_categories: "res"
      atrace_categories: "dalvik"
      atrace_categories: "sched"
      atrace_categories: "freq"
      atrace_categories: "idle"
      atrace_categories: "binder_driver"
      atrace_categories: "binder_lock"
      atrace_categories: "power"
      atrace_categories: "memory"
      atrace_categories: "disk"
      atrace_apps: "com.takahashirinta.ncrust"
    }
  }
}
data_sources {
  config {
    name: "linux.sys_stats"
    sys_stats_config {
      stat_period_ms: 1000
      stat_counters: "cpufreq"
      stat_counters: "cpuidle"
      stat_counters: "devfreq"
    }
  }
}
data_sources { config { name: "android.packages_list" } }
duration_ms: 600000
CFG
$ADB push /tmp/ncrust.cfg /data/misc/perfetto-configs/ncrust.cfg >/dev/null
$ADB shell perfetto -c /data/misc/perfetto-configs/ncrust.cfg --txt \
  -o "/data/misc/perfetto-traces/ncrust-$LABEL.perfetto-trace" &
PERFETTO_PID=$!
echo "   → 手动：播放 3 首（含切歌）→ 拉起/收起播放器 5 次 → 进收藏页滚一段 → 保持播放到 trace 结束"
wait "$PERFETTO_PID" || true
$ADB pull "/data/misc/perfetto-traces/ncrust-$LABEL.perfetto-trace" "$OUT/" || true

# ---------- 7. 日志侧证据（切歌/取链/错误） ----------
$ADB logcat -d -s SongUrlFetcher:D PlaybackService:D MainScreen:D AudioSink:E \
  > "$OUT/logcat-keys.txt" || true

echo ""
echo "== 完成: $OUT =="
echo "复测: 改代码 → assembleRelease 装机（保持登录态/不清数据）→ bash perf_measure.sh opt"
echo "对比要点:"
echo "  1) startup/scroll/expand 的 P50/P90 与 jank 帧数（benchmark-console.txt）"
echo "  2) paused 窗口的 Wakeups / Estimated power use（batt-paused.txt）与 wakelocks/wifilocks"
echo "  3) frames-idle-collapsed.txt 的帧数增长（折叠态是否还在出帧）"
echo "  4) meminfo-* 的 Java Heap / Graphics 峰值差"
echo "  5) like-roundtrips / home-repeat-requests 的计数"
echo "  6) Perfetto 里对齐看: 折叠态是否有 drawFrame、暂停态是否有周期性 Runnable、"
echo "     切歌瞬间的 decodeBitmap/disk 段、JIT/编译活动（dalvik 轨道）"
```

**跑之前要注意的三件事**

1. **对照的可比性**：同机、同网络、同亮度、同电量区间、不插电、同一 Coil 缓存热度（第一轮跑完后不要让应用被清理）；Perfetto 与 macrobenchmark 都建议每轮至少 6~8 次迭代。
2. **`--reset` 的语义**：`dumpsys batterystats --reset` 会清全部统计，必须在**每个功耗窗口前**重置，且窗口内不要让系统弹通知/更新。
3. **不要跑会读真实 cookie 的命令**：本脚本只读设备上的应用状态；`benchmark/cookie.secret` 未使用。

---

## 6. 本次没做的事（显式记录）

- 没有编译、没有跑 benchmark、没有真机数据；本报告里的收益/风险全部是**代码推断**，没有任何实测百分比。
- 没有读 `benchmark/cookie.secret`，没有跑任何需要真实登录态的命令。
- 没有改任何产品代码，只新建了本文件。
- 没有验证 `AGENTS.md` 里除「折叠门控」之外的其他性能断言（例如「预载 60 s」「2 Hz」「ContentCache」这几条与代码一致，已核对；「每帧 1-3 ms」这类实测数字未复现）。
- `Kanesumi` 侧只读了 `MetroLyricsPanel.kt` 一个文件（`../Kanesumi-sec-a`，只读），其余 Kanesumi 组件（`MetroBottomNav`/`MetroSidebar`/`MetroText` 内部实现）未审计——若帧率问题定位到它们，需要单独一轮。
- 没有审计 `windows/` 与 `spec/`（不在本任务范围）。
