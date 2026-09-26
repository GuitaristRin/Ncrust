# fixtures/quality —— 音质阶梯

7 级音质的降级顺序与 FLAC 门控规则，各端共用（见 `spec/README.md`）。

## schema

`quality.json` 是用例数组。公共字段 `id` / `description` / `source` / `input` / `expect`。
每条带 `kind`：

| kind | input | expect |
|---|---|---|
| `ladder` | `{ "level": "<请求档位>", "supportsFlac": true\|false }` | `{ "levels": ["<按顺序尝试的档位>"] }` |
| `retry` | `{ "level": "<当前实际档位>" }` | `{ "nextLevel": "<降一档>" \| null }` |
| `preference` | `{ "index": <偏好索引> }` | `{ "level": "<档位>" }` |

## 规则（与 Android `SongUrlFetcher` / `PlayerViewModel` 一致）

- 档位全集（偏好索引顺序）：`standard`(0) `higher`(1) `exhigh`(2) `lossless`(3)
  `hires`(4) `jyeffect`(5) `dolby`(6)。默认 Wi-Fi = 3，移动 = 1。
- 降级序列（请求档位 → 依次尝试）：
  - `dolby` → dolby, hires, lossless, exhigh, higher, standard
  - `jyeffect` → jyeffect, lossless, exhigh, higher, standard
  - `hires` → hires, lossless, exhigh, higher, standard
  - `lossless` → lossless, exhigh, higher, standard
  - `exhigh` → exhigh, higher, standard
  - `higher` → higher, standard
  - `standard` → standard
  - 未知档位（如服务端返回 `sky`）→ 该档位, lossless, exhigh, higher, standard
- **FLAC 门控**：设备解不了 FLAC 时，从序列里剔除 `lossless` / `hires` / `jyeffect`
  三档。`dolby` 用 mp4（EAC3）容器输出，不在剔除之列。
- **出错降一档**：播放解码失败时沿固定顺序 `dolby, jyeffect, hires, lossless, exhigh,
  higher, standard` 降一级；不在阶梯内的档位从 `lossless` 起降；已是 `standard` 返回 null
  （由上层跳歌）。注意这条顺序与上面的降级序列**不同**（这里 `jyeffect` 排在 `hires` 前）。
