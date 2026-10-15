// Ncrust for Walkman —— 独立 Gradle 构建。
//
// 刻意不加入根 settings.gradle.kts：Walkman 分支的 minSdk 是 16，主工程是 24，
// 两者的 AGP/依赖约束不同；对主构建不可见（与 windows/ 同一策略），互不牵制。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NcrustWalkman"
include(":app")
