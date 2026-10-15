# 账号与权益状态：让「网易的限制」看起来不像「Ncrust 的 bug」

## 问题

用户未开通黑胶 VIP、VIP 已过期、或登录已失效时，网易接口**大多不报错**，而是悄悄降级。客户端照单全收，
用户看到的就是「歌放到一半断了 / 自动跳歌 / 无损设置不生效 / 推荐变了 / 收藏刷不出来」，自然归咎于 Ncrust。

2026-10-15 实测（`spec/tools/probe-entitlement.mjs` 匿名；无效 cookie 用伪造的 `MUSIC_U`，未触碰任何真实账号）：

| 场景 | 接口 | 实际返回 | 用户若不被告知会以为 |
|---|---|---|---|
| VIP 歌，非会员 | `song/enhance/player/url/v1` | `code 200`、有 `url`，但 `freeTrialInfo:{start:0,end:45}`、`time:45035`、`level` 被降到 `standard` | 歌放 45 秒就断 / 跳歌 |
| 无版权 / 下架 | 同上 | `code 404`、`url null`；详情 `privilege.st = -200`、`freeTrialPrivilege.cannotListenReason = 1` | 点了没反应 / 被跳过 |
| 低音质免费（`fee 8`） | 同上 | 请求 `exhigh` 以上时只给到 `privilege.pl` 对应的档位 | 音质设置失效 |
| 登录失效 | `w/nuser/account/get` | `code 200`、`account:null`、`profile:null`（与未登录一模一样） | —— |
| 登录失效 | `v3/discovery/recommend/songs` | `code 200`，返回**非个性化**的通用列表 | 「每日推荐变了」 |
| 登录失效 | `song/like/get`、`music-vip-membership/client/vip/info` | `code 301`（「用户未登录」） | 收藏刷不出来 |

## 状态模型

### 会话（每端一个单例，界面订阅）

| 状态 | 判定 | 界面 |
|---|---|---|
| `LoggedOut` | 本地没有 cookie，且没有「曾登录」标记 | 现有「登录」入口 |
| `Valid(profile, vip)` | `account/get` 的 `account` 与 `profile` 都非空 | 正常；资料页显示 VIP 状态（见下） |
| `Expired` | 本地**有** cookie，但 `account/get` 返回 `account == null`，**或**任一需登录接口返回 `code 301` | 全局横幅「登录已失效，部分歌曲只能试听，推荐与收藏不可用 · 重新登录」；不清空本地收藏缓存 |
| `Unknown` | 网络失败 / 非 200 / 解析失败 | **维持上一个已知状态**，绝不因为网络抖动判失效 |

- 判定时机：启动时一次；回到前台且距上次判定 ≥ 30 分钟时一次；任一接口返回 301 时立即转 `Expired`（去抖：同一时刻只发一次 `account/get` 复核）。
- 转入 `Expired` 时清掉请求层里的失效 cookie（继续携带它没有意义），但保留「曾登录」标记，横幅文案据此区分「已失效」与「未登录」。重新登录成功后横幅消失。
- 首页「每日推荐」在非 `Valid` 时加一行说明「登录后为你推荐」，不把通用列表冒充为个性化推荐。

### VIP（`Valid` 时拉取，`weapi /api/music-vip-membership/client/vip/info`，`userId=<uid>`）

`data.associator`（黑胶 VIP）、`data.musicPackage`（音乐包）、`data.redplus`（黑胶 SVIP，可能缺省），各含 `vipCode` 与
`expireTime`（毫秒）；`data.now` 为服务端时间（缺省用本机时间）。某项有效 ⇔ `expireTime > now`。

| 派生 | 规则 | 资料/设置页文案 |
|---|---|---|
| `Active(kind, expireAt)` | 任一项有效；`kind` 取 SVIP > 黑胶 VIP > 音乐包 | 「黑胶 VIP · 有效期至 2026-12-01」 |
| `Lapsed(expireAt)` | 都无效，但最近一项 `expireTime > 0` | 「黑胶 VIP 已于 2026-09-01 过期 · VIP 歌曲仅可试听」 |
| `None` | 都无效且无记录 | 「未开通黑胶 VIP · VIP 歌曲仅可试听，最高音质受限」 |

接口失败（含 301）时 VIP 显示为未知，不显示「未开通」。

## 单曲播放结果分类（纯函数，三端一致；夹具 `spec/fixtures/entitlement/playback.json`）

输入：`url/v1` 返回的 `data[i]`（`url`、`code`、`level`、`fee`、`freeTrialInfo`、`freeTrialPrivilege`）、请求的档位、
（可选）歌曲详情的 `privilege`（`st`、`pl`、`fee`）、会话是否 `Valid`。依次判定：

1. `url` 非空且 `freeTrialInfo != null` → **`trial`**，`trialSeconds = end - start`；`reason`：`fee == 4` → `album`（需购买专辑），
   否则 `vip`；会话非 `Valid` 时 `reason = login`（登录后可能可以完整播放）。
2. `url` 非空 → **`full`**；若实际 `level` 低于请求档位（按 7 级阶梯比较）→ 附 `capped = true`、`cappedTo = <实际 level>`。
   会话非 `Valid` 时 `cappedReason = login`，否则 `vip`。**FLAC 门控造成的降级不算 `capped`**（那是设备原因，各端在调用前已知）。
3. `url` 为空 → **`unavailable`**，`reason`：`privilege.st < 0` → `copyright`；`fee == 4` → `album`；`fee == 1` → 会话 `Valid` ? `vip` : `login`；
   其余 → `unknown`。

### 界面要求

- `trial`：照常播放（与官方一致），但迷你条与全屏播放器的音质徽标位置显示「试听」，每首首次进入时提示一次：
  「试听 45 秒 · 需要黑胶 VIP」/「…需购买专辑」/「…登录后可能可完整播放」。试听结束按正常结束切下一首，**但不上报为完整播放**
  （不进 PlayReporter 的 80%/自然结束判定）。设置项「非会员遇到 VIP 歌曲」：播放试听片段（默认）/ 直接跳过。
- `full + capped`：音质徽标显示**实际**档位；同一会话内首次发生时提示一次「当前账号最高可播放「极高」」（`login` 时改为「登录后可获得更高音质」）。
- `unavailable`：跳到下一首时提示「已跳过《歌名》：暂无版权 / 需要黑胶 VIP / 需购买专辑 / 登录后可播放 / 暂时无法播放」，
  不再静默。连续多首不可播时合并为一条「已跳过 N 首无法播放的歌曲」。
- 提示文案走各端 i18n；不弹模态框，不打断播放。
