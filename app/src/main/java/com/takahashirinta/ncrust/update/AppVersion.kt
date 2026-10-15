package com.takahashirinta.ncrust.update

/**
 * 可比较的版本号（最多 4 段，缺段按 0 补）。
 *
 * 与 Windows 侧 `Ncrust.Core.Update.AppVersion` 同一把尺子：发布资产名里的版本
 * （`1.4.0`）与 `versionName` / `versionCode` 派生的版本（可能是 `1.4.0.1`）要能比大小。
 * 空串、非法段、超过 4 段一律解析失败，绝不抛到调用方——GitHub 上什么资产名都有。
 *
 * 为什么不用 `PackageInfo.versionCode` 比：班次里的资产名带的是 `versionName`（三段），
 * 而 `versionCode` 只在安装校验时用（见 [ApkVerification]）。两者不可混用。
 */
data class AppVersion(
    val major: Int,
    val minor: Int = 0,
    val build: Int = 0,
    val revision: Int = 0
) : Comparable<AppVersion> {

    init {
        require(major >= 0 && minor >= 0 && build >= 0 && revision >= 0) { "版本段不能为负" }
    }

    override fun compareTo(other: AppVersion): Int {
        if (major != other.major) return major.compareTo(other.major)
        if (minor != other.minor) return minor.compareTo(other.minor)
        if (build != other.build) return build.compareTo(other.build)
        return revision.compareTo(other.revision)
    }

    /** 显示用三段式，与设置页 / 更新页一致，不显示第四段开发构建号。 */
    override fun toString(): String = "$major.$minor.$build"

    /**
     * 落盘用四段式，保证 [parseOrNull] 能原样读回来。
     *
     * 不能用 [toString]：它只写三段，`1.4.0.1` 存下去再读回来会变成 `1.4.0.0`，
     * 「稍后」过的版本就会因为比较结果偏小而重新冒出来（圆点去不掉）。
     */
    fun storageString(): String = "$major.$minor.$build.$revision"

    companion object {
        /**
         * 解析 `1` / `1.0` / `1.0.1` / `1.0.1.0`（允许前缀 `v`）。
         *
         * 逐段只认纯数字：`+1`、`-1`、` 1`、`1a` 全部判非法（Windows 用
         * `int.TryParse(NumberStyles.None)`，行为一致——资产名里混进别的东西时
         * 必须判失败，不能猜）。
         */
        fun parseOrNull(text: String?): AppVersion? {
            if (text.isNullOrBlank()) return null
            var trimmed = text.trim()
            if (trimmed.startsWith("v") || trimmed.startsWith("V")) trimmed = trimmed.substring(1)
            if (trimmed.isEmpty()) return null
            val parts = trimmed.split('.')
            if (parts.size > 4) return null
            val numbers = IntArray(4)
            for ((index, part) in parts.withIndex()) {
                if (part.isEmpty() || part.any { it !in '0'..'9' }) return null
                numbers[index] = part.toIntOrNull() ?: return null
            }
            return AppVersion(numbers[0], numbers[1], numbers[2], numbers[3])
        }
    }
}
