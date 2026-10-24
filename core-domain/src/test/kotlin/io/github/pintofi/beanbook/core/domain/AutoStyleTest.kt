package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class AutoStyleTest {

    private fun choose(total: Int, coloured: Int) =
        AutoStyle.choose(ColourStats(total, counted = total, coloured = coloured))

    @Test fun `a page with a few tenths of a percent of colour is a colour scan`() {
        assertEquals(PageStyle.DOCUMENT, choose(total = 20_000, coloured = 60))
        assertEquals(PageStyle.DOCUMENT, choose(total = 60_000, coloured = 200))
    }

    @Test fun `just under the share is grey`() {
        assertEquals(PageStyle.GREYSCALE, choose(total = 20_000, coloured = 59))
        assertEquals(PageStyle.GREYSCALE, choose(total = 60_000, coloured = 179))
    }

    @Test fun `a handful of coloured pixels is noise, however small the page`() {
        assertEquals(PageStyle.GREYSCALE, choose(total = 100, coloured = 39))
    }

    @Test fun `an empty page is grey and big counts do not overflow`() {
        assertEquals(PageStyle.GREYSCALE, choose(total = 0, coloured = 0))
        assertEquals(PageStyle.DOCUMENT, choose(total = Int.MAX_VALUE / 2, coloured = Int.MAX_VALUE / 2))
        assertEquals(PageStyle.GREYSCALE, choose(total = Int.MAX_VALUE, coloured = 100))
    }
}
