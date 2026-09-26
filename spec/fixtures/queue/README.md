# fixtures/queue —— 队列状态机

队列操作后「队列内容 + 当前索引」的标准答案（见 `spec/README.md`）。语义基线是 Android
`MainScreen` 的队列函数，**除 remove 外逐条对照**；remove 的偏差见文末。

## 关键不变量

`queue[current]` 恒等于正在播放的歌。任何去重操作必须先记下当前歌 id，过滤后**重新定位**
当前索引，不能只 `filter`。`SHUFFLE` 模式下每次修改都要重新生成打乱表。

## schema

`queue.json` 是用例数组，`kind` 固定为 `op`：

```
input:  { "queue": [id...], "current": <int, -1 表示无当前>, "mode": "CYCLE|SINGLE|SHUFFLE|LINE|INFINITY", "op": { ... } }
expect: { "queue": [id...], "current": <int>, "shuffleValid": <bool, 仅 SHUFFLE 用例> }
```

`op.type`：

| type | 字段 | 语义 |
|---|---|---|
| `insertNext` | `id` | 去重后插到当前歌的下一首；当前歌不变 |
| `append` | `id` | 去重后加到队尾；当前歌不变 |
| `playSong` | `id` | = insertNext 后把当前切到该曲 |
| `insertAllNext` | `ids` | 去重后整批插到当前歌之后；当前歌不变；无当前时等价 replace |
| `appendAll` | `ids` | 只追加队列里没有的；当前歌不变；无当前时等价 replace |
| `remove` | `index` | 删除该索引 |
| `move` | `from`,`to` | 拖拽排序，当前项跟随其歌曲移动 |
| `playFrom` | `index` | 直接切当前到该索引 |
| `replace` | `ids` | 整队替换，当前置 0 |

`shuffleValid: true` 表示断言：打乱表长度 == 队列长度、是 `0..n-1` 的排列、首项 == 当前索引、
`shuffledPosition == 0`。

## remove 的规格修正

Android `removeFromQueue` 只做 `currentIndex >= size` 的 clamp：删掉当前曲**之前**的歌后，
`currentIndex` 数值不变，于是指向了原来当前曲的**后一首**，破坏上述不变量。本夹具按正确的
行为定义：**删除后按歌曲身份重新定位当前索引**（删除当前曲本身时，落到它原本所在槽位的下一项，
队尾则前移一格）。Android 侧在 M3 接入本夹具时同步修正。

## 来源

Android：`app/.../MainActivity.kt` 的 `insertNext` / `appendToQueue` / `playSongItem` /
`insertAllNext` / `appendAllToQueue` / `removeFromQueue` / `moveInQueue` / `playFromQueue` /
`generateShuffledIndices`；打乱表结构见 `PlaybackQueueLogic.kt`。
