package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PageSizeTest {

    private fun place(size: PageSize, w: Int, h: Int) = PageLayout.place(size, w, h)

    @Test fun `an A4-shaped statement fills A4 inside the margins`() {
        assertEquals(PagePlacement(59528, 84189, 1417, 2007, 56694, 80174), place(PageSize.A4, 1654, 2339))
    }

    @Test fun `a landscape page goes on landscape paper`() {
        assertEquals(PagePlacement(84189, 59528, 2007, 1417, 80174, 56694), place(PageSize.A4, 2339, 1654))
    }

    @Test fun `a long thin receipt is fitted to the height and centred`() {
        assertEquals(PagePlacement(59528, 84189, 19594, 1417, 20339, 81355), place(PageSize.A4, 600, 2400))
    }

    @Test fun `a square page is portrait`() {
        assertEquals(PagePlacement(59528, 84189, 1417, 13747, 56694, 56694), place(PageSize.A4, 1000, 1000))
    }

    @Test fun `a small crop is not enlarged past twice its natural size`() {
        assertEquals(PagePlacement(61200, 79200, 19800, 25200, 21600, 28800), place(PageSize.LETTER, 300, 400))
    }

    @Test fun `as scanned is the natural size at the origin`() {
        assertEquals(PagePlacement(59544, 84204, 0, 0, 59544, 84204), place(PageSize.AS_SCANNED, 1654, 2339))
    }

    @Test fun `the picture is always inside the margins and keeps its shape`() {
        val random = kotlin.random.Random(184)
        repeat(2_000) {
            val w = random.nextInt(50, 4000)
            val h = random.nextInt(50, 4000)
            PageSize.entries.filter { it != PageSize.AS_SCANNED }.forEach { size ->
                val p = place(size, w, h)
                assertTrue(p.x >= PageLayout.MARGIN && p.y >= PageLayout.MARGIN, "$size $w x $h: $p")
                assertTrue(p.x + p.width <= p.pageWidth - PageLayout.MARGIN + 1)
                assertTrue(p.y + p.height <= p.pageHeight - PageLayout.MARGIN + 1)
                // Same shape to within a hundredth of a point on the longer side.
                assertTrue(kotlin.math.abs(p.width.toLong() * h - p.height.toLong() * w) <= maxOf(w, h), "$p $w x $h")
            }
        }
    }

    @Test fun `the choices start with as scanned and put the local paper second`() {
        assertEquals(listOf(PageSize.AS_SCANNED, PageSize.LETTER, PageSize.A4), PageLayout.choices(PageSize.LETTER))
        assertEquals(listOf(PageSize.AS_SCANNED, PageSize.A4, PageSize.LETTER), PageLayout.choices(PageSize.A4))
    }

    @Test fun `a page with no size is refused`() {
        assertFailsWith<IllegalArgumentException> { place(PageSize.A4, 0, 100) }
        assertFailsWith<IllegalArgumentException> { place(PageSize.A4, 100, -1) }
    }
}
