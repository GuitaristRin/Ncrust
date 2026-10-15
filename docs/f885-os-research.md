# NW-F885 系统方向可行性调研（决策材料）

调研日期 2026-10-15。**纯桌面端联网检索 + 对设备的只读 adb 采集,全程未对 Walkman 做任何写入 / root / 刷写。**
目的是帮负责人判断「要不要立项做系统级工程」,而不是给出绕过锁的步骤。方法沿用 REA 口径:每条结论标注
**[实测]**（设备或权威来源直接可见）/ **[推断]**（有据但未亲证）/ **[未知]**（需进一步查证)。

## 0. 一句话结论

**现状能满足你的需求(本地听歌 + S-Master HX 音效),而三条系统方向都要先翻过「让自有代码启动」这道没有现成社区路径的墙,
且都极可能牺牲掉你最在意的 S-Master HX 音效。按「能跑就别动」的铁律,默认建议保持原厂;系统工程只作为你愿意承担变砖与失去音效风险的
纯爱好实验,不作为 Ncrust 产品路线。**

## 1. 硬件与软件事实

| 项 | 值 | 证据 |
|---|---|---|
| SoC | TI OMAP4430,Cortex-A9 双核 @ 1 GHz | [实测] 设备 `ro.board.platform=omap4` `ro.hardware=icx1237`;[实测] Wikipedia Walkman F Series 与 Sony 新闻稿均记 OMAP4430 |
| 数字功放 | **S-Master HX**（Class-D 开关放大,Sony 专有) | [实测] Sony 官方 F880 资料与 CDRInfo 规格表记 S-Master HX(不是 MX,MX 属 M 系列) |
| 音频软件栈 | ALSA 声卡 `ICXAudioCard` + 私有 HAL `libmix.icx.so` / `libsrc.icx.so`（混音 / 采样率转换) | [实测] 设备 `/proc/asound/cards`、`/system/lib/hw` |
| 系统 | Android 4.1.1 / API 16,内核 Linux 3.0.31(2013-10) | [实测] 设备 getprop / `/proc/version` |
| 引导链 | OMAP 标准 x-loader(p1)→ U-Boot 1.1.4(p2);独立 boot/recovery/system/userdata 分区 | [实测] 设备 by-name 软链与 getprop |
| 安全状态 | `ro.secure=1`、user 版、release-keys、无 su | [实测] 设备 |
| 芯片 HS / GP(是否只引导签名镜像) | **未知** —— 决定能否刷自有系统的头号问题 | [未知] |

## 2. 三条方向逐一评估

### 方向 A:在原厂 Android 4.1 上做精简 Ncrust（不碰系统)

风险最低、和铁律不冲突。可行性由另一个 spike 回答(`D:/arcdeck/spike-f885/REPORT.md`,进行中):4.1 能否跑通网易 HTTPS、取链、播放。
**这条不属于「系统级工程」,与本报告的 A/B/C 是并列关系,且是唯一不威胁 S-Master 音效的路线。** 若 spike 通过,这就是答案。

### 方向 B:刷 LineageOS 一类的 Android 定制系统

- **社区现状 [实测]**:F880 系列(F885/F886/F887)**没有** LineageOS、没有任何 custom ROM、没有 bootloader unlock。
  历史上只有一个 2014–2015 年基于 KingRoot 的 root 方法,绑定特定固件版本,现固件是否可用未经证实。
  （对比:更晚的 NW-A100 / ZX500 系列有 fastboot 解锁,但那是另一代产品,不能套用。)
- **判断 [推断]**:LineageOS 要有人为 icx1237 写设备树与 HAL 适配,F880 这代从未有人做过,等于从零起。即便做出来,音频走的也是 AOSP
  通用通路,**接不上 S-Master HX**(私有 HAL 在原厂才有)。所以 B 的代价是「付出巨大移植工作,换一个更现代但音效更差的系统」——与你的目标相悖。

### 方向 C:上主线 Linux(postmarketOS 一类)/ 自有 Rust 系统

- **SoC 层面乐观 [实测]**:OMAP4430 主线支持成熟——摩托罗拉 **Droid 4 就是 OMAP4430**,是 postmarketOS 维护良好、接近日用的机型;
  Nexus Q(OMAP4460)跑在 6.18 内核。CPU、显示、基础外设、USB、蓝牙 A2DP 这些大概率能起来。
- **但音频是死结 [推断]**:主线只会驱动参考设计的标准 codec(如 TWL6040),**没有 S-Master HX 的驱动**。你最在意的音效正是 Sony 私有的那一块,
  要移植就得:逆向 `libmix.icx.so`/`libsrc.icx.so` 的 userspace 控制逻辑 + 为 `ICXAudioCard` 写 ASoC 机器驱动,且 Sony 的 GPL 内核源码里**未必**
  包含完整的 ICX 音频驱动。
- **先例 [实测]**:Rockbox 的 Sony NW Linux 移植存在,但只覆盖更早的 NWZ(非 Android、不同 SoC/NAND),实验性,**不含 F880 这代**;
  S-Master 的驱动逆向在这条线上**没有做成**。最接近的参考是 NW-A30 的 Rockbox 移植(又是另一颗 SoC,CXD3778GF codec),有人逆向了 S-Master
  控制守护进程——只能当方法参考,不是 F885 的进度。
- **自有 Rust 系统**:在主线 Linux 之上做用户态是另一回事,但前提同样是先能启动、且愿意接受没有 S-Master 的声音。

## 3. 立项前必须先查清的「门槛问题」(都还没答案)

1. **OMAP4430 是 HS 还是 GP 芯片** [未知]:HS(高安全)只引导 Sony 签名镜像,没有签名私钥就刷不了自有系统,方向 B/C 直接不成立;GP 才有可能。
   查法:读 U-Boot / x-loader 行为、OMAP4 的 eFuse 状态——需要进一步只读探测或社区资料。
2. **Sony 是否公开了本机 GPL 内核源码**(含 ICX 音频驱动) [未知]:Sony 在 oss.sony.net 按产品发布 GPL 源码。有没有 F880 条目、驱动完整度,决定 C 的音频移植起点。
3. **能否不破坏 efs / nvp 做完整备份** [未知]:这两个分区是设备唯一数据(序列号、校准、可能的 DRM 密钥),动系统前必须先能完整备份、且确认刷坏能恢复。
4. **有无提权或官方 / 工厂刷机通道** [未知]:没有 root 就连完整 dump 都做不到;没有恢复通道,任何写操作都是单向的变砖赌注。

## 4. 风险 vs 收益

- **收益**:一台能跑你自有系统的随身实验室 + 一个深度逆向的爱好项目。对 Ncrust 产品本身没有直接收益。
- **风险**:变砖(无恢复通道时不可逆)、**失去 S-Master HX 音效**(B/C 几乎必然)、吞掉大量时间在一台孤儿机型上(社区零基础)。
- **铁律吻合度**:这台机器现在本地听歌 + 音效都正常,属于典型的「能跑就别动」。系统工程只在「你明确愿意拿这台机器当可牺牲的实验品」时才成立。

## 5. 建议的下一步(按风险从低到高,随时可停)

1. **先等方向 A 的 spike 结论**。若 4.1 能跑精简 Ncrust,产品需求就满足了,系统工程可以纯粹当兴趣、不占产品排期。
2. 若你确实想探系统方向,**只做零风险的桌面调研**:去 oss.sony.net 找 F880 的 GPL 内核源码并读其音频驱动完整度;查 OMAP4430 在本机的 HS/GP
   证据;看 Droid 4 / postmarketOS 的 OMAP4 移植文档摸清工作量。这些都不碰设备。
3. **只有在**门槛问题 1–4 都有正面答案、且你接受失去 S-Master 的前提下,再考虑动设备,且第一步永远是「先能完整备份与恢复」。

## 附:本轮来源

- Rockbox SonyNW / SonyNWLinuxPort wiki、NW-A30 Rockbox 移植(S-Master 守护进程逆向参考)
- postmarketOS:Nexus Q(OMAP4460)移植、linux-postmarketos-omap 内核;Droid 4(OMAP4430)为同 SoC 日用先例
- Wikipedia Walkman F Series、Sony F880 官方资料 / CDRInfo(OMAP4430、S-Master HX)
- Sony oss.sony.net GPL 源码发布模式(由 ILCE-7M4 GPL 仓库旁证)
- XDA / Head-Fi F880 系列 root 讨论(KingRoot,2014–2015,未证实现固件可用)
