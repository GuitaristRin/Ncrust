package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 数值取自调研文档的三档 + 对照档（docs/small-screen/research.md）。
 *
 * 约定（与 PlayerCard 的调用一致）：
 *  - [contentHeight] 是**内容区底边**（卡片根坐标）= 整屏高 − 系统导航栏；
 *  - [controls] 是**控件区连同它与面板之间那道 16dp Spacer** 的实测高度。
 * 系统导航栏按 24dp、状态栏按 24dp 代入（Xperia 10 VII 手势导航）。
 */
class CoverGeometryTest {

    private fun geometry(
        width: Float,
        contentHeight: Float,
        topBarBottom: Float,
        controls: Float,
        titleReserve: Float,
        gap: Float = 12f,
        preferredCenterY: Float = contentHeight * 0.3f + 24f
    ) = narrowCoverGeometry(
        availableWidthPx = width,
        contentHeightPx = contentHeight,
        topBarBottomPx = topBarBottom,
        controlsHeightPx = controls,
        titleReservePx = titleReserve,
        minGapPx = gap,
        preferredCenterYPx = preferredCenterY
    )

    @Test
    fun `regular phone keeps full width and the original anchor`() {
        // 384x896dp 对照档：改动前 coverSize = 屏宽、centerY = 屏高*0.3 + 24dp。
        // 控件区 287dp + Spacer 16dp = 303dp；Regular 不预留歌名块。
        // 锚点按整屏高（896dp）算，与 PlayerCard 传的值一致。
        val g = geometry(
            width = 384f, contentHeight = 872f, topBarBottom = 80f, controls = 303f, titleReserve = 0f,
            preferredCenterY = 896f * 0.3f + 24f
        )
        assertEquals(384f, g.sizePx, 0.01f)
        assertEquals(192f, g.centerXPx, 0.01f)
        assertEquals(896f * 0.3f + 24f, g.centerYPx, 0.01f)
    }

    @Test
    fun `compact E5 is constrained by height and stays clear of controls`() {
        // 427x640dp，紧凑控件区 186dp + 16dp = 202dp，歌名块 68dp。
        val g = geometry(width = 427f, contentHeight = 616f, topBarBottom = 80f, controls = 202f, titleReserve = 68f)
        // 可用带 = 616 - 202 - 68 - 12 = 334（底） - 80（顶） = 254dp
        assertEquals(254f, g.sizePx, 0.01f)
        assertEquals(213.5f, g.centerXPx, 0.01f)
        // 纵向夹进带内：上边不越过顶栏、下边不压住歌名块
        assertEquals(80f + 254f / 2f, g.centerYPx, 0.01f)
        assertTrue("封面下边须在控件区之上", g.centerYPx + g.sizePx / 2f <= 616f - 202f - 68f)
    }

    @Test
    fun `tiny walkman is constrained by height`() {
        // 320x533dp，Tiny 控件区 118dp（进度与时间一行 + 传输键行）+ 16dp = 134dp。
        val g = geometry(width = 320f, contentHeight = 509f, topBarBottom = 80f, controls = 134f, titleReserve = 68f)
        // 可用带 = 509 - 134 - 68 - 12 = 295（底） - 80（顶） = 215dp
        assertEquals(215f, g.sizePx, 0.01f)
        assertEquals(160f, g.centerXPx, 0.01f)
        assertEquals(80f + 215f / 2f, g.centerYPx, 0.01f)
    }

    @Test
    fun `tiny extreme tier keeps a positive cover above the controls`() {
        // 320x427dp 极限档：空间最紧，封面必须仍然 > 0 且不压控件区。
        val g = geometry(width = 320f, contentHeight = 403f, topBarBottom = 80f, controls = 134f, titleReserve = 68f)
        assertEquals(109f, g.sizePx, 0.01f)
        assertTrue(g.sizePx > 0f)
        assertTrue(g.centerYPx + g.sizePx / 2f <= 403f - 134f)
    }

    @Test
    fun `width wins when it is the smaller constraint`() {
        val g = geometry(width = 200f, contentHeight = 872f, topBarBottom = 80f, controls = 303f, titleReserve = 0f)
        assertEquals(200f, g.sizePx, 0.01f)
        assertEquals(100f, g.centerXPx, 0.01f)
        // 纵向不受约束 → 沿用既有锚点
        assertEquals(872f * 0.3f + 24f, g.centerYPx, 0.01f)
    }

    @Test
    fun `extreme values never go negative`() {
        // 控件区比可用高度还高：带宽夹到 0，边长 0 而不是负数。
        val g = geometry(width = 320f, contentHeight = 100f, topBarBottom = 80f, controls = 1000f, titleReserve = 68f)
        assertEquals(0f, g.sizePx, 0.01f)
        assertTrue(g.centerYPx >= 0f)

        val zero = geometry(width = 0f, contentHeight = 0f, topBarBottom = 0f, controls = 0f, titleReserve = 0f)
        assertEquals(0f, zero.sizePx, 0.01f)
        assertEquals(0f, zero.centerXPx, 0.01f)
        assertEquals(0f, zero.centerYPx, 0.01f)

        val negative = narrowCoverGeometry(
            availableWidthPx = -50f,
            contentHeightPx = -10f,
            topBarBottomPx = -1f,
            controlsHeightPx = -2f,
            titleReservePx = -3f,
            minGapPx = 12f,
            preferredCenterYPx = -40f
        )
        assertEquals(0f, negative.sizePx, 0.01f)
        assertEquals(0f, negative.centerXPx, 0.01f)
        assertEquals(0f, negative.centerYPx, 0.01f)
    }

    @Test
    fun `reserving the title block shrinks the cover instead of covering the name`() {
        // 同一块屏：不预留歌名块时封面更大，但下边会压进歌名块所在的底部区域。
        val unreserved = geometry(width = 427f, contentHeight = 616f, topBarBottom = 80f, controls = 202f, titleReserve = 0f)
        val reserved = geometry(width = 427f, contentHeight = 616f, topBarBottom = 80f, controls = 202f, titleReserve = 68f)
        assertTrue(reserved.sizePx < unreserved.sizePx)
        assertTrue(unreserved.centerYPx + unreserved.sizePx / 2f > 616f - 202f - 68f)
    }
}
