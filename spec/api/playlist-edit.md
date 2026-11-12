# 歌单编辑：官方 9.6.22 实证

> 来源：应用宝 9.6.22 原包（SHA-256 `A0040599…D6BB7`）jadx 反编译。类文件分散在三份 dump：
> `NeteaseMusicApiImpl`（`classes14`）、`y22`/`y32`/`by0`（`classes4` 与主 dump）、
> `q42/a.java` + `s32/i.java`（`classes4`）。下面每条都标了是**实证**还是**推断**。

## 增 / 删曲目 —— `v1/playlist/manipulate/tracks`（实证）

- **POST**，表单提交；调用点 `NeteaseMusicApiImpl.q0(...)`。
- 字段：

  | 字段 | 值 |
  |---|---|
  | `op` | 字符串 `"add"` / `"del"`（**不是数字**） |
  | `pid` | 歌单 id |
  | `trackIds` | **字符串**，内容是 JSON 数组：`"[\"123\",\"456\"]"`；元素可写成 `id_extraId` |
  | `imme` | 布尔字符串 |
  | `ex` | 布尔字符串 |
  | `checkToken` | `securityGetToken()` 原生易盾 token |
  | `reverse` | 仅 `add` 时发，恒 `"true"` |
  | `userActionMap` | 可选，非空才发 |

- **没有 `userId` 字段**（有界否定）。也没有任何 `position` / `index` / `seq` 字段。
- 成功判定：**只有 `code == 200`**。其余码映射成 `state` 串：`512→-10`、`506→-8`、`511→-7`、
  `515→-9`、`404→-3`、`502→-2`、`505→-4`、`400→-5`、`401→-6`、`425→-14`、`514→-11`、
  `518→-12`、`521→-15`、`410→-17`；`500` 抛异常。这些码到中文文案的消费者不在 dump 里，
  「非本人 / 歌单满 / 已存在」的对应关系属**推断**。
- 写后不再发 GET：**响应体自带的 `trackIds` 就是权威读回**，客户端按它算 `state`。

## 排序 —— 同一条 `manipulate/tracks` 的 `op="update"`（实证 + 推断）

- **没有独立的曲目排序接口**（有界否定）：26 个 playlist 端点里没有 order / move / position。
  `playlist/order/update` 排的是**用户歌单列表**（字段 `ids` + `self`），不是歌单内曲目。
- 官方 `NeteaseMusicApiImpl.I3(Collection<Long> collection, long pid)`（`classes14`）实证：

  | 项 | 值 |
  |---|---|
  | 路径 | **`playlist/manipulate/tracks`（不带 `v1`）** —— 与 `add`/`del` 同一方法族 |
  | `op` | `DAITaskInfo.ACTION_UPGRADE`，常量值为 **`"update"`** |
  | `trackIds` | `String.format("[%s]", join(collection, ","))` —— 集合是 `Long`，元素**不加引号** |
  | `imme` / `ex` | 硬编码 `"false"` / `"false"`（`add`/`del` 的 `imme` 由调用方给） |
  | `checkToken` | 有 |
  | 成功判定 | **200 / 514 / 518 都不报错**，其它码走 `ha(code)` |
  | 读回 | **没有**，`state` 与 `trackIds` 都不解析 |

- **推断**：`I3` 收的是整个 `Collection<Long>`，最符合的解释是「把歌单曲目设为这个集合，
  顺序即传入顺序」（整表替换）。它的调用方不在已 dump 的三份 dex 里（有界否定），
  因此这条语义没有读回证据。
- **客户端安全约束**（由上面的不确定性与「`op` 非 `add` 被当删除」的实测共同决定）：
  1. 只在内容完整时可用；
  2. 传入集合必须与当前曲目**完全同集**（同数量、同成员），绝不提交部分列表；
  3. 提交前落盘备份；
  4. 提交后一律重新读取并比对完整顺序，业务码只用来选失败文案，不能当成功依据。

## 权限与前置检查

- API 层**没有归属校验**，也没有 `playlist/subscribe` 之类的守卫；`isMyCreatePl()` 只用于解析响应。
- 唯一的写前调用是 `playlist/track/add/pre/check`（`ids`、`trackIds`、`containsPodcast`），
  原样返回 JSON 交由上层判断，策略在服务端。

## 删除歌单（实证）

- `playlist/delete/?pid=<id>`：**pid 在 query 里**，无 body、无 token；`200→true`，`518` 走错误提示。
- 相邻别混：`playlist/remove`（`ids` + `self`）是从「我的歌单」移出；`playlist/unsubscribe/?id=` 是取消收藏。

## 通道（实证）

- 调用点既没有强制加密、也没有强制不加密（`y32/f.java` 两个标志默认 false），因此歌单写入默认走
  **明文 `/eapi/`**；只有运行时开关 `network#aegisEncryptEnabled` 打开时才进入
  `q42/a.h()` 的 `/xeapi/` 加密信封。
- 易盾 token 有两条独立通道：**body `checkToken`**（`manipulate/tracks`、`create`、`desc/update`、
  `subscribe` 有；`update/name`、`order/update`、`delete`、`remove` 没有），以及
  **`X-antiCheatToken` 头**（按服务端下发的 `antiSpamUrl` 列表动态加，静态不可判定）。
- `I3`（`op="update"`）同样没有加密标志，走明文 `/eapi/`。
