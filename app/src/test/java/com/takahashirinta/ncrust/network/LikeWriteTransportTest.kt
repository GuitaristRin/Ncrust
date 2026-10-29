package com.takahashirinta.ncrust.network

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class LikeWriteTransportTest {
    @Test
    fun `both directions use one v1 request with official fields and token injection`() {
        for (desired in listOf(true, false)) {
            var calls = 0
            val code = PlaylistApi.writeLikeOnce(2754388325L, desired) { uri, pairs, antiCheat ->
                calls++
                assertEquals("/api/v1/radio/like", uri)
                assertTrue(antiCheat)
                assertEquals(mapOf(
                    "trackId" to "2754388325", "time" to "3", "alg" to "itembased",
                    "like" to desired.toString(), "rqRefer" to "", "songRqRefer" to "",
                    "mode" to "", "subMode" to "", "source" to "userfm"
                ), pairs.toMap())
                "{\"code\":502}"
            }
            assertTrue(PlaylistApi.isLikeWriteAccepted(code))
            assertEquals(1, calls)
        }
    }

    @Test
    fun `risk errors malformed responses and network failures never submit another request`() {
        for (response in listOf("{\"code\":524}", "{\"code\":-460}", "broken", "network")) {
            var calls = 0
            val code = PlaylistApi.writeLikeOnce(42, true) { uri, _, _ ->
                calls++
                assertEquals("/api/v1/radio/like", uri)
                if (response == "network") throw IOException("offline")
                response
            }
            assertFalse(PlaylistApi.isLikeWriteAccepted(code))
            assertEquals(1, calls)
        }
    }

    @Test(expected = CancellationException::class)
    fun `cancellation is propagated without falling back`() {
        PlaylistApi.writeLikeOnce(42, true) { _, _, _ -> throw CancellationException("cancel") }
    }
}
