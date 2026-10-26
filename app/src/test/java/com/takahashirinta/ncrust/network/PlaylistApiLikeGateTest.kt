package com.takahashirinta.ncrust.network

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 锁住收藏写入的业务码判定（`PlaylistApi.isLikeWriteAccepted`，spec/api/xeapi.md §6）。
 *
 * 502 必须算成功：歌已在红心歌单时 xeapi 回 502，把它当失败会让 likeSong 掉进 weapi
 * 老通道，老通道可能触发风控 524「已取消喜欢」，把已有的收藏反手撤销（issue #33）。
 * 风控码（-460 / 524 等）与其余非 200 一律失败——判定只在 likeSong 的 xeapi 分支使用，
 * weapi / eapi 老通道仍从严只认 200。
 */
class PlaylistApiLikeGateTest {

    @Test
    fun `200 and 502 are the only accepted codes`() {
        assertTrue(PlaylistApi.isLikeWriteAccepted(200))
        assertTrue("502 = 歌曲已在期望状态（spec §6），必须判成功", PlaylistApi.isLikeWriteAccepted(502))
    }

    @Test
    fun `risk-control and error codes are rejected`() {
        assertFalse(PlaylistApi.isLikeWriteAccepted(-1))
        assertFalse(PlaylistApi.isLikeWriteAccepted(301))
        assertFalse(PlaylistApi.isLikeWriteAccepted(403))
        assertFalse(PlaylistApi.isLikeWriteAccepted(405))
        assertFalse("524 = 环境异常已取消喜欢，绝不是成功", PlaylistApi.isLikeWriteAccepted(524))
        assertFalse(PlaylistApi.isLikeWriteAccepted(-460))
    }
}
