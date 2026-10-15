// 命令行扫码登录：给调研 / 接口实测拿一份新鲜的登录 cookie。
//   node spec/tools/qr-login.mjs <输出 cookie 文件> [二维码 PNG 路径]
// 打印二维码内容（同时用本机 Python qrcode 渲染 PNG，若可用），用网易云 App 扫码确认后，
// 把 Set-Cookie 拼成一行写进输出文件。cookie 只写文件，不打印到终端。
// 流程对齐 api-enhanced login_qr_key / login_qr_check（eapi + PC 身份 + type 3）。
import fs from 'node:fs'
import crypto from 'node:crypto'
import { spawnSync } from 'node:child_process'
import * as X from './xeapi.mjs'

const [outFile, pngFile] = process.argv.slice(2)
if (!outFile) {
  console.error('usage: qr-login.mjs <cookie file> [qr.png]')
  process.exit(64)
}

// 2026-10：weapi type=1 的 unikey 会被回 -462（风控校验）。参考实现（api-enhanced login_qr_key）改走
// eapi + PC 客户端身份 + type=3，打 interfacepc；二维码内容是不带 chainId 的 pc 形态。
const PC_UA = 'Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 (KHTML, like Gecko) Safari/537.36 Chrome/91.0.4472.164 NeteaseMusicDesktop/3.1.29.205117'
const deviceId = crypto.randomBytes(26).toString('hex').toUpperCase()
const pcHeader = () => ({
  osver: 'Microsoft-Windows-10-Professional-build-19045-64bit', deviceId, os: 'pc', appver: '3.1.17.204416',
  versioncode: '140', mobilename: '', buildver: String(Date.now()).slice(0, 10), resolution: '1920x1080',
  __csrf: '', channel: 'netease', requestId: `${Date.now()}_${String(crypto.randomInt(1000)).padStart(4, '0')}`,
})
async function eapiPc(uri, data) {
  const header = pcHeader()
  const text = JSON.stringify({ ...data, e_r: false, header })
  const digest = crypto.createHash('md5').update(`nobody${uri}use${text}md5forencrypt`).digest('hex')
  const params = X.aesEcb(X.EAPI_KEY, Buffer.from(`${uri}-36cd479b6b5-${text}-36cd479b6b5-${digest}`)).toString('hex').toUpperCase()
  const res = await fetch('https://interfacepc.music.163.com/eapi/' + uri.slice(5), {
    method: 'POST',
    body: X.formEncode([['params', params]]),
    headers: {
      'User-Agent': PC_UA, 'Content-Type': 'application/x-www-form-urlencoded',
      Cookie: Object.entries(header).map(([a, b]) => `${encodeURIComponent(a)}=${encodeURIComponent(b)}`).join('; '),
    },
  })
  return { json: await res.json(), setCookie: res.headers.getSetCookie ? res.headers.getSetCookie() : [] }
}
const { json: k } = await eapiPc('/api/login/qrcode/unikey', { type: 3 })
if (k.code !== 200 || !k.unikey) { console.error('unikey failed', k.code, k.message || k.msg || ''); process.exit(1) }
const qr = `https://music.163.com/login?codekey=${k.unikey}`
console.log('QR content:', qr)
if (pngFile) {
  const r = spawnSync('python', ['-c', 'import sys,qrcode; qrcode.make(sys.argv[1]).save(sys.argv[2])', qr, pngFile])
  console.log(r.status === 0 ? 'QR image: ' + pngFile : 'QR image failed (python qrcode)')
}

for (let i = 0; i < 150; i++) {
  await new Promise((r) => setTimeout(r, 2000))
  let res
  try { res = await eapiPc('/api/login/qrcode/client/login', { key: k.unikey, type: 3 }) } catch { continue }
  const code = res.json.code
  if (code === 802 && i % 5 === 0) console.log('scanned, waiting for confirm…')
  if (code === 800) { console.error('QR expired'); process.exit(2) }
  if (code === 803) {
    // 以最后一个非空值为准（Set-Cookie 里可能先出现删除用的空值）。
    const jar = new Map()
    for (const line of res.setCookie) {
      const pair = line.split(';')[0].trim(); const eq = pair.indexOf('=')
      if (eq > 0 && pair.slice(eq + 1)) jar.set(pair.slice(0, eq), pair.slice(eq + 1))
    }
    if (!jar.get('MUSIC_U')) { console.error('803 but no MUSIC_U'); process.exit(3) }
    fs.writeFileSync(outFile, [...jar].map(([a, b]) => `${a}=${b}`).join('; '))
    console.log('logged in; cookie written to', outFile, '(fields:', [...jar.keys()].join(','), ')')
    process.exit(0)
  }
}
console.error('timeout')
process.exit(4)
