# spec/ —— 跨平台标准答案

Ncrust 各平台线（Android `app/`、Windows `windows/`、Lumia 原型 `lumia/`、Android 4 计划 `walkman/`、以后的 Rust/Kanesumi Linux）**不共享实现代码，
共享答案**。本目录存放与语言无关的规格与测试夹具：协议怎么调、加密该出什么 hex、
音质阶梯怎么降、队列操作后索引落在哪、LRC 怎么解析、设计 token 取什么值。

> 为什么不做共享核心库：这层逻辑量小（Android 侧约三四千行）但随 NetEase 接口频繁变动，
> FFI 边界的固定成本（async、错误类型、绑定生成、UWP 下的原生库限制）不划算。
> 真正要防的是「同一个坑三端各踩一次」与「一端修了另一端忘了」—— 共享夹具正好防这个。

## 规则

1. **先改 spec，再改实现。** 协议或业务行为的变更，先更新这里的文档/夹具，再改各端代码。
   能同一个 commit 改完两端最好；只改了一端时，commit body 写明另一端的跟进状态。
2. **各端测试直接读本目录**（相对路径引用），**不复制夹具**。复制即漂移。
3. **夹具是数据不是代码**：JSON，UTF-8，每个文件带 `description`（这条用例在防什么）
   与 `source`（依据：哪段实现、哪个 commit、哪次实测）。
4. **spec 与某端实现冲突时**：以实测正确的一方为准修正另一方（可能是 spec 本身），
   并在 commit 里说明判断依据。不要为了让测试变绿去改夹具。
5. `design/tokens.json` 的值**只收录已在某端代码中落地的取值**，并注明来源。
   某端独有的取值（如 Windows 播放栏 72px）写在该端自己的文档里，不进 token。

## 目录与状态

| 路径 | 内容 | 状态 |
|---|---|---|
| `design/tokens.json` | 颜色（6 强调色 × 明/暗）、字号、缓动曲线、动画时长、布局常量 | ✅ 已落地（取自 Android + Kanesumi-sec-a） |
| `api/endpoints.md` | 端点目录：路径、加密方式（REST / eapi / weapi）、参数、所用 host | ✅ 已落地（Windows M1，覆盖 Android 现行端点） |
| `api/playlists.md` | 歌单主机/路径、匿名读取证据、分页与完整性判定、写操作待验证边界 | Android 实验入口已接入；fake-gateway 与模拟器验收记录见 [实施说明](../docs/playlists/implementation.md)，真实账号写入仍待验证 |
| `fixtures/crypto/` | eapi / weapi / xeapi 固定输入 → 固定输出（随机量全部固定以保证可复现） | Windows 已接入三组夹具；Android 已接入 xeapi，eapi/weapi 夹具测试待补 |
| `api/xeapi.md` | 官方 Android 9.x 请求加密层 + 收藏新通道 `/api/v1/radio/like` | 两端已接入实现与 xeapi 夹具测试；真实账号收藏持久性仍待验证 |
| `fixtures/update/` | 应用内更新：跨班次按资产名找严格更高版本、忽略 draft / prerelease、资产名与扩展名校验 | ✅ 两端消费同一份夹具，prerelease 与扩展名的历史差异已消除 |
| `api/entitlement.md` + `fixtures/entitlement/` | 登录失效 / VIP / 试听 / 无版权 / 音质受限的判定规则与界面要求，及三组判定夹具 | 两端实现及三组夹具测试已接入；真机与真实账号验收仍待完成 |
| `tools/` | 零依赖 Node 参考实现与调研脚本：`xeapi.mjs`、`gen-xeapi-fixtures.mjs`、`probe-xeapi.mjs`（匿名探针）、`like-experiment.mjs`（收藏回滚对照实验，**会改账号数据，须账号主人同意**） | 📝 |
| `tools/probe-playlists.mjs` + `.test.mjs` | 白名单匿名只读探针；`node --test spec/tools/probe-playlists.test.mjs` 校验读写边界与完整性摘要 | ✅ 4 项离线测试；公开样本 7 次读取通过，不证明账号写入 |
| `fixtures/quality/` | 7 级音质阶梯、各起点的降级序列、FLAC 门控规则 | ✅ 已落地（Windows Core + 测试） |
| `fixtures/queue/` | 队列操作用例：初始队列 + 模式 + 操作 → 期望队列与索引 | Windows 全量接入；Android 已接入 remove 用例，其余操作夹具待补 |
| `fixtures/lrc/` | LRC 时间戳解析、`tlyric` 双语合并用例 | Windows 全量接入；Android 已接入 parse 用例，merge 待补 |

消费方：

| 端 | 测试位置 | 接入状态 |
|---|---|---|
| Windows | `windows/tests/Ncrust.Core.Tests`（net9.0 + xUnit） | crypto、quality、queue、lrc、update、entitlement |
| Android | `app/src/test`（JUnit） | xeapi、entitlement、update、queue 的 remove、lrc 的 parse；其余夹具待补 |

## 夹具格式约定

每个用例一个 JSON 对象；同一主题的用例放在同一文件的数组里。公共字段：

```json
{
  "id": "queue.insert-next.dedupe-before-current",
  "description": "这条用例在防什么（一句话）",
  "source": "依据：代码位置 / commit / 实测记录",
  "input": { },
  "expect": { }
}
```

- `id` 全局唯一，测试失败信息里直接打印它。
- `input` / `expect` 的形状按主题各自定义，在该子目录的 `README.md` 里写明 schema。
- 队列用例的语义以 Android `MainScreen` 的队列函数为基线：逐条对照
  `insertNext` / `appendToQueue` / `playSongItem` / `insertAllNext` / `appendAllToQueue` /
  `moveInQueue` 等，并覆盖关键不变量 —— `queue[current]` 必须始终是正在播放的歌
  （去重时先记下当前歌 id，过滤后重新定位索引）。
- **已修正的历史偏差**：Android `removeFromQueue` 曾只做 clamp，删除当前曲之前的歌后索引
  会错位。现已由 `PlaybackQueueLogic.removeAt` 按歌曲身份重新定位，
  `PlaybackQueueSpecFixtureTest` 直接读取共享 remove 用例；细节见 `fixtures/queue/README.md`。
