pluginManagement {
    repositories {
        // dl.google.com 在本机网络时断时续, 阿里云镜像兜底(同 content 过滤范围)
        maven("https://maven.aliyun.com/repository/google") {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        maven("https://maven.aliyun.com/repository/central")
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // dl.google.com 不稳时走阿里云镜像(google 仓 + central 的完整代理)
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/central")
        google()
        mavenCentral()
    }
}

rootProject.name = "Ncrust"
include(":app")
include(":benchmark")

// Kanesumi-sec-a 通过组合构建接入。这样 Kanesumi 侧源码改动不需要 publish 就能被
// Ncrust 增量构建看到,迁移期间来回改两边 API 最省事。
// 显式 dependencySubstitution 让 Kanesumi 无需配 maven-publish 或设 group/version。
// 未来 Kanesumi 上 Maven Central 时,只需删掉整个 includeBuild 块,
// app/build.gradle.kts 里的坐标一字不改就能切。
includeBuild("../Kanesumi-sec-a") {
    dependencySubstitution {
        substitute(module("io.github.takahashirinta:kanesumi-core"))
            .using(project(":kanesumi-core"))
        substitute(module("io.github.takahashirinta:kanesumi-anim"))
            .using(project(":kanesumi-anim"))
        substitute(module("io.github.takahashirinta:kanesumi-controls"))
            .using(project(":kanesumi-controls"))
        substitute(module("io.github.takahashirinta:kanesumi-structure"))
            .using(project(":kanesumi-structure"))
    }
}
