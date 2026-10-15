# AGENTS.md —— Ncrust for Walkman

面向 **Sony Walkman NW-F885（Android 4.1.1 / API 16）** 的精简版 Ncrust。这是**独立分支工程**：
与仓库根的 Android 主应用（`app/`，minSdk 24、Compose、AndroidX）**不共享源码**，对根 Gradle 构建
不可见（与 `windows/` 同一策略）。

任务书：`walkman/TASKBOOK.md`（§0 不可让渡的约束优先于本文）。阶段任务：`walkman/tasks/`。
可行性依据：`docs/f885-spike-report.md`。

---

## 构建与验收

所有命令从**仓库根目录**执行（`-p walkman` 指到本工程）：

```bash
walkman\gradlew.bat -p walkman testDebugUnitTest     # JVM 单测（夹具逐字节比对）
walkman\gradlew.bat -p walkman assembleDebug         # 出 APK
walkman\gradlew.bat -p walkman lintDebug             # API 兼容性与静态检查
```

Windows 上先设置本机 SDK 路径（或在 gitignored 的 `walkman/local.properties` 中配置 `sdk.dir`）：

```powershell
$env:ANDROID_HOME = "D:\Applications\AndroidSDK"
```

该路径仅为本机示例，不属于工程配置；新 worktree 不会自动继承主工程的 `local.properties`。

产物：`walkman/app/build/outputs/apk/debug/app-debug.apk`

- **不要**把 `walkman/` 加进根 `settings.gradle.kts`，也不要改根 Gradle 文件。
- 本工程的 Gradle wrapper 是从仓库根**复制**来的（同一版本），不共享。
- 本机本次构建已解析依赖；新环境首次构建需要联网获取工具链与依赖。

## 目录结构

```
walkman/
├── settings.gradle.kts          # 独立构建入口，单模块 :app
├── build.gradle.kts             # 插件版本（与根工程同版本，见下）
├── gradle.properties            # android.useAndroidX=false
├── gradlew / gradlew.bat / gradle/wrapper/
├── TASKBOOK.md                  # 分支工程任务书（§0 约束）
├── tasks/                       # 分阶段任务书
└── app/src/
    ├── main/java/com/takahashirinta/ncrust/walkman/
    │   ├── WalkmanApp.kt        # Application：最早插入 Conscrypt provider
    │   ├── crypto/              # eapi / weapi / xeapi 加密 + 自带 base64 / hex / X25519
    │   ├── json/                # 自带 JSON 解析（主代码不能引 Gson）
    │   ├── net/                 # OkHttp 客户端、TLS、表单编码、三种 POST 风格
    │   ├── quality/             # 音质阶梯（纯函数）
    │   ├── api/                 # 接口 + 数据模型 + 权益判定（纯函数）
    │   └── smoke/               # W0 冒烟 Activity
    ├── main/res/                # 框架原生 View（无 Compose、无 AndroidX）
    └── test/java/…              # 夹具测试
```

## 依赖白名单（`TASKBOOK.md` §1）

| 依赖 | 版本 | 用途 |
|---|---|---|
| `org.jetbrains.kotlin:kotlin-stdlib` | 1.9.24 | 语言运行时 |
| `com.squareup.okhttp3:okhttp` | 3.12.13 | HTTP（最后支持 API 9+ 的分支；**不要升 4.x**，它要 API 21） |
| `org.conscrypt:conscrypt-android` | 2.5.3 | TLS 1.2+（4.1 的系统栈只有 TLS 1.0） |
| `junit:junit` | 4.13.2 | **仅测试** |
| `com.google.code.gson:gson` | 2.10.1 | **仅测试**（读夹具） |

Okio 由 OkHttp 传递带入，不显式声明。**加任何依赖都要先在最终回复里说明理由并等派工者批准。**

不用 AndroidX（主流版本已不支持 API 16）、不用 Compose、不用协程（除非在设备上验证可用）。

工具链：AGP 8.5.0 / Kotlin 1.9.24 / Gradle 9.3.1（以 JDK 17 运行）/ Java 与 Kotlin 字节码目标 8 /
`compileSdk 36` / `minSdk 16` / `targetSdk 16` /
ABI 只打 `armeabi-v7a`。`targetSdk 16` 的理由：本分支只在这台机器上跑，写更高的值等于声明
"已适配那些版本的行为变更"，而 W0~W4 都无法验证。

## 设备纪律（**强制**）

本机同时连着负责人的主力测试机。**所有 adb 命令必须带 `-s 110024C5064474`**，绝不碰另一台。

```bash
export MSYS_NO_PATHCONV=1          # Git Bash 下给 adb 传设备路径必须，否则路径被改写
adb -s 110024C5064474 install -r walkman/app/build/outputs/apk/debug/app-debug.apk
adb -s 110024C5064474 shell am start -n com.takahashirinta.ncrust.walkman/.smoke.SmokeActivity --ez auto true
adb -s 110024C5064474 logcat -s NcrustWm
adb -s 110024C5064474 uninstall com.takahashirinta.ncrust.walkman
```

- **收尾必须卸载**，设备上不留任何东西（`TASKBOOK.md` §0.5）。
- 不 root、不刷写、不改系统设置。调试属性用完必须恢复。
- 4.1 的 `input swipe` **不接受时长参数**。
- 不跑任何使用真实登录 cookie 的命令，不读 `benchmark/cookie.secret`。匿名只读联网探针可以跑。

## 本阶段（W0）已知坑

1. **weapi 的 `params` 是"base64 文本再加密"**：两层 AES-CBC 之间，中间值是内层结果的
   **base64 字符串**（不是内层密文的原始字节）。喂原始字节能得到**长度对、内容错**的结果 ——
   服务端解不开，本地看不出问题。依据：`spec/tools/ncm-client.mjs:118` 与 Android
   `WeapiCrypto.aesCbcEncrypt`（返回 `String`）。夹具 `weapi.json` 逐字节锁住它。
2. **`BigInteger.TWO` 是 Java 9 的常量**，4.1 上 `NoSuchFieldError`。用 `BigInteger.valueOf(2)`。
3. **`GCMParameterSpec` 是 API 19 才有的类**：设备上只能用 `IvParameterSpec`（Conscrypt 认），
   JVM 上 SunJCE 只认 `GCMParameterSpec`。且只能用**反射**构造 —— 直接 `new` 会让 Dalvik 在
   类加载期解析一个不存在的类，那是 `NoClassDefFoundError`，catch 不到。见 `crypto/Aes.kt`。
4. **`AudioAttributes` 是 API 21 的类**：只能用 `MediaPlayer.setAudioStreamType(STREAM_MUSIC)`。
5. **主代码不能引 Gson**（白名单里 Gson 仅测试），**`org.json` 在 JVM 单测里是空桩** ——
   所以自带 `json/Json.kt`。数字保留原始文本：19 位歌曲 id 先过 `Double` 会丢精度。
6. **`java.util.Base64` 要 API 26**，`android.util.Base64` 在 JVM 单测里是空桩 —— 自带
   `crypto/Base64.kt`（`C` 用 base64url 不补 `=`，`S`/`R` 用标准 base64 补 `=`）。
7. **夹具测试找不到 `spec/` 必须报错，不能跳过**：跳过会让"没读到夹具"表现成绿色构建。
   `Fixtures` 从工作目录向上找仓库根。
8. **W0 刻意跳过 FLAC 门控**：本机无 MediaCodec `audio/flac` 解码器，但 `MediaPlayer` 走
   Stagefright 解析，**可能**能播 FLAC。取链时按 `supportsFlac = true` 把 FLAC 档留在阶梯里，
   W4 登录后实测再定。门控函数本身已由 `quality.json` 锁住。
9. **xeapi 的 `deviceId` 在 W0 只活在进程内**（进程重启就换一个）。协议要求每台设备一个、
   `nextUpdateTime` 之前不变 —— **持久化是 W1 的活**，别忘了。
10. **`targetSdk 16` + `android:usesCleartextTraffic`**：该属性 API 23 才存在，4.1 忽略它；
     写在那里是为了日后抬 targetSdk 时不至于音频 CDN 突然全哑（取链回的 URL 是 `http://`）。
11. **播放器生命周期必须同线程**：冒烟 Activity 用独立 HandlerThread 创建、`prepareAsync`、启动、
    轮询与释放 MediaPlayer。Activity 销毁只投递清理消息；异步取链结束后再次检查销毁标记，
    防止页面已退出却开始播放。准备过程有 30 s 超时，完整冒烟播放 20 s 后释放。
12. **X25519 不能放主线程**：API 16 真机上四次 BigInteger ladder 运算实测约 1.1–1.4 s。
    xeapi 每次请求需要两次运算；后续登录与收藏界面继续在后台执行，不可拿 JVM 耗时推算设备性能。
13. **lint 不可全局放行错误**：仅禁用不适用于本工程侧载发布的 `ExpiredTargetSdkVersion`，
    保留其它错误的阻断。`TrulyRandom` 为旧 Android 的通用提示；本机冒烟实测随机源 provider 为 Conscrypt。

## W0 验证状态（2026-10-15）

- 分支 `dsh/ncrust-walkman-w0`，工作区 `D:/arcdeck/wt/ncrust-walkman-w0`；尚未合入主分支。
- `testDebugUnitTest`、`assembleDebug`、`lintDebug` 均通过；22 个 JUnit 测试方法，0 失败、0 跳过。
  其中夹具测试遍历全部 54 条共享用例：eapi 4、weapi 2、xeapi 13、quality 23、entitlement 12。
- lint 报告 0 错误、9 个警告；没有 `NewApi` 错误。警告包括旧版依赖、ARM-only、协议要求的 ECB、
  未设置启动图标以及冒烟文本等，不代表产品 UI 或 W4 打包已经完成。
- APK 为 `0.1.0-w0`，3,158,674 字节，min/target SDK 16，仅 `armeabi-v7a`。
- NW-F885（`110024C5064474`）上安装、启动成功；Conscrypt 2.5.3 位于 provider 第 1 位。
  AES-GCM 往返（API 16 的 IvParameterSpec 分支）、纯 Kotlin X25519 双端协商均通过。
- 匿名歌曲 5257138：业务码 200，TLS 1.2，standard / 128001 bps MP3；系统 MediaPlayer
  异步准备约 1.4 s，播放 20 s，位置推进至 19995 ms，输出 `PASS: playback position advanced`。
  取链期间替换 Activity 的探针没有继续启动 MediaPlayer。
- 验证后已卸载本应用，`pm path` 确认无包；没有修改系统设置或调试属性。
- 登录态接口、线上 xeapi 业务请求、VIP/FLAC、媒体键、锁屏与无缝衔接尚待后续阶段实测。

## 与 `spec/` 的关系

协议、加密、权益判定、音质阶梯以 `spec/` 为准，测试**直接读** `spec/fixtures/**`，不复制夹具。
改协议先改 `spec/`，再改各端。**实现与夹具不一致时以夹具为准改实现**；确信夹具错了，
写进最终回复，不要改夹具。
