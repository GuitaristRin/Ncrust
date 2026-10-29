package com.takahashirinta.ncrust.network

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class XeapiWriteSubmissionTest {
    @Test
    fun `empty or whitespace token prevents any business request`() {
        for (token in listOf("", " ", "\n")) {
            var requests = 0
            try {
                RetrofitClient.xeapiPostOnce(emptyList(), true, { token }, { _, _ ->
                    requests++
                    "{\"code\":200}".toByteArray()
                }, { fail("no response to invalidate") })
                fail("missing token must remain pending")
            } catch (_: IOException) { }
            assertEquals(0, requests)
        }
    }

    @Test
    fun `each write fetches fresh token and uses exactly that token in body and header`() {
        var fetches = 0
        var requests = 0
        repeat(2) {
            val text = RetrofitClient.xeapiPostOnce(listOf("checkToken" to "stale", "trackId" to "42"),
                true, { "fresh-${++fetches}" }, { pairs, headerToken ->
                    requests++
                    assertEquals("fresh-$requests", headerToken)
                    assertEquals(listOf("fresh-$requests"), pairs.filter { it.first == "checkToken" }.map { it.second })
                    assertEquals("42", pairs.toMap()["trackId"])
                    "{\"code\":200}".toByteArray()
                }, { fail("valid response") })
            assertEquals("{\"code\":200}", text)
        }
        assertEquals(2, fetches)
        assertEquals(2, requests)
    }

    @Test
    fun `unreadable response invalidates key without replaying a possibly accepted write`() {
        var requests = 0
        var invalidations = 0
        try {
            RetrofitClient.xeapiPostOnce(emptyList(), true, { "fresh" }, { _, _ ->
                requests++
                byteArrayOf(1, 2, 3)
            }, { invalidations++ })
            fail("unreadable response must remain pending")
        } catch (_: IOException) { }
        assertEquals(1, requests)
        assertEquals(1, invalidations)
    }

    @Test
    fun `transport exception after submission is not retried`() {
        var requests = 0
        try {
            RetrofitClient.xeapiPostOnce(emptyList(), true, { "fresh" }, { _, _ ->
                requests++
                throw IOException("response lost")
            }, { fail("transport failure is not a key decode failure") })
            fail("response loss must remain pending")
        } catch (_: IOException) { }
        assertEquals(1, requests)
    }
}
