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

/** The sign button keeps the cursor on the character it was on. */
class SignToggleCursorTest {
    private fun at(text: String, start: Int, end: Int = start) =
        androidx.compose.ui.text.input.TextFieldValue(text, androidx.compose.ui.text.TextRange(start, end))

    @org.junit.Test fun `a minus added in front moves the cursor one place right`() {
        val result = withSignToggled(at("1230", 4))
        assertEquals("-1230", result.text)
        assertEquals(5, result.selection.start)
    }

    @org.junit.Test fun `a cursor in the middle stays between the same two digits`() {
        val result = withSignToggled(at("1230", 2))
        assertEquals("-1230", result.text)
        assertEquals(3, result.selection.start)
    }

    @org.junit.Test fun `a minus taken away moves the cursor one place left`() {
        val result = withSignToggled(at("-1230", 5))
        assertEquals("1230", result.text)
        assertEquals(4, result.selection.start)
    }

    @org.junit.Test fun `a cursor before the digits stays before them`() {
        assertEquals(1, withSignToggled(at("1230", 0)).selection.start)
        assertEquals(0, withSignToggled(at("-1230", 1)).selection.start)
    }

    @org.junit.Test fun `a plus becomes a minus and the cursor stays on its character`() {
        val result = withSignToggled(at("+50", 3))
        assertEquals("-50", result.text)
        assertEquals(3, result.selection.start)
    }

    @org.junit.Test fun `an empty box gets a minus with the cursor after it`() {
        val result = withSignToggled(at("", 0))
        assertEquals("-", result.text)
        assertEquals(1, result.selection.start)
    }

    @org.junit.Test fun `a selected range moves as a whole`() {
        val result = withSignToggled(at("1230", 1, 3))
        assertEquals(2, result.selection.start)
        assertEquals(4, result.selection.end)
    }

    @org.junit.Test fun `toggling twice returns the text and the cursor`() {
        val twice = withSignToggled(withSignToggled(at("1,250.75", 3)))
        assertEquals("1,250.75", twice.text)
        assertEquals(3, twice.selection.start)
    }
}
