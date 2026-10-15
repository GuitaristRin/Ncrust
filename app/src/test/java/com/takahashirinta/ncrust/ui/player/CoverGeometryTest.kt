package com.takahashirinta.ncrust.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverGeometryTest {
    // 以 dp 当 px（density=1）的估算输入，并非设备实测：系统栏各 24、顶栏 56、
    // 标题 28+24+16=68。控件估算 Regular 287、Compact 186、Tiny 118，另加
    // 实际调用中同样预留的 16dp Spacer 和 12dp 封面间隙。每档均预留完整标题。
    private fun geometry(width: Float, height: Float, controls: Float, title: Float = 68f) =
        narrowCoverGeometry(width, height - 24f, 80f, controls + 16f, title, 12f, height * .3f + 24f)

    private fun assertClear(g: CoverGeometry, height: Float, controls: Float, title: Float = 68f) {
        assertTrue(g.centerYPx - g.sizePx / 2f >= 80f)
        assertTrue(g.centerYPx + g.sizePx / 2f <= height - 24f - controls - 16f - title - 12f)
    }

    @Test fun `regular phone keeps full width and original anchor including title reserve`() {
        val g = geometry(384f, 896f, 287f)
        assertEquals(384f, g.sizePx, .001f)
        assertEquals(192f, g.centerXPx, .001f)
        assertEquals(896f * .3f + 24f, g.centerYPx, .001f)
        assertClear(g, 896f, 287f)
    }

    @Test fun `compact E5 is constrained by measured region reserves`() {
        val g = geometry(427f, 640f, 186f)
        assertEquals(254f, g.sizePx, .001f)
        assertEquals(213.5f, g.centerXPx, .001f)
        assertEquals(207f, g.centerYPx, .001f)
        assertClear(g, 640f, 186f)
    }

    @Test fun `tiny walkman is constrained by height`() {
        val g = geometry(320f, 533f, 118f)
        assertEquals(215f, g.sizePx, .001f)
        assertEquals(160f, g.centerXPx, .001f)
        assertEquals(187.5f, g.centerYPx, .001f)
        assertClear(g, 533f, 118f)
    }

    @Test fun `tiny extreme tier still clears title and controls`() {
        val g = geometry(320f, 427f, 118f)
        assertEquals(109f, g.sizePx, .001f)
        assertEquals(134.5f, g.centerYPx, .001f)
        assertClear(g, 427f, 118f)
    }

    @Test fun `width wins when smaller than the available band`() {
        val g = geometry(200f, 896f, 287f)
        assertEquals(200f, g.sizePx, .001f)
        assertEquals(100f, g.centerXPx, .001f)
        assertEquals(896f * .3f + 24f, g.centerYPx, .001f)
    }

    @Test fun `larger text or controls shrink the cover including on regular heights`() {
        val normal = geometry(427f, 640f, 186f)
        val largeTitle = geometry(427f, 640f, 186f, 100f)
        assertEquals(normal.sizePx - 32f, largeTitle.sizePx, .001f)
        assertClear(largeTitle, 640f, 186f, 100f)
        val regular = geometry(384f, 720f, 287f)
        assertTrue(regular.sizePx < 384f)
        assertClear(regular, 720f, 287f)
    }

    @Test fun `preferred anchor clamps at either end of a band`() {
        val top = narrowCoverGeometry(100f, 500f, 80f, 100f, 68f, 12f, -100f)
        val bottom = narrowCoverGeometry(100f, 500f, 80f, 100f, 68f, 12f, 1000f)
        assertEquals(130f, top.centerYPx, .001f)
        assertEquals(270f, bottom.centerYPx, .001f)
    }

    @Test fun `no system bars uses the same coordinate contract as automotive`() {
        val g = narrowCoverGeometry(427f, 640f, 56f, 202f, 68f, 12f, 216f)
        assertEquals(302f, g.sizePx, .001f)
        assertEquals(207f, g.centerYPx, .001f)
    }

    @Test fun `density scales size and anchors together`() {
        val dp = geometry(427f, 640f, 186f)
        val px = narrowCoverGeometry(1281f, 1848f, 240f, 606f, 204f, 36f, 648f)
        assertEquals(dp.sizePx * 3, px.sizePx, .001f)
        assertEquals(dp.centerXPx * 3, px.centerXPx, .001f)
        assertEquals(dp.centerYPx * 3, px.centerYPx, .001f)
    }

    @Test fun `fractional pixels do not invert a height constrained center interval`() {
        // 合成回归输入，并非实测。旧实现两端分别加减半边长，得到
        // 293.8829345703125 > 293.8829040527344，coerceIn 因区间倒置抛异常。
        val g = narrowCoverGeometry(
            400f, 440.60577392578125f, 147.16004943847656f,
            0f, 0f, 0f, 300f
        )
        assertEquals(293.44573974609375f, g.sizePx, .001f)
        assertEquals(293.8829345703125f, g.centerYPx, .001f)
        assertTrue(g.centerYPx - g.sizePx / 2f >= 147.16004943847656f - .001f)
        assertTrue(g.centerYPx + g.sizePx / 2f <= 440.60577392578125f + .001f)
    }

    @Test fun `fractional densities keep every tier inside its reserved band`() {
        // 估算的四档输入跨非整数 density 验证，允许浮点运算的亚像素误差。
        for ((width, height, controls) in listOf(
            Triple(384f, 896f, 287f), Triple(427f, 640f, 186f),
            Triple(320f, 533f, 118f), Triple(320f, 427f, 118f)
        )) {
            for (density in listOf(.75f, 1.1f, 1.33125f, 1.5f, 2.625f, 3f)) {
                val g = narrowCoverGeometry(
                    width * density, (height - 24f) * density, 80f * density,
                    (controls + 16f) * density, 68f * density, 12f * density,
                    (height * .3f + 24f) * density
                )
                assertTrue(g.sizePx >= 0f && g.sizePx <= width * density)
                assertTrue(g.centerYPx - g.sizePx / 2f >= 80f * density - .001f)
                assertTrue(g.centerYPx + g.sizePx / 2f <=
                    (height - 24f - controls - 16f - 68f - 12f) * density + .001f)
            }
        }
    }

    @Test fun `empty and overcommitted areas never return negative dimensions`() {
        val empty = narrowCoverGeometry(0f, 0f, 0f, 0f, 0f, 0f, 0f)
        assertEquals(CoverGeometry(0f, 0f, 0f), empty)
        val negative = narrowCoverGeometry(-50f, -10f, -1f, -2f, -3f, 12f, -40f)
        assertEquals(CoverGeometry(0f, 0f, 0f), negative)
        val crowded = narrowCoverGeometry(320f, 100f, 80f, 1000f, 68f, 12f, 200f)
        assertEquals(CoverGeometry(0f, 160f, 80f), crowded)
    }
}
