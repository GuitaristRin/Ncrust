package com.takahashirinta.ncrust.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 安装包与已安装应用的比对规则（包名 → versionCode → 签名），以及「该不该提示」的规则。
 *
 * 这两条都是纯函数，之所以单独测：签名不一致（测试机装的是临时密钥签名的包）时
 * 绝不能调起安装器，否则用户只会看到系统的 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`；
 * 「稍后」的语义也不是「记住点过稍后」，而是「比被推迟的版本更高才再提示」。
 */
class ApkVerificationTest {

    private val installed = ApkIdentity(
        packageName = "com.takahashirinta.ncrust",
        versionCode = 7L,
        signatureDigests = listOf("aa11", "bb22")
    )

    private fun archive(
        packageName: String? = installed.packageName,
        versionCode: Long = 8L,
        digests: List<String> = installed.signatureDigests
    ) = ApkIdentity(packageName, versionCode, digests)

    @Test
    fun `accepts a higher version with the same signature`() {
        assertEquals(ApkCheck.OK, ApkVerification.check(archive(), installed))
    }

    @Test
    fun `rejects a different package`() {
        assertEquals(
            ApkCheck.PACKAGE_MISMATCH,
            ApkVerification.check(archive(packageName = "com.example.other"), installed)
        )
        assertEquals(ApkCheck.PACKAGE_MISMATCH, ApkVerification.check(archive(packageName = null), installed))
    }

    @Test
    fun `rejects an equal or lower version code`() {
        assertEquals(ApkCheck.VERSION_NOT_HIGHER, ApkVerification.check(archive(versionCode = 7L), installed))
        assertEquals(ApkCheck.VERSION_NOT_HIGHER, ApkVerification.check(archive(versionCode = 6L), installed))
    }

    @Test
    fun `rejects a different signature`() {
        assertEquals(
            ApkCheck.SIGNATURE_MISMATCH,
            ApkVerification.check(archive(digests = listOf("cc33")), installed)
        )
        // 读不到签名（空列表）绝不放行。
        assertEquals(
            ApkCheck.SIGNATURE_MISMATCH,
            ApkVerification.check(archive(digests = emptyList()), installed)
        )
        assertEquals(
            ApkCheck.SIGNATURE_MISMATCH,
            ApkVerification.check(archive(), installed.copy(signatureDigests = emptyList()))
        )
    }

    @Test
    fun `version is checked before signature`() {
        // 版本不高于当前说明包本身就不对，这时签名一不一样都轮不到安装。
        assertEquals(
            ApkCheck.VERSION_NOT_HIGHER,
            ApkVerification.check(archive(versionCode = 7L, digests = listOf("cc33")), installed)
        )
    }

    @Test
    fun `signature comparison is order and case insensitive`() {
        assertTrue(ApkVerification.sameSignatures(listOf("AA11", "bb22"), listOf("bb22", "aa11")))
        assertFalse(ApkVerification.sameSignatures(listOf("aa11"), listOf("aa11", "bb22")))
    }

    @Test
    fun `notification follows the dismissed version not the dismissal itself`() {
        val available = UpdateInfo(
            version = AppVersion(1, 4, 0),
            assetName = "ncrust-android-universal-1.4.0.apk",
            assetUrl = "https://example.invalid/1.4.0.apk",
            assetSize = 1L,
            releaseTag = null,
            releasePageUrl = null,
            publishedAt = null,
            body = null
        )

        assertTrue(UpdateNotifier.shouldNotify(available, dismissedVersion = null))
        assertTrue(UpdateNotifier.shouldNotify(available, dismissedVersion = AppVersion(1, 3, 9)))
        assertFalse(UpdateNotifier.shouldNotify(available, dismissedVersion = AppVersion(1, 4, 0)))
        assertFalse(UpdateNotifier.shouldNotify(available, dismissedVersion = AppVersion(1, 5, 0)))
        assertFalse(UpdateNotifier.shouldNotify(null, dismissedVersion = null))
    }
}
