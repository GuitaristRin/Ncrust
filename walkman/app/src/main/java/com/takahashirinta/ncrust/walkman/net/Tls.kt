package com.takahashirinta.ncrust.walkman.net

import android.util.Log
import org.conscrypt.Conscrypt
import java.security.Provider
import java.security.Security

/**
 * TLS 栈。设备是 Android 4.1（API 16），系统默认只启用 SSLv3/TLSv1 —— 网易的 CDN 与接口
 * 随时可能下线 TLS 1.0，所以必须自带 TLS 1.2 实现。Conscrypt 是 spike 实测跑通的那一个
 * （`docs/f885-spike-report.md` §2：OkHttp 3.12.13 + Conscrypt 2.5.3 协商到 TLS 1.2/1.3）。
 *
 * 为什么用 `insertProviderAt(1)` 而不是给 OkHttp 单独配 SSLSocketFactory：OkHttp 3.12 内部
 * 走 `SSLContext.getInstance("TLS")`，按 provider 顺序解析；插到第 1 位之后所有 JCA 取用方
 * （OkHttp、以及 W2 里可能用到的其它 HTTPS 调用）都自动拿到 Conscrypt，不必逐处传工厂。
 */
object Tls {

    /** logcat 统一 tag：冒烟时 `adb logcat -s NcrustWm` 一条命令能看全。 */
    const val TAG = "NcrustWm"

    /** 插入结果，供冒烟界面显示。null = 还没试过。 */
    @Volatile
    var installResult: String? = null
        private set

    /** 真的用上 Conscrypt 了吗（`isAvailable()` 为假时 provider 在，但握手会失败）。 */
    @Volatile
    var available: Boolean = false
        private set

    fun installConscrypt(): Boolean {
        if (installResult != null) return available
        return synchronized(this) {
            if (installResult != null) return@synchronized available
            try {
                available = Conscrypt.isAvailable()
                val provider: Provider = Conscrypt.newProvider()
                // 重复插入时 insertProviderAt 返回 -1；本进程只会走一次，出现 -1 说明有人先插过。
                val position = Security.insertProviderAt(provider, 1)
                installResult = buildString {
                    append("Conscrypt ").append(conscryptVersion())
                    append(" available=").append(available)
                    append(" provider=").append(provider.name)
                    append(" position=").append(position)
                }
                Log.i(TAG, "TLS: $installResult")
                if (!available) {
                    // 原生库没加载上（ABI 不匹配时就是这样）：provider 在但握手必失败，
                    // 早点喊出来，免得后面把 TLS 错误当成网络问题查。
                    Log.e(TAG, "TLS: Conscrypt native library NOT available - HTTPS will fail")
                }
            } catch (t: Throwable) {
                available = false
                installResult = "install failed: ${t.javaClass.simpleName}: ${t.message}"
                Log.e(TAG, "TLS: Conscrypt install failed", t)
            }
            available
        }
    }

    private fun conscryptVersion(): String = Conscrypt.version().let {
        "${it.major()}.${it.minor()}.${it.patch()}"
    }
}
