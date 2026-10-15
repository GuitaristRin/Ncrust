# api/endpoints.md —— 端点目录

各端共用的接口清单：路径、加密方式、所用 host、参数。来源是 Android 现行实现
（`RetrofitClient` / `NcmApi` / `PlaylistApi`）。改协议先改这里，再改各端。

主机常量（Android `RetrofitClient.kt`）：

| 名称 | 值 | 用途 |
|---|---|---|
| `BASE_URL` | `https://music.163.com` | REST、weapi |
| `API_URL` | `https://interface.music.163.com` | eapi 默认域、xeapi 公钥接口 |
| `INTERFACE_URL` | `https://interface3.music.163.com` | eapi 取歌曲 URL（`useInterface = true`）、xeapi 业务请求 |
| `INTERFACE_PC_URL` | `https://interfacepc.music.163.com` | eapi + PC 客户端指纹（扫码登录，见下） |
| `CLIENT_LOG_URL` | `https://clientlog.music.163.com` | 播放上报（eapi + osx 身份，见末节） |
| clientlogusf（**废弃**） | `https://clientlogusf.music.163.com` | 旧播放上报通道：回 200 但不入账 |
| antiCheat | `https://ac.dun.163yun.com/v3/b?pn=YD00000558929251` | 易盾 token（xeapi 写入用，见下） |

通用头：PC UA、`Referer: https://music.163.com/`、`Cookie`（有登录态时）。写接口另有
官方客户端指纹变体（见下）。

## REST（`BASE_URL`，表单 POST 或 GET）

| 路径 | 方法 | 参数 | 用途 |
|---|---|---|---|
| `/api/cloudsearch/pc` | POST 表单 | `s`, `type`(1 歌曲 / 10 专辑 / 100 歌手), `limit` | 三类搜索 |
| `/api/v3/song/detail` | POST 表单 | `c` = `[{"id":N},…]` | 批量歌曲详情 |
| `/api/song/lyric` | POST 表单 | `id`, `cp=false`, `tv=-1`, `lv=-1`, `rv=0`, `kv=0`, `yv=0`, `ytv=0`, `yrv=0` | 歌词（`lrc` + `tlyric`） |
| `/api/v1/album/{id}` | GET | — | 专辑详情 |
| `/api/artist/detail/{id}` | GET | — | 歌手详情 |
| `/api/artist/albums/{id}` | GET | `limit`,`offset` | 歌手专辑 |
| `/api/v1/user/detail/{uid}` | GET | — | 用户公开信息 |
| `/api/personalized` | GET | `limit` | 推荐歌单（免登录） |
| `/api/album/new` | GET | `area`,`limit`,`offset` | 新碟上架 |
| `/api/v1/discovery/new/songs` | GET | `limit`,`offset` | 新歌速递（首页） |

## eapi（`API_URL`；取 URL 走 `INTERFACE_URL`）

POST 表单 `params=<hex>`；加密见 `fixtures/crypto`。

| 路径 | 参数 | 用途 |
|---|---|---|
| `/eapi/w/nuser/account/get` | — | 当前账号 / UID |
| `/eapi/user/playlist` | `uid`,`limit`,`offset`,`includeVideo=false` | 用户歌单 |
| `/eapi/v6/playlist/detail` | `id`,`n=1000`,`s=0` | 歌单详情（`trackIds` 全量 + 部分 `tracks`） |
| `/eapi/v3/song/detail` | `c` | 批量歌曲详情（eapi 版） |
| `/eapi/v1/artist/detail` | `id` | 歌手详情 |
| `/eapi/artist/albums` | `id`,`limit`,`offset` | 歌手专辑 |
| `/eapi/v2/discovery/recommend/songs` | — | 每日推荐 |
| `/eapi/v1/discovery/recommend/resource` | — | 推荐歌单 |
| `/eapi/v1/radio/get` | — | 私人 FM（`data[].song`） |
| `/eapi/radio/trash/add` | `songId`,`alg=itembased`,`time=25` | FM 不喜欢 |
| `/eapi/v1/discovery/similarSong` | `songid`,`limit`,`offset` | 相似歌曲（INFINITY） |
| `/eapi/song/enhance/player/url/v1` | `ids=[id]`,`level`,`header`(JSON),`encodeType`(dolby→`mp4`,否则`flac`) | 播放 URL（**host 用 `INTERFACE_URL`**） |
| `/eapi/album/sublist` | `limit`,`offset`,`total=true` | 收藏专辑 |
| `/eapi/album/sub` / `/eapi/album/unsub` | `id` | 收藏 / 取消收藏专辑 |
| `/eapi/radio/like` | `alg=itembased`,`trackId`,`like`,`time=3`,`e_r=TRUE`,`csrf_token` | 喜欢 / 取消喜欢（**回退通道**，官方客户端指纹，见下） |

**官方客户端指纹**（`EapiPostOfficial`，用于 `/eapi/radio/like`）：iOS UA；设备字段
`os=iphone`,`appver=8.9.60`,`deviceId`,`osver`,`versioncode=140`,`mobilename`,`buildver`,
`resolution=1920x1080`,`__csrf`,`channel=yykj`,`requestId` 以 URL-encoded Cookie 形式发送，
同时整体作为 `header` 字段写进加密 body。写接口在 `BASE_URL` 之外的 host 上可能被判
`-460 风险`，故固定打 `API_URL`。

> 收藏写入已改走 xeapi（见下节）。`/eapi/radio/like` 与 weapi 的 `/api/radio/like` 只作回退：
> 两者都是**老通道**，写入的收藏会被服务端事后回滚（「收藏隔天消失」，`spec/api/xeapi.md` 末节）。

### eapi · PC 客户端指纹（扫码登录，打 `https://interfacepc.music.163.com`）

扫码登录**不再走 weapi**：它用的是桌面客户端（PC）身份 + `type=3`，且 host 是 `interfacepc`。
UA 为桌面客户端（`Mozilla/5.0 (Windows NT 10.0; WOW64) … NeteaseMusicDesktop/3.1.29.205117`）；
设备字段 `osver=Microsoft-Windows-10-Professional-build-19045-64bit`,`deviceId`（复用 xeapi 的持久
设备 id，见下节）、`os=pc`,`appver=3.1.17.204416`,`versioncode=140`,`mobilename=`(空),
`buildver=<10 位秒级时间戳>`,`resolution=1920x1080`,`__csrf=`(空),`channel=netease`,
`requestId=<毫秒时间戳>_<4 位随机>` 按 `encodeURIComponent(k)=encodeURIComponent(v)`、以 `; ` 拼成请求
**Cookie**，同时整体作为 `header` 字段写进加密 body；body 另带 `e_r=false`（布尔，不是字符串）。

| 路径 | 参数 | 用途 |
|---|---|---|
| `/eapi/login/qrcode/unikey` | `type=3` | 申请二维码 key（响应 `unikey`） |
| `/eapi/login/qrcode/client/login` | `key`,`type=3` | 轮询扫码状态（800 过期 / 801 待扫 / 802 已扫待确认 / 803 成功），803 的 Set-Cookie 里取 `MUSIC_U`（同名取最后一个非空值） |

二维码内容：`https://music.163.com/login?codekey=<unikey>` —— **PC 形态，不带 `chainId`**
（`chainId` 是 web 登录会话的绑定字段，PC 身份不用它）。局域网交接协议（Android `QrPair`）只依赖
`codekey` 里的 unikey，因此不受这次改动影响。

> **依据（2026-10-15）**：weapi `/api/login/qrcode/unikey`（`type=1` + `noCheckToken=true`）实测返回
> **`code -462`**（风控校验），二维码根本出不来。参考实现
> `NeteaseCloudMusicApiEnhanced/api-enhanced` 的 `module/login_qr_key.js` / `module/login_qr_check.js`
> 已改为上面的 eapi + PC 身份 + `type=3`。本仓库的零依赖实测脚本 `spec/tools/qr-login.mjs`
> （逐项照该参考实现写）实测：unikey 返回 `code 200` 且二维码可生成；本轮实现即与它逐字段对照。

## xeapi（业务请求打 `INTERFACE_URL` 的 `/xeapi/…`，公钥打 `API_URL`）

官方 Android 9.x 的请求加密层，协议全文与算法见 `spec/api/xeapi.md`（夹具
`fixtures/crypto/xeapi.json`）。表单体 `C=<…>&S=<…>&R=<…>`，另有设备头
`X-Client-Enc-State: ENCRYPTED`、`x-aeapi`、`x-deviceid`/`x-sdeviceid`、`x-os`/`x-osver`/`x-appver`/`x-buildver`、
`x-music-u`、`X-antiCheatToken`，以及 Cookie 里追加的
`os=android; osver=16; appver=9.1.65; buildver=<10 位秒级时间戳>; deviceId=<32 hex>; sDeviceId=<同上>`。

| 路径 | 方法 | 参数 | 用途 |
|---|---|---|---|
| `/api/bsr/sk/get` | POST 明文表单（打 `API_URL`） | `appVersion=9.5.61`,`currentKeyVersion`,`deviceId`,`nonce`,`os=android`,`requestType=active`,`signature`,`t1=`,`t2=`,`timestamp`,`uid=` | 取公钥（`publicKey`/`version`/`sk`/`nextUpdateTime`），响应需验签 |
| `/api/v1/radio/like` | POST xeapi（打 `INTERFACE_URL`，实际路径 `/xeapi/v1/radio/like`） | `alg=itembased`,`trackId`,`like`,`time=3`；带 `X-antiCheatToken` | 喜欢 / 取消喜欢（**主通道**，`code == 200` 即成功） |

`deviceId` 每台设备生成一次（32 位小写 hex）后持久化，登出也不清；公钥状态落盘、按
`nextUpdateTime` 或业务解密失败刷新（刷新时带旧 `version`）；`x-encr-ssid`/`x-encr-sskey` 会话只存内存。

## weapi（`BASE_URL`，表单 `params` + `encSecKey`）

路径 `/api/…` 会改写成 `/weapi/…`；payload 自动注入 `csrf_token`（来自 Cookie 的 `__csrf`）。

| 路径 | 参数 | 用途 |
|---|---|---|
| `/api/discovery/simiSong` | `songid`,`limit`,`offset` | 相似歌曲回退路径 |
| `/api/radio/like` | `alg=itembased`,`trackId`,`like`,`time=3` | 喜欢 / 取消喜欢（**回退通道**，老通道，可能被服务端事后回滚） |

> 扫码登录的两行（`type=1` + `noCheckToken=true` 的 `/api/login/qrcode/unikey` 与
> `/api/login/qrcode/client/login`，轮询另带 `os=pc; NMTID=<hex16>; sDeviceId=<52 hex>`）已弃用：
> 实测 `-462`，改用上面 eapi 节的 PC 客户端指纹通道。

## 播放上报 / 听歌打卡（`CLIENT_LOG_URL`，eapi + osx 身份）

`POST https://clientlog.music.163.com/eapi/feedback/weblog`，**eapi 加密**（签名路径
`/api/feedback/weblog`；请求路径是 `/eapi/…`，`EapiCrypto` 内部换回 `/api/` 再签名）。
业务字段只有一个 `logs`（JSON **文本**），加密信封为
`{"logs":"<JSON 文本>","e_r":false,"header":{…}}` —— `e_r` 是布尔 `false`（参考实现
`util/config.json` 的 `encryptResponse = false`）。

**两次上报，缺一不可**（参考实现 `module/scrobble.js` 依次发两条）：

| 顺序 | `logs[0].action` | 作用 | `json` 字段（**顺序即参考实现写法**） |
|---|---|---|---|
| 1 | `startplay` | 进「最近播放」 | `id`,`type:"song"`,`mainsite:"1"`,`mainsiteWeb:"1"`,`content:"id=<sourceId>"` |
| 2 | `play` | 涨「听歌排行」计数 | `download:0`,`end:"playend"`,`id`,`sourceId`,`time`,`type:"song"`,`wifi`,`source:"list"`,`mainsite:"1"`,`mainsiteWeb:"1"`,`content:"id=<sourceId>"` |

- `id`：歌曲 id。`sourceId`：歌曲来源的歌单 / 专辑 id —— **不知道时用空串**（`content` 就是 `id=`），不要编。
- `time`：**播放秒数**（不是毫秒）。
- `wifi`：`0` = Wi-Fi，`1` = 计费网络。参考实现固定发 `0`（它的对照实验都在 Wi-Fi 下做），
  本仓库两端按实际网络发 0 / 1。
- 时机：每首歌**开始播放**时发 `startplay`（每首一次）；自然结束**或**进度 ≥ 80% 时发 `play`（每首一次）。
  **试听片段两条都不发**（`api/entitlement.md`：45 秒片段不该进「最近播放」与排行）。
- 仍然尽力而为：fire-and-forget，失败与未登录都不影响、不阻塞播放。

**身份：`os=osx`（决定性变量）** —— android / iphone / pc 身份的同名请求会被服务端丢弃。

- UA：`Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36`
- `header` 对象同时进加密 body 与请求 Cookie；作 Cookie 时按
  `encodeURIComponent(k)=encodeURIComponent(v)` 以 `; ` 拼接。字段与顺序（`util/request.js` eapi 分支的
  构造顺序）：`osver=15.5`、`deviceId`（复用 xeapi 的持久设备 id，一台设备一个）、`os=osx`、
  `appver=3.1.10.5100`、`versioncode=140`、`mobilename=`（空）、`buildver=<10 位秒级时间戳>`、
  `resolution=1920x1080`、`__csrf`（登录 Cookie 里的值）、`channel=netease`、
  `requestId=<毫秒时间戳>_<4 位随机>`，末尾按有无补 `MUSIC_U` / `MUSIC_A`。
- 请求头只有 `User-Agent` 与 `Cookie`（参考实现这条路径不带 `Referer`）；表单体是 `params=<hex>`。
- **与参考实现的已知差异**：参考实现在 eapi 头末尾还会补上它自己缓存到的 `NMTID`（来自某次 eapi
  响应的 Set-Cookie）。两端都不带这个字段 —— 上面这份字段清单里没有它，而它取决于一次额外的响应
  缓存；**去掉它是否影响入账没有实测**，若日后发现打卡不生效，这是第一个值得补回的字段。

**旧通道（已废弃，别再回去）**：`POST https://clientlogusf.music.163.com/api/feedback/weblog?csrf_token=…`，
明文表单 `logs=<JSON 数组>`（不含 eapi/weapi 加密）。2026 年社区对照实验
（[`NeteaseCloudMusicApiEnhanced/api-enhanced#167`](https://github.com/NeteaseCloudMusicApiEnhanced/api-enhanced/issues/167)，
维护者已据此在 4.35.2 修复）实测：**weapi/music.163.com、eapi/interface、clientlogusf 三条通道都回
HTTP 200 / `code 200`，但服务端静默丢弃，不入账**（「最近播放」「听歌排行」「每日推荐」都吃不到）。

**依据**（2026-10-15 逐字段核对参考实现）：`module/scrobble.js`（两次上报与全部字段）、
`util/request.js` 的 `case 'eapi'` 分支（header 构造与 `createHeaderCookie`、`cookie.os === 'osx'` 时
专用的 Mac Chrome UA、`data.header = header`）、`util/config.json`
（`clDomian = https://clientlog.music.163.com`、`encryptResponse = false`），以及 #167 的对照实验结论。

> **验证边界**：两端各自的单测锁的是「请求形态 = 上面这份规格」（URL、UA、Cookie、解密后的 logs 字段与
> 顺序）。**「服务端是否真的入账」没有在真实账号上验证过** —— 未登录 / 无效 cookie 的匿名探针只会拿到
> `code 200`（正是 #167 里那条假通道的表现），只能由账号主人在官方 App 的「最近播放 / 听歌排行」里核对。
