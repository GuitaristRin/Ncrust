# Android 收藏回滚调查与安装验收

2026-10-29。

## 任务边界

- 目标：排查 Android 1.4.1 的云端收藏撤销，修复可证实的缺陷，并安装包含歌单管理的版本。
- 真值：手机实际 APK、`spec/api/xeapi.md`、Windows 对应实现及真实服务端读回；模拟测试不代替隔日验证。
- 参考：`PlaylistApi`、`RetrofitClient`、`LibraryManager`；歌单入口及写入边界见 [implementation.md](implementation.md)。
- 验收：收藏请求失败时不写旧通道；最后一次用户操作不能被旧请求覆盖；待确认收藏不被刷新抹去；回归测试、签名构建与保留数据的覆盖安装。
- 止损：不重放未知结果，不改现有歌单或批量恢复收藏，不把立即读回当成隔日持久性证明；服务端证据不足时保留未确认结论。

## 已核实事实

- 用户确认：收藏在网易云官方客户端中也消失，故并非仅本地分页显示问题。
- 连接手机安装的是 versionCode 9 / 1.4.1，最后更新 2026-10-28；APK SHA-256 为 `363a980a5a75362ecede73c90be2ac9b4b8f870bced122811462809ee9607e3f`。
- 该 APK 含 `checkToken`、`rqRefer`、`songRqRefer` 及三个通道的日志字符串，不含歌单管理开关字符串；仅靠字符串不能证明每次运行都发出了正确请求。
- Android 与 Windows 源码均仍在 xeapi 失败后尝试 weapi/eapi 老通道。Windows 的既往成功不能证明 Android 当前失败原因，也不能证明这个回退安全。
- Android 的收藏/取消各自启动 IO 协程，未串行化；旧请求读回成功后无条件清除同一歌曲的新待同步状态。刷新也会用云端列表直接覆盖待同步的乐观收藏。
- 本轮读取的手机 logcat 没有保留下收藏标签日志，无法追溯本次消失歌曲的历史实际请求。
- 手机 APK 与本地归档 `build/release-android-1.4.1/ncrust-android-universal-1.4.1.apk` 的 SHA-256 完全相同。

## 修复与证据边界

1. 删除 Android 的 weapi/eapi 收藏回退，只提交 xeapi v1。失败保留待同步，不能再用老通道的暂时成功清除待同步。
2. 关闭 xeapi 写请求的连接自动重试及重定向；响应解不开仅使公钥失效，不自动重放可能已执行的写入；HTTP 错误也不能按成功体处理。
3. 每次写入现取非空反作弊 token，同一 token 放入 `checkToken` 与请求头；获取失败则不发送业务请求。此前允许空 token 的兼容逻辑确实存在，但**没有证据证明它就是本次历史回滚的触发原因**。
4. `PendingLikeWrites` 按序提交，排队的旧意图跳过；在途旧请求完成后不能清除新意图。请求绑定发起时凭证，切换账号后旧响应不能确认新账号的状态。
5. 待同步意图在调度网络前写入 SharedPreferences，不再等待 300ms 防抖。普通刷新保留待确认收藏/取消；刷新期间发生新操作或写入确认时，拒绝应用旧快照。
6. 内部 `files/like-sync-audit.jsonl` 最多保留 256 条写入/读回事件，不记录凭证、token、账号或设备标识；它只记录新版本之后的操作，无法补回旧日志。

上述改动消除已确认存在的风险路径，**不等于复现并确定了本次服务端撤销的具体原因**。已被撤销且没有历史意图记录的收藏不会自动恢复。

上游当前 [like_v1.js](https://raw.githubusercontent.com/NeteaseCloudMusicApiEnhanced/api-enhanced/master/module/like_v1.js) 使用 xeapi `/api/v1/radio/like`；[register_checktoken_v3.js](https://raw.githubusercontent.com/NeteaseCloudMusicApiEnhanced/api-enhanced/master/module/register_checktoken_v3.js) 每次获取 token。空 token 时禁止提交是本轮 Android 的客户端保护策略，非上游隔日成功证明。

## 验收记录

| 验收 | 结果 |
|---|---|
| 收藏、加密、歌单写协调、歌单播放与队列回归 | 14 个测试类，95 项通过，0 失败 / 0 错误；没有真实账号写入 |
| 新增回归 | 16 项，覆盖请求通道/字段、风险响应、取消、并发顺序、新旧意图、账号切换、刷新竞争、token 缺失/新鲜度/头体同值及不可读响应不重放 |
| Release / R8 / lintVital | 最终构建成功，1m57s；保留仓库既有弃用及无需 cast 提示，未新增依赖 |
| APK ZIP 与签名 | ZIP 完整性检查成功；签名证书 SHA-256 与手机原包相同 |
| 覆盖安装 | `adb install -r` 成功；versionName 1.4.2 / versionCode 10，首次安装时间仍为 2026-10-15 |
| 实际手机 APK | SHA-256 与最终本地构建一致：`0ea46efcc67abaae1a5c38f896e4602a4b5aef4691dfa3be7963e3ff0aa03148` |
| 手机启动与只读操作 | Xperia XQ-ES72，1080×2520 / 450dpi；可读取本人及收藏歌单，登录状态保留；未见 AndroidRuntime 崩溃日志 |
| 实验开关 | 手机已开启，强制停止后重启以及覆盖最终包后均仍显示可用的新建入口；全局缺省仍为 false |
| 新建界面 | 实际手机打开空表单，默认私密、空名称时创建禁用；全表单及按钮在屏幕内，已截图并人工查看；随后取消，未提交创建 |

最终验证命令：

```powershell
.\gradlew.bat :app:testDebugUnitTest --tests '*Like*Test' --tests '*LibraryManager*Test' --tests '*Xeapi*Test' --tests '*PlaylistRepositoryTest' --tests '*PlaylistEditApiTest' --tests '*PlaylistPlaybackTest' --tests '*PlaybackQueue*' :app:assembleRelease --console=plain
adb -s <device> install -r build/like-audit-20261029/ncrust-android-universal-1.4.2.apk
```

本地完整输出：`build/like-audit-20261029/final-build.txt`。测试 XML：`app/build/test-results/testDebugUnitTest/`。
原手机 APK：`build/like-audit-20261029/installed-1.4.1.apk`，ZIP 可读；未卸载、未清数据。
最终包：`build/like-audit-20261029/ncrust-android-universal-1.4.2.apk`。
界面证据：同目录 `settings-enabled.png`、`playlist-create.png`；新建表单截图没有账号或现有歌单名称。

**未运行：真实账号收藏写入、歌单创建/编辑/删除、写后真实读回与隔日持久性。** 本轮没有变更任何云端收藏或歌单；没有发布、推送；Windows 的旧回退路径未修改。立即读回即使成功，也不能代替隔日验收。
