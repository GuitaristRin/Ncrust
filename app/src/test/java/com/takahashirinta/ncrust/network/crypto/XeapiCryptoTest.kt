package com.takahashirinta.ncrust.network.crypto

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.takahashirinta.ncrust.testutil.Fixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * xeapi 实现与跨端夹具 `spec/fixtures/crypto/xeapi.json` 的逐字节对照。
 *
 * 夹具是「标准答案」（由 `spec/tools/gen-xeapi-fixtures.mjs` 用参考实现生成，全部随机量固定），
 * 实现与它不一致时以夹具为准。断言一律比较 base64 文本而不是解码后的字节：`C` 的
 * base64url-无填充 与 `S`/`R` 的标准 base64 是协议的一部分，多一个 `=` 就是另一个字节串。
 *
 * 解析夹具用 Gson 而不是 org.json：本地单测里 org.json 是空桩，一调就抛 "not mocked"。
 */
class XeapiCryptoTest {

    private val cases: JsonArray = JsonParser.parseString(
        Fixtures.readText("spec/fixtures/crypto/xeapi.json")
    ).asJsonArray

    private fun casesOf(kind: String): List<JsonObject> =
        cases.map { it.asJsonObject }.filter { it.get("kind").asString == kind }

    private fun id(c: JsonObject) = c.get("id").asString

    private fun pairs(c: JsonObject): List<Pair<String, String>> =
        c.getAsJsonObject("input").getAsJsonArray("pairs").map { element ->
            val pair = element.asJsonArray
            pair[0].asString to pair[1].asString
        }

    private fun keyState(json: JsonObject) = XeapiCrypto.KeyState(
        publicKey = json.get("publicKey").asString,
        version = json.get("version").asString,
        sk = json.get("sk").asString
    )

    private fun rng(json: JsonObject) = XeapiCrypto.Rng(
        dynamicKey = XeapiCrypto.base64Decode(json.get("dynamicKey").asString),
        midRandom = XeapiCrypto.base64Decode(json.get("midRandom").asString),
        ephemeralPrivate = XeapiCrypto.base64Decode(json.get("ephemeralPrivate").asString),
        iv = XeapiCrypto.base64Decode(json.get("iv").asString)
    )

    /**
     * 夹具新增 kind 而测试没跟上时必须红——否则新用例会被静默跳过，看着全绿。
     */
    @Test
    fun `every fixture kind is exercised`() {
        val handled = setOf(
            "xeapi-sign",
            "xeapi-public-key-decrypt",
            "xeapi-plaintext",
            "xeapi-mid-transform",
            "xeapi-x25519",
            "xeapi-kdf",
            "xeapi-s",
            "xeapi-request",
            "xeapi-response"
        )
        val present = cases.map { it.asJsonObject.get("kind").asString }.toSet()
        assertEquals("夹具里的 kind 与测试覆盖的 kind 不一致", handled, present)
        assertTrue("夹具为空", cases.size() > 0)
    }

    @Test
    fun `sign matches fixture`() {
        for (c in casesOf("xeapi-sign")) {
            val input = c.getAsJsonObject("input")
            assertEquals(
                "case ${id(c)}",
                c.getAsJsonObject("expect").get("signature").asString,
                XeapiCrypto.sign(input.get("timestamp").asString, input.get("nonce").asString)
            )
        }
    }

    @Test
    fun `public key decrypt matches fixture`() {
        for (c in casesOf("xeapi-public-key-decrypt")) {
            assertEquals(
                "case ${id(c)}",
                c.getAsJsonObject("expect").get("plaintext").asString,
                XeapiCrypto.decryptPublicKey(c.getAsJsonObject("input").get("encryptedData").asString)
            )
        }
    }

    @Test
    fun `plaintext envelope matches fixture`() {
        for (c in casesOf("xeapi-plaintext")) {
            assertEquals(
                "case ${id(c)}",
                c.getAsJsonObject("expect").get("plaintext").asString,
                XeapiCrypto.buildPlaintext(pairs(c))
            )
        }
    }

    @Test
    fun `mid transform matches fixture`() {
        for (c in casesOf("xeapi-mid-transform")) {
            val input = c.getAsJsonObject("input")
            val output = XeapiCrypto.midTransform(
                XeapiCrypto.base64Decode(input.get("ciphertext").asString),
                XeapiCrypto.base64Decode(input.get("random").asString)
            )
            assertEquals(
                "case ${id(c)}",
                c.getAsJsonObject("expect").get("output").asString,
                XeapiCrypto.base64Encode(output)
            )
        }
    }

    @Test
    fun `x25519 matches fixture`() {
        for (c in casesOf("xeapi-x25519")) {
            val input = c.getAsJsonObject("input")
            val expect = c.getAsJsonObject("expect")
            val privateKey = XeapiCrypto.base64Decode(input.get("privateKey").asString)
            val peerPublic = XeapiCrypto.base64Decode(input.get("peerPublicKey").asString)
            assertEquals(
                "case ${id(c)} publicKey",
                expect.get("publicKey").asString,
                XeapiCrypto.base64Encode(XeapiCrypto.x25519PublicFromPrivate(privateKey))
            )
            assertEquals(
                "case ${id(c)} sharedSecret",
                expect.get("sharedSecret").asString,
                XeapiCrypto.base64Encode(XeapiCrypto.x25519(privateKey, peerPublic))
            )
        }
    }

    @Test
    fun `kdf matches fixture`() {
        for (c in casesOf("xeapi-kdf")) {
            val input = c.getAsJsonObject("input")
            assertEquals(
                "case ${id(c)}",
                c.getAsJsonObject("expect").get("aesKey").asString,
                XeapiCrypto.base64Encode(
                    XeapiCrypto.deriveAesKey(
                        XeapiCrypto.base64Decode(input.get("sharedSecret").asString),
                        XeapiCrypto.base64Decode(input.get("ephemeralPublicKey").asString)
                    )
                )
            )
        }
    }

    @Test
    fun `S envelope matches fixture`() {
        for (c in casesOf("xeapi-s")) {
            val input = c.getAsJsonObject("input")
            val keyStateJson = input.getAsJsonObject("keyState")
            val s = XeapiCrypto.encryptS(
                dynamicKey = XeapiCrypto.base64Decode(input.get("dynamicKey").asString),
                publicKeyBase64 = keyStateJson.get("publicKey").asString,
                sk = keyStateJson.get("sk").asString,
                os = input.get("os").asString,
                ephemeralPrivate = XeapiCrypto.base64Decode(input.get("ephemeralPrivate").asString),
                iv = XeapiCrypto.base64Decode(input.get("iv").asString)
            )
            assertEquals("case ${id(c)}", c.getAsJsonObject("expect").get("S").asString, XeapiCrypto.base64Encode(s))
        }
    }

    @Test
    fun `full request body matches fixture`() {
        for (c in casesOf("xeapi-request")) {
            val input = c.getAsJsonObject("input")
            val expect = c.getAsJsonObject("expect")
            val sessionJson = input.get("session")
            val session = if (sessionJson == null || sessionJson.isJsonNull) null else {
                val s = sessionJson.asJsonObject
                XeapiCrypto.Session(s.get("id").asString, s.get("key").asString)
            }
            val request = XeapiCrypto.encryptRequest(
                pairs = pairs(c),
                keyState = keyState(input.getAsJsonObject("keyState")),
                os = input.get("os").asString,
                session = session,
                rng = rng(input.getAsJsonObject("rng"))
            )
            assertEquals("case ${id(c)} plaintext", expect.get("plaintext").asString, request.plaintext)
            assertEquals("case ${id(c)} C", expect.get("C").asString, request.c)
            assertEquals("case ${id(c)} S", expect.get("S").asString, request.s)
            assertEquals("case ${id(c)} R", expect.get("R").asString, request.r)
            assertEquals("case ${id(c)} body", expect.get("body").asString, request.body)
        }
    }

    @Test
    fun `response decrypt matches fixture`() {
        for (c in casesOf("xeapi-response")) {
            assertEquals(
                "case ${id(c)}",
                c.getAsJsonObject("expect").get("plaintext").asString,
                XeapiCrypto.decryptResponse(
                    XeapiCrypto.base64Decode(c.getAsJsonObject("input").get("base64").asString)
                )
            )
        }
    }
}
