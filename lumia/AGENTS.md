# Ncrust for Lumia

先读仓库根 `AGENTS.md` 及其指向的工作台通用约定。本目录是 Ncrust 的 C++ / UWP（Windows.UI.Xaml）Lumia 950 开发线，与 `windows/` 的 C# 桌面实现分别维护。

- 2026-10-28 从原 Ritsudou 的 `ae46a99` 基线导入，来源见 README。现有工程、命名空间和包身份仍为 Ritsudou；未完成品牌迁移或当前协议对齐，不能作为 Ncrust 正式发行包。
- 目标为 Windows 10 Mobile / ARM32；保留 C++/WinRT + Windows.UI.Xaml。用户所称 C++/WinUI 线不意味着可以直接添加面向较新桌面系统的 WinUI 2/3 包；先验证 Mobile 系统与 SDK 约束。
- 共享协议与行为以根 `spec/` 为准；原型中的旧协议与外链兜底不是新真值，接线前逐项对齐。
- 在本目录运行原型构建：`msbuild Ritsudou.sln /t:Restore /p:Configuration=Debug /p:Platform=x64`，再 `/t:Build`；ARM 发布使用 `/p:Configuration=Release /p:Platform=ARM`。本次导入未运行这些构建，原环境与签名须重新核对。
- 不加入 Android Gradle 构建，不复用桌面 C# 二进制，不升级工具链或依赖来绕过 Mobile 兼容性。
- 设备部署、签名证书与包身份变更另行验收；不操作真实设备，不生成或提交私钥。
- 本目录提交使用 `(lumia)` scope；代码迁移、协议对齐、品牌迁移与实际功能分别按逻辑单元提交。
