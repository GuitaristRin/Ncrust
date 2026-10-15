// 「收藏隔天消失」对照实验。会改动账号的「我喜欢的音乐」—— 只在账号主人明确同意后运行。
//
//   node spec/tools/like-experiment.mjs start <cookie 文件> <日志.json>
//   node spec/tools/like-experiment.mjs check <cookie 文件> <日志.json>     # 立即、+1 天、+2 天各跑一次
//   node spec/tools/like-experiment.mjs cleanup <cookie 文件> <日志.json>   # 结束后撤掉实验收藏
//
// 设计：每条写入通道各收藏一首「此前不在红心歌单里」的歌，记录服务端业务码与写后立即读回；
// 之后按时间点回读红心歌单，看哪条通道写入的收藏被服务端回滚。对照组请账号主人在官方 App 里
// 手动收藏一首歌，把 id 填进日志的 officialControlId，check 会一起跟踪。
//
// 通道（与 Ncrust 现行/候选实现一一对应）：
//   weapi       music.163.com/weapi/radio/like           Android/Windows 现行主通道（de193ff / 25f0c39）
//   eapi-ios    interface.music.163.com/eapi/radio/like  现行回退通道（官方 iPhone 指纹）
//   xeapi-v1    interface3.music.163.com/xeapi/v1/radio/like  官方 Android 9.x 现行通道（候选修复）
//
// cookie 文件内容为一行原始 Cookie 串（含 MUSIC_U / __csrf）；日志里不写 cookie。
import fs from 'node:fs'
import { parseCookie, weapiPost, eapiPostOfficial, xeapiPost, fetchKeyState, randomDeviceId } from './ncm-client.mjs'

const [mode, cookieFile, logFile] = process.argv.slice(2)
if (!['start', 'check', 'cleanup'].includes(mode) || !cookieFile || !logFile) {
  console.error('usage: like-experiment.mjs start|check|cleanup <cookie file> <log.json>')
  process.exit(64)
}
const cookieStr = fs.readFileSync(cookieFile, 'utf8').trim()
const cookie = parseCookie(cookieStr)
if (!cookie.MUSIC_U) {
  console.error('cookie 里没有 MUSIC_U')
  process.exit(64)
}

// 候选歌：热门老歌（id < 2^31）与新歌（id > 2^31，issue gmg137/netease-cloud-music-gtk#410 的风控分界）。
const CANDIDATES = [
  186016, 185811, 25906124, 167876, 5257138, 287035, 1374051000, 1901371647,
  2754388325, 3408803371, 2611788659,
]

async function uid() {
  const j = await weapiPost('/api/w/nuser/account/get', {}, cookieStr)
  const id = j && j.account && j.account.id
  if (!id) throw new Error('account/get 失败（cookie 失效？）: ' + JSON.stringify(j).slice(0, 200))
  return id
}

async function likedIds(userId) {
  const j = await weapiPost('/api/song/like/get', { uid: String(userId) }, cookieStr)
  if (j.code !== 200 || !Array.isArray(j.ids)) throw new Error('song/like/get 失败: ' + JSON.stringify(j).slice(0, 200))
  return new Set(j.ids)
}

const xeapiCtx = async () => {
  const deviceId = cookie.deviceId || randomDeviceId()
  return { keyState: await fetchKeyState(deviceId), deviceId, cookie }
}

const CHANNELS = {
  weapi: async (id, like) => weapiPost('/api/radio/like', { alg: 'itembased', trackId: id, like, time: '3' }, cookieStr),
  'eapi-ios': async (id, like) =>
    eapiPostOfficial('/eapi/radio/like', { alg: 'itembased', trackId: String(id), like: String(like), time: '3', e_r: 'TRUE', csrf_token: cookie.__csrf || '' }, cookieStr),
  'xeapi-v1': async (id, like) => {
    const ctx = await xeapiCtx()
    return (await xeapiPost('/api/v1/radio/like', [['alg', 'itembased'], ['trackId', String(id)], ['like', String(like)], ['time', '3']], { ...ctx, checkToken: true })).json
  },
}

const now = () => new Date().toISOString()
const userId = await uid()

if (mode === 'start') {
  if (fs.existsSync(logFile)) {
    console.error('日志已存在，拒绝覆盖：' + logFile)
    process.exit(64)
  }
  const before = await likedIds(userId)
  const free = CANDIDATES.filter((id) => !before.has(id))
  const log = { startedAt: now(), uid: userId, officialControlId: null, entries: [], checks: [] }
  // 每条通道各取一首老歌 + 一首新歌（大 id），新歌不够时只做老歌。
  const small = free.filter((id) => id < 2 ** 31), big = free.filter((id) => id >= 2 ** 31)
  for (const name of Object.keys(CHANNELS)) {
    for (const id of [small.shift(), big.shift()].filter(Boolean)) {
      const res = await CHANNELS[name](id, true)
      log.entries.push({ channel: name, songId: id, at: now(), code: res && res.code, msg: res && (res.msg || res.message) || null })
      await new Promise((r) => setTimeout(r, 1500))
    }
  }
  const after = await likedIds(userId)
  for (const e of log.entries) e.readBackImmediately = after.has(e.songId)
  fs.writeFileSync(logFile, JSON.stringify(log, null, 2))
  console.table(log.entries)
} else {
  const log = JSON.parse(fs.readFileSync(logFile, 'utf8'))
  const liked = await likedIds(userId)
  if (mode === 'check') {
    const row = { at: now(), present: {} }
    for (const e of log.entries) row.present[`${e.channel}:${e.songId}`] = liked.has(e.songId)
    if (log.officialControlId) row.present[`official:${log.officialControlId}`] = liked.has(Number(log.officialControlId))
    log.checks.push(row)
    fs.writeFileSync(logFile, JSON.stringify(log, null, 2))
    console.table(row.present)
  } else {
    // cleanup：用实测仍有效的通道撤掉实验收藏（先 xeapi，失败再 weapi）。
    for (const e of log.entries) {
      if (!liked.has(e.songId)) continue
      let res = await CHANNELS['xeapi-v1'](e.songId, false)
      if (!res || res.code !== 200) res = await CHANNELS.weapi(e.songId, false)
      console.log('unlike', e.songId, res && res.code)
    }
  }
}
