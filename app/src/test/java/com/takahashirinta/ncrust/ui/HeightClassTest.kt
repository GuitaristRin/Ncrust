package com.takahashirinta.ncrust.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HeightClassTest {
    @Test fun `regular at and above 720dp`() {
        assertEquals(HeightClass.Regular, heightClassFor(720f))
        assertEquals(HeightClass.Regular, heightClassFor(720.1f))
        assertEquals(HeightClass.Regular, heightClassFor(896f))
    }

    @Test fun `compact between 560 and 720dp`() {
        assertEquals(HeightClass.Compact, heightClassFor(560f))
        assertEquals(HeightClass.Compact, heightClassFor(640f))
        assertEquals(HeightClass.Compact, heightClassFor(719.999f))
    }

    @Test fun `tiny below 560dp`() {
        assertEquals(HeightClass.Tiny, heightClassFor(559.999f))
        assertEquals(HeightClass.Tiny, heightClassFor(533f))
        assertEquals(HeightClass.Tiny, heightClassFor(427f))
    }

    @Test fun `adjacent floats on both boundaries`() {
        assertEquals(HeightClass.Tiny, heightClassFor(Math.nextDown(560f)))
        assertEquals(HeightClass.Compact, heightClassFor(Math.nextUp(560f)))
        assertEquals(HeightClass.Compact, heightClassFor(Math.nextDown(720f)))
        assertEquals(HeightClass.Regular, heightClassFor(Math.nextUp(720f)))
    }

    @Test fun `degenerate heights fall back to tiny`() {
        assertEquals(HeightClass.Tiny, heightClassFor(0f))
        assertEquals(HeightClass.Tiny, heightClassFor(-100f))
        assertEquals(HeightClass.Tiny, heightClassFor(Float.NaN))
    }
}
