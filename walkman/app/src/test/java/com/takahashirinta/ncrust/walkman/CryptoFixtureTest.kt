package com.takahashirinta.ncrust.walkman

import com.google.gson.JsonObject
import com.takahashirinta.ncrust.walkman.crypto.Base64
import com.takahashirinta.ncrust.walkman.crypto.Eapi
import com.takahashirinta.ncrust.walkman.crypto.Weapi
import com.takahashirinta.ncrust.walkman.crypto.X25519
import com.takahashirinta.ncrust.walkman.crypto.Xeapi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * `spec/fixtures/crypto/{eapi,weapi,xeapi}.json` 的**全部**用例。
 *
 * 跑法是逐条分派到实现、逐字节比对期望值，并且**先收集全部失败再断言**：夹具的意义是
 * 「一次看到所有不一致」，第一条就 fail-fast 会把后面 12 条的信息一起埋掉。
 * 每条失败都带上夹具的 `id`，报错信息要能直接指出是哪条用例。
 *
 * 没有 `@Ignore`、没有 `assumeTrue`：夹具读不到 [Fixtures] 会抛异常，不会静默变绿。
 */
class CryptoFixtureTest {

    @Test
    fun rejectsLowOrderXeapiPeerKey() {
        assertThrows(IllegalArgumentException::class.java) {
            Xeapi.encryptS(
                ByteArray(16), Xeapi.KeyState(Base64.encode(ByteArray(32)), "1", "sk"),
                "android", ByteArray(32) { 1 }, ByteArray(12)
            )
        }
    }

    @Test
    fun eapi() {
        val failures = runCases("crypto/eapi.json") { case ->
            val input = case.obj("input")
            val expect = case.obj("expect")
            when (case.kind) {
                "eapi-encrypt" -> assertEquals(
                    expect.str("params"),
                    Eapi.encryptParams(input.str("url"), input.str("payloadJson"))
                )

                "eapi-decrypt" -> assertEquals(
                    expect.str("plaintext"),
                    Eapi.decryptResponse(input.str("base64"))
                )

                else -> fail("未实现的 eapi 用例类型: ${case.kind}")
            }
        }
        assertNoFailures("crypto/eapi.json", failures)
    }

    @Test
    fun weapi() {
        val failures = runCases("crypto/weapi.json") { case ->
            val input = case.obj("input")
            val expect = case.obj("expect")
            when (case.kind) {
                "weapi-encrypt" -> {
                    val (params, encSecKey) = Weapi.encryptParams(input.str("payloadJson"), input.str("secKey"))
                    assertEquals("params", expect.str("params"), params)
                    assertEquals("encSecKey", expect.str("encSecKey"), encSecKey)
                }

                else -> fail("未实现的 weapi 用例类型: ${case.kind}")
            }
        }
        assertNoFailures("crypto/weapi.json", failures)
    }

    @Test
    fun xeapi() {
        val failures = runCases("crypto/xeapi.json") { case ->
            val input = case.obj("input")
            val expect = case.obj("expect")
            when (case.kind) {
                "xeapi-sign" -> assertEquals(
                    expect.str("signature"),
                    Xeapi.sign(input.str("timestamp"), input.str("nonce"))
                )

                "xeapi-public-key-decrypt" -> assertEquals(
                    expect.str("plaintext"),
                    Xeapi.decryptPublicKey(input.str("encryptedData"))
                )

                "xeapi-plaintext" -> assertEquals(
                    expect.str("plaintext"),
                    Xeapi.buildPlaintext(input.pairs())
                )

                "xeapi-mid-transform" -> assertEquals(
                    expect.str("output"),
                    Base64.encode(Xeapi.midTransform(Base64.decode(input.str("ciphertext")), Base64.decode(input.str("random"))))
                )

                "xeapi-x25519" -> {
                    val priv = Base64.decode(input.str("privateKey"))
                    val peer = Base64.decode(input.str("peerPublicKey"))
                    assertEquals("publicKey", expect.str("publicKey"), Base64.encode(X25519.publicKeyFromPrivate(priv)))
                    assertEquals("sharedSecret", expect.str("sharedSecret"), Base64.encode(X25519.x25519(priv, peer)))
                }

                "xeapi-kdf" -> assertEquals(
                    expect.str("aesKey"),
                    Base64.encode(Xeapi.deriveAesKey(Base64.decode(input.str("sharedSecret")), Base64.decode(input.str("ephemeralPublicKey"))))
                )

                "xeapi-s" -> assertEquals(
                    expect.str("S"),
                    Base64.encode(
                        Xeapi.encryptS(
                            dynamicKey = Base64.decode(input.str("dynamicKey")),
                            keyState = input.keyState(),
                            os = input.str("os"),
                            ephemeralPrivate = Base64.decode(input.str("ephemeralPrivate")),
                            iv = Base64.decode(input.str("iv"))
                        )
                    )
                )

                "xeapi-request" -> {
                    val actual = Xeapi.encryptRequest(
                        pairs = input.pairs(),
                        keyState = input.keyState(),
                        os = input.str("os"),
                        session = input.session(),
                        rng = input.rng()
                    )
                    assertEquals("plaintext", expect.str("plaintext"), actual.plaintext)
                    assertEquals("C", expect.str("C"), actual.c)
                    assertEquals("S", expect.str("S"), actual.s)
                    assertEquals("R", expect.str("R"), actual.r)
                    assertEquals("body", expect.str("body"), actual.body)
                }

                "xeapi-response" -> assertEquals(
                    expect.str("plaintext"),
                    Xeapi.decryptResponse(Base64.decode(input.str("base64")))
                )

                else -> fail("未实现的 xeapi 用例类型: ${case.kind}")
            }
        }
        assertNoFailures("crypto/xeapi.json", failures)
    }

    // ------------------------------------------------------------------ 夹具读取

    private class Case(val id: String, val kind: String, private val json: JsonObject) {
        fun obj(name: String): JsonObject = json.getAsJsonObject(name)
    }

    private fun runCases(file: String, body: (Case) -> Unit): List<String> {
        val cases = Fixtures.readArray(file)
        assertTrue("$file 里没有用例", cases.size() > 0)
        val failures = ArrayList<String>()
        for (element in cases) {
            val json = element.asJsonObject
            val id = json.get("id").asString
            val case = Case(id, json.get("kind").asString, json)
            try {
                body(case)
            } catch (t: Throwable) {
                failures += "$id: ${t.message}"
            }
        }
        return failures
    }

    private fun assertNoFailures(file: String, failures: List<String>) {
        if (failures.isNotEmpty()) {
            throw AssertionError("$file 有 ${failures.size} 条用例不通过:\n" + failures.joinToString("\n"))
        }
    }

    private fun fail(message: String): Nothing = throw AssertionError(message)

    private fun JsonObject.str(name: String): String = get(name).asString

    private fun JsonObject.pairs(): List<Pair<String, String>> =
        getAsJsonArray("pairs").map { row ->
            val pair = row.asJsonArray
            pair[0].asString to pair[1].asString
        }

    private fun JsonObject.keyState(): Xeapi.KeyState {
        val k = getAsJsonObject("keyState")
        return Xeapi.KeyState(publicKey = k.str("publicKey"), version = k.str("version"), sk = k.str("sk"))
    }

    private fun JsonObject.session(): Xeapi.Session? {
        val s = get("session")
        if (s == null || s.isJsonNull) return null
        val o = s.asJsonObject
        return Xeapi.Session(id = o.str("id"), key = o.str("key"))
    }

    private fun JsonObject.rng(): Xeapi.XeapiRng {
        val r = getAsJsonObject("rng")
        return Xeapi.XeapiRng(
            dynamicKey = Base64.decode(r.str("dynamicKey")),
            midRandom = Base64.decode(r.str("midRandom")),
            ephemeralPrivate = Base64.decode(r.str("ephemeralPrivate")),
            iv = Base64.decode(r.str("iv"))
        )
    }
}
