package io.github.pintofi.beanbook.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomScaleTest {
    private val dead = ZoomScale.DEAD_ZONE_DP
    private val perDoubling = ZoomScale.DP_PER_DOUBLING_PER_SECOND

    @Test fun `holding the finger left zooms in and holding it right zooms out`() {
        assertTrue(ZoomScale.doublingsPerSecond(-60f) > 0f)
        assertTrue(ZoomScale.doublingsPerSecond(60f) < 0f)
    }

    @Test fun `nothing moves inside the dead zone`() {
        assertEquals(0f, ZoomScale.doublingsPerSecond(0f), 0f)
        assertEquals(0f, ZoomScale.doublingsPerSecond(-dead), 0f)
        assertEquals(0f, ZoomScale.doublingsPerSecond(dead - 1f), 0f)
    }

    @Test fun `the further the finger is held, the faster the zoom changes`() {
        assertTrue(ZoomScale.doublingsPerSecond(-80f) > ZoomScale.doublingsPerSecond(-40f))
        assertEquals(1f, ZoomScale.doublingsPerSecond(-(dead + perDoubling)), 1e-5f)
    }

    @Test fun `the speed has a ceiling`() {
        assertEquals(ZoomScale.MAX_DOUBLINGS_PER_SECOND, ZoomScale.doublingsPerSecond(-10_000f), 0f)
        assertEquals(-ZoomScale.MAX_DOUBLINGS_PER_SECOND, ZoomScale.doublingsPerSecond(10_000f), 0f)
    }

    @Test fun `holding a rate for a second doubles the zoom that many times, wherever it starts`() {
        assertEquals(2f, ZoomScale.advanced(1f, 1f, 1f, 0.5f, 10f), 1e-4f)
        assertEquals(6f, ZoomScale.advanced(3f, 1f, 1f, 0.5f, 10f), 1e-4f)
        assertEquals(1.5f, ZoomScale.advanced(3f, -1f, 1f, 0.5f, 10f), 1e-4f)
    }

    @Test fun `the zoom keeps climbing frame after frame until the camera's limit`() {
        var ratio = 1f
        val seen = mutableListOf(ratio)
        repeat(300) {
            ratio = ZoomScale.advanced(ratio, 1f, 1f / 60f, 0.5f, 10f)
            seen += ratio
        }
        assertTrue("it should never go down while held", seen.zipWithNext().all { (a, b) -> b >= a })
        assertEquals(10f, ratio, 1e-4f)
    }

    @Test fun `a step is a tenth of the zoom`() {
        assertEquals(10, ZoomScale.step(1.0f))
        assertEquals(10, ZoomScale.step(1.04f))
        assertEquals(11, ZoomScale.step(1.1f))
        assertEquals(24, ZoomScale.step(2.4f))
    }

    @Test fun `steps between two zooms count the ticks crossed in either direction`() {
        assertEquals(0, ZoomScale.stepsBetween(1.01f, 1.09f))
        assertEquals(1, ZoomScale.stepsBetween(1.09f, 1.11f))
        assertEquals(1, ZoomScale.stepsBetween(1.11f, 1.09f))
        assertEquals(10, ZoomScale.stepsBetween(1f, 2f))
    }

    @Test fun `the readout says a whole zoom without a decimal and any other with one`() {
        assertEquals("1×", ZoomScale.label(1f))
        assertEquals("2×", ZoomScale.label(2.01f))
        assertEquals("2.4×", ZoomScale.label(2.4f))
        assertEquals("0.6×", ZoomScale.label(0.6f))
        assertTrue(ZoomScale.label(10f).endsWith("×"))
    }
}
