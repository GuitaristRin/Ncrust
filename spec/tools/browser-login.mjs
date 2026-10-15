// 浏览器登录：用独立的临时 Chrome 配置打开网易云登录页，用户在页面里正常登录（扫码 / 短信 / 密码均可），
// 本脚本经 Chrome 调试协议（CDP）轮询 music.163.com 的 cookie，检测到非空 MUSIC_U 且会话有效后写入文件。
//   node spec/tools/browser-login.mjs <输出 cookie 文件> [调试端口=9333]
// 只连本机回环端口；cookie 只写文件，不打印。临时配置目录在 %TEMP%，与用户日常 Chrome 隔离。
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { spawn } from 'node:child_process'
import { weapiPost } from './ncm-client.mjs'

const [outFile, portArg] = process.argv.slice(2)
if (!outFile) {
  console.error('usage: browser-login.mjs <cookie file> [port]')
  process.exit(64)
}
const port = Number(portArg || 9333)
const chrome = [
  'C:/Program Files/Google/Chrome/Application/chrome.exe',
  'C:/Program Files (x86)/Google/Chrome/Application/chrome.exe',
  path.join(process.env.LOCALAPPDATA || '', 'Google/Chrome/Application/chrome.exe'),
].find((p) => fs.existsSync(p))
if (!chrome) {
  console.error('找不到 Chrome')
  process.exit(1)
}
const profile = fs.mkdtempSync(path.join(os.tmpdir(), 'ncrust-login-'))
const child = spawn(chrome, [
  `--remote-debugging-port=${port}`, '--remote-allow-origins=http://127.0.0.1',
  `--user-data-dir=${profile}`, '--no-first-run', '--no-default-browser-check',
  'https://music.163.com/#/login',
], { detached: true, stdio: 'ignore' })
child.unref()
console.log('Chrome opened (isolated profile). Waiting for login…')

const sleep = (ms) => new Promise((r) => setTimeout(r, ms))

async function cdpCookies() {
  const targets = await (await fetch(`http://127.0.0.1:${port}/json`)).json()
  const page = targets.find((t) => t.type === 'page' && t.webSocketDebuggerUrl)
  if (!page) return []
  const ws = new WebSocket(page.webSocketDebuggerUrl)
  await new Promise((res, rej) => { ws.onopen = res; ws.onerror = rej })
  const result = await new Promise((res, rej) => {
    ws.onmessage = (e) => { const m = JSON.parse(e.data); if (m.id === 1) res(m.result) }
    ws.onerror = rej
    ws.send(JSON.stringify({ id: 1, method: 'Network.getCookies', params: { urls: ['https://music.163.com/'] } }))
  })
  ws.close()
  return result.cookies || []
}

let lastTried = ''
for (let i = 0; i < 600; i++) {
  await sleep(2000)
  let cookies
  try { cookies = await cdpCookies() } catch { continue }
  const musicU = cookies.find((c) => c.name === 'MUSIC_U' && c.value)
  if (!musicU || musicU.value === lastTried) continue
  lastTried = musicU.value
  const cookieStr = cookies.filter((c) => c.value).map((c) => `${c.name}=${c.value}`).join('; ')
  // 只认服务端确认有效的会话：account/get 的 account 非空。
  const j = await weapiPost('/api/w/nuser/account/get', {}, cookieStr).catch(() => null)
  if (!j || !j.account) {
    console.log('MUSIC_U present but session not valid yet, keep waiting…')
    continue
  }
  fs.writeFileSync(outFile, cookieStr)
  console.log(`logged in (uid present, nickname length ${(j.profile && j.profile.nickname || '').length}); cookie written to ${outFile}`)
  console.log('You can close that Chrome window. Temporary profile:', profile)
  process.exit(0)
}
console.error('timeout (20 min)')
process.exit(4)
