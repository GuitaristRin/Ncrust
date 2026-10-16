package com.takahashirinta.ncrust.network

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 客户端身份 cookie 的合并规则：字段顺序、覆盖语义、`deviceId` 例外。
 *
 * 身份 cookie 是写操作不被 403、取链不被静默封顶的前提；这里的断言逐字节锁住拼法。
 */
class ClientIdentityTest {

    private val deviceId = "0123456789abcdef0123456789abcdef"

    @Test
    fun `appends identity fields in order when absent`() {
        assertEquals(
            "MUSIC_U=u; __csrf=c; os=pc; appver=${ClientIdentity.APPVER}; " +
                "osver=${ClientIdentity.OSVER}; deviceId=$deviceId",
            ClientIdentity.withIdentity("MUSIC_U=u; __csrf=c", deviceId)
        )
    }

    @Test
    fun `overrides os appver osver but keeps their original position`() {
        // appver 为空值：旧实现「有同名键就跳过」会让身份永久缺失，这里必须覆盖。
        assertEquals(
            "MUSIC_U=u; os=pc; appver=${ClientIdentity.APPVER}; __csrf=c; " +
                "osver=${ClientIdentity.OSVER}; deviceId=$deviceId",
            ClientIdentity.withIdentity("MUSIC_U=u; os=android; appver=; __csrf=c; osver=x", deviceId)
        )
    }

    @Test
    fun `keeps an existing device id untouched`() {
        assertEquals(
            "MUSIC_U=u; deviceId=orig; os=pc; appver=${ClientIdentity.APPVER}; osver=${ClientIdentity.OSVER}",
            ClientIdentity.withIdentity("MUSIC_U=u; deviceId=orig", deviceId)
        )
    }

    @Test
    fun `null cookie yields identity fields only`() {
        assertEquals(
            "os=pc; appver=${ClientIdentity.APPVER}; osver=${ClientIdentity.OSVER}; deviceId=$deviceId",
            ClientIdentity.withIdentity(null, deviceId)
        )
    }
}
