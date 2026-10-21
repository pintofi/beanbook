package io.github.pintofi.beanbook.app.ui

import org.junit.Test
import org.junit.Assert.assertEquals

class SignToggleTest {
    @Test fun `a positive amount becomes negative`() {
        assertEquals("-12.50", withSignToggled("12.50"))
    }

    @Test fun `a negative amount becomes positive`() {
        assertEquals("12.50", withSignToggled("-12.50"))
    }

    @Test fun `a plus sign turns into a minus`() {
        assertEquals("-3", withSignToggled("+3"))
    }

    @Test fun `an empty box becomes a lone minus to type after`() {
        assertEquals("-", withSignToggled(""))
    }

    @Test fun `a lone minus goes back to empty`() {
        assertEquals("", withSignToggled("-"))
    }

    @Test fun `surrounding spaces do not matter`() {
        assertEquals("-7", withSignToggled("  7 "))
    }

    @Test fun `toggling twice returns the original`() {
        assertEquals("1,250.75", withSignToggled(withSignToggled("1,250.75")))
    }
}
