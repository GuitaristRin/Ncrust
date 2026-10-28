# Ncrust 平台规划

2026-10-28，按负责人决定：当前侧重点是 Ncrust 本身，先推进 Ncrust 的平台覆盖，再开展 Ritsudou 的独立表达。此处的「全平台」指以下五条明确的开发线，不表示所有平台已经可用。

| 平台线 | 技术路线 | 仓内落点 | 当前状态 |
|---|---|---|---|
| Windows 桌面 | C# / UWP + WinUI 2 | `windows/` | 已有客户端与发行线；模块规则见 `windows/AGENTS.md` |
| Android 主版 | Kotlin / Android + Compose / Media3 | `app/` | 已有客户端与发行线；继续修复更新与阅读体验、研究歌单管理 |
| Linux | Rust / Kanesumi | 待建立实现目录 | 路线已指定；本轮无应用骨架、构建或 Linux 运行验证 |
| Lumia 950 | C++ / WinUI（UWP、Windows.UI.Xaml）/ ARM32 | `lumia/` | 从原 Ritsudou 迁入原型基线；尚未品牌迁移、协议对齐或本轮构建验收 |
| Android 4 极简版 | 沿用 Walkman 立项的 Kotlin / 原生 View / MediaPlayer | `walkman/` | 已有任务书与 API 16 可行性研究；仓内尚只有任务文档，没有 W0 应用工程 |

## 分工与优先级

- Ncrust 继续作为网易云音乐第三方客户端，跨端共享协议与行为答案，保留符合平台能力的原生实现。
- 当前进行中的安卓缺陷与歌单调研继续收尾；平台规划不取消这些工作。
- Windows 桌面与 Lumia 950 分别维护。Lumia 线采用原型已有的 C++/WinRT + Windows.UI.Xaml，不把桌面 WinUI 包的系统要求直接施加到 Windows 10 Mobile。
- Linux 采用 Rust / Kanesumi；先另立最小播放闭环任务卡，不在本轮用空目录或占位界面声称完成移植。
- Android 4 极简线承接 `walkman/TASKBOOK.md`：API 16、无 Compose/AndroidX、系统 MediaPlayer、保留原厂音频通路。是否扩展到其他 Android 4 设备，分别验证。
- Ritsudou 保持 private，独立方向保留但暂缓。其旧 Lumia 开发工作归入 Ncrust，不在两仓并行推进同一客户端。

## 共用边界

`spec/` 继续是协议、加密、音质阶梯、队列、歌词和更新行为的共同答案。平台差异应明确记录；具备相同能力时不能各自发明业务规则。Android 4 和 Windows 10 Mobile 的能力限制须用真实系统验证。

各线独立构建、独立版本、独立发布。只有通过相应构建与目标环境验收后，才在发布索引中提供安装包；源码迁入、架构选定和平台可用是不同状态。

## Lumia 迁移边界

基线来自原 Ritsudou 的 `ae46a99e5f21935ba0680d56001f14aa69ee41f6`，55 个原文件按 Git 行尾规范化后核对 blob，再补迁入说明与本目录规则。保留原源仓与历史；源码仍用原工程与包身份。

下一步先核对 ARM32 工具链、Windows 10 Mobile API、签名与包身份迁移，再对齐当前 spec，之后恢复功能开发。原型的外链兜底、旧登录/收藏协议与历史「完成」描述不能作为当前验收证据。本轮没有操作 Lumia 或 Walkman 设备。
