package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ScanToneTest {

    private fun hist(vararg counts: Pair<Int, Int>) = IntArray(256).also { h -> counts.forEach { (v, n) -> h[v] = n } }

    // --- percentiles ----------------------------------------------------------------------------

    @Test fun `percentiles are exact on hand made histograms`() {
        val h = hist(10 to 50, 100 to 30, 200 to 20)
        assertEquals(10, Percentiles.at(h, 0.5f))
        assertEquals(100, Percentiles.at(h, 0.6f))
        assertEquals(100, Percentiles.at(h, 0.8f))
        assertEquals(200, Percentiles.at(h, 0.81f))
    }

    @Test fun `fraction zero and one are the first and last non-empty bins`() {
        val h = hist(10 to 5, 99 to 1)
        assertEquals(10, Percentiles.at(h, 0f))
        assertEquals(99, Percentiles.at(h, 1f))
        assertEquals(0, Percentiles.at(IntArray(256), 0.5f))
    }

    @Test fun `the mode is the fullest bin in range`() {
        val h = hist(60 to 900, 230 to 500, 240 to 100)
        assertEquals(230, Percentiles.mode(h, 128, 255))
    }

    // --- tone curve -----------------------------------------------------------------------------

    @Test fun `ink at 60 and paper at 230 become black and white and the curve never goes down`() {
        val lut = ToneCurve.lut(hist(60 to 100, 230 to 900), gamma = 1.0f)
        assertEquals(255, lut[230])
        assertEquals(0, lut[60])
        for (v in 1 until 256) assertTrue(lut[v] >= lut[v - 1], "dropped at $v")
    }

    @Test fun `gamma above one darkens the middle`() {
        val h = hist(40 to 100, 230 to 900)
        val straight = ToneCurve.lut(h, 1.0f)[135]
        val darker = ToneCurve.lut(h, 1.4f)[135]
        assertTrue(darker < straight, "$darker should be below $straight")
    }

    @Test fun `a page with almost no contrast is lifted and not stretched into noise`() {
        val lut = ToneCurve.lut(hist(150 to 500, 160 to 400, 170 to 100), 1.4f)
        for (v in 0 until 256) assertTrue(lut[v] >= v, "lowered $v to ${lut[v]}")
        for (v in 1 until 256) assertTrue(lut[v] >= lut[v - 1], "dropped at $v")
        assertTrue(lut[140] < 255, "grain just under the paper must not be pushed to white")
    }

    @Test fun `a blank white page puts its grain at white and nothing needs lowering`() {
        val lut = ToneCurve.lut(hist(235 to 500, 238 to 400, 240 to 100), 1.4f)
        assertTrue(lut[238] >= 230)
        assertEquals(255, lut[255])
    }

    @Test fun `a few dark pixels set the black point and the paper peak sets the white point`() {
        val lut = ToneCurve.lut(hist(20 to 6, 240 to 994), 1.0f)
        assertEquals(0, lut[20])
        assertEquals(255, lut[240])
    }

    // --- kernels --------------------------------------------------------------------------------

    @Test fun `kernel sizes are odd and clamped`() {
        for (short in listOf(0, 10, 100, 136, 362, 512, 5000)) {
            listOf(KernelSizes.inkEraser(short), KernelSizes.lightingMedian(short), KernelSizes.sauvolaWindow(short))
                .forEach { assertEquals(1, it % 2, "even kernel for $short") }
        }
        assertEquals(5, KernelSizes.inkEraser(0))
        assertEquals(31, KernelSizes.inkEraser(100_000))
        assertEquals(15, KernelSizes.lightingMedian(0))
        assertEquals(63, KernelSizes.lightingMedian(100_000))
    }

    @Test fun `the kernels depend only on the fixed working copy so a preview and a PDF agree`() {
        // Both renders of one page are measured on a copy whose long edge is 512, whatever their size.
        fun shortOfCopy(width: Int, height: Int) = minOf(width, height) * 512 / maxOf(width, height)
        val preview = shortOfCopy(1131, 1600)
        val pdf = shortOfCopy(1697, 2400)
        assertEquals(KernelSizes.inkEraser(preview), KernelSizes.inkEraser(pdf))
        assertEquals(KernelSizes.lightingMedian(preview), KernelSizes.lightingMedian(pdf))
    }
}
