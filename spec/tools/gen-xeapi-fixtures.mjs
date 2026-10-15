// 生成 spec/fixtures/crypto/xeapi.json。所有随机量固定，输出逐字节可复现。
//   node spec/tools/gen-xeapi-fixtures.mjs > spec/fixtures/crypto/xeapi.json
import crypto from 'node:crypto'
import zlib from 'node:zlib'
import * as X from './xeapi.mjs'

const hex = (s) => Buffer.from(s, 'hex')
const seq = (n, start) => Buffer.from(Array.from({ length: n }, (_, i) => (start + i * 7) & 0xff))

const SRC = 'spec/tools/xeapi.mjs（对齐 api-enhanced@2db6684 util/crypto.js）；gen-xeapi-fixtures.mjs 生成'

// 夹具用的「服务端」密钥对：只有公钥进夹具，私钥留在这里以便自检 S 可被解开。
const serverPrivate = hex('a8abababababababababababababababababababababababababababababab6b')
const keyState = {
  publicKey: X.x25519PublicFromPrivate(serverPrivate).toString('base64'),
  version: '1000000000000',
  sk: '8PZfbIFA1779944463972',
}
const rng = {
  dynamicKey: seq(16, 0x10),
  midRandom: seq(16, 0xa3),
  ephemeralPrivate: seq(32, 0x41),
  iv: seq(12, 0x05),
}
const b = (buf) => buf.toString('base64')

const cases = []

cases.push({
  id: 'xeapi.sign.key-request',
  kind: 'xeapi-sign',
  description: '公钥接口签名：HMAC-SHA256，key 是 SIGN_KEY 字符串的 ASCII 字节（不是 base64 解码后的字节）。',
  source: SRC,
  input: { timestamp: '1779955023124', nonce: '1234567890123456' },
  expect: { signature: X.sign('1779955023124', '1234567890123456') },
})

const keyJson = JSON.stringify({ ...keyState, nextUpdateTime: 1803882269000 })
cases.push({
  id: 'xeapi.public-key.decrypt',
  kind: 'xeapi-public-key-decrypt',
  description: '公钥接口 data.encryptedData：AES-256-ECB/PKCS7（静态 key）解出公钥状态 JSON。',
  source: SRC,
  input: { encryptedData: b(X.aesEcb(X.STATIC_KEY, Buffer.from(keyJson))) },
  expect: { plaintext: keyJson },
})

const formCases = [
  ['xeapi.form.like', '收藏写入的真实字段顺序与布尔/数字写法。', [['alg', 'itembased'], ['trackId', '2754388325'], ['like', 'true'], ['time', '3']]],
  ['xeapi.form.json-value', 'JSON 值里的 [ ] { } " : , 必须百分号编码（大写 hex）。', [['c', '[{"id":347230}]']]],
  ['xeapi.form.tricky', "空格编码为 +；! ' ( ) ~ 必须编码；* - . _ 保留；非 ASCII 先 UTF-8 再编码。e_r 字段被剔除。", [['q', "a b!'()~*-._"], ['name', '海阔天空'], ['e_r', 'true']]],
]
for (const [id, description, pairs] of formCases) {
  cases.push({
    id: id.replace('xeapi.form.', 'xeapi.plaintext.'),
    kind: 'xeapi-plaintext',
    description: '明文信封 ' + description,
    source: SRC,
    input: { pairs },
    expect: { plaintext: X.buildPlaintext(pairs) },
  })
}

cases.push({
  id: 'xeapi.mid-transform.rotate',
  kind: 'xeapi-mid-transform',
  description: '中间变换：XOR r[i&15] → 左移 (r[0]&15)%len → r 前缀。r[0]=0xa3 时 rot=3。',
  source: SRC,
  input: { ciphertext: b(seq(37, 0x01)), random: b(rng.midRandom) },
  expect: { output: b(X.midTransform(seq(37, 0x01), rng.midRandom)) },
})

const eph = X.x25519PublicFromPrivate(rng.ephemeralPrivate)
const shared = X.x25519(rng.ephemeralPrivate, Buffer.from(keyState.publicKey, 'base64'))
cases.push({
  id: 'xeapi.x25519.ecdh',
  kind: 'xeapi-x25519',
  description: 'X25519（RFC 7748）：私钥 clamp 由算法自身完成，输出 32 字节共享密钥与临时公钥。',
  source: SRC,
  input: { privateKey: b(rng.ephemeralPrivate), peerPublicKey: keyState.publicKey },
  expect: { publicKey: b(eph), sharedSecret: b(shared) },
})

cases.push({
  id: 'xeapi.kdf.aes-key',
  kind: 'xeapi-kdf',
  description: 'PRK = HMAC(0^32, shared)；OKM = HMAC(PRK, ephPub ‖ 0x01)；取前 16 字节作 AES-128-GCM key。',
  source: SRC,
  input: { sharedSecret: b(shared), ephemeralPublicKey: b(eph) },
  expect: { aesKey: b(X.deriveAesKey(shared, eph)) },
})

const sPlain = { dynamicKey: rng.dynamicKey, publicKey: keyState.publicKey, sk: keyState.sk, os: 'android', ephemeralPrivate: rng.ephemeralPrivate, iv: rng.iv }
cases.push({
  id: 'xeapi.envelope.s',
  kind: 'xeapi-s',
  description: 'S = ephPub(32) ‖ iv(12) ‖ GCM 密文 ‖ tag(16)，明文 base64(dynamicKey)|os|sk。',
  source: SRC,
  input: { dynamicKey: b(rng.dynamicKey), keyState, os: 'android', ephemeralPrivate: b(rng.ephemeralPrivate), iv: b(rng.iv) },
  expect: { S: b(X.encryptS(sPlain)) },
})

const requestCases = [
  ['xeapi.request.like-v1', '收藏 /api/v1/radio/like 的完整请求体（无会话：随机 16 字节动态密钥 → AES-128-ECB；R 的会话段为空）。', [['alg', 'itembased'], ['trackId', '2754388325'], ['like', 'true'], ['time', '3']], null],
  ['xeapi.request.session', '有会话时：x-encr-sskey 的 ASCII 字节（32 字节）作动态密钥 → AES-256-ECB；R = version|ssid。', [['alg', 'itembased'], ['trackId', '347230'], ['like', 'false'], ['time', '3']], { id: '01c3a3532b0d4e6f', key: '0123456789abcdef0123456789abcdef' }],
]
for (const [id, description, pairs, session] of requestCases) {
  const r = X.encryptRequest({ pairs, keyState, session, rng })
  cases.push({
    id,
    kind: 'xeapi-request',
    description: description + ' C 用 base64url 且不补 =；S/R 用标准 base64；最后整体表单编码。',
    source: SRC,
    input: {
      pairs,
      keyState,
      os: 'android',
      session,
      rng: { dynamicKey: b(rng.dynamicKey), midRandom: b(rng.midRandom), ephemeralPrivate: b(rng.ephemeralPrivate), iv: b(rng.iv) },
    },
    expect: { plaintext: r.plaintext, C: r.C, S: r.S, R: r.R, body: r.body },
  })
}

const respJson = '{"code":200,"playlistId":6936573734,"songs":[]}'
cases.push({
  id: 'xeapi.response.plain',
  kind: 'xeapi-response',
  description: '响应：AES-128-ECB（eapi key）解密后直接是 UTF-8 JSON。',
  source: SRC,
  input: { base64: b(X.aesEcb(X.EAPI_KEY, Buffer.from(respJson))) },
  expect: { plaintext: respJson },
})
const gz = zlib.gzipSync(Buffer.from(respJson), { level: 9 })
cases.push({
  id: 'xeapi.response.gzip',
  kind: 'xeapi-response',
  description: '响应：解密后以 1f8b 开头时须再 gunzip。',
  source: SRC,
  input: { base64: b(X.aesEcb(X.EAPI_KEY, gz)) },
  expect: { plaintext: respJson },
})

// 自检：S 能被「服务端」私钥解开，且解出的动态密钥能还原 C 的明文。
{
  const r = X.encryptRequest({ pairs: requestCases[0][2], keyState, rng })
  const S = Buffer.from(r.S, 'base64')
  const ephPub = S.subarray(0, 32), iv = S.subarray(32, 44), tag = S.subarray(S.length - 16)
  const key = X.deriveAesKey(X.x25519(serverPrivate, ephPub), ephPub)
  const d = crypto.createDecipheriv('aes-128-gcm', key, iv)
  d.setAuthTag(tag)
  const plain = Buffer.concat([d.update(S.subarray(44, S.length - 16)), d.final()]).toString()
  const dyn = Buffer.from(plain.split('|')[0], 'base64')
  const mid = X.aesEcb(dyn, Buffer.from(r.C, 'base64url'), true)
  const rr = mid.subarray(0, 16), rest = mid.subarray(16)
  const rot = (rr[0] & 15) % rest.length
  const unrot = Buffer.concat([rest.subarray(rest.length - rot), rest.subarray(0, rest.length - rot)])
  for (let i = 0; i < unrot.length; i++) unrot[i] ^= rr[i & 15]
  const inner = X.aesEcb(X.STATIC_KEY, unrot, true).toString()
  if (inner !== r.plaintext) throw new Error('self-check failed: ' + inner)
}

process.stdout.write(JSON.stringify(cases, null, 2) + '\n')
