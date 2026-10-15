# 任务 W0：Ncrust for Walkman 地基（独立构建 + 网络 + 加密 + 夹具测试 + 冒烟）

## 先读

- `walkman/TASKBOOK.md`（**全文**，尤其 §0 不可让渡的约束、§1 工程形态）。
- `docs/f885-spike-report.md`（设备实测结论）；可参考 spike 独立工程 `D:/arcdeck/spike-f885/`（只读，不要改它）：
  `app/src/com/ncrust/f885spike/Net.java`（OkHttp + Conscrypt 的可用写法）、`Eapi.java`、`tools/build.ps1`（Conscrypt aar 的取法）。
- `spec/README.md`、`spec/api/endpoints.md`、`spec/api/xeapi.md`、`spec/fixtures/crypto/README.md`、`spec/fixtures/quality/README.md`。
- Android 主应用的对应实现**只作参考、不复制粘贴整文件**：`app/src/main/java/com/takahashirinta/ncrust/network/crypto/{EapiCrypto,WeapiCrypto,XeapiCrypto}.kt`、
  `network/RetrofitClient.kt`、`player/SongUrlFetcher.kt`。

## 要求

1. **独立工程** `walkman/`：
   - 自带 `settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`；复制根目录的 `gradlew.bat`、`gradlew`、`gradle/wrapper/*`。
     **不要**修改根目录 `settings.gradle.kts` / `build.gradle.kts`，不要让根构建感知到 `walkman/`。
   - 单模块 `:app`（目录 `walkman/app/`）；`minSdk 16`；`abiFilters` 只留 `armeabi-v7a`；Kotlin；无 Compose、无 AndroidX。
   - 依赖按 `TASKBOOK.md` §1 白名单。Conscrypt 用 Maven 坐标 `org.conscrypt:conscrypt-android:2.5.x`（选定具体版本并说明；spike 用的 2.5.3 实测可用）。
   - `.gitignore` 覆盖 `walkman/build`、`walkman/app/build`、`walkman/.gradle`、`walkman/local.properties`。
2. **网络层**（`net/`）：
   - `Application.onCreate` 里把 Conscrypt 插到 provider 第 1 位；OkHttpClient 用 `ConnectionSpec.MODERN_TLS`（TLS 1.2+），超时、连接池复用一份客户端。
   - eapi POST（默认 host `interface.music.163.com`，取链用 `interface3`）、weapi POST（`music.163.com`）、xeapi POST（协议见 `spec/api/xeapi.md`）。
   - cookie 由上层注入（W1 再做持久化），本阶段允许匿名。
3. **加密**（`crypto/`）：eapi（AES-128-ECB + MD5）、weapi（双层 AES-CBC + raw RSA，`BigInteger`）、xeapi（X25519 **纯 Kotlin** Montgomery ladder；
   HKDF；AES-GCM 优先用 Conscrypt 的 `Cipher.getInstance("AES/GCM/NoPadding")`，JVM 测试环境下用 JDK 自带实现）。base64 自己实现或用不依赖 Android 的写法（单测在 JVM 跑）。
4. **夹具测试**（JVM，`app/src/test/`）：逐字节跑 `spec/fixtures/crypto/eapi.json`、`weapi.json`、`xeapi.json` 的**全部用例**，失败信息打印用例 id；
   从测试工作目录向上找到仓库根定位 `spec/`，找不到要**报错**而不是跳过。音质阶梯跑 `spec/fixtures/quality/quality.json`（`ladder` / `retry` 两类）。
5. **接口**（`api/`，返回简单数据类，不做 UI）：歌曲详情（`/eapi/v3/song/detail`，同时解析 `privileges[]`）、取链（`/eapi/song/enhance/player/url/v1`，
   按 `spec/fixtures/quality` 的阶梯降级，**跳过 FLAC 档的门控**：本机无 MediaCodec FLAC 解码器，但 `MediaPlayer` 可能能播——本阶段把 FLAC 档留在阶梯里并在注释说明，W4 实测后再定）、
   歌词（`/api/song/lyric`）、每日推荐（需登录，本阶段只写调用、匿名下返回失败即可）、我喜欢的音乐 id 列表读取（需登录，同上）。
   权益判定：实现 `spec/api/entitlement.md` 的 `classifyPlayback` 纯函数，JVM 测试跑 `spec/fixtures/entitlement/playback.json` 全部用例。
6. **冒烟 Activity**：一个只有按钮与日志文本框的 Activity：「匿名取链并播放」→ 取歌曲 5257138 的 standard 档地址，用 `MediaPlayer`（`AudioManager.STREAM_MUSIC`）播放，
   把每一步（TLS 版本、业务码、码率、`prepare` / `start` / 位置推进）打到 logcat（tag `NcrustWm`）与界面。
7. 建立 `walkman/AGENTS.md`：构建命令、目录结构、依赖白名单、设备纪律（`-s 110024C5064474`）、本阶段已知坑。

## 设备

可以用设备验证冒烟（安装 → 启动 → 点按钮或 `am start` 带参数触发 → 看 logcat → **卸载**）。所有 adb 命令带 `-s 110024C5064474`。
Git Bash 下给 adb 传设备路径要 `export MSYS_NO_PATHCONV=1`。4.1 的 `input swipe` 不接受时长参数。完成后设备上不留任何东西。

## 允许修改的文件

只允许新建 / 修改 `walkman/**` 与根目录 `.gitignore`（只追加 walkman 相关行）。不要改 `app/**`、`windows/**`、`spec/**`、根目录 Gradle 文件。

## 验收

```
walkman\gradlew.bat -p walkman testDebugUnitTest
walkman\gradlew.bat -p walkman assembleDebug
```
