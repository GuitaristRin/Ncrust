import java.util.concurrent.TimeUnit

plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

// Macrobenchmark 模块：设备上跑自动化性能基线（冷启动 / 滚动帧率）。
//
// 生产式测量流程(推荐, 见 run_benchmark.sh):
//   1. ./gradlew :app:assembleRelease 装到设备(R8 优化, 与上架包一致)
//   2. 打开 app 扫码登录一次(登录态保留, 不再被卸载)
//   3. 正常使用几分钟, 让 ART profile 与 Coil 磁盘缓存热起来
//   4. adb install benchmark APK + adb shell am instrument 驱动测量
//      迭代间 app 数据不动, 测的是"真实使用状态下的冷启动/滚动"。
//
// 不用 :benchmark:connectedCheck 的原因: AGP 会用 debug 包重装+测后卸载,
// 得到的是"全新安装的 debug 空壳"数字, 与生产行为无关。
// connectedCheck 仍然可以用(干净设备 CI 基线), 但不要把它当生产数字。
//
// 结果输出在 benchmark/build/outputs/connected_android_test_additional_output/benchmark/ 下。

android {
    namespace = "com.takahashirinta.ncrust.benchmark"
    compileSdk = 36
    // 被测应用：com.android.test 模块必须显式指定
    targetProjectPath = ":app"
    // 测量版本: 默认 debug。要基准 release 时,先 `./gradlew :app:installRelease`
    // 再以 -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.suppressErrors=DEBUGGABLE
    // 运行 connectedCheck, 并卸载 debug 版(见 AGENTS.md 性能章节)。

    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }

    // Perfetto trace 二进制按架构各带一份(总 ~50MB), 设备只需要当前 ABI。
    // 只保留 arm64, 否则 benchmark APK 大到难以装进存储紧张的测试机。
    // (若要在 x86_64 模拟器上跑, 把排除项换一下即可。)
    packagingOptions {
        resources {
            excludes += listOf(
                "assets/trace_processor_shell_x86",
                "assets/trace_processor_shell_x86_64",
                "assets/trace_processor_shell_arm",
                "assets/tracebox_x86",
                "assets/tracebox_x86_64",
                "assets/tracebox_arm"
            )
        }
    }

    // 基准进程与 app 进程分离, 否则测量包含 instrumentation 自身开销,
    // 冷启动数值失真。benchmark-macro 1.4+ 未开启时直接判 ERROR。
    experimentalProperties["android.experimental.self-instrumenting"] = true
}

dependencies {
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    // 1.4.1+ 移除了 MacrobenchmarkRule 内部无条件授予 WRITE_EXTERNAL_STORAGE 的逻辑
    // (GrantPermissionRule -> pm grant 在 API 33+ 必失败, 导致所有迭代还没跑就挂)。
    implementation("androidx.benchmark:benchmark-macro-junit4:1.4.1")
}
