// 匿名只读歌单探针：不读取登录态；只输出数量、完整性和摘要，不保存账号或曲目信息。
// node spec/tools/probe-playlists.mjs [公开歌单ID] [摘要输出路径]
import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import { pathToFileURL } from 'node:url'
import { aesEcb, EAPI_KEY } from './xeapi.mjs'

const HOST = 'https://interface.music.163.com'
const READ_PATHS = new Set(['/eapi/v6/playlist/detail', '/eapi/v3/song/detail', '/eapi/user/playlist'])
const UA = 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36'

export async function readOnlyEapi(path, payload, deviceId) {
  if (!READ_PATHS.has(path)) throw new Error('path is not in the read-only allowlist')
  const apiPath = path.replace('/eapi/', '/api/')
  const json = JSON.stringify(payload)
  const digest = crypto.createHash('md5').update(`nobody${apiPath}use${json}md5forencrypt`).digest('hex')
  const params = aesEcb(EAPI_KEY, Buffer.from(`${apiPath}-36cd479b6b5-${json}-36cd479b6b5-${digest}`)).toString('hex')
  const response = await fetch(HOST + path, {
    method: 'POST', body: new URLSearchParams({ params }), redirect: 'error',
    signal: AbortSignal.timeout(15_000),
    headers: {
      'User-Agent': UA, Referer: 'https://music.163.com/',
      Cookie: `os=pc; appver=3.0.6; osver=10.0.19045; deviceId=${deviceId}`,
      'Content-Type': 'application/x-www-form-urlencoded',
    },
  })
  const body = await response.json()
  if (!response.ok || body.code !== 200) throw new Error(`HTTP ${response.status}; business ${Number(body.code) || -1}`)
  return { httpStatus: response.status, body }
}

export function summarizePlaylist(playlist) {
  const trackIdsPresent = Array.isArray(playlist?.trackIds)
  const ids = trackIdsPresent ? playlist.trackIds.map(x => x.id).filter(x => Number.isSafeInteger(x) && x > 0) : []
  const uniqueCount = new Set(ids).size
  const trackCount = Number.isSafeInteger(playlist?.trackCount) ? playlist.trackCount : null
  const validIds = trackIdsPresent && ids.length === playlist.trackIds.length
  return {
    trackCount, trackIdsPresent, trackIdsCount: ids.length, uniqueCount,
    tracksCount: Array.isArray(playlist?.tracks) ? playlist.tracks.length : null,
    completeIdSnapshot: validIds && trackCount !== null && trackCount >= 0 && trackCount === ids.length && uniqueCount === ids.length,
    orderedIdsDigest: trackIdsPresent ? crypto.createHash('sha256').update(JSON.stringify(ids)).digest('hex') : null,
  }
}

async function main() {
  const id = process.argv[2] || '3778678'
  if (!/^[1-9]\d*$/.test(id) || !Number.isSafeInteger(Number(id))) throw new Error('invalid public playlist ID')
  const deviceId = crypto.randomBytes(16).toString('hex')
  const report = { checkedAt: new Date().toISOString(), host: HOST, anonymous: true, writes: 0, playlistId: id, reads: [] }
  let ownerId
  for (const n of ['1', '1000', '100000']) {
    try {
      const { body, httpStatus } = await readOnlyEapi('/eapi/v6/playlist/detail', { id, n, s: '0' }, deviceId)
      const summary = summarizePlaylist(body.playlist)
      report.reads.push({ path: '/eapi/v6/playlist/detail', n, httpStatus, code: body.code, ...summary })
      ownerId = body.playlist?.creator?.userId
      const sample = body.playlist?.trackIds?.find(x => Number.isSafeInteger(x.id) && x.id > 0)?.id
      if (n === '1' && sample) {
        const detail = await readOnlyEapi('/eapi/v3/song/detail', { c: JSON.stringify([{ id: sample }]) }, deviceId)
        report.reads.push({ path: '/eapi/v3/song/detail', httpStatus: detail.httpStatus, code: detail.body.code,
          returnedSongs: detail.body.songs?.length ?? null, requestedSongPresent: detail.body.songs?.some(x => x.id === sample) ?? false })
      }
    } catch (error) {
      report.reads.push({ path: '/eapi/v6/playlist/detail', n, error: error.name + ': ' + error.message })
      break
    }
  }
  if (Number.isSafeInteger(ownerId) && ownerId > 0) {
    const seen = new Set()
    try {
      for (const offset of ['0', '2', '4']) {
        const { body, httpStatus } = await readOnlyEapi('/eapi/user/playlist', {
          uid: String(ownerId), limit: '2', offset, includeVideo: 'false',
        }, deviceId)
        const lists = Array.isArray(body.playlist) ? body.playlist : []
        report.reads.push({ path: '/eapi/user/playlist', limit: '2', offset, httpStatus, code: body.code,
          returnedPlaylists: lists.length, specialPlaylists: lists.filter(x => x.specialType !== 0).length,
          overlapWithPreviousPages: lists.filter(x => seen.has(x.id)).length, more: body.more ?? null })
        lists.forEach(x => seen.add(x.id))
        if (!body.more) break
      }
    } catch (error) {
      report.reads.push({ path: '/eapi/user/playlist', error: error.name + ': ' + error.message })
    }
  }
  const output = JSON.stringify(report, null, 2) + '\n'
  if (process.argv[3]) await fs.writeFile(process.argv[3], output)
  process.stdout.write(output)
  if (report.reads.some(x => x.error)) process.exitCode = 1
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  main().catch(error => { process.stderr.write(error.name + ': ' + error.message + '\n'); process.exitCode = 1 })
}
