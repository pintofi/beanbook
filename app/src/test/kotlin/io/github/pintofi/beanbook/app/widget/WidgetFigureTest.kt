package io.github.pintofi.beanbook.app.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/** The one-figure widget never hides its first line behind the ones after it. */
class WidgetFigureTest {

    @Test fun `one line is shown as it is`() {
        assertEquals("812,345.67 PKR", widgetFigure("812,345.67 PKR"))
    }

    @Test fun `two lines are shown as they are`() {
        assertEquals("100.00 PKR\n2.00 USD", widgetFigure("100.00 PKR\n2.00 USD"))
    }

    @Test fun `more than two lines keep the first and count the rest`() {
        assertEquals(
            "-250000.50 PKR\n+3 more",
            widgetFigure("-250000.50 PKR\n2000.00 SAR\n80.00 EGF-E\n6 ACME"),
        )
    }

    @Test fun `blank lines are not counted`() {
        assertEquals("1.00 EUR", widgetFigure("1.00 EUR\n"))
    }
}
