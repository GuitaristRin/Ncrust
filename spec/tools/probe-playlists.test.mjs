import test from 'node:test'
import assert from 'node:assert/strict'
import { readOnlyEapi, summarizePlaylist } from './probe-playlists.mjs'

test('write endpoints and untrusted URLs are rejected before any request', async () => {
  for (const path of ['/eapi/playlist/create', '/eapi/playlist/manipulate/tracks', '/eapi/playlist/delete', 'https://example.org']) {
    await assert.rejects(readOnlyEapi(path, {}, 'unused'), /read-only allowlist/)
  }
})

test('partial full-detail tracks do not make a complete ID snapshot incomplete', () => {
  const summary = summarizePlaylist({ trackCount: 3, trackIds: [{ id: 1 }, { id: 2 }, { id: 3 }], tracks: [{ id: 1 }] })
  assert.equal(summary.completeIdSnapshot, true)
  assert.equal(summary.tracksCount, 1)
  assert.equal(summary.trackIdsCount, 3)
})

test('missing IDs, duplicates, invalid IDs and unknown counts cannot claim completeness', () => {
  for (const playlist of [
    { trackCount: 3, trackIds: [{ id: 1 }], tracks: [] },
    { trackCount: 2, trackIds: [{ id: 1 }, { id: 1 }] },
    { trackCount: 0, trackIds: [{ id: 0 }] },
    { trackIds: [] }, { trackCount: 0 }, null,
  ]) assert.equal(summarizePlaylist(playlist).completeIdSnapshot, false)
  assert.equal(summarizePlaylist({ trackCount: 0, trackIds: [] }).completeIdSnapshot, true)
})

test('ordered snapshot digest changes when order changes', () => {
  const a = summarizePlaylist({ trackCount: 2, trackIds: [{ id: 1 }, { id: 2 }] })
  const b = summarizePlaylist({ trackCount: 2, trackIds: [{ id: 2 }, { id: 1 }] })
  assert.notEqual(a.orderedIdsDigest, b.orderedIdsDigest)
})
