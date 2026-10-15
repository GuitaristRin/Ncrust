// xeapi 线上探针（匿名，不需要也不读取任何登录 cookie）：
//   node spec/tools/probe-xeapi.mjs
// 拉公钥 → 用 xeapi 调一次匿名读接口（歌曲详情）→ 解密响应。通过即说明 spec/tools/xeapi.mjs
// 与当前服务端一致；协议被改时这里先红。
import { fetchKeyState, xeapiPost, randomDeviceId } from './ncm-client.mjs'

const deviceId = randomDeviceId()
const keyState = await fetchKeyState(deviceId)
console.log('public key: version', keyState.version, 'sk', keyState.sk ? 'present' : 'MISSING')

const { status, json, session } = await xeapiPost('/api/v3/song/detail', [['c', '[{"id":347230}]']], {
  keyState,
  deviceId,
})
console.log('song/detail http', status, 'code', json.code, 'session', session ? 'issued' : 'none')
const song = json.songs && json.songs[0]
if (json.code !== 200 || !song) {
  console.error('FAIL', JSON.stringify(json).slice(0, 300))
  process.exit(1)
}
console.log('OK', song.id, song.name)
