package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A ledger says how it wants to be read, and the app now listens (B59).
 *
 * Beancount works the display context out from the numbers in the file, and `render_commas`
 * is the user's own option. The app used neither, so a currency written to three places with
 * separators came out at two places without them — the app quietly disagreeing with the file
 * it was showing.
 */
class LedgerFormatTest {

    private val pkr = LedgerFormat(
        groupThousands = true,
        precision = mapOf("PKR" to 3),
        tolerances = mapOf("PKR" to BigDecimal("0.005")),
    )

    private fun amount(value: String, currency: String = "PKR") =
        Amount(BigDecimal(value), currency)

    @Test fun `places and separators come from the file`() {
        assertEquals("1,234,567.891 PKR", pkr.format(amount("1234567.891")))
    }

    @Test fun `a figure is padded to the places the file uses`() {
        assertEquals("12.000 PKR", pkr.format(amount("12")))
    }

    @Test fun `a negative keeps its sign outside the grouping`() {
        assertEquals("-1,000.500 PKR", pkr.format(amount("-1000.5")))
    }

    @Test fun `a commodity the file says nothing about keeps its own digits`() {
        // Inventing a precision for a share price would be the app deciding how somebody's
        // holdings are quoted.
        assertEquals("2.5 HOOL", pkr.format(amount("2.5", "HOOL")))
    }

    @Test fun `a ledger that says nothing gets plain rendering`() {
        assertEquals("1234567.891 PKR", LedgerFormat.PLAIN.format(amount("1234567.891")))
    }

    /** The file's own tolerance decides what balances, not the app's inference. */
    @Test fun `a residual inside the file's tolerance is not an imbalance`() {
        val draft = TransactionDraft(
            date = "2026-09-08",
            postings = listOf(
                PostingDraft("Assets:Cash", amount("10.000")),
                PostingDraft("Expenses:Food", amount("-9.996")),
            ),
        )
        assertTrue(
            draft.validate(pkr).none { it is DraftProblem.DoesNotBalance },
            "the file says 0.005 is close enough: ${draft.validate(pkr)}",
        )
    }

    @Test fun `a residual outside it still is`() {
        val draft = TransactionDraft(
            date = "2026-09-08",
            postings = listOf(
                PostingDraft("Assets:Cash", amount("10.000")),
                PostingDraft("Expenses:Food", amount("-9.900")),
            ),
        )
        assertTrue(draft.validate(pkr).any { it is DraftProblem.DoesNotBalance })
    }
}
