package com.takahashirinta.ncrust.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * 从 `PackageManager` 读 APK 身份（包名 / versionCode / 签名证书摘要）。
 *
 * 这里只负责把 Android API 的差异吃掉，规则在纯函数 [ApkVerification] 里：
 *  - API 28+：`GET_SIGNING_CERTIFICATES` + `signingInfo`，版本号用 `longVersionCode`；
 *  - API 24~27：`GET_SIGNATURES` + `signatures`，`versionCode` 是 Int。
 *
 * minSdk 是 24，两条路径都必须真的能走，不能只写新 API 再补个 `@Suppress`。
 */
object ApkArchive {

    private const val FLAGS_LEGACY = PackageManager.GET_SIGNATURES
    private const val FLAGS_SIGNING = 0x08000000 // PackageManager.GET_SIGNING_CERTIFICATES (API 28)

    /** 已安装的本应用。读不到返回 null（调用方按「校验不了」处理，不放行）。 */
    fun readInstalled(context: Context): ApkIdentity? =
        read(context) { flags -> context.packageManager.getPackageInfo(context.packageName, flags) }

    /** 磁盘上的 APK 文件。文件不是合法 APK / 读不到时返回 null。 */
    fun readArchive(context: Context, file: File): ApkIdentity? {
        if (!file.isFile) return null
        return read(context) { flags ->
            @Suppress("DEPRECATION")
            context.packageManager.getPackageArchiveInfo(file.absolutePath, flags)
        }
    }

    private inline fun read(context: Context, load: (Int) -> PackageInfo?): ApkIdentity? {
        val info = runCatching { load(if (Build.VERSION.SDK_INT >= 28) FLAGS_SIGNING else FLAGS_LEGACY) }
            .getOrNull() ?: return null
        return ApkIdentity(
            packageName = info.packageName,
            versionCode = versionCodeOf(info),
            signatureDigests = signatureDigestsOf(info)
        )
    }

    private fun versionCodeOf(info: PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) {
            info.longVersionCode
        } else {
            @Suppress("DEPRECATION")
            info.versionCode.toLong()
        }

    /**
     * 证书摘要（SHA-256，小写十六进制）。
     *
     * API 28+ 优先 `apkContentsSigners`，签名轮换过的包退回 `signingCertificateHistory`——
     * 已安装应用与下载到的包走同一个函数，两边取到的口径才会一致。
     */
    private fun signatureDigestsOf(info: PackageInfo): List<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) {
            val signing = info.signingInfo ?: return emptyList()
            (if (signing.hasMultipleSigners()) signing.apkContentsSigners
            else signing.signingCertificateHistory)?.toList() ?: return emptyList()
        } else {
            @Suppress("DEPRECATION")
            info.signatures?.toList() ?: return emptyList()
        }
        val digest = MessageDigest.getInstance("SHA-256")
        return signatures.map { signature ->
            digest.reset()
            digest.digest(signature.toByteArray()).joinToString("") { "%02x".format(it) }
        }
    }
}
