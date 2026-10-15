# 任务 K1（Kanesumi 仓库）：MetroIndication 焦点态 + MetroBottomNav 标签策略

## 背景

Ncrust 要适配按键小屏机（荣悦 E5 一类，方向键 + 数字键），调研见 Ncrust 仓库 `docs/small-screen/research.md`。实测按方向键时**看不到任何焦点**——
Kanesumi 的 `MetroIndication` 只画按压态。负责人决定在 Kanesumi 上游补齐，所有 `Metro*` 控件一次受益。另外矮屏需要更紧凑的底部导航。

**本任务在 Kanesumi-sec-a 仓库**（不是 Ncrust）。先读该仓库的 `AGENTS.md` 与 `CLAUDE.md`（铁律：零 M3、GPU 零重组、用 Sokuou 预设、直角无圆角）。

## 要求

1. **`MetroIndication` 焦点态**（`kanesumi-core/.../theme/MetroIndication.kt`，Modifier.Node + DrawModifierNode 的参考实现）：
   - 订阅 `InteractionSource` 的 `FocusInteraction.Focus` / `Unfocus`，在**绘制阶段**画焦点框：直角、2dp、`LocalMetroColors.current.primary` 描边，
     贴组件边界向内画（不改变布局尺寸），无圆角、无阴影、无动画或只用 Sokuou 预设的极短淡入。**不得引入组合期状态读写**（零重组）。
   - 焦点框与按压态叠加时两者都可见。
   - Compose 的 `clickable` 在触摸模式下不可获得焦点，所以触屏用户不会看到焦点框——这是期望行为，在 KDoc 里写明。
   - 提供让调用方关闭焦点框的方式（构造参数，默认开启），以兼容个别自己画焦点的控件。
2. **`MetroBottomNav` 标签策略**（`kanesumi-structure/.../bottomnav/MetroBottomNav.kt`）：
   - 新增参数 `labelMode: MetroBottomNavLabelMode = Always`，取值 `Always` / `SelectedOnly` / `Never`。`Never` 时只画图标并让图标在可用高度内居中；
     `SelectedOnly` 时未选中项只画图标、选中项画图标 + 文字。切换选中项时文字的出现 / 消失沿用现有「双 Icon 叠 alpha」的做法，不用颜色动画、不触发逐帧重组。
   - 已有的 `heightDp` 参数保持；确认在 `heightDp = 48.dp` 且 `labelMode = Never` 时布局正确（不裁切、指示条位置正确）。
   - 每个 tab 在方向键下可获得焦点并显示 MetroIndication 焦点框；左右键在 tab 间移动。
3. **sample app**：在 `:sample` 加一页「焦点 / 小屏」演示：一列 `MetroButton` / 列表行用方向键可遍历；三种 `labelMode` 的底部导航可切换；heightDp 可选 56 / 48。
4. `README.md` / `AGENTS.md` 若有组件参数表，同步更新。

## 允许修改的文件

`Kanesumi-sec-a` 仓库内：`kanesumi-core/**`、`kanesumi-structure/**`、`kanesumi-controls/**`（仅当某控件需要把 `interactionSource` 传给 MetroIndication 才能显示焦点时）、
`sample/**`、`README.md`、`AGENTS.md`、`CLAUDE.md`。不加任何依赖。

## 验收

```
./gradlew.bat :sample:assembleDebug
./gradlew.bat assemble
```

最终回复里列出：哪些 `Metro*` 控件现在会显示焦点框、哪些不会（及原因），以及 API 变更清单（新参数、默认值），派工者据此改 Ncrust。
