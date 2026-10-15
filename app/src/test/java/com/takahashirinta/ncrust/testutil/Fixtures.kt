package com.takahashirinta.ncrust.testutil

import java.io.File

/**
 * 跨端夹具（`spec/fixtures/` 下的各 `.json`）的定位辅助。
 *
 * Gradle 单测的工作目录是模块目录（`app/`）而不是仓库根，不同 AGP 版本 / IDE 下还可能不同，
 * 所以从 `user.dir` 起向上找到第一个存在该相对路径的目录（即仓库根）。找不到就直接失败，
 * 绝不静默跳过——夹具读不到时测试必须红，否则等于没有对照。
 */
object Fixtures {
    fun file(relativePath: String): File {
        val start = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var dir: File? = start
        while (dir != null) {
            val candidate = File(dir, relativePath)
            if (candidate.isFile) return candidate
            dir = dir.parentFile
        }
        throw AssertionError("fixture not found: $relativePath (cwd=$start)")
    }

    fun readText(relativePath: String): String = file(relativePath).readText()
}
