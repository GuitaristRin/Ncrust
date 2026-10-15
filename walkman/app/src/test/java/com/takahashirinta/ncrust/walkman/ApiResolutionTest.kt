package com.takahashirinta.ncrust.walkman

import com.takahashirinta.ncrust.walkman.api.Entitlement
import com.takahashirinta.ncrust.walkman.api.NcmApi
import com.takahashirinta.ncrust.walkman.api.Privilege
import com.takahashirinta.ncrust.walkman.json.Json
import com.takahashirinta.ncrust.walkman.net.Requests
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiResolutionTest {
    private val id = 5257138L

    private fun response(text: String, httpCode: Int = 200) = Requests.Raw(
        httpCode, text.toByteArray(Charsets.UTF_8), "TLSv1.2", null
    )

    @Test
    fun fallsBackInOrderAndReportsActualQuality() {
        val calls = ArrayList<String>()
        val result = NcmApi.resolveSongUrl(id, "hires", null, false) { level ->
            calls += level
            if (level == "hires") response("""{"code":200,"data":[{"id":$id,"code":404,"url":null}]}""")
            else response("""{"code":200,"data":[{"id":$id,"code":200,"url":"http://audio.example/song.mp3","level":"exhigh"}]}""")
        }
        // MediaPlayer 的 FLAC 能力尚待实测，不能套用主应用的 MediaCodec 门控。
        assertEquals(listOf("hires", "lossless"), calls)
        assertEquals(calls, result.triedLevels)
        assertEquals("exhigh", result.resolvedLevel)
        val verdict = result.verdict!!
        assertEquals(Entitlement.Kind.FULL, verdict.kind)
        assertTrue(verdict.capped)
        assertEquals(Entitlement.Reason.LOGIN, verdict.cappedReason)
    }

    @Test
    fun returnsTrialWithoutTreatingItAsFullPlayback() {
        val result = NcmApi.resolveSongUrl(id, "lossless", null, false) {
            response("""{"code":200,"data":[{"id":$id,"code":200,"url":"http://audio.example/trial.mp3","level":"standard","fee":1,"freeTrialInfo":{"start":0,"end":45}}]}""")
        }
        assertEquals(listOf("lossless"), result.triedLevels)
        val verdict = result.verdict!!
        assertEquals(Entitlement.Kind.TRIAL, verdict.kind)
        assertEquals(45, verdict.trialSeconds)
    }

    @Test
    fun exhaustedLadderRetainsCopyrightReason() {
        val result = NcmApi.resolveSongUrl(id, "exhigh", Privilege(id, -200, 0, 0), true) {
            response("""{"code":200,"data":[{"id":$id,"code":404,"url":null}]}""")
        }
        assertNull(result.url)
        assertEquals(listOf("exhigh", "higher", "standard"), result.triedLevels)
        val verdict = result.verdict!!
        assertEquals(Entitlement.Kind.UNAVAILABLE, verdict.kind)
        assertEquals(Entitlement.Reason.COPYRIGHT, verdict.reason)
    }

    @Test
    fun rejectsAnItemErrorEvenWithUrl() {
        val result = NcmApi.resolveSongUrl(id, "standard", null, true) {
            response("""{"code":200,"data":[{"id":$id,"code":404,"url":"http://audio.example/invalid.mp3","fee":4}]}""")
        }
        assertNull(result.url)
        val verdict = result.verdict!!
        assertEquals(Entitlement.Kind.UNAVAILABLE, verdict.kind)
        assertEquals(Entitlement.Reason.ALBUM, verdict.reason)
    }

    @Test
    fun sessionFailureStopsTheLadderAndPreservesBusinessCode() {
        var calls = 0
        val error = assertThrows(NcmApi.ApiException::class.java) {
            NcmApi.resolveSongUrl(id, "hires", null, true) {
                calls++
                response("""{"code":301}""")
            }
        }
        assertEquals(301, error.code)
        assertEquals(1, calls)
    }

    @Test
    fun malformedOrHttpFailureIsNotReportedAsUnavailable() {
        for (raw in listOf(response("<html>upstream error</html>"), response("{}"), response("", 503))) {
            assertThrows(NcmApi.ApiException::class.java) {
                NcmApi.resolveSongUrl(id, "standard", null, true) { raw }
            }
        }
    }

    @Test
    fun weapiInjectsCsrfWithoutChangingBusinessFields() {
        val payload = Requests.weapiPayload(""" {"trackId":2123456789012345678,"like":true} """, "quote\"token")
        val parsed = Json.parse(payload)
        assertEquals(2123456789012345678L, parsed.longOrNull("trackId"))
        assertEquals(true, parsed.boolOrNull("like"))
        assertEquals("quote\"token", parsed.strOrNull("csrf_token"))
        assertEquals("", Json.parse(Requests.weapiPayload("{}", null)).strOrNull("csrf_token"))
        val explicit = """{"csrf_token":"explicit"}"""
        assertEquals(explicit, Requests.weapiPayload(explicit, "cookie"))
    }
}
