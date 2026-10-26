# xeapi —— 官方 Android 9.x 的请求加密层

网易云官方 Android 客户端（9.x，AegisSDK）的新一代请求包装。Ncrust 用它承载**收藏写入**
（`/api/v1/radio/like`），原因见文末「为什么收藏要走 xeapi」。

- 参考实现（三端标准答案）：`spec/tools/xeapi.mjs`，零依赖 Node。
- 夹具：`spec/fixtures/crypto/xeapi.json`（`node spec/tools/gen-xeapi-fixtures.mjs` 生成，全部随机量固定）。
- 线上探针：`node spec/tools/probe-xeapi.mjs`（匿名，不读 cookie）。2026-10-15 实测通过。
- 上游依据：NeteaseCloudMusicApiEnhanced/api-enhanced@2db6684 `util/crypto.js`、`util/request.js`、
  `module/register_xeapikey.js`、`module/like_v1.js`、`module/register_checktoken_v3.js`；
  gmg137/netease-cloud-music-api@0983ec2（「增加 xeapi 支持，新增 like_v1 接口」，修复其 issue #410）。

## 1. 常量

| 名称 | 值 | 用途 |
|---|---|---|
| `STATIC_KEY` | hex `ab1d5a430f6bb04a3f01e81ddd72bd916d5ce591248ac128714806d7f8fb1b84`（32 字节） | AES-256-ECB：公钥解密、C 内层、R |
| `SIGN_KEY` | `mUHCwVNWJbunMqAHf5MImuirT6plvs6VSFW62MGHstFQxhBGdEoIhLItH3djc4+FB/OKty3+lL2rGeoFBpVe5g==` | HMAC-SHA256 的 key，**取这串字符的 ASCII 字节**，不做 base64 解码 |
| `EAPI_KEY` | `e82ckenh8dichen8` | 响应解密（AES-128-ECB），与 eapi 同一把 |
| Android UA | `NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; HBN-AL00 Build/cd737a2.0)` | 公钥请求与业务请求 |

AES 一律 PKCS#7 填充。

## 2. 公钥（每台设备取一次，按 `nextUpdateTime` 刷新）

`POST https://interface.music.163.com/api/bsr/sk/get`，**明文表单**（不加密），`Cookie: deviceId=<id>`：

```
appVersion=9.5.61&currentKeyVersion=<旧 version 或空>&deviceId=<id>&nonce=<16 位十进制>&os=android
&requestType=active&signature=<sign(timestamp, nonce)>&t1=&t2=&timestamp=<毫秒>&uid=
```

- `sign(ts, nonce) = Base64(HMAC-SHA256(SIGN_KEY, ts + nonce))`
- 响应 `{"code":200,"data":{"encryptedData","signature","timestamp"}}`。**必须验签**：
  `data.signature == sign(data.timestamp, 请求时的 nonce)`，不等即丢弃。
- `AES-256-ECB-decrypt(STATIC_KEY, base64(encryptedData))` → `{"publicKey","version","nextUpdateTime","sk"}`；
  `publicKey` 是 32 字节 X25519 原始公钥的 base64。缺 `sk` 视为失败。
- 持久化缓存（Android SharedPreferences / Windows 设置存储），`nextUpdateTime`（毫秒）过后或业务请求失败时刷新，
  刷新时把旧 `version` 填进 `currentKeyVersion`。

## 3. 业务请求

URL：`https://interface3.music.163.com/xeapi/<uri 去掉开头的 /api/>`，例如 `/api/v1/radio/like` →
`/xeapi/v1/radio/like`。请求体是表单 `C=<…>&S=<…>&R=<…>`（整体再做一次表单编码）。

### 3.1 明文信封 `P`

只覆盖 Ncrust 用到的形态（POST + 表单 + UTF-8）：

```json
{"content":"<业务字段的表单编码，剔除 e_r>","queryString":"e_r=true"}
```

字段顺序固定（JSON 文本整体参与加密）；无业务字段时省略 `content`。

### 3.2 三个参数

```
dynamicKey = 有会话 ? ASCII(x-encr-sskey)（32 字节 → AES-256） : 随机 16 字节（AES-128）
C = base64url_nopad( AES-ECB(dynamicKey, mid( AES-256-ECB(STATIC_KEY, P) )) )
S = base64( ephPub(32) ‖ iv(12) ‖ AES-128-GCM(aesKey, iv, "<base64(dynamicKey)>|android|<sk>") ‖ tag(16) )
R = base64( AES-256-ECB(STATIC_KEY, "<version>|<有会话 ? x-encr-ssid : 空>") )
```

- `mid(x)`：取随机 16 字节 `r`；`x[i] ^= r[i & 15]`；`rot = (r[0] & 15) % len`；输出 `r ‖ x[rot..] ‖ x[..rot]`。
- `ephPub/ephPriv`：每次请求新生成的 X25519 密钥对；`shared = X25519(ephPriv, publicKey)`。
- `aesKey = HMAC-SHA256( HMAC-SHA256(0^32, shared), ephPub ‖ 0x01 )[0..16]`（单块 HKDF，salt 全 0）。
- **C 用 base64url 且不补 `=`**；S、R 用标准 base64（带 `=`）。夹具同时覆盖两种。

### 3.3 请求头与 Cookie

```
User-Agent: <Android UA>
Content-Type: application/x-www-form-urlencoded;charset=utf-8
X-Client-Enc-State: ENCRYPTED
x-aeapi: true
x-deviceid / x-sdeviceid: <deviceId>
x-os: android    x-osver: 16    x-appver: 9.1.65    x-buildver: <秒级时间戳前 10 位>
x-music-u: <MUSIC_U>          （已登录时）
X-antiCheatToken: <易盾 token>（收藏写入带上，见 §5）
Cookie: <登录 cookie>; os=android; osver=16; appver=9.1.65; buildver=<同上>; deviceId=<id>; sDeviceId=<id>
```

`deviceId` 每台设备生成一次后持久化（32 位小写 hex），公钥请求与业务请求用同一个。
**不要每次请求换 deviceId** —— 那正是风控画像里「非真实设备」的特征。

### 3.4 响应与会话

- 响应体：`AES-128-ECB-decrypt(EAPI_KEY, body)`；结果以 `1f 8b` 开头时再 gunzip；得到 JSON。
- 响应头带 `x-encr-ssid` + `x-encr-sskey` 时记下，之后的请求按 §3.2 走会话模式（只存内存，进程级）。

## 4. 表单编码（易错）

WHATWG `application/x-www-form-urlencoded`：字节集 `A–Z a–z 0–9 * - . _` 原样，空格 → `+`，
其余（含 `! ' ( ) ~`）按 UTF-8 字节编码为大写 `%XX`。

- C#：`WebUtility.UrlEncode` / `Uri.EscapeDataString` **都不对**（前者不编码 `! ( )`，后者空格出 `%20`、不编码 `~`）。手写。
- Kotlin/Java：`URLEncoder.encode(s, "UTF-8")` 与之等价（`~` → `%7E`，`*` 原样）。

夹具 `xeapi.plaintext.tricky` 专门防这一点。

## 5. 易盾反作弊 token（收藏写入用）

`GET https://ac.dun.163yun.com/v3/b?pn=YD00000558929251`，响应形如 `null([200,<n>,"<token>"])`，
取第三项。**每次写入现取、不缓存**。取不到时发空串（上游同样处理），不阻断请求。

官方（Android 9.6.08 / Windows 3.1.40）把这份 token **同时放进两处**，缺一不可：

- 加密业务体的 `checkToken` 字段（进 §3.1 的 `content`）；以及
- `X-antiCheatToken` 请求头。

## 6. 收藏：`/api/v1/radio/like`

业务字段（官方 9.6.13 反编译实证，**每次必发**，缺字段本身就是可识别指纹）：

```
trackId=<id>&time=3&alg=itembased&like=<true|false>
&rqRefer=<playlist ref 链>&songRqRefer=<song ref>&mode=<场景名>&subMode=<子场景>&source=<point|userfm>
&checkToken=<易盾 token>
```

- **用 `HashMap` 组字段，顺序不参与指纹**（`NeteaseMusicApiImpl.b7` 里逐项 `map.put`，
  服务端看到的顺序由哈希迭代决定）；**字段是否齐全才参与**。旧版本文档把「写入顺序」当指纹
  是错的，已被 9.6.13 dexdump 推翻。
- 取值来源（9.6.13 实证）：
  - `rqRefer` / `songRqRefer`：调用场景传入的播放列表引用链，可为空串。
  - `mode` / `subMode`：场景名。已观测值：相似歌曲列表 `mode=SIMILAR`、AI DJ `mode=aidj`、
    私人 FM 取 `FMModeScene.getMode()/getSubMode()`；`subMode` 各路径均为空串。
    Ncrust 没有这些场景，按空串发，**不臆造场景名**。
  - `source`：官方规则「调用方没给 point 就发 `userfm`」，Ncrust 照此发 `userfm`。
  - `userActionMap`：可选，官方仅在非空时追加；Ncrust 不发。
- `checkToken` 放在体里（官方 `securityGetToken` 原生 token），同时发 `X-antiCheatToken` 头。
- 成功判定与官方代码一致：**`code == 200` 或 `502`**（502 = 歌曲已在期望状态，官方随后照常
  读 `playlistId`）。`505` 在官方是独立分支（写 PageValue），不算成功。
- 随后照旧对红心歌单做读回校验。

> **`checkToken` 必须进体**（2026-10-16 两端官方包实证）。REA 反编译 Android 9.6.08：
> `NeteaseMusicApiImpl.Z6` 构造 `v1/radio/like` 的业务 map 时含 `checkToken = vz0.b.G0()`
> （原生 `securityGetToken`），最终打到 `/eapi/v1/radio/like`。Windows 3.1.40 的
> `orpheus.ntpk`（webpack `app.chunk.js`）请求拦截器对命中 businessId 的写入执行
> `Object.assign(a,{checkToken:e})` **且** `headers["X-antiCheatToken"]=e`——同一 token 两处都发。
> 只发 `X-antiCheatToken` 头、体里没有 `checkToken` 的写入，会被服务端判为异常环境、事后回滚
> （即「收藏隔天消失」）。
>
> **2026-10-25 复核（Android 9.6.13，应用宝原包，签名 `CN=LiangJian, OU=Corp.Netease,
> O=CloudMusic` 已验）**：`dexdump -d classes14.dex` → `com.netease.cloudmusic.api.impl.
> NeteaseMusicApiImpl.b7` 的字段 assembly 与上文一致，且确认了完整字段集与 200/502 判定；
> 调用点（classes18.dex，接口 `w6/l.z5`）给出 mode/subMode 的实观测值。上面「字段顺序固定、
> 换顺序就是另一种指纹」的旧结论作废。


### 为什么收藏要走 xeapi（2026-10-15 调研）

- 现象：经 Ncrust（weapi `/api/radio/like`，`time=3`）点亮的红心当天在官方与两端都可见，隔天消失。
  de193ff / 25f0c39 把 `time` 从时间戳改成 `3` 后**仍复现**，说明 `time` 不是（唯一）诱因。
- 服务端对老通道确有「事后取消喜欢」的机制：gmg137/netease-cloud-music-gtk#410 实测
  `/weapi/radio/like` 对部分歌曲直接返回 `524「当前环境异常，已取消喜欢」`，而官方客户端已改用新接口；
  该项目改走 xeapi `/api/v1/radio/like` 后修复。
- 同类「服务端先收下、事后丢弃」在播放上报上已被实测（api-enhanced#167：weapi/clientlogusf 返回
  200 但不入账）—— 网易对非官方指纹的写入做静默/延迟处理是一贯手法。
- 结论（2026-10-16 更新）：老通道写入被风控判为异常环境，例行审核时回滚。修复方向是让写入与官方
  客户端同形——xeapi + 持久 deviceId + 易盾 token，**且易盾 token 必须同时进加密体的 `checkToken`**
  （见 §6 实证；1.4.0 只发了 `X-antiCheatToken` 头，漏了体里的 `checkToken`）。对照实验：
  `spec/tools/like-experiment.mjs`。
