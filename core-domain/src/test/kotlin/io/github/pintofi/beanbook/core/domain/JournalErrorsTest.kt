package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * An error belongs to the entry that caused it.
 *
 * The list at the top of the screen named a file and a line and left the reader to find the
 * transaction themselves. These are the rules for putting the two back together, and the
 * cases that matter are the ones where a wrong answer would mark an innocent entry.
 */
class JournalErrorsTest {

    private fun entry(line: Int, narration: String, file: String = "main.beancount") =
        JournalEntry(
            date = "2026-01-0${line % 9 + 1}",
            flag = "*",
            payee = null,
            narration = narration,
            tags = emptyList(),
            links = emptyList(),
            postings = emptyList(),
            file = file,
            line = line,
        )

    private fun error(line: Int, message: String, file: String? = "main.beancount") =
        LedgerError(message, file, line, "ValidationError")

    private val journal = listOf(entry(14, "third"), entry(8, "second"), entry(4, "first"))

    @Test fun `an error on the entry's own line marks that entry`() {
        val attached = JournalErrors.attach(journal, listOf(error(4, "does not balance")))

        assertEquals(setOf("main.beancount:4"), attached.keys)
        assertEquals("does not balance", attached.getValue("main.beancount:4").single().message)
    }

    @Test fun `an error on a posting line marks the entry containing it`() {
        // Beancount points at the posting for an unknown account, which is a line or two
        // inside the transaction rather than the line it starts on.
        val attached = JournalErrors.attach(journal, listOf(error(10, "unknown account")))

        assertEquals(setOf("main.beancount:8"), attached.keys)
    }

    @Test fun `an error past the last entry of a file still marks it`() {
        val attached = JournalErrors.attach(journal, listOf(error(16, "does not balance")))

        assertEquals(setOf("main.beancount:14"), attached.keys)
    }

    @Test fun `an error between two entries does not reach the later one`() {
        val attached = JournalErrors.attach(journal, listOf(error(8, "at the second")))

        assertEquals(setOf("main.beancount:8"), attached.keys)
    }

    @Test fun `an error before every entry belongs to none of them`() {
        val errors = listOf(error(1, "bad option"))

        assertTrue(JournalErrors.attach(journal, errors).isEmpty())
        assertEquals(errors, JournalErrors.unattached(journal, errors))
    }

    @Test fun `an error in another file does not mark this one`() {
        val errors = listOf(error(4, "elsewhere", file = "other.beancount"))

        assertTrue(JournalErrors.attach(journal, errors).isEmpty())
        assertEquals(errors, JournalErrors.unattached(journal, errors))
    }

    @Test fun `an error with no position stays in the ledger's own list`() {
        val errors = listOf(LedgerError("something went wrong", null, null, "LoadError"))

        assertTrue(JournalErrors.attach(journal, errors).isEmpty())
        assertEquals(errors, JournalErrors.unattached(journal, errors))
    }

    @Test fun `one entry can own several errors`() {
        val attached = JournalErrors.attach(
            journal,
            listOf(error(8, "unknown account"), error(9, "no position matches")),
        )

        assertEquals(2, attached.getValue("main.beancount:8").size)
    }

    @Test fun `errors on entries are not repeated in the ledger's own list`() {
        val onEntry = error(4, "does not balance")
        val onNothing = error(1, "bad option")

        assertEquals(
            listOf(onNothing),
            JournalErrors.unattached(journal, listOf(onEntry, onNothing)),
        )
    }

    @Test fun `a clean ledger attaches nothing`() {
        assertTrue(JournalErrors.attach(journal, emptyList()).isEmpty())
    }
}
