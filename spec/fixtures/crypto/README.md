# fixtures/crypto —— eapi / weapi 加密的固定答案

协议或算法变更时先改这里，再改各端实现（见 `spec/README.md`）。各端测试直接读本目录，
不复制夹具。

## schema

每个 JSON 文件是一个用例数组。公共字段：`id`（全局唯一）、`description`（这条在防什么）、
`source`（依据：代码位置 / commit / 实测）、`input`、`expect`。

### `eapi.json`

逐条带 `kind`：

| kind | input | expect |
|---|---|---|
| `eapi-encrypt` | `{ "url": "<完整 URL 或 /eapi/ 开头的路径>", "payloadJson": "<提交加密的 JSON 明文>" }` | `{ "params": "<AES-128-ECB/PKCS5 加密后的 hex，小写>" }` |
| `eapi-decrypt` | `{ "base64": "<响应体 base64>" }` | `{ "plaintext": "<解密后 UTF-8 文本>" }` |

要点（与 Android `EapiCrypto.kt` 一致）：

- 路径里的 `/eapi/` 先重写为 `/api/`，签名与加密体都用重写后的路径。
- 签名串 `nobody<path>use<json>md5forencrypt`，MD5 小写 hex。
- 加密体 `<path>-36cd479b6b5-<json>-36cd479b6b5-<digest>`。
- 响应解密：AES-128-ECB/PKCS5，同一把 key；只用于部分写接口的加密响应。

### `weapi.json`

| input | expect |
|---|---|
| `{ "payloadJson": "<JSON 明文>", "secKey": "<固定 16 字符随机密钥>" }` | `{ "params": "<base64>", "encSecKey": "<256 位小写 hex>" }` |

要点（与 Android `WeapiCrypto.kt` 一致）：

- `secKey` 真实实现是随机 16 字符；夹具固定它以让输出可复现。
- `params = Base64(AES-128-CBC(AES-128-CBC(json, PRESET_KEY), secKey))`，IV 固定
  `0102030405060708`。
- `encSecKey = (BigInt(reverse(secKey)) ^ e mod n).toString(16)` 补足 256 位，**不加填充、不是 base64**。

## 取值来源

`eapi.json` / `weapi.json` 的期望值由一个独立参考实现（Node.js `crypto` + `BigInt`）
按上述算法生成，再与 Android 实现逐条核对。它是三端共同的「标准答案」。

### `xeapi.json`

官方 Android 9.x 的请求加密层，协议见 `spec/api/xeapi.md`。期望值由 `spec/tools/xeapi.mjs`
生成（`node spec/tools/gen-xeapi-fixtures.mjs > spec/fixtures/crypto/xeapi.json`），所有随机量
（动态密钥、mid 随机数、X25519 临时私钥、GCM IV）都固定在 `input` 里。二进制一律标准 base64。

| kind | input | expect |
|---|---|---|
| `xeapi-sign` | `{ timestamp, nonce }` | `{ signature }` |
| `xeapi-public-key-decrypt` | `{ encryptedData }` | `{ plaintext }` |
| `xeapi-plaintext` | `{ pairs: [[k, v], …] }`（有序） | `{ plaintext }`（明文信封 JSON 文本） |
| `xeapi-mid-transform` | `{ ciphertext, random }` | `{ output }` |
| `xeapi-x25519` | `{ privateKey, peerPublicKey }` | `{ publicKey, sharedSecret }` |
| `xeapi-kdf` | `{ sharedSecret, ephemeralPublicKey }` | `{ aesKey }` |
| `xeapi-s` | `{ dynamicKey, keyState, os, ephemeralPrivate, iv }` | `{ S }` |
| `xeapi-request` | `{ pairs, keyState, os, session, rng: { dynamicKey, midRandom, ephemeralPrivate, iv } }` | `{ plaintext, C, S, R, body }` |
| `xeapi-response` | `{ base64 }` | `{ plaintext }` |

`C` 是 base64url **不补 `=`**；`body` 是 `C=…&S=…&R=…` 的表单编码。`session` 为 `null` 时用
`rng.dynamicKey`；非空时用 `session.key` 的 ASCII 字节作动态密钥、`R` 带 `session.id`。
