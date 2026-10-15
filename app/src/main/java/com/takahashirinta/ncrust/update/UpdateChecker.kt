package com.takahashirinta.ncrust.update

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser

/** 一次可用更新：目标版本、要下载的 APK，以及更新页要展示的元数据。 */
data class UpdateInfo(
    val version: AppVersion,
    val assetName: String,
    val assetUrl: String,
    val assetSize: Long,
    val releaseTag: String?,
    val releasePageUrl: String?,
    val publishedAt: String?,
    val body: String?
)

/**
 * 从 GitHub Releases API 的 JSON 里找出适用于 Android 的更新。
 * **纯函数**（Gson 树 API，不反射绑定 model），网络由 [UpdateService] 负责。
 *
 * 规则与 Windows `Ncrust.Core.Update.UpdateChecker` 一致（跨端夹具
 * `spec/fixtures/update/releases.json` 锁住）：
 * 资产命名 `ncrust-[平台]-[架构]-[版本].[扩展名]`，逐个班次扫资产、解析版本，
 * 取**严格大于当前版本**的最高者；不依赖 GitHub 的「Latest」徽章——发布走滚动班次
 * （`docs/RELEASES.md`），徽章只指向最近一班。
 *
 * 用 Gson 的树 API 而不是 `fromJson(model)`：R8 会剪掉未 keep 的类的字段注解，
 * 反射绑定在 release 包上会退化（见根 AGENTS.md「Gson 反射 vs R8」）。树 API 没有反射。
 */
object UpdateChecker {

    const val DEFAULT_PLATFORM = "android"

    /** 通用包（universal）：不区分 ABI，与 `docs/RELEASES.md` 的资产名一致。 */
    const val DEFAULT_ARCH = "universal"

    /** 可安装的扩展名：别把 `…-1.4.0.asc` 当成 APK 下载下来。 */
    private const val APK_EXTENSION = "apk"

    /** 解析失败 / 无更新 / 不是数组时返回 null；绝不抛。 */
    fun find(
        releasesJson: String?,
        current: AppVersion?,
        platform: String = DEFAULT_PLATFORM,
        arch: String = DEFAULT_ARCH
    ): UpdateInfo? {
        if (releasesJson.isNullOrBlank() || current == null) return null

        val root = runCatching { JsonParser.parseString(releasesJson) }.getOrNull() ?: return null
        if (!root.isJsonArray) return null

        val prefix = "ncrust-$platform-$arch-"
        var best: UpdateInfo? = null
        for (element in root.asJsonArray) {
            if (!element.isJsonObject) continue
            val release = element.asJsonObject
            // 草稿与预发布都不可见：草稿是发布者还没发出来的，预发布是拿来试的。
            if (release.boolOrFalse("draft")) continue
            if (release.boolOrFalse("prerelease")) continue

            val hit = bestAssetOf(release, prefix) ?: continue
            if (hit.version <= current) continue
            if (best == null || hit.version > best.version) {
                best = UpdateInfo(
                    version = hit.version,
                    assetName = hit.name,
                    assetUrl = hit.url,
                    assetSize = hit.size,
                    releaseTag = release.stringOrNull("tag_name"),
                    releasePageUrl = release.stringOrNull("html_url"),
                    publishedAt = release.stringOrNull("published_at"),
                    body = release.stringOrNull("body")
                )
            }
        }
        return best
    }

    /** 一个 release 里版本最高的合规资产；没有则 null。 */
    private fun bestAssetOf(release: JsonObject, prefix: String): AssetHit? {
        val assets = release.arrayOrNull("assets") ?: return null
        var hit: AssetHit? = null
        for (element in assets) {
            if (!element.isJsonObject) continue
            val asset = element.asJsonObject
            val name = asset.stringOrNull("name") ?: continue
            if (!name.startsWith(prefix)) continue

            // rest = "<版本>.<扩展名>"；扩展名取**最后一个点**之后，版本段里不允许再有点
            // （`…-1.4.0.apk.sha256` 的版本段是 `1.4.0.apk`，解析失败即忽略）。
            val rest = name.substring(prefix.length)
            val dot = rest.lastIndexOf('.')
            if (dot <= 0) continue
            if (!rest.substring(dot + 1).equals(APK_EXTENSION, ignoreCase = true)) continue
            val version = AppVersion.parseOrNull(rest.substring(0, dot)) ?: continue

            val url = asset.stringOrNull("browser_download_url") ?: continue
            if (hit == null || version > hit.version) {
                hit = AssetHit(version, name, url, asset.longOrZero("size"))
            }
        }
        return hit
    }

    private data class AssetHit(
        val version: AppVersion,
        val name: String,
        val url: String,
        val size: Long
    )

    private fun JsonObject.arrayOrNull(name: String): JsonArray? {
        val element = get(name) ?: return null
        return if (element.isJsonArray) element.asJsonArray else null
    }

    private fun JsonObject.stringOrNull(name: String): String? {
        val element = get(name) ?: return null
        return if (element.isJsonPrimitive && element.asJsonPrimitive.isString) element.asString else null
    }

    private fun JsonObject.boolOrFalse(name: String): Boolean {
        val element = get(name) ?: return false
        return element.isJsonPrimitive && runCatching { element.asBoolean }.getOrDefault(false)
    }

    private fun JsonObject.longOrZero(name: String): Long {
        val element = get(name) ?: return 0L
        return if (element.isJsonPrimitive) runCatching { element.asLong }.getOrDefault(0L) else 0L
    }
}
