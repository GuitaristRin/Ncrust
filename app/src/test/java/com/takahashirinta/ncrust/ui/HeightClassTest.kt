package com.takahashirinta.ncrust.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HeightClassTest {

    @Test
    fun `regular at and above 720dp`() {
        assertEquals(HeightClass.Regular, heightClassFor(720f))
        assertEquals(HeightClass.Regular, heightClassFor(720.1f))
        // 对照档：Xperia 10 VII 本身 384x896dp
        assertEquals(HeightClass.Regular, heightClassFor(896f))
    }

    @Test
    fun `compact between 560 and 719dp`() {
        // 荣悦 E5 档 427x640dp
        assertEquals(HeightClass.Compact, heightClassFor(640f))
        assertEquals(HeightClass.Compact, heightClassFor(560f))
        assertEquals(HeightClass.Compact, heightClassFor(719.9f))
    }

    @Test
    fun `tiny below 560dp`() {
        // Walkman NW-F885 档 320x533dp
        assertEquals(HeightClass.Tiny, heightClassFor(533f))
        assertEquals(HeightClass.Tiny, heightClassFor(559.9f))
        // 调研里的极限档 320x427dp
        assertEquals(HeightClass.Tiny, heightClassFor(427f))
    }

    @Test
    fun `boundary values land in the higher class`() {
        // 边界归属：720 / 560 本身算「够高」，避免浮点误差在档位间抖动。
        assertEquals(HeightClass.Regular, heightClassFor(720f))
        assertEquals(HeightClass.Compact, heightClassFor(719.999f))
        assertEquals(HeightClass.Compact, heightClassFor(560f))
        assertEquals(HeightClass.Tiny, heightClassFor(559.999f))
    }

    @Test
    fun `degenerate heights are tiny not negative`() {
        assertEquals(HeightClass.Tiny, heightClassFor(0f))
        assertEquals(HeightClass.Tiny, heightClassFor(-100f))
    }
}
