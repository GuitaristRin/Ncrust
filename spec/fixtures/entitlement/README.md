# fixtures/entitlement —— 账号 / VIP / 单曲权益的判定答案

规则全文见 `spec/api/entitlement.md`。生成：`node spec/tools/gen-entitlement-fixtures.mjs`
（期望值是手写的规则答案；playback 的输入取自 2026-10-15 匿名实测的真实响应）。

| 文件 / kind | input | expect |
|---|---|---|
| `playback.json` / `playback` | `{ requestedLevel, sessionValid, item: <url/v1 的 data[i]>, privilege: <歌曲详情 privilege 或 null> }` | `{ kind: "full"\|"trial"\|"unavailable", … }`：`trial` 带 `trialSeconds`、`reason`(`vip`/`album`/`login`)；`full` 带 `capped`，为真时带 `cappedTo`、`cappedReason`(`vip`/`login`)；`unavailable` 带 `reason`(`copyright`/`vip`/`album`/`login`/`unknown`) |
| `vip.json` / `vip` | `{ response: <vip/info 原始响应> }` | `{ state: "active"\|"lapsed"\|"none"\|"unknown", kind?: "svip"\|"vip"\|"package", expireAt?: <毫秒> }` |
| `session.json` / `session` | `{ hasCookie, accountGet: <account/get 响应或 null=网络失败>, otherApiCode? }` | `{ state: "valid"\|"expired"\|"logged-out"\|"unknown" }` |

档位比较按 `fixtures/quality` 的 7 级偏好顺序；阶梯外的档位（如 `sky`）不参与比较、不算 capped。
