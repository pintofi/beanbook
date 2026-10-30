package io.github.pintofi.beanbook.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Units, At cost and Market value row is judged by the height the widget really has. */
class BasisRowFitsTest {

    @Test fun `upright, the smaller height decides`() {
        assertTrue(WidgetRenderer.basisRowFits(minHeightDp = 120, maxHeightDp = 60, landscape = false))
        assertFalse(WidgetRenderer.basisRowFits(minHeightDp = 60, maxHeightDp = 120, landscape = false))
    }

    @Test fun `on its side, the larger height decides`() {
        assertTrue(WidgetRenderer.basisRowFits(minHeightDp = 60, maxHeightDp = 120, landscape = true))
        assertFalse(WidgetRenderer.basisRowFits(minHeightDp = 120, maxHeightDp = 60, landscape = true))
    }

    @Test fun `the row appears at exactly 90 dp and not below`() {
        assertTrue(WidgetRenderer.basisRowFits(90, 90, landscape = false))
        assertFalse(WidgetRenderer.basisRowFits(89, 89, landscape = false))
    }

    @Test fun `a size that is not known keeps the row`() {
        assertTrue(WidgetRenderer.basisRowFits(0, 0, landscape = false))
        assertTrue(WidgetRenderer.basisRowFits(0, 0, landscape = true))
    }
}
