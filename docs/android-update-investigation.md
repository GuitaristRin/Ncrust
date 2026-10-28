# Android 更新问题核查

2026-10-28。用户反馈更新说明表格显示异常，以及更新页内曾提示找不到包；已手动升级到最新版，原始失败无法再次提供。

## 已确认与修复

旧 `MarkdownText` 逐行显示内容，没有表格块解析，也没有链接注解。Release 正文的管道表格直接作为文字出现，下载链接显示原始 Markdown。

本次增加表格块识别、表头与列对齐、整表横向滚动、转义竖线、缺列补空、下载链接和围栏代码块。标题与列表强调色跟随主题。未新增依赖；仍是限定语法的轻量阅读器，不声称完整支持 CommonMark/HTML。

验证结果：

- `:app:testDebugUnitTest --tests '*MarkdownBlocksTest'`：11 项通过，0 失败，包含实际发布说明形状、链接注解与空表格防崩溃用例。
- `:app:assembleDebug`、`:app:assembleDebugAndroidTest`：通过。
- 本地 Pixel_10 Android 模拟器、API 37：`MarkdownRenderingTest` 2 项通过，包含双主题表格滚动与系统读取已发布 APK 的包名、版本、签名摘要。
- 四张 1080×2424 截图已人工查看；初始列对齐，滚动后下载列可完整阅读，代码块内竖线保留。正文/背景令牌对比度：暗色 18.43:1、浅色 16.05:1；浅色表头 13.65:1。这些数值仅为该验收配色的计算值。
- 本地截图：`build/markdown-qa/{dark,dark-scrolled,light,light-scrolled}.png`；构建及模拟器输出：同目录 `test-build.txt`、`instrumentation.txt`（build 目录不入库）。

## 找不到包：尚未确认原因

当前班次 [CrateStack-20261025A](https://github.com/GuitaristRin/Ncrust/releases/tag/CrateStack-20261025A) 在 2026-10-28 发布，包含 `ncrust-android-universal-1.4.1.apk`。

本轮验证了：

| 检查 | 结果 |
|---|---|
| 无 GitHub 认证访问公开 APK 链接、跟随重定向 | HTTP 200，4,075,560 字节 |
| 本地 aapt2 读取身份 | `com.takahashirinta.ncrust`，versionName 1.4.1，versionCode 9，minSdk 24，targetSdk 36 |
| apksigner verify | 通过，v2 签名，1 个签名者 |
| SHA-256 与 GitHub 资产摘要比对 | 一致，`363a980a5a75362ecede73c90be2ac9b4b8f870bced122811462809ee9607e3f` |
| APK 位于应用 cacheDir 后由 ApkArchive 读取 | 模拟器通过，包名、版本和非空签名摘要均有效 |
| 原有更新规则与下载器测试 | 本轮基线运行通过；未改动这些断言或跨端夹具 |

这些结果只能说明当前公开资产、下载链路及该模拟器上的包读取正常，不能证明用户当时的失败已解决。原始提示、发生时间与失败阶段没有足够证据；网络、当时资产状态或缓存身份等均不能在本轮定性。没有为猜测的原因修改更新选择规则、降低签名/版本校验或允许同版本更新。

测试过程中第一次把 APK 放到 shell 创建的外部测试目录，应用访问受限；改用应用实际缓存路径后通过。该测试目录权限问题不是用户报障的根因证据。

## Issue 核查

- [#35](https://github.com/GuitaristRin/Ncrust/issues/35)：`0b3684d` 的滑动切页已列入 Android 1.4.1 发布说明，已回复并关闭。
- [#33](https://github.com/GuitaristRin/Ncrust/issues/33)：`c84c0fd` 的全量红心状态修复与 `293430c` 的写入判定已列入同版本说明；`LibraryManagerSongSavedTest` 基线通过，已回复并关闭。评论中的切歌显示模式及同时播放建议没有标为已完成。
- #34、#32、#31、#29、#4 缺少解决证据，保持开放。

本轮仅产生调试构建；Markdown 修复尚未发布。真机升级闭环未运行，没有操作真实 Android、Lumia 或 Walkman 设备。
