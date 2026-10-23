package io.github.pintofi.beanbook.app.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PdfBitmapSizeTest {
    @Test
    fun `an A4 page is drawn at the full width in its own shape`() {
        val size = pdfBitmapSize(595, 842)!!
        assertEquals(1440, size.width)
        assertEquals(2037, size.height)
    }

    @Test
    fun `a landscape page keeps its shape`() {
        val size = pdfBitmapSize(842, 595)!!
        assertEquals(1440, size.width)
        assertEquals(1017, size.height)
    }

    @Test
    fun `a page 50 points wide and 100000 tall is refused, not allocated`() {
        assertNull(pdfBitmapSize(50, 100_000))
    }

    @Test
    fun `a page with no area is refused`() {
        assertNull(pdfBitmapSize(0, 842))
        assertNull(pdfBitmapSize(595, 0))
        assertNull(pdfBitmapSize(-1, 842))
    }

    @Test
    fun `a very wide page is refused`() {
        assertNull(pdfBitmapSize(100_000, 50))
    }

    @Test
    fun `a long receipt is made narrower, not cropped, to stay under the pixel limit`() {
        val size = pdfBitmapSize(100, 2000)!! // ratio 20, the longest allowed
        assertTrue("${size.width} x ${size.height}", size.width.toLong() * size.height <= 16_000_000)
        assertTrue(size.width < 1440)
        // The shape is the page's, within a pixel of rounding.
        assertEquals(20.0, size.height.toDouble() / size.width, 0.05)
    }

    @Test
    fun `a long receipt of ratio 6 still fits at the full width`() {
        val size = pdfBitmapSize(100, 600)!!
        assertEquals(1440, size.width)
        assertEquals(8640, size.height)
        assertTrue(size.width.toLong() * size.height <= 16_000_000)
    }
}
