// 生成 spec/fixtures/entitlement/{playback,vip,session}.json。
//   node spec/tools/gen-entitlement-fixtures.mjs
// playback 的输入取自 2026-10-15 probe-entitlement.mjs 的真实响应（匿名），其余按 spec/api/entitlement.md 构造。
// 期望值是手写的规则答案，不是由某个实现算出来的。
import fs from 'node:fs'

const PROBE = '2026-10-15 spec/tools/probe-entitlement.mjs 实测响应（匿名）；规则见 spec/api/entitlement.md'
const RULE = '按 spec/api/entitlement.md 规则构造'
const trial45 = { fragmentType: -1, start: 0, end: 45, algData: { fragSource: 'default' } }
const ftp = (reason) => ({ resConsumable: reason == null, userConsumable: false, listenType: null, cannotListenReason: reason, playReason: null, freeLimitTagType: null })
const item = (o) => ({ id: 1, url: null, code: 200, level: null, fee: 0, freeTrialInfo: null, freeTrialPrivilege: ftp(null), ...o })

const playback = []
const pc = (id, description, source, input, expect) =>
  playback.push({ id: 'entitlement.playback.' + id, kind: 'playback', description, source, input, expect })

pc('vip-trial-anonymous', 'VIP 歌、未登录：200 + url 但带 freeTrialInfo，是 45 秒试听，原因归为「登录」。', PROBE,
  { requestedLevel: 'exhigh', sessionValid: false, item: item({ id: 347230, url: 'http://m702.music.126.net/x.mp3', level: 'standard', fee: 1, freeTrialInfo: trial45 }), privilege: { st: 0, pl: 0, fee: 1 } },
  { kind: 'trial', trialSeconds: 45, reason: 'login' })
pc('vip-trial-logged-in', 'VIP 歌、已登录非会员：同样的试听，原因归为「需要 VIP」。', RULE,
  { requestedLevel: 'lossless', sessionValid: true, item: item({ id: 1901371647, url: 'http://m702.music.126.net/y.mp3', level: 'standard', fee: 1, freeTrialInfo: trial45 }), privilege: { st: 0, pl: 0, fee: 1 } },
  { kind: 'trial', trialSeconds: 45, reason: 'vip' })
pc('album-trial', 'fee 4（数字专辑）试听：原因是「需购买专辑」，与登录无关；试听时长 = end - start。', RULE,
  { requestedLevel: 'exhigh', sessionValid: false, item: item({ id: 2, url: 'http://m7.music.126.net/z.mp3', level: 'standard', fee: 4, freeTrialInfo: { start: 30, end: 60 } }), privilege: { st: 0, pl: 0, fee: 4 } },
  { kind: 'trial', trialSeconds: 30, reason: 'album' })
pc('full-exact', '免费歌按请求档位拿到完整音源：full，不 capped。', PROBE,
  { requestedLevel: 'exhigh', sessionValid: false, item: item({ id: 5257138, url: 'http://m701.music.126.net/a.mp3', level: 'exhigh', fee: 8 }), privilege: { st: 0, pl: 320000, fee: 8 } },
  { kind: 'full', capped: false })
pc('full-capped-vip', 'fee 8 已登录非会员请求无损只给到极高：capped，原因 vip。', RULE,
  { requestedLevel: 'lossless', sessionValid: true, item: item({ id: 5257138, url: 'http://m701.music.126.net/a.mp3', level: 'exhigh', fee: 8 }), privilege: { st: 0, pl: 320000, fee: 8 } },
  { kind: 'full', capped: true, cappedTo: 'exhigh', cappedReason: 'vip' })
pc('full-capped-login', '未登录请求无损只给到极高：capped，原因 login。', RULE,
  { requestedLevel: 'lossless', sessionValid: false, item: item({ id: 5257138, url: 'http://m701.music.126.net/a.mp3', level: 'exhigh', fee: 8 }), privilege: { st: 0, pl: 320000, fee: 8 } },
  { kind: 'full', capped: true, cappedTo: 'exhigh', cappedReason: 'login' })
pc('full-unknown-level', '服务端返回阶梯外的档位（如 sky）：无法比较，不算 capped。', RULE,
  { requestedLevel: 'lossless', sessionValid: true, item: item({ id: 3, url: 'http://m7.music.126.net/b.flac', level: 'sky' }), privilege: null },
  { kind: 'full', capped: false })
pc('unavailable-copyright', '无版权：404 + url null，详情 st=-200 → copyright。', PROBE,
  { requestedLevel: 'exhigh', sessionValid: false, item: item({ id: 186016, code: 404, freeTrialPrivilege: ftp(1) }), privilege: { st: -200, pl: 0, fee: 0 } },
  { kind: 'unavailable', reason: 'copyright' })
pc('unavailable-vip', 'fee 1 无 url、已登录：需要 VIP。', RULE,
  { requestedLevel: 'exhigh', sessionValid: true, item: item({ id: 4, code: 404, fee: 1 }), privilege: { st: 0, pl: 0, fee: 1 } },
  { kind: 'unavailable', reason: 'vip' })
pc('unavailable-login', 'fee 1 无 url、未登录：原因是 login；没有详情 privilege 时用 item.fee。', RULE,
  { requestedLevel: 'exhigh', sessionValid: false, item: item({ id: 4, code: 404, fee: 1 }), privilege: null },
  { kind: 'unavailable', reason: 'login' })
pc('unavailable-album', 'fee 4 无 url：需购买专辑。', RULE,
  { requestedLevel: 'exhigh', sessionValid: true, item: item({ id: 5, code: 404, fee: 4 }), privilege: null },
  { kind: 'unavailable', reason: 'album' })
pc('unavailable-unknown', 'fee 0 无 url 且无版权信号：unknown，不编原因。', RULE,
  { requestedLevel: 'exhigh', sessionValid: true, item: item({ id: 6, code: 404 }), privilege: null },
  { kind: 'unavailable', reason: 'unknown' })

const now = 1792000000000, day = 86400000
const vip = []
const vc = (id, description, input, expect) =>
  vip.push({ id: 'entitlement.vip.' + id, kind: 'vip', description, source: 'XiaoMengXinX/Music163Api-Go types/vipInfo.go 字段；规则见 spec/api/entitlement.md', input, expect })
const none = { vipCode: 0, expireTime: 0 }
vc('black-active', '黑胶 VIP 有效。', { response: { code: 200, data: { now, redVipLevel: 3, associator: { vipCode: 100, expireTime: now + 30 * day }, musicPackage: none } } }, { state: 'active', kind: 'vip', expireAt: now + 30 * day })
vc('svip-wins', 'SVIP 与黑胶同时有效时取 SVIP。', { response: { code: 200, data: { now, associator: { vipCode: 100, expireTime: now + 30 * day }, redplus: { vipCode: 300, expireTime: now + 10 * day }, musicPackage: none } } }, { state: 'active', kind: 'svip', expireAt: now + 10 * day })
vc('package-only', '只有音乐包有效。', { response: { code: 200, data: { now, associator: none, musicPackage: { vipCode: 230, expireTime: now + 5 * day } } } }, { state: 'active', kind: 'package', expireAt: now + 5 * day })
vc('lapsed', '都过期：lapsed，expireAt 取最近过期的一项。', { response: { code: 200, data: { now, associator: { vipCode: 100, expireTime: now - 3 * day }, musicPackage: { vipCode: 230, expireTime: now - 40 * day } } } }, { state: 'lapsed', expireAt: now - 3 * day })
vc('none', '从未开通：none。', { response: { code: 200, data: { now, associator: none, musicPackage: none } } }, { state: 'none' })
vc('not-logged-in', '301 未登录：unknown，不能显示「未开通」。', { response: { message: '用户未登录', code: 301 } }, { state: 'unknown' })

const session = []
const sc = (id, description, input, expect) =>
  session.push({ id: 'entitlement.session.' + id, kind: 'session', description, source: '2026-10-15 伪造 cookie 实测：account/get 返回 code 200 + account:null；需登录接口返回 301', input, expect })
sc('valid', '有 cookie，account/profile 非空。', { hasCookie: true, accountGet: { code: 200, account: { id: 1 }, profile: { userId: 1, nickname: 'a' } } }, { state: 'valid' })
sc('expired', '有 cookie，但 account 为 null（code 200）→ 失效。', { hasCookie: true, accountGet: { code: 200, account: null, profile: null } }, { state: 'expired' })
sc('logged-out', '无 cookie → 未登录。', { hasCookie: false, accountGet: { code: 200, account: null, profile: null } }, { state: 'logged-out' })
sc('network-error', '网络失败（accountGet 为 null）→ unknown，维持上一个状态。', { hasCookie: true, accountGet: null }, { state: 'unknown' })
sc('server-error', '非 200 业务码 → unknown，不判失效。', { hasCookie: true, accountGet: { code: 500 } }, { state: 'unknown' })
sc('api-301', '任一需登录接口返回 301 → expired。', { hasCookie: true, otherApiCode: 301 }, { state: 'expired' })

const dir = new URL('../fixtures/entitlement/', import.meta.url)
fs.mkdirSync(dir, { recursive: true })
for (const [name, cases] of [['playback', playback], ['vip', vip], ['session', session]]) {
  fs.writeFileSync(new URL(name + '.json', dir), JSON.stringify(cases, null, 2) + '\n')
}
console.log('playback', playback.length, 'vip', vip.length, 'session', session.length)
