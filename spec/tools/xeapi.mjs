// xeapi 参考实现（Node.js 内置库，零依赖）。
//
// 三端的「标准答案」生成器：所有随机量都可注入，以便 gen-xeapi-fixtures.mjs 产出逐字节
// 可复现的夹具。算法对齐 NeteaseCloudMusicApiEnhanced/api-enhanced@2db6684
// util/crypto.js（xeapi / xeapiSign / xeapiResDecrypt / xeapiDecryptPublicKey）与
// module/register_xeapikey.js；2026-10-15 用 probe-xeapi.mjs 实测公钥获取 + 匿名读接口通过。
//
// 协议说明见 spec/api/xeapi.md。
import crypto from 'node:crypto'
import zlib from 'node:zlib'

export const STATIC_KEY = Buffer.from(
  'ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84',
  'hex',
)
export const SIGN_KEY =
  'mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g=='
export const EAPI_KEY = Buffer.from('e82ckenh8dichen8')

const X25519_SPKI_PREFIX = Buffer.from('302a300506032b656e032100', 'hex')
const X25519_PKCS8_PREFIX = Buffer.from('302e020100300506032b656e04220420', 'hex')

export const aesEcb = (key, data, decrypt = false) => {
  const make = decrypt ? crypto.createDecipheriv : crypto.createCipheriv
  const c = make(`aes-${key.length * 8}-ecb`, key, Buffer.alloc(0))
  return Buffer.concat([c.update(data), c.final()])
}

/** 公钥接口的请求签名：Base64(HMAC-SHA256(SIGN_KEY 的 ASCII 字节, timestamp + nonce))。 */
export const sign = (timestamp, nonce) =>
  crypto.createHmac('sha256', SIGN_KEY).update(String(timestamp) + nonce).digest('base64')

/** 公钥接口响应 data.encryptedData → 公钥状态 JSON 文本（AES-256-ECB，静态 key）。 */
export const decryptPublicKey = (encryptedDataB64) =>
  aesEcb(STATIC_KEY, Buffer.from(encryptedDataB64, 'base64'), true).toString('utf8')

/** application/x-www-form-urlencoded 序列化（WHATWG URLSearchParams 语义，见 xeapi.md §4）。 */
export const formEncode = (pairs) => new URLSearchParams(pairs).toString()

/**
 * 业务明文信封。只覆盖 Ncrust 用到的形态：POST + 表单 + UTF-8 body。
 * 字段顺序是协议的一部分（JSON 文本参与加密）：先 content，再 queryString。
 */
export const buildPlaintext = (pairs) => {
  const fields = {}
  const filtered = pairs.filter(([k]) => k !== 'e_r')
  const body = formEncode(filtered)
  if (body.length) fields.content = body
  fields.queryString = 'e_r=true'
  return JSON.stringify(fields)
}

/** 中间变换：密文逐字节 XOR r[i & 15] → 左循环移位 rot = (r[0] & 15) % len → r ‖ 结果。 */
export const midTransform = (ciphertext, random16) => {
  const xored = Buffer.alloc(ciphertext.length)
  for (let i = 0; i < ciphertext.length; i++) xored[i] = ciphertext[i] ^ random16[i & 0x0f]
  const rot = xored.length ? (random16[0] & 0x0f) % xored.length : 0
  return Buffer.concat([random16, xored.subarray(rot), xored.subarray(0, rot)])
}

export const x25519PublicFromPrivate = (privateRaw) => {
  const priv = crypto.createPrivateKey({
    key: Buffer.concat([X25519_PKCS8_PREFIX, privateRaw]),
    format: 'der',
    type: 'pkcs8',
  })
  return Buffer.from(crypto.createPublicKey(priv).export({ format: 'der', type: 'spki' })).subarray(-32)
}

export const x25519 = (privateRaw, peerPublicRaw) => {
  const priv = crypto.createPrivateKey({
    key: Buffer.concat([X25519_PKCS8_PREFIX, privateRaw]),
    format: 'der',
    type: 'pkcs8',
  })
  const pub = crypto.createPublicKey({
    key: Buffer.concat([X25519_SPKI_PREFIX, peerPublicRaw]),
    format: 'der',
    type: 'spki',
  })
  return crypto.diffieHellman({ privateKey: priv, publicKey: pub })
}

/** HKDF-SHA256 的手写形态：salt = 32 字节 0，info = 临时公钥 ‖ 0x01，只取前 16 字节。 */
export const deriveAesKey = (sharedSecret, ephemeralPublic) => {
  const prk = crypto
    .createHmac('sha256', Buffer.alloc(32))
    .update(sharedSecret.length ? sharedSecret : Buffer.alloc(32))
    .digest()
  return crypto
    .createHmac('sha256', prk)
    .update(Buffer.concat([ephemeralPublic, Buffer.from([1])]))
    .digest()
    .subarray(0, 16)
}

/** S = 临时公钥(32) ‖ IV(12) ‖ AES-128-GCM(base64(dynamicKey)|os|sk) ‖ tag(16)。 */
export const encryptS = ({ dynamicKey, publicKey, sk, os, ephemeralPrivate, iv }) => {
  const ephemeralPublic = x25519PublicFromPrivate(ephemeralPrivate)
  const shared = x25519(ephemeralPrivate, Buffer.from(publicKey, 'base64'))
  const aesKey = deriveAesKey(shared, ephemeralPublic)
  const cipher = crypto.createCipheriv('aes-128-gcm', aesKey, iv)
  const plaintext = Buffer.from(`${dynamicKey.toString('base64')}|${os}|${sk || ''}`)
  const enc = Buffer.concat([cipher.update(plaintext), cipher.final()])
  return Buffer.concat([ephemeralPublic, iv, enc, cipher.getAuthTag()])
}

/**
 * 组装一次 xeapi 请求体。
 * rng: { dynamicKey(16), midRandom(16), ephemeralPrivate(32), iv(12) } —— 生产环境全部随机。
 * session: 上一次响应头 x-encr-ssid / x-encr-sskey；有则 sskey 的 ASCII 字节作动态密钥。
 */
export const encryptRequest = ({ pairs, keyState, os = 'android', session, rng }) => {
  const sessionKey = session && session.key ? Buffer.from(String(session.key)) : null
  const dynamicKey = sessionKey || rng.dynamicKey
  const plaintext = buildPlaintext(pairs)
  const C = aesEcb(dynamicKey, midTransform(aesEcb(STATIC_KEY, Buffer.from(plaintext)), rng.midRandom))
  const S = encryptS({
    dynamicKey,
    publicKey: keyState.publicKey,
    sk: keyState.sk,
    os,
    ephemeralPrivate: rng.ephemeralPrivate,
    iv: rng.iv,
  })
  const R = aesEcb(STATIC_KEY, Buffer.from(`${keyState.version}|${sessionKey ? session.id : ''}`))
  const c = C.toString('base64url')
  const s = S.toString('base64')
  const r = R.toString('base64')
  return { plaintext, C: c, S: s, R: r, body: formEncode([['C', c], ['S', s], ['R', r]]) }
}

/** 响应解密：AES-128-ECB(eapi key)，若以 gzip 魔数 1f8b 开头再解压。 */
export const decryptResponse = (bytes) => {
  const d = aesEcb(EAPI_KEY, bytes, true)
  return (d[0] === 0x1f && d[1] === 0x8b ? zlib.gunzipSync(d) : d).toString('utf8')
}

/** 生产用随机量。 */
export const randomRng = () => ({
  dynamicKey: crypto.randomBytes(16),
  midRandom: crypto.randomBytes(16),
  ephemeralPrivate: crypto.randomBytes(32),
  iv: crypto.randomBytes(12),
})
