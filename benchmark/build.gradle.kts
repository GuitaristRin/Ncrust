plugins {
    id("com.android.test")
    id("org.jetbrains.kotlin.android")
}

// Macrobenchmark 模块：设备上跑自动化性能基线（冷启动 / 滚动帧率）。
// 运行方式（手机连 adb 且已开 USB 调试）：
//   ./gradlew :benchmark:connectedCheck
// 建议先把系统动画关掉（Macrobenchmark 也会警告）：
//   adb shell settings put global window_animation_scale 0
//   adb shell settings put global transition_animation_scale 0
//   adb shell settings put global animator_duration_scale 0
// 结果输出在 benchmark/build/outputs/connected_android_test_additional_output/benchmark/ 下。

android {
    namespace = "com.takahashirinta.ncrust.benchmark"
    compileSdk = 36
    // 被测应用：com.android.test 模块必须显式指定
    targetProjectPath = ":app"

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
}

dependencies {
    implementation("androidx.test.ext:junit:1.1.5")
    implementation("androidx.test:runner:1.5.2")
    implementation("androidx.test.uiautomator:uiautomator:2.3.0")
    implementation("androidx.benchmark:benchmark-macro-junit4:1.3.1")
}
