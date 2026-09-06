package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Finding one entry among thousands (B53).
 *
 * The search is word-by-word rather than a substring of the whole entry, because that is how
 * a transaction is remembered: two fragments — who and roughly where the money went — which
 * never appear next to each other in the text.
 */
class JournalSearchTest {

    private val entry = JournalEntry(
        date = "2026-03-14",
        flag = "*",
        payee = "Corner Coffee",
        narration = "flat white",
        tags = listOf("treat"),
        links = emptyList(),
        postings = listOf(
            JournalPosting("Expenses:Food:Coffee", Amount(BigDecimal("3.40"), "USD")),
            JournalPosting("Assets:Bank:Checking", Amount(BigDecimal("-3.40"), "USD")),
        ),
        file = "main.beancount",
        line = 12,
        metadata = mapOf("note" to "paid by card"),
    )

    @Test fun `an empty query matches everything`() {
        assertTrue(entry.matches("   "))
    }

    @Test fun `words may come from different parts of the entry`() {
        assertTrue(entry.matches("coff bank"))
    }

    @Test fun `every word has to appear`() {
        assertFalse(entry.matches("coffee taxi"))
    }

    @Test fun `the date, the amount, a tag and metadata are all searchable`() {
        assertTrue(entry.matches("2026-03"))
        assertTrue(entry.matches("3.40"))
        assertTrue(entry.matches("treat"))
        assertTrue(entry.matches("card"))
    }

    @Test fun `case does not matter`() {
        assertTrue(entry.matches("CORNER white"))
    }

    /** The check has to bite: a query matching nothing in the entry must fail. */
    @Test fun `an unrelated word matches nothing`() {
        assertFalse(entry.matches("mortgage"))
    }
}
