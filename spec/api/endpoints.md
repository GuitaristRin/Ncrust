# api/endpoints.md —— 端点目录

各端共用的接口清单：路径、加密方式、所用 host、参数。来源是 Android 现行实现
（`RetrofitClient` / `NcmApi` / `PlaylistApi`）。改协议先改这里，再改各端。

主机常量（Android `RetrofitClient.kt`）：

| 名称 | 值 | 用途 |
|---|---|---|
| `BASE_URL` | `https://music.163.com` | REST、weapi |
| `API_URL` | `https://interface.music.163.com` | eapi 默认域 |
| `INTERFACE_URL` | `https://interface3.music.163.com` | eapi 取歌曲 URL（`useInterface = true`） |
| weblog | `https://clientlogusf.music.163.com` | 播放上报 |

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
| `/eapi/radio/like` | `alg=itembased`,`trackId`,`like`,`time`,`e_r=TRUE`,`csrf_token` | 喜欢 / 取消喜欢（**官方客户端指纹**，见下） |

**官方客户端指纹**（`EapiPostOfficial`，用于 `/eapi/radio/like`）：iOS UA；设备字段
`os=iphone`,`appver=8.9.60`,`deviceId`,`osver`,`versioncode=140`,`mobilename`,`buildver`,
`resolution=1920x1080`,`__csrf`,`channel=yykj`,`requestId` 以 URL-encoded Cookie 形式发送，
同时整体作为 `header` 字段写进加密 body。写接口在 `BASE_URL` 之外的 host 上可能被判
`-460 风险`，故固定打 `API_URL`。

## weapi（`BASE_URL`，表单 `params` + `encSecKey`）

路径 `/api/…` 会改写成 `/weapi/…`；payload 自动注入 `csrf_token`（来自 Cookie 的 `__csrf`）。

| 路径 | 参数 | 用途 |
|---|---|---|
| `/api/login/qrcode/unikey` | `type=1`,`noCheckToken=true` | 申请二维码 key |
| `/api/login/qrcode/client/login` | `type=1`,`noCheckToken=true`,`key`；额外 Cookie `os=pc; NMTID=<hex16>; sDeviceId=<52 hex>` | 轮询扫码状态（800/801/802/803），803 的 Set-Cookie 里取 `MUSIC_U` |
| `/api/discovery/simiSong` | `songid`,`limit`,`offset` | 相似歌曲回退路径 |

## weblog（`https://clientlogusf.music.163.com`）

`POST /api/feedback/weblog?csrf_token=<csrf>`，表单 `logs=<JSON 数组>`（不含 eapi/weapi 加密）。
字段与官方 web 播放器一致，见 `Ncrust.Core.Playback.PlayReport`。
