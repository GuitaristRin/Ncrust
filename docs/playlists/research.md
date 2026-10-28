# 歌单管理调研

2026-10-28。范围：安卓现状、协议证据、交互方案与接入任务。尚未接入歌单管理界面，未操作真实账号歌单。

补充进展：本轮已完成匿名只读探针，读取结果与主机/路径边界见 [spec/api/playlists.md](../../spec/api/playlists.md)；交互、点歌的两种选择、播放循环与写入反馈闭环见 [interaction.md](interaction.md)。本轮范围经负责人确认仅为方案与只读核查，不写账号数据。

## 证据与可信边界

- 当前协议依据为 [端点正典](../../spec/api/endpoints.md#eapi--歌单写操作api_url需客户端身份-cookie)。
- 安卓已有 `PlaylistEditApi`、`PlaylistWriteGate` 与 `PlaylistEditApiTest`，由 `bfba8b9` 引入；全仓搜索未发现生产调用者。
- `PlaylistApi.getPlaylistDetail` 已按 `trackIds` 补拉缺失详情并恢复顺序，不能把 `tracks.size` 当作歌单全量。
- 本轮通过 GitHub API 阅读上游 [api-enhanced](https://github.com/neteasecloudmusicapienhanced/api-enhanced/tree/984cc021b8598cf94c22134f32a8426887fb2de7/module) 的固定提交 `984cc021b8598cf94c22134f32a8426887fb2de7`。上游源码只能证明参考实现，不能替代网易云服务端实测。
- 现有单测锁定请求形态和响应映射；不证明真实写入、持久性或隔日无回滚。本轮没有新的登录态写入实测。

## 能力与缺口

| 能力 | 安卓现状 | 接入前要求 |
|---|---|---|
| 用户歌单列表、详情、全量曲目 | 已有读接口与浏览页 | 区分自建/收藏，详情携带归属、特殊类型、隐私与版本 |
| 新建公开/私密歌单 | `createPlaylist` 已有，未接界面 | 返回有类型的结果，支持新建后添加歌曲 |
| 改名、简介、隐私 | 写函数已有，未接界面 | 校验输入、读回确认、刷新列表和详情 |
| 单个删除歌单 | `deletePlaylist` 已有，未接界面 | 限本人普通歌单；展示名称和曲目数，确认后执行 |
| 添加/移除歌曲 | 已有去重、每批 500、串行闸门 | 表达部分成功，不把重复响应当作整批全部成功 |
| 批量删除歌单 | `removePlaylists` 已有 | 旧正典记录非本人可能回 200 但无效果，首批不开放 |
| 曲目排序 | 安卓未有 | 上游候选 `op=update`；与现有正典冲突，须先验证 |
| 收藏/取消收藏他人歌单 | 安卓未有写接口 | 上游要求 eapi 与 checkToken；先验证身份与写后持久性 |
| 修改封面 | 安卓未有 | 先完成上传链路和封面绑定验证，另行拆任务 |
| 搜索歌单内歌曲、本地排序 | 未接界面 | 可基于已确认完整的快照；本地排序不得冒充云端重排 |
| 文件夹、马赛克封面、推荐曲目 | 未接界面 | 不假定服务端有对应能力，延后立项 |

## 新发现与待确认协议

1. **排序不能复用现有增删函数。** 上游 [song_order_update.js](https://github.com/neteasecloudmusicapienhanced/api-enhanced/blob/984cc021b8598cf94c22134f32a8426887fb2de7/module/song_order_update.js) 发往 `/api/playlist/manipulate/tracks`，字段为 `pid`、`trackIds` 和 `op=update`。现有正典记载「任何非 add 值都可能删除」，故这只是待验证候选，不能照抄接线。未来单独构造固定排序操作，不放宽 add/del 白名单。
2. **歌单列表排序是另一个接口。** [playlist_order_update.js](https://github.com/neteasecloudmusicapienhanced/api-enhanced/blob/984cc021b8598cf94c22134f32a8426887fb2de7/module/playlist_order_update.js) 通过 weapi `/api/playlist/order/update` 发送 `ids`，不等同于歌单内部曲目顺序。
3. **收藏歌单也有风控字段。** [playlist_subscribe.js](https://github.com/neteasecloudmusicapienhanced/api-enhanced/blob/984cc021b8598cf94c22134f32a8426887fb2de7/module/playlist_subscribe.js) 使用 eapi `/api/playlist/subscribe` 或 `/api/playlist/unsubscribe`，订阅分支带 checkToken 并启用 token 选项。不能从「收藏歌曲」的既有修复推断歌单收藏通道已验证。
4. **封面涉及上传后绑定。** [playlist_cover_update.js](https://github.com/neteasecloudmusicapienhanced/api-enhanced/blob/984cc021b8598cf94c22134f32a8426887fb2de7/module/playlist_cover_update.js) 先上传，再以 weapi `/api/playlist/cover/update` 提交 `id`、`coverImgId`。首批管理不包含该链路。
5. **大歌单完整性仍需测量。** 旧正典写 n 上限 1000、无法可靠判重；安卓则会使用完整 `trackIds` 补拉，且上游 [playlist_detail.js](https://github.com/neteasecloudmusicapienhanced/api-enhanced/blob/984cc021b8598cf94c22134f32a8426887fb2de7/module/playlist_detail.js) 请求 `n=100000`。这些不证明服务端无限量返回。应记录 `trackCount`、`trackIds.size`、唯一 ID 数、`tracks.size`，确认完整后才做本地判重。

以上候选暂不写入已验证端点表。真实探针通过后先更新 spec，再修改各端实现。

## 现有写层需要补齐的契约

- `addSongs` / `removeSongs` 某批失败直接返回 null，之前各批可能已成功。UI 需要成功批次、失败批次与待处理 ID，不可显示「全部失败」后无差别重放。
- `count` 缺失或重复响应时返回 0，0 不能代表空歌单或确切的最终曲目数。计数未知应单独表达，随后读回。
- `lastError` 为全局可变状态，成功不清空，且多请求可能覆盖。界面接入前改为每次操作携带结果，避免展示另一操作的错误。
- `parseWriteResponse` 当前对所有动作统一把 502 映射 Duplicate，通用 `isSuccess` 也接受它。应按动作区分：只有已验证的添加幂等语义可接受，其他操作不能推定成功。
- `ContentCache` 只有歌曲详情缓存，没有按歌单失效函数；列表数据和详情标题也没有统一刷新源。必须补按 ID 失效、元数据刷新以及变更事件。
- `isOwnedBy` 现只判断作者和 subscribed，未排除 `specialType != 0` 或未知/零用户 ID。管理入口还须验证有效登录用户与普通歌单类型，后端仍是最终权限裁判。
- 取消只停止后续批次；发出的写请求结果不明时先读回，再决定是否重试。网络失败与 405 不自动重放写入。

## 交互方案（待确认）

```text
歌曲菜单 → 添加到歌单
  ├─ 新建歌单 → 名称 + 公开/私密 → 创建 → 加入所选曲目
  └─ 本人普通歌单列表 → 选择 → 写入 → 读回 → 显示结果

音乐库 → 歌单
  ├─ 自建 → 新建 / 详情
  └─ 收藏 → 浏览详情（管理入口待订阅接口验证）

本人普通歌单详情 → 管理
  ├─ 修改名称 / 简介 / 隐私
  ├─ 选择曲目 → 从本歌单移除（不影响红心或其他歌单）
  └─ 删除歌单 → 确认名称与影响 → 删除 → 刷新音乐库
```

表单和确认使用 Kanesumi 控件，所有新增文案同步八种语言。播放器中的红心仍表示「我喜欢」，添加到普通歌单使用单独入口。写入中禁用重复提交；失败保留输入。批量部分成功明确显示已完成与未完成数量。最近使用歌单可在本地排序，不能改变网易云的歌单顺序。

## 首批任务卡（待确认后实施）

| 项目 | 约束 |
|---|---|
| 目标 | 安卓支持新建、添加歌曲、修改元数据、移除歌曲和单个删除；保留现有收藏与播放行为 |
| 真值 | `spec/api/endpoints.md`、经确认的匿名响应摘要、专用测试歌单的写后读回；单测不替代线上证据 |
| 参考 | 本文交互流程、现有 SongMenuSheet / LibraryScreen / PlaylistDetailScreen、Kanesumi 正典 |
| 阶段 P0 | 完整性、权限、批量部分成功、动作相关结果与缓存失效契约先落 spec |
| 阶段 P1 | typed result 与读回协调层；有协议与状态回归测试，再接统一添加入口和新建 |
| 阶段 P2 | 自建/收藏分组、详情管理、元数据编辑、移除与删除确认、八语言 |
| 验收 | 定向 JVM 测试、Debug 构建、深浅主题/窄宽布局截图；测试歌单写后/重登/隔日读回 |
| 止损 | 协议冲突、权限不明、完整性不足、写后回滚或 405 时停止该动作，不回落到未知写通道 |

真实账号实验只针对负责人明确指定或允许创建的测试歌单；不选择用户已有歌单作删除、重排或风控试验。Windows 的管理接线另行拆分；本轮未改 Windows。
