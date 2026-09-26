# fixtures/lrc —— 歌词解析与双语合并

LRC 时间戳解析与 `tlyric` 合并的固定答案（见 `spec/README.md`）。

## schema

`lrc.json` 是用例数组，每条带 `kind`：

| kind | input | expect |
|---|---|---|
| `parse` | `{ "lrc": "<LRC 文本>" }` | `{ "lines": [ { "timeMs": <long>, "text": "<文本>" } ] }` |
| `merge` | `{ "lrc": "<原词>", "tlyric": "<翻译>" }` | `{ "lines": [ { "timeMs": <long>, "text": "<原句>", "translation": "<译文或空串>" } ] }` |

## 规则（与 Android `LrcParser.kt` / `LyricsView.kt` 一致）

- 时间戳格式 `[MM:SS.mm]` 或 `[MM:SS.mmm]`；**两位**毫秒按十毫秒解释（`03` → 30ms）。
- 每行只取**第一个**时间戳；同一行后面若还有 `[..]`，会原样进入歌词文本（Android
  `Regex.find` 只匹配一次的行为，夹具把它钉住，避免各端实现不一致）。
- 文本取匹配后的剩余部分并 `trim()`；空文本的行丢弃。
- 无时间戳的行忽略；输出按 `timeMs` 升序（稳定排序）。
- 合并：译文按 `timeMs` **精确**对齐原句（网易 tlyric 与原 lrc 时间戳一致）；
  原句没有对应时间戳的译文时，`translation` 为空串；译文里多出的孤立行忽略。
