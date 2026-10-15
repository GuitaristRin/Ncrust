// spec/tools 的最小 HTTP 客户端：xeapi / weapi / eapi 三种通道，只给调研脚本用，不是产品代码。
import crypto from 'node:crypto'
import * as X from './xeapi.mjs'

export const ANDROID_UA =
  'NeteaseMusic/9.5.61.260802021928(9005061);Dalvik/2.1.0 (Linux; U; Android 12; HBN-AL00 Build/cd737a2.0)'
const WEB_UA =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'
const IOS_UA =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1'

export const randomDeviceId = () => crypto.randomBytes(16).toString('hex')

export const parseCookie = (s) =>
  Object.fromEntries(
    String(s || '')
      .split(';')
      .map((p) => p.trim())
      .filter((p) => p.includes('='))
      .map((p) => [p.slice(0, p.indexOf('=')).trim(), p.slice(p.indexOf('=') + 1)]),
  )

const cookieString = (obj) =>
  Object.entries(obj)
    .filter(([, v]) => v !== undefined && v !== null)
    .map(([k, v]) => `${k}=${v}`)
    .join('; ')

// ── xeapi ─────────────────────────────────────────────────────────────────

export async function fetchKeyState(deviceId) {
  let nonce = ''
  for (let i = 0; i < 16; i++) nonce += crypto.randomInt(10)
  const timestamp = String(Date.now())
  const body = X.formEncode([
    ['appVersion', '9.5.61'], ['currentKeyVersion', ''], ['deviceId', deviceId], ['nonce', nonce],
    ['os', 'android'], ['requestType', 'active'], ['signature', X.sign(timestamp, nonce)],
    ['t1', ''], ['t2', ''], ['timestamp', timestamp], ['uid', ''],
  ])
  const r = await fetch('https://interface.music.163.com/api/bsr/sk/get', {
    method: 'POST',
    body,
    headers: { 'User-Agent': ANDROID_UA, 'Content-Type': 'application/x-www-form-urlencoded', Cookie: `deviceId=${deviceId}` },
  })
  const j = await r.json()
  if (j.code !== 200 || !j.data || !j.data.encryptedData) throw new Error('key request failed: ' + JSON.stringify(j).slice(0, 200))
  if (X.sign(j.data.timestamp, nonce) !== j.data.signature) throw new Error('key response signature mismatch')
  const state = JSON.parse(X.decryptPublicKey(j.data.encryptedData))
  if (!state.sk) throw new Error('key response missing sk')
  return state
}

export async function antiCheatToken() {
  try {
    const r = await fetch('https://ac.dun.163yun.com/v3/b?pn=YD00000558929251')
    const m = (await r.text()).match(/null\(\[(\d+),\d+,"([^"]+)"\]\)/)
    return m && m[1] === '200' ? m[2] : ''
  } catch {
    return ''
  }
}

export async function xeapiPost(uri, pairs, { keyState, deviceId, cookie = {}, session, checkToken = false }) {
  const buildver = String(Date.now()).slice(0, 10)
  const { body } = X.encryptRequest({ pairs, keyState, session, rng: X.randomRng() })
  const headers = {
    'User-Agent': ANDROID_UA,
    'X-Client-Enc-State': 'ENCRYPTED',
    'x-aeapi': 'true',
    'content-type': 'application/x-www-form-urlencoded;charset=utf-8',
    'x-deviceid': deviceId,
    'x-os': 'android',
    'x-osver': '16',
    'x-appver': '9.1.65',
    'x-sdeviceid': deviceId,
    'x-buildver': buildver,
    Cookie: cookieString({ ...cookie, os: 'android', osver: '16', appver: '9.1.65', buildver, deviceId, sDeviceId: deviceId }),
  }
  if (cookie.MUSIC_U) headers['x-music-u'] = cookie.MUSIC_U
  if (checkToken) headers['X-antiCheatToken'] = await antiCheatToken()
  const res = await fetch('https://interface3.music.163.com/xeapi/' + uri.slice(5), { method: 'POST', body, headers })
  const raw = Buffer.from(await res.arrayBuffer())
  let json
  try {
    json = JSON.parse(X.decryptResponse(raw))
  } catch {
    json = { code: -1, raw: raw.toString('utf8').slice(0, 200) }
  }
  const ssid = res.headers.get('x-encr-ssid'), sskey = res.headers.get('x-encr-sskey')
  return { status: res.status, json, session: ssid && sskey ? { id: ssid, key: sskey } : session }
}

// ── weapi（与 Android RetrofitClient.weapiPost 同形）─────────────────────────

const WEAPI_PRESET = Buffer.from('0CoJUm6Qyw8W8jud')
const WEAPI_IV = Buffer.from('0102030405060708')
const RSA_N = BigInt('0x00e0b509f6259df8642dbc35662901477df22677ec152b5ff68ace615bb7b725152b3ab17a876aea8a5aa76d2e417629ec4ee341f56135fccf695280104e0312ecbda92557c93870114af6c9d05c4f7f0c3685b7a46bee255932575cce10b424d813cfe4875d3e82047b97ddef52741d546b8e289dc6935b3ece0462db0a22b8e7')
const RSA_E = 65537n
const cbc = (key, data) => {
  const c = crypto.createCipheriv('aes-128-cbc', key, WEAPI_IV)
  return Buffer.concat([c.update(data), c.final()])
}
const modPow = (b, e, m) => {
  let r = 1n
  b %= m
  while (e > 0n) {
    if (e & 1n) r = (r * b) % m
    b = (b * b) % m
    e >>= 1n
  }
  return r
}

export async function weapiPost(uri, payload, cookieStr) {
  const c = parseCookie(cookieStr)
  const data = { ...payload, csrf_token: c.__csrf || '' }
  const secKey = Array.from({ length: 16 }, () => 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789'[crypto.randomInt(62)]).join('')
  const params = cbc(Buffer.from(secKey), Buffer.from(cbc(WEAPI_PRESET, Buffer.from(JSON.stringify(data))).toString('base64'))).toString('base64')
  const encSecKey = modPow(BigInt('0x' + Buffer.from(secKey.split('').reverse().join('')).toString('hex')), RSA_E, RSA_N).toString(16).padStart(256, '0')
  const res = await fetch('https://music.163.com/weapi/' + uri.slice(5), {
    method: 'POST',
    body: X.formEncode([['params', params], ['encSecKey', encSecKey]]),
    headers: { 'User-Agent': WEB_UA, Referer: 'https://music.163.com/', Origin: 'https://music.163.com', 'Content-Type': 'application/x-www-form-urlencoded', Cookie: cookieStr },
  })
  const text = await res.text()
  try {
    return JSON.parse(text)
  } catch {
    return { code: -1, raw: text.slice(0, 200) }
  }
}

// ── eapi「官方 iPhone 指纹」（与 Android RetrofitClient.eapiPostOfficial 同形）────

export async function eapiPostOfficial(path, payload, cookieStr) {
  const c = parseCookie(cookieStr)
  const header = {
    os: 'iphone', appver: '8.9.60', deviceId: c.deviceId || crypto.randomBytes(10).toString('hex'), osver: c.osver || '16.0',
    versioncode: '140', mobilename: '', buildver: String(Math.floor(Date.now() / 1000)).slice(0, 10), resolution: '1920x1080',
    __csrf: c.__csrf || '', channel: 'yykj', requestId: String(20000000 + crypto.randomInt(10000000)),
  }
  if (c.MUSIC_U) header.MUSIC_U = c.MUSIC_U
  const data = { ...payload, header: JSON.stringify(header) }
  const apiPath = path.replace(/^\/eapi\//, '/api/')
  const text = JSON.stringify(data)
  const digest = crypto.createHash('md5').update(`nobody${apiPath}use${text}md5forencrypt`).digest('hex')
  const params = X.aesEcb(X.EAPI_KEY, Buffer.from(`${apiPath}-36cd479b6b5-${text}-36cd479b6b5-${digest}`)).toString('hex').toUpperCase()
  const res = await fetch('https://interface.music.163.com' + path, {
    method: 'POST',
    body: X.formEncode([['params', params]]),
    headers: {
      'User-Agent': IOS_UA, Referer: 'https://music.163.com/', 'Content-Type': 'application/x-www-form-urlencoded',
      Cookie: Object.entries(header).map(([k, v]) => `${encodeURIComponent(k)}=${encodeURIComponent(v)}`).join('; '),
    },
  })
  const raw = Buffer.from(await res.arrayBuffer())
  for (const attempt of [() => X.decryptResponse(raw), () => raw.toString('utf8')]) {
    try {
      return JSON.parse(attempt())
    } catch {}
  }
  return { code: -1 }
}
