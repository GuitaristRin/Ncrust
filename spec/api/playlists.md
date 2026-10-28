# 歌单接口与证据边界

2026-10-28。线上实测仅有匿名读取，没有创建、编辑、删除或收藏账号歌单。安卓已接入实验管理界面与读后确认协调层；[交互约定](../../docs/playlists/interaction.md)与[实施验收](../../docs/playlists/implementation.md)分别描述行为及证据，不能把离线测试当作真实写入可用的证明。

实施范围已于同日由负责人确认：安卓接入新建、名称/简介编辑、公开/私密设置、添加/移除歌曲及单个删除，并采用歌单上下文播放。编码与模拟器验收不包含操作真实账号；未完成真实写入验收前，管理入口经设置中的显式实验开关启用，缺省关闭。所有写入绑定发起时的账号凭证，成功提示以读回结果为准，网络结果未知时只提供重新读取。

播放接入契约：点歌按原歌单顺序建立去重快照并定位所选 ID，不旋转列表；普通歌单不继承私人 FM 的标记和 INFINITY 模式。单曲循环区分自然结束与手动下一首；顺序一次的队尾保持真实末曲；随机的预载、自然衔接和手动切换使用同一个已准备的下一轮排列，多曲时新轮首曲不等于上轮末曲。云端歌单编辑不改写已建立的播放快照。当前 Android 已接入，其他平台尚待按各自任务验收。

## 请求发到哪里

Android 直接连接网易云，歌单 eapi 主机为 `https://interface.music.163.com`。请求为 POST 表单 `params=<eapi 密文>`，不是把 JSON 明文 POST 到接口，也不经过本仓 `ncrust-api/`。加密路径把 `/eapi/` 换成 `/api/` 后参与签名。

对应入口：`app/.../network/RetrofitClient.kt:eapiPost`；管理读取在 `playlist/PlaylistReadApi.kt`，曲目详情补拉在 `network/PlaylistApi.kt`，写传输在 `PlaylistEditApi.kt`。阅读上游 `playlist/create` 一类路由时，应区分代理服务的入口名、远端业务路径和 weapi/eapi 加密方式，三者不是同一个 URL。

| 操作 | 远端 eapi 路径 | 关键参数 | 本轮证据 |
|---|---|---|---|
| 当前登录账号 | `/eapi/w/nuser/account/get` | 登录 cookie | 现有代码；本轮未使用登录态 |
| 用户歌单列表 | `/eapi/user/playlist` | `uid,limit,offset,includeVideo=false` | 公开用户匿名读成功；不证明可读私密歌单 |
| 歌单快照 | `/eapi/v6/playlist/detail` | `id,n,s=0` | 公开歌单匿名读成功 |
| 补齐曲目详情 | `/eapi/v3/song/detail` | `c`，如 `[{"id":123}]` 的 JSON 串 | 匿名读成功；现有代码每批至多 500 个 |
| 新建歌单 | `/eapi/playlist/create` | `name,privacy=0/10,type=NORMAL,subType=NORMAL` | 旧正典与封装；本轮未写入验证 |
| 增删曲目 | `/eapi/playlist/manipulate/tracks` | `pid,op=add/del,trackIds` JSON 串，`imme=true` | 旧正典与封装；本轮未写入验证 |
| 修改名称 | `/eapi/playlist/update/name` | `id,name` | 同上 |
| 修改简介 | `/eapi/playlist/desc/update` | `id,desc` | 同上 |
| 修改隐私 | `/eapi/playlist/update/privacy` | `id,privacy=0/10` | 同上 |
| 单个删除 | `/eapi/playlist/delete` | `pid` | 同上；客户端须先确认归属 |
| 批量删除 | `/eapi/playlist/remove` | `ids` JSON 串 | 旧正典记录存在无效果的 200；首批不接 |

登录态与客户端身份、csrf 的既有约束见 [endpoints.md](endpoints.md#eapi--歌单写操作api_url需客户端身份-cookie)。本轮没有证明现有 PC 身份写路径的长期持久性，不把匿名读取成功当作写接口成功。

## 本轮匿名实测

来源：`node spec/tools/probe-playlists.mjs 3778678 <输出路径>`，使用进程内随机 PC 身份，不读取 cookie 文件、环境中的登录态或设备账号。该脚本只有三个读路径的白名单，虽然传输方法是 POST，业务全部为读取。

脱敏数量摘要：[anonymous-read-2026-10-28.json](../../docs/playlists/evidence/anonymous-read-2026-10-28.json)。不含账号标识、cookie、歌曲名称、创作者资料或完整服务端响应。

| 检查 | 观察 |
|---|---|
| 200 首公开歌单，n=1 | `tracks=1`，`trackIds=200`，`trackCount=200` |
| 同歌单，n=1000 / 100000 | 两次均 `tracks=200`、`trackIds=200`，ID 顺序摘要与 n=1 一致 |
| 从该快照取一首补拉详情 | HTTP 200、业务码 200，返回了所请求的歌曲 |
| 公开用户歌单，limit=2、offset=0/2/4 | 三页分别返回 3/2/2 条；第一页含 1 个特殊歌单，各页没有重复 |

这组样本没有超过 1000 首，不能据此推断大歌单的返回上限或所有账号的权限表现。`s` 不作为歌曲偏移；曲目详情通过 `trackIds` 补拉。

分页应按请求的 `limit` 推进 offset，不能按过滤后的可见项数量推进，也不能按第一页附带特殊歌单后的原始条数推进。本轮样本证明的是 0/2/4 的页序列；实现还需按 ID 去重，并在 `more=false`、空页或页面无新 ID 时有界结束，不把单页显示当成完整歌单列表。

## 完整快照的判定

`tracks` 是元数据集合，可能截断；`trackIds` 是服务端声明的歌曲顺序。只有满足以下条件时，当前响应才可被标为完整 ID 快照：

1. `trackIds` 是数组，所有 ID 都为有效正整数且不重复。
2. `trackCount` 存在且非负，等于 ID 数量；明确的空数组配合 count=0 可视为空歌单。
3. 请求与响应仍对应同一账号、歌单和本次操作，旧响应不能覆盖新版本状态。

满足这些条件只确认当次响应的 ID 集合，不保证下一次读取不会被其他设备修改。缺条件时显示「内容未加载完整」，不能据此确认不存在、清空歌单、云端重排或执行全量覆盖。不能从 `tracks.size` 小推断账号只有这些歌，也不能只因大于 1000 就断言 ID 一定不完整。

## 写操作的确认闭环与证据边界

HTTP 200 本身不足以确认成功；非成功 HTTP 也不能因 body.code=200 就放行。需要解析业务码，保留操作级结果，再用读取确认目标状态。

- 添加：只在 `add` 动作下解释 502 的重复语义；批量 502 不能证明本批所有歌曲都已加入。读回逐个检查目标 ID。
- 移除：读回确认目标 ID 不再存在；读取不完整时为「待确认」，不是「已移除」。
- 新建：获得新 ID 后读取其元数据；添加失败不自动删除新歌单，也不重放创建。
- 元数据编辑：读取目标字段；多个字段分别提交时保留各项结果，不能假设事务原子性。
- 删除：要求有效当前用户 ID、本人作者、普通歌单且非 subscribed；确认后写入。详情读取 404 不能单独证明删除成功，仍需完整的本人歌单列表与账号上下文。
- 405、301、权限错误或网络结果未知时，不换身份绕限流、不换未知写通道、不自动重放。保留输入，读回或等待用户决定。
- 本轮没有执行上述写入及读回闭环；不能将它们标为已线上验证。

## 还不能标为正确可用的扩展

以下只来自 [api-enhanced 固定提交](https://github.com/neteasecloudmusicapienhanced/api-enhanced/tree/984cc021b8598cf94c22134f32a8426887fb2de7/module)，属于参考源码证据：

- `song_order_update.js`：`/api/playlist/manipulate/tracks` + `op=update`，与旧正典的非 add 风险记录冲突；不开放云端重排。
- `playlist_order_update.js`：weapi `/api/playlist/order/update` + `ids`，只改变歌单列表顺序。
- `playlist_subscribe.js`：eapi `/api/playlist/subscribe` / `unsubscribe`，订阅有 checkToken 配置；不等于红心收藏歌曲。
- `playlist_cover_update.js`：先上传，再 weapi `/api/playlist/cover/update` + `id,coverImgId`；尚无本项目验证。
- 上游创建歌单默认走 weapi，删除使用 weapi `playlist/remove`；本项目旧封装采用 eapi 创建和单个 `playlist/delete`。通道不同不自动判定哪一个错误；真实写入核验前不替换现有封装。
