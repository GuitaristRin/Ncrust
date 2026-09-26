# spec/ —— 跨平台标准答案

Ncrust 的三个端（Android `app/`、Windows `windows/`、以后的 Linux）**不共享实现代码，
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
| `api/endpoints.md` | 端点目录：路径、加密方式（REST / eapi / weapi）、参数、所用 host | ⏳ Windows M1 |
| `fixtures/crypto/` | eapi / weapi 固定输入 → 固定输出（weapi 固定 16 位 secret 以保证可复现） | ✅ 已落地（Windows Core + 测试） |
| `fixtures/quality/` | 7 级音质阶梯、各起点的降级序列、FLAC 门控规则 | ⏳ Windows M1 |
| `fixtures/queue/` | 队列操作用例：初始队列 + 模式 + 操作 → 期望队列与索引 | ⏳ Windows M1 |
| `fixtures/lrc/` | LRC 时间戳解析、`tlyric` 双语合并用例 | ⏳ Windows M1 |

消费方：

| 端 | 测试位置 | 接入时间 |
|---|---|---|
| Windows | `windows/tests/Ncrust.Core.Tests`（net9.0 + xUnit） | M1，与 Core 同步编写 |
| Android | `app/src/test`（JUnit） | Windows M3 |

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
- 队列用例的语义**以 Android 现行实现为准**：编写第一批时逐条对照 `MainScreen`
  里的 `insertNext` / `appendToQueue` / `removeFromQueue` / `moveInQueue` 等函数，
  并覆盖 `AGENTS.md` 里的关键不变量 —— `playbackQueue[currentQueueIndex]` 必须始终是
  正在播放的歌（去重时先记下当前歌 id，过滤后重新定位索引）。
