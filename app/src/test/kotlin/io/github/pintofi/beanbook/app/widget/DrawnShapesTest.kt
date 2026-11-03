package io.github.pintofi.beanbook.app.widget

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** A launcher repeats a widget's size; only a report that changes how it is drawn redraws it. */
class DrawnShapesTest {

    private val tall = WidgetShape(basisRow = true, keyguard = false)
    private val short = WidgetShape(basisRow = false, keyguard = false)
    private val lockScreen = WidgetShape(basisRow = true, keyguard = true)

    @Test fun `a widget not drawn yet is drawn`() {
        assertFalse(DrawnShapes.isDrawn(9001, tall))
    }

    @Test fun `the same shape again is not drawn again`() {
        DrawnShapes.remember(9002, tall)
        assertTrue(DrawnShapes.isDrawn(9002, tall))
    }

    @Test fun `losing the basis row or moving to the lock screen is drawn`() {
        DrawnShapes.remember(9003, tall)
        assertFalse(DrawnShapes.isDrawn(9003, short))
        assertFalse(DrawnShapes.isDrawn(9003, lockScreen))
    }

    @Test fun `each widget has its own shape, and a removed one is forgotten`() {
        DrawnShapes.remember(9004, tall)
        DrawnShapes.remember(9005, short)
        assertFalse(DrawnShapes.isDrawn(9005, tall))
        DrawnShapes.forget(intArrayOf(9004))
        assertFalse(DrawnShapes.isDrawn(9004, tall))
    }
}
