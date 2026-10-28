package io.github.pintofi.beanbook.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ZoomScaleTest {
    private val perDoubling = ZoomScale.DP_PER_DOUBLING

    @Test fun `sliding left zooms in and sliding right zooms out`() {
        assertTrue(ZoomScale.slid(1f, -50f, perDoubling, 0.5f, 10f) > 1f)
        assertTrue(ZoomScale.slid(2f, 50f, perDoubling, 0.5f, 10f) < 2f)
    }

    @Test fun `a slide of the doubling distance doubles the zoom wherever it starts`() {
        assertEquals(2f, ZoomScale.slid(1f, -perDoubling, perDoubling, 0.5f, 10f), 1e-4f)
        assertEquals(6f, ZoomScale.slid(3f, -perDoubling, perDoubling, 0.5f, 10f), 1e-4f)
        assertEquals(1.5f, ZoomScale.slid(3f, perDoubling, perDoubling, 0.5f, 10f), 1e-4f)
    }

    @Test fun `no slide leaves the zoom where it was`() {
        assertEquals(2.4f, ZoomScale.slid(2.4f, 0f, perDoubling, 0.5f, 10f), 1e-5f)
    }

    @Test fun `the zoom stops at the camera's own limits`() {
        assertEquals(10f, ZoomScale.slid(5f, -10 * perDoubling, perDoubling, 0.5f, 10f), 1e-4f)
        assertEquals(0.5f, ZoomScale.slid(1f, 10 * perDoubling, perDoubling, 0.5f, 10f), 1e-4f)
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
