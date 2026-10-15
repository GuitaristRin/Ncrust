// 与主工程同版本的 AGP / Kotlin：工具链只有一套在机器上被验证过，换版本是白添变量。
plugins {
    id("com.android.application") version "8.5.0" apply false
    id("org.jetbrains.kotlin.android") version "1.9.24" apply false
}
