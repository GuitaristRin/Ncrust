package com.takahashirinta.ncrust.update

/** 安装包的校验结论。 */
enum class ApkCheck {
    /** 包名一致、versionCode 更高、签名摘要一致 → 可以交给系统安装器。 */
    OK,

    /** 下载到的不是本应用的包（包名对不上）。 */
    PACKAGE_MISMATCH,

    /** 安装包的 versionCode 不高于已安装版本：下载到的是旧包或被换过的包。 */
    VERSION_NOT_HIGHER,

    /** 签名摘要与已安装应用不一致（测试机装的是临时密钥签名的包时必然如此）。 */
    SIGNATURE_MISMATCH
}

/**
 * 一个 APK 的身份三元组：包名、versionCode、签名证书摘要。
 *
 * 纯数据，Android 侧由 [ApkArchive] 从 `PackageManager` 读出来，
 * 规则本身留在 [ApkVerification] 里以便单测。
 */
data class ApkIdentity(
    val packageName: String?,
    val versionCode: Long,
    val signatureDigests: List<String>
)

/**
 * 安装包与已安装应用的比对规则（**纯函数**）。
 *
 * 为什么要比对签名：签名不同的包系统直接拒装（`INSTALL_FAILED_UPDATE_INCOMPATIBLE`），
 * 与其让用户看到一句系统报错，不如提前说清「需要先卸载」——测试机装的是临时密钥
 * 签名的包，这条路径是常态而不是异常。
 *
 * 比对顺序：包名 → versionCode → 签名。包名不对说明下错了东西；版本不高于当前说明
 * 拿到的包根本不对（下载被截断、或班次里被换了旧包），这两种情况都轮不到签名说话。
 */
object ApkVerification {

    fun check(archive: ApkIdentity, installed: ApkIdentity): ApkCheck {
        if (archive.packageName == null ||
            installed.packageName == null ||
            archive.packageName != installed.packageName
        ) {
            return ApkCheck.PACKAGE_MISMATCH
        }
        if (archive.versionCode <= installed.versionCode) return ApkCheck.VERSION_NOT_HIGHER
        if (!sameSignatures(archive.signatureDigests, installed.signatureDigests)) {
            return ApkCheck.SIGNATURE_MISMATCH
        }
        return ApkCheck.OK
    }

    /**
     * 签名证书摘要集合是否一致。
     *
     * 用**集合**而不是列表：同一个应用可能被多把证书签名（签名轮换 / 多签名），
     * 系统读出来的顺序不保证稳定。任一侧为空视为不一致——读不到签名时绝不能放行。
     * 摘要统一小写后比较（不同 API 版本给出的写法大小写不一）。
     */
    fun sameSignatures(archiveDigests: List<String>, currentDigests: List<String>): Boolean {
        if (archiveDigests.isEmpty() || currentDigests.isEmpty()) return false
        val archive = archiveDigests.map { it.lowercase() }.toSet()
        val current = currentDigests.map { it.lowercase() }.toSet()
        return archive == current
    }
}

/**
 * 提示（底部导航圆点 / 用户页横幅）该不该出现。
 *
 * 规则：有更高版本，且该版本没被「稍后」过。用户点过「稍后」的版本不再提示，
 * 直到出现**更高**的版本——所以这里比的是版本，而不是「有没有点过稍后」。
 */
object UpdateNotifier {
    fun shouldNotify(available: UpdateInfo?, dismissedVersion: AppVersion?): Boolean {
        if (available == null) return false
        if (dismissedVersion == null) return true
        return available.version > dismissedVersion
    }
}
