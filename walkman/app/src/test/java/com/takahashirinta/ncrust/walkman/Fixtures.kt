package com.takahashirinta.ncrust.walkman

import com.google.gson.JsonArray
import com.google.gson.JsonParser
import java.io.File

/**
 * 夹具定位。
 *
 * 单测的工作目录是模块目录（`walkman/app`），而夹具在仓库根的 `spec/fixtures/` —— 中间隔着
 * 两层。**向上找**而不是写死相对路径：Gradle 换版本、IDE 里直接跑、或者从仓库根用
 * `-p walkman` 跑，工作目录都可能不同，写死 `../../spec` 会在其中一种情况下静默读到别处。
 *
 * 找不到就**抛异常**，绝不 `Assume.assumeTrue` 跳过：跳过会让"夹具没读到"表现成绿色的构建，
 * 而这套测试的全部价值就是逐字节比对夹具。宁可红。
 */
object Fixtures {

    private const val MARKER = "spec/fixtures/crypto/eapi.json"

    val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, MARKER).isFile) return@lazy dir
            dir = dir.parentFile
        }
        throw IllegalStateException(
            "找不到仓库根（从 ${File("").absolutePath} 向上没有 $MARKER）。" +
                "夹具测试必须在仓库内的某个子目录下运行。"
        )
    }

    fun readText(relativePath: String): String =
        File(repoRoot, "spec/fixtures/$relativePath").readText(Charsets.UTF_8)

    fun readArray(relativePath: String): JsonArray =
        JsonParser.parseString(readText(relativePath)).asJsonArray.also {
            println("spec/fixtures/$relativePath: ${it.size()} cases")
        }
}
