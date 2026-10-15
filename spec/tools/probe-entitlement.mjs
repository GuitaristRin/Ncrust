// 权益探针（匿名，不读 cookie）：看未登录 / 无 VIP 时，歌曲 URL 与歌曲详情接口分别返回什么，
// 以便客户端把「版权 / VIP / 试听 / 未登录」与「Ncrust 自己出错」区分开。
//   node spec/tools/probe-entitlement.mjs [songId …]
import { fetchKeyState, xeapiPost, randomDeviceId } from './ncm-client.mjs'

// 186016 晴天（VIP），347230 海阔天空，1901371647（新歌），5257138 七里香
const ids = process.argv.slice(2).map(Number)
const songIds = ids.length ? ids : [186016, 347230, 5257138, 1901371647]

const deviceId = randomDeviceId()
const keyState = await fetchKeyState(deviceId)

const url = await xeapiPost(
  '/api/song/enhance/player/url/v1',
  [['ids', JSON.stringify(songIds)], ['level', 'exhigh'], ['encodeType', 'flac']],
  { keyState, deviceId },
)
console.log('== song/enhance/player/url/v1 code', url.json.code)
for (const d of url.json.data || []) {
  const pick = (({ id, url, code, br, level, fee, payed, freeTrialInfo, freeTrialPrivilege, time }) =>
    ({ id, url: url ? url.slice(0, 60) + '…' : url, code, br, level, fee, payed, time, freeTrialInfo, freeTrialPrivilege }))(d)
  console.log(JSON.stringify(pick))
}

const detail = await xeapiPost('/api/v3/song/detail', [['c', JSON.stringify(songIds.map((id) => ({ id })))]], { keyState, deviceId })
console.log('== v3/song/detail code', detail.json.code)
for (const p of detail.json.privileges || []) {
  const { id, fee, payed, st, pl, dl, sp, cp, subp, maxbr, fl, toast, flag, plLevel, maxBrLevel, chargeInfoList, freeTrialPrivilege } = p
  console.log(JSON.stringify({ id, fee, payed, st, pl, dl, sp, cp, subp, maxbr, fl, toast, flag, plLevel, maxBrLevel, freeTrialPrivilege, charge: (chargeInfoList || []).map((c) => [c.rate, c.chargeType]) }))
}
