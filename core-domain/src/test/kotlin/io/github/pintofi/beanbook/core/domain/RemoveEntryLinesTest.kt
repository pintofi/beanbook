package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/** Adding an entry and deleting it again leaves the file as it was (B99). */
class RemoveEntryLinesTest {

    private fun lines(text: String) = text.split(Regex("(?<=\n)")).filter { it.isNotEmpty() }.toMutableList()

    @Test
    fun `an appended entry is removed with the blank line that set it apart`() {
        val original = "2026-01-01 open Assets:Cash\n"
        val appended = original + "\n2026-01-02 *\n  A  1 USD\n  B\n"
        val l = lines(appended)

        BeancountText.removeEntryLines(l, startLine = 3, endLine = 5)

        assertEquals(original, l.joinToString(""))
    }

    @Test
    fun `an entry between two others takes one blank line, not both`() {
        val l = lines("a\n\nENTRY\n  p\n\nb\n")

        BeancountText.removeEntryLines(l, startLine = 3, endLine = 4)

        assertEquals("a\n\nb\n", l.joinToString(""))
    }

    @Test
    fun `the first entry in a file takes the blank line after it`() {
        val l = lines("ENTRY\n  p\n\nb\n")

        BeancountText.removeEntryLines(l, startLine = 1, endLine = 2)

        assertEquals("b\n", l.joinToString(""))
    }

    @Test
    fun `an entry glued to its neighbours takes nothing extra`() {
        val l = lines("a\nENTRY\nb\n")

        BeancountText.removeEntryLines(l, startLine = 2, endLine = 2)

        assertEquals("a\nb\n", l.joinToString(""))
    }
}
