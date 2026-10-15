# 任务 S1：高度档 + 全屏播放器按高度适配（封面 / 控件区 / 歌词 / 默认模式）

## 背景

调研见 `docs/small-screen/research.md`（**必读全文**，含截图 `docs/small-screen/shots/`）。核心问题：矮屏上满宽方形封面盖住进度条与歌名，最矮一档连播放键都被盖住；
歌词模式只剩约 2 行。根因是响应式分支只看宽度。负责人确认：**扁屏不接受左右贴边的方形封面，封面要按可用高度适配**。

必读：`AGENTS.md` 中 Player Card、Animation Pattern、Key Constraints 各节（播放器卡片是 load-bearing 的手调动画，**不得批量重构**）；
`ui/player/PlayerCard.kt`、`FullPlayerControls.kt`、`LyricsView.kt`；`MainActivity.kt` 里 `collapsedOffsetY` / 车机实测高度的逻辑（高度来源要与它一致）。

## 要求

1. **高度档**（新文件如 `ui/HeightClass.kt`）：`enum HeightClass { Regular, Compact, Tiny }`，按内容可用高度：Regular ≥ 720dp、Compact 560–719dp、Tiny < 560dp。
   提供 `LocalHeightClass`（`MainActivity` 顶层按与 `collapsedOffsetY` 相同的高度来源计算并提供；车机走实测高度）。判定写成纯函数并单测（含边界值）。
2. **封面按高度定尺寸**（窄屏，即非 `isWidePlayer`）：
   - 大封面边长 = `min(可用宽度, 可用高度 − 顶栏 − 控件区实测高度 − 最小留白)`，水平居中。普通手机（Regular）算出来应仍是满宽——外观不变。
   - **只改** `coverSizePx`、`largeCoverCenterX/Y` 的取值来源（改为由布局测量得到），**不改**封面 `graphicsLayer` 里的 progress / lyricAnimProgress 插值公式，
     不改动画时长与缓动。控件区高度用 `onSizeChanged` / `onGloballyPositioned` 测量，读取放在 draw / layout 阶段，不引入逐帧重组。
   - 把「由可用尺寸算封面边长与锚点」写成纯函数并单测（Regular 满宽、Compact / Tiny 受高度约束、极端值不为负）。
3. **控件区按档收紧**：Compact 用 `FullPlayerControls` 已有的 `compact = true`；Tiny 在 compact 基础上把进度条与时间压成一行（时间放进度条两端或同一行），
   切换行（歌词 / 队列 / 收藏三个图标）移进顶栏右侧（收起按钮左边）。Regular 不变。
4. **默认模式**：Compact / Tiny 档在当前歌有歌词时默认进歌词模式（小封面在顶栏）；无歌词时大封面。沿用现有「大封面 ↔ 歌词视图」自动切换的入口，不另造状态机。
5. **歌词排版按档**：`MetroLyricsPanel` 已有 `fontSize` / `lineHeight` / `translationFontSize` / `translationLineHeight` 参数（Kanesumi 不用改）。
   Regular 保持 32/42/20/26sp；Compact 约 24/32/16/21sp；Tiny 约 20/27/14/18sp（可按实测微调，在注释说明）。
6. **验收标准**（派工者会用 `wm size/density` 模拟复核）：三档下歌名 / 歌手、进度条与时间、上一首 / 播放 / 下一首都**可见可操作**，没有被封面盖住；
   E5 档（427×640dp）歌词模式至少显示 5 行。Regular 档（如 384×896dp）与改动前像素级外观一致。
7. 更新 `AGENTS.md` / `CLAUDE.md` 中「cover always fills the full screen width」的表述为：Regular 档满宽；Compact / Tiny 按可用高度适配并居中。

## 设备

**不要操作任何设备**（本机连着负责人的手机与 Walkman，负责人可能正在使用）。视觉复核由派工者用 `wm size/density` 模拟三档完成；
你在最终回复里写清楚每档下应该看到什么（封面边长、控件区布局、歌词行数的估算），派工者据此核对。

## 允许修改的文件

`app/src/main/java/com/takahashirinta/ncrust/ui/**`、`MainActivity.kt`（只做高度档提供与必要接线）、`app/src/test/**`、`AGENTS.md`、`CLAUDE.md`。
不改 Kanesumi、`network/**`、`player/**`、`windows/**`、`spec/**`。

## 验收

```
./gradlew.bat :app:testDebugUnitTest --offline
./gradlew.bat :app:assembleDebug --offline
```
