package io.github.pintofi.beanbook.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomScaleTest {
    @Test fun `a camera that goes from 1x to 8x offers 1x 2x and 5x`() {
        assertEquals(listOf(1f, 2f, 5f), ZoomScale.stops(1f, 8f))
    }

    @Test fun `a camera with a wide lens also offers 0_5x`() {
        assertEquals(listOf(0.5f, 1f, 2f, 5f, 10f), ZoomScale.stops(0.5f, 10f))
    }

    @Test fun `a stop the camera cannot reach is left out, one at its edge stays`() {
        assertEquals(listOf(1f, 2f), ZoomScale.stops(1f, 2f))
        assertEquals(listOf(1f), ZoomScale.stops(1f, 1.5f))
    }

    @Test fun `positions run from the smallest zoom to the largest on a log scale`() {
        assertEquals(0f, ZoomScale.fractionOf(1f, 1f, 8f), 1e-6f)
        assertEquals(1f, ZoomScale.fractionOf(8f, 1f, 8f), 1e-6f)
        // 2x is a third of the way from 1x to 8x: each doubling is the same distance.
        assertEquals(1f / 3f, ZoomScale.fractionOf(2f, 1f, 8f), 1e-5f)
    }

    @Test fun `a position and its zoom convert back and forth`() {
        listOf(0.5f, 1f, 1.7f, 4f, 9.9f).forEach { ratio ->
            val back = ZoomScale.ratioAt(ZoomScale.fractionOf(ratio, 0.5f, 10f), 0.5f, 10f)
            assertEquals(ratio, back, ratio * 1e-4f)
        }
    }

    @Test fun `a position outside the strip gives the nearest end`() {
        assertEquals(1f, ZoomScale.ratioAt(-0.5f, 1f, 8f), 1e-6f)
        assertEquals(8f, ZoomScale.ratioAt(1.5f, 1f, 8f), 1e-4f)
    }

    @Test fun `the zoom is on a stop when it is within a few percent of it`() {
        assertEquals(2f, ZoomScale.stopAt(2.04f, 1f, 8f))
        assertEquals(1f, ZoomScale.stopAt(1.0f, 1f, 8f))
        assertNull(ZoomScale.stopAt(2.4f, 1f, 8f))
    }

    @Test fun `the readout says a whole zoom without a decimal and any other with one`() {
        assertEquals("1×", ZoomScale.label(1f))
        assertEquals("2×", ZoomScale.label(2.01f))
        assertEquals("2.4×", ZoomScale.label(2.4f))
        assertEquals("0.6×", ZoomScale.label(0.6f))
        assertTrue(ZoomScale.label(10f).endsWith("×"))
    }
}
