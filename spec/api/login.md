# 登录方式调研：能否弃用 WebView 改原生登录

状态：**已搁置**（2026-10-15 负责人决定：暂时放弃原生方案，继续保持内嵌官方网页登录）。下文保留为调研记录；若日后重启，从「实验」一节开始。

## 问题

两端主登录是内嵌官方网页（Android WebView / Windows WebView2 打开 `music.163.com/#/login`，轮询 cookie）。
体验割裂（整个网页播放器 SPA、样式与 Kanesumi 不搭、加载重），但它是**风险最低**的方案。要回答：原生登录能不能替代它，代价是什么。

## 网易的风控面（2026-10-15 实测）

| 请求 | 结果 |
|---|---|
| weapi `/api/login/qrcode/unikey`（type 1，两端现用） | `-462`（「请完成验证操作」） |
| eapi `interfacepc/.../login/qrcode/unikey`（type 3，PC 身份；api-enhanced 现用） | 200，可出码（被风控标记 IP 之前） |
| weapi `/api/sms/captcha/sent`（PC 身份） | 200，验证码送达 |
| weapi `/api/w/login/cellphone`（验证码登录） | `-462`，`data.url` 指向官方安全验证页 `st.music.163.com/encrypt-pages`（verifyType 40） |
| 用户在**自己的浏览器**里完成该验证 | 浏览器随后登录成功；**脚本的请求仍然 -462**（含匿名请求、xeapi） |

结论一：`-462` 是一个需要**交互验证**（滑块 / 扫码确认）的风控挑战。官方网页遇到它会就地弹出验证，这就是 WebView 方案「风险最低」的原因。
结论二：验证结果绑定在**完成验证的那个客户端身份**（浏览器的 cookie：`NMTID`、`_ntes_nuid` 等设备标识）上，不是绑定 IP。
脚本每次随机生成设备 cookie，所以验证过了也没用，而且「每次换一个设备」本身就是风控特征——当天大量探针请求后，本机出口 IP 被整体标记，连匿名读接口都 -462。

## 生态现状（第三方 2026-09 调研，h4rvey-g/fika-music `docs/netease-login-research.md`，及本仓复核）

- SPlayer-Next、VutronMusic、AlgerMusicPlayer：**官方网页登录（独立会话、轮询非空 MUSIC_U、再校验账号）+ 扫码 + 导入 cookie**。密码 / 短信登录的代码还在，但都不再作为可见入口。
- 唯一的「接口过 -462」路径 `frontrisk/verify/getqrcode`（api-enhanced `verify_getQr`）：fika 实测真实账号返回 301，**未被证明可用**。
- MusicBot-Go：url 类接口缺少 `NMTID` 设备 cookie 时，无论 MUSIC_U 是否有效、IP 如何，都会被回 `-462 / verifyType 50`。
  api-enhanced 也为每个请求补齐 `NMTID`、`_ntes_nuid`、`_ntes_nnid`、`WNMCID`、`WEVNSM`、`os/appver/osver/channel` 等设备 cookie，且进程内保持不变。

## 方案对比

| 方案 | 体验 | 风控风险 | 遇到 -462 时 | 评价 |
|---|---|---|---|---|
| A. 现状：内嵌官方网页为主 | 割裂、重 | **最低** | 网页就地验证 | 稳，但难看 |
| B. 纯原生（扫码 + 短信表单） | 最好 | 高 | **无解**，只能报错 | 不可接受：风控一来用户就登不上 |
| C. **原生优先 + 按需官方验证页**（推荐） | 好；只在被风控时出现一个小网页 | 低（验证仍交给官方页） | 弹出只装 `verifyUrl` 的小窗，完成后自动重试 | 需要先实测「验证结果能否回到 App 的请求」 |
| D. 原生优先 + 退回官方登录网页 | 好；被风控时退回方案 A | 最低 | 退回整页网页登录 | C 实测不成立时的保底 |

## 推荐：C，D 兜底

1. **主入口原生**：扫码面板（eapi + PC 身份 + type 3，修复见 qr-login 任务）为默认；短信验证码表单为第二项。界面完全 Kanesumi。
2. **设备身份持久化（无论选哪个方案都该先做）**：两端为所有 NetEase 请求补齐并**持久化**一套设备 cookie
   （`NMTID`、`_ntes_nuid`、`_ntes_nnid`、`WNMCID`、`WEVNSM`、`deviceId`/`sDeviceId`），一台设备一套、登出也不换。这是降低 -462 的直接手段，
   与 xeapi 的「设备 id 永不更换」同一思路。
3. **-462 / 8821 → 官方验证小窗**：用 App 自己的设备 cookie 预先写进 WebView 的 cookie 存储，再加载响应里的 `data.url`；用户完成验证后，
   把 WebView 里 `music.163.com` 域的 cookie 读回 App 的 cookie 存储，自动重试刚才失败的请求。
4. **若实验证明第 3 步回不来**（验证结果不进 App 的请求）：改为 D——被风控时打开官方登录网页（移动版 `music.163.com/m/login`，比桌面 SPA 轻），
   登录完成后照现有方式取 cookie。
5. **网页登录保留为「其它方式」入口**，不删除。全局的 `-462`（非登录接口）同理接入第 3 步：权益规格（`spec/api/entitlement.md`）里新增
   「需要安全验证」状态，而不是把它当成网络错误或登录失效。

## 实验（在真机 / 冷却后的网络上做，决定 C 还是 D）

1. 设备 cookie 持久化后，同一网络下连续做 50 次匿名读请求：对照「每次随机设备 cookie」的 -462 出现率。
2. 故意触发一次短信登录的 -462：把 App 的设备 cookie 注入 WebView 后打开 `data.url` 完成验证，读回 cookie，重试登录。
   成功 → 方案 C 成立；失败 → 再试「重试时带上 `verifyToken` / 验证页新写入的 cookie」；仍失败 → 方案 D。
3. 两端各跑一遍，结果写回本文件。

## 不做的事

- 不做密码登录（MD5 密码上传、被 8821 拦截概率最高、泄露面最大）。
- 不用接口刷验证（`frontrisk/verify/getqrcode` 未被证明可用；反复试只会加重风控）。
- 不在 URL 查询串里带任何凭据。
