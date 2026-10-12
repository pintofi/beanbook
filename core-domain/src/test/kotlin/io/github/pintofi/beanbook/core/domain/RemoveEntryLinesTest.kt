package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** An entry is followed by one blank line, and a delete takes that line with it (B101). */
class RemoveEntryLinesTest {

    private fun lines(text: String) = text.split(Regex("(?<=\n)")).filter { it.isNotEmpty() }.toMutableList()

    @Test
    fun `an entry written by the app is removed with the blank line after it`() {
        // "A", a blank line, the entry, and the blank line that follows every entry.
        val l = lines("2026-01-01 open Assets:Cash\n\n2026-01-02 *\n  A  1 USD\n  B\n\n")

        BeancountText.removeEntryLines(l, startLine = 3, endLine = 5)

        assertEquals("2026-01-01 open Assets:Cash\n\n", l.joinToString(""))
    }

    @Test
    fun `an entry between two others takes one blank line, not both`() {
        val l = lines("a\n\nENTRY\n  p\n\nb\n")

        BeancountText.removeEntryLines(l, startLine = 3, endLine = 4)

        assertEquals("a\n\nb\n", l.joinToString(""))
    }

    @Test
    fun `an entry with no blank line after it takes the one before`() {
        val l = lines("a\n\nENTRY\n  p\n")

        BeancountText.removeEntryLines(l, startLine = 3, endLine = 4)

        assertEquals("a\n", l.joinToString(""))
    }

    @Test
    fun `an entry glued to its neighbours takes nothing extra`() {
        val l = lines("a\nENTRY\nb\n")

        BeancountText.removeEntryLines(l, startLine = 2, endLine = 2)

        assertEquals("a\nb\n", l.joinToString(""))
    }
}
