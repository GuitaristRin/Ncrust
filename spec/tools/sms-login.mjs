// 终端短信验证码登录：给调研 / 接口实测拿登录 cookie，不开网页。
//   node spec/tools/sms-login.mjs send <手机号>
//   node spec/tools/sms-login.mjs verify <手机号> <验证码> <输出 cookie 文件>
// 对齐 api-enhanced module/captcha_sent.js、login_cellphone.js（weapi + PC 身份 cookie）。
// 手机号只走命令行参数，不落盘；cookie 只写文件，不打印。
import fs from 'node:fs'
import crypto from 'node:crypto'
import * as X from './xeapi.mjs'

const [mode, phone, captcha, outFile] = process.argv.slice(2)
if (!['send', 'verify'].includes(mode) || !/^\d{6,15}$/.test(phone || '') || (mode === 'verify' && (!captcha || !outFile))) {
  console.error('usage: sms-login.mjs send <phone> | verify <phone> <captcha> <cookie file>')
  process.exit(64)
}

const UA = 'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Safari/537.36 Edg/124.0.0.0'
const PRESET = Buffer.from('0CoJUm6Qyw8W8jud')
const IV = Buffer.from('0102030405060708')
const N = BigInt('0x00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7')
const cbc = (k, d) => { const c = crypto.createCipheriv('aes-128-cbc', k, IV); return Buffer.concat([c.update(d), c.final()]) }
const modPow = (b, e, m) => { let r = 1n; b %= m; while (e > 0n) { if (e & 1n) r = (r * b) % m; b = (b * b) % m; e >>= 1n } return r }

// 参考实现 processCookieObject 的 PC 身份默认值；两次调用之间保持同一组设备字段。
const nuid = crypto.randomBytes(16).toString('hex')
const baseCookie = [
  '__remember_me=true', 'ntes_kaola_ad=1', `_ntes_nuid=${nuid}`, `_ntes_nnid=${nuid},${Date.now()}`,
  `NMTID=00O${crypto.randomBytes(19).toString('hex')}`, 'WEVNSM=1.0.0', 'os=pc', 'appver=3.1.17.204416',
  'osver=Microsoft-Windows-10-Professional-build-19045-64bit', 'channel=netease',
].join('; ')

async function weapi(uri, payload) {
  const sec = Array.from({ length: 16 }, () => 'abcdefghijklmnopqrstuvwxyz0123456789'[crypto.randomInt(36)]).join('')
  const params = cbc(Buffer.from(sec), Buffer.from(cbc(PRESET, Buffer.from(JSON.stringify({ ...payload, csrf_token: '' }))).toString('base64'))).toString('base64')
  const encSecKey = modPow(BigInt('0x' + Buffer.from([...sec].reverse().join('')).toString('hex')), 65537n, N).toString(16).padStart(256, '0')
  const res = await fetch('https://music.163.com/weapi/' + uri.slice(5), {
    method: 'POST',
    body: X.formEncode([['params', params], ['encSecKey', encSecKey]]),
    headers: { 'User-Agent': UA, Referer: 'https://music.163.com', 'Content-Type': 'application/x-www-form-urlencoded', Cookie: baseCookie },
  })
  return { json: await res.json(), setCookie: res.headers.getSetCookie() }
}

async function send() {
  const { json } = await weapi('/api/sms/captcha/sent', { ctcode: '86', secrete: 'music_middleuser_pclogin', cellphone: phone })
  console.log('captcha/sent code', json.code, json.message || json.msg || '')
  return json.code === 200 ? 0 : 1
}

async function verify() {
  const { json, setCookie } = await weapi('/api/w/login/cellphone', {
    type: '1', https: 'true', phone, countrycode: '86', captcha, remember: 'true', secureCaptcha: '',
  })
  if (json.code !== 200) {
    console.error('login code', json.code, json.message || json.msg || '')
    // -462：风控要求先在官方安全验证页完成验证（滑块等），验证页地址在 data.url。
    if (json.data && json.data.url) console.error('verify url:', json.data.url)
    return 1
  }
  // 以最后一个非空值为准（Set-Cookie 里可能先出现删除用的空值）。
  const jar = new Map()
  for (const line of setCookie) {
    const pair = line.split(';')[0].trim()
    const eq = pair.indexOf('=')
    if (eq > 0 && pair.slice(eq + 1)) jar.set(pair.slice(0, eq), pair.slice(eq + 1))
  }
  if (!jar.get('MUSIC_U')) {
    console.error('login 200 but no MUSIC_U in Set-Cookie')
    return 2
  }
  fs.writeFileSync(outFile, [...jar].map(([k, v]) => `${k}=${v}`).join('; '))
  console.log('logged in; cookie written to', outFile, '(fields:', [...jar.keys()].join(','), ')')
  return 0
}

// 用 exitCode 自然退出：Windows 上 fetch 的 keep-alive 连接未关时 process.exit 会触发 libuv 断言。
process.exitCode = mode === 'send' ? await send() : await verify()
