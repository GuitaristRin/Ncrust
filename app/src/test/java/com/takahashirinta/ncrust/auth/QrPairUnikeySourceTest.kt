package com.takahashirinta.ncrust.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 局域网交接（`QrPair`）在「unikey 来源改走 eapi + PC 身份」之后仍然成立。
 *
 * 交接协议本身没动：它只认二维码内容里的 `codekey`（unikey）与最终那份 cookie。
 * 这里锁住的就是这条连接点 —— 新的 PC 形态二维码（不带 chainId）依旧能解析出 unikey。
 */
class QrPairUnikeySourceTest {

    @Test
    fun `pc form qr content yields the unikey`() {
        val content = QrLoginProtocol.qrContent("UNIKEY123")

        assertEquals("UNIKEY123", QrPair.unikeyFromQrContent(content))
    }

    @Test
    fun `handoff payloads carry that unikey`() {
        val unikey = QrPair.unikeyFromQrContent(QrLoginProtocol.qrContent("UNIKEY123"))!!

        assertEquals(unikey, QrPair.parseRequest(QrPair.requestPayload(unikey)))
        assertEquals(unikey to 47821, QrPair.parseResponse(QrPair.responsePayload(unikey, 47821)))
        // 非本协议的广播不能被当成本次交接。
        assertNull(QrPair.parseRequest("NCRUSTPAIR2?UNIKEY123"))
    }

    @Test
    fun `cookie survives the handoff encryption`() {
        val unikey = "UNIKEY123"
        val cookie = "MUSIC_U=abc; __csrf=xyz"

        assertEquals(cookie, QrPair.decrypt(unikey, QrPair.encrypt(unikey, cookie)))
        // 换一把 unikey（= 换密钥）就解不开，这正是交接能只靠屏幕上的 unikey 加密的原因。
        assertNull(QrPair.decrypt("OTHERKEY", QrPair.encrypt(unikey, cookie)))
    }
}
