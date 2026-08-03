pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
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
        google()
        mavenCentral()
    }
}

rootProject.name = "Ncrust"
include(":app")

// Kanesumi 通过组合构建接入。这样 Kanesumi 侧源码改动不需要 publish 就能被
// Ncrust 增量构建看到,迁移期间来回改两边 API 最省事。
// 显式 dependencySubstitution 让 Kanesumi 无需配 maven-publish 或设 group/version。
// 未来 Kanesumi 上 Maven Central 时,只需删掉整个 includeBuild 块,
// app/build.gradle.kts 里的坐标一字不改就能切。
includeBuild("../../projects/Kanesumi") {
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
