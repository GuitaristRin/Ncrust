plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.takahashirinta.ncrust.walkman"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.takahashirinta.ncrust.walkman"
        minSdk = 16
        // targetSdk 取 16 = 设备本身（NW-F885，Android 4.1.1）。理由：本分支只在这台机器上跑，
        // 写更高的值等于声明「已适配那些版本的行为变更」，而 W0~W4 都没法验证；
        // 同时 targetSdk < 31 让 AGP 不再强制 android:exported 等新平台要求（清单里仍写了，
        // 免得日后抬 targetSdk 时踩）。
        targetSdk = 16
        versionCode = 1
        versionName = "0.1.0-w0"

        // 只打 armeabi-v7a：设备是 32 位 ARMv7。Conscrypt 的 .so 因此只带一份。
        ndk {
            abiFilters += "armeabi-v7a"
        }
    }

    buildTypes {
        // W0 不做混淆：R8 会重排/删除类，出问题时先排除掉这个变量。W4 打包时再开。
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        // Java 8 是 API 16 + D8 的安全下限；不用 Java 8 的库 API（没有 core library desugaring），
        // 只用语法糖（lambda / default method），那些由 D8 脱糖。
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    // 不开 returnDefaultValues：单测里误用 android.jar 空桩时要立刻炸，
    // 而不是拿到 null/0 继续跑出一个假绿的用例。
    testOptions {
        unitTests.all {
            it.testLogging {
                events("failed")
                exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
            }
        }
    }

    lint {
        // 专供 API 16 设备侧载，不上 Google Play；其它错误仍然阻断 lint。
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    // TASKBOOK §1 白名单。Okio 由 OkHttp 3.12.13 传递带入（1.15.0），不显式声明。
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
    implementation("com.squareup.okhttp3:okhttp:3.12.13")
    // 2.5.3：spike 在 NW-F885 上实测 TLS 1.2/1.3 全通的那个版本（docs/f885-spike-report.md §2）。
    implementation("org.conscrypt:conscrypt-android:2.5.3")

    // 仅测试：夹具是 JSON，解析用 Gson；判定逻辑本身不依赖 Gson。
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.10.1")
}
