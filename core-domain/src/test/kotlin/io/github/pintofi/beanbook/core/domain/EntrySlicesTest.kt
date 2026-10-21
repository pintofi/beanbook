package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

class EntrySlicesTest {

    private val file = """
        option "title" "T"

        2026-01-01 * "A" "first"
          Expenses:X    1.00 USD
          Assets:Y

        2026-01-02 * "B" "second"
          ; a comment
          Expenses:X    2.00 USD
          Assets:Y
        2026-01-03 * "C" "touching the one above"
          Expenses:X    3.00 USD
          Assets:Y
    """.trimIndent() + "\n"

    private val lines = EntrySlices.linesOf(file)

    @Test fun `an entry runs to the blank line after it`() {
        val slice = EntrySlices.at(lines, 3)!!
        assertEquals(3, slice.startLine)
        assertEquals(5, slice.endLine)
        assertEquals("2026-01-01 * \"A\" \"first\"\n  Expenses:X    1.00 USD\n  Assets:Y", slice.text)
    }

    @Test fun `a comment inside an entry is part of it, and a line in column zero ends it`() {
        val slice = EntrySlices.at(lines, 7)!!
        assertEquals(7, slice.startLine)
        assertEquals(10, slice.endLine)
        assertEquals(true, slice.text.contains("; a comment"))
        assertEquals(11, EntrySlices.at(lines, 11)!!.startLine)
    }

    @Test fun `a position with no entry on it is not an entry`() {
        assertNull(EntrySlices.at(lines, 1), "an option line is not an entry")
        assertNull(EntrySlices.at(lines, 4), "an indented posting is not where an entry starts")
        assertNull(EntrySlices.at(lines, 99))
        assertNull(EntrySlices.at(lines, 0))
    }

    @Test fun `windows line endings give the same text and extent`() {
        val crlf = EntrySlices.linesOf(file.replace("\n", "\r\n"))
        val plain = EntrySlices.at(lines, 3)!!
        val windows = EntrySlices.at(crlf, 3)!!
        assertEquals(plain, windows)
    }

    @Test fun `a changed entry has a different hash`() {
        val other = EntrySlices.linesOf(file.replace("1.00 USD", "1.10 USD"))
        assertNotEquals(EntrySlices.at(lines, 3)!!.sha256, EntrySlices.at(other, 3)!!.sha256)
    }

    @Test fun `the last entry in a file without a final newline is found`() {
        val trimmed = EntrySlices.linesOf(file.trimEnd())
        assertEquals(13, EntrySlices.at(trimmed, 11)!!.endLine)
    }
}
