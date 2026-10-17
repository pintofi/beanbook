package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A ledger says how it wants to be read, and the app now listens.
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

    /**
     * Beancount rounds half to even, and so must this.
     *
     * Its display context quantizes through Python's default decimal context, so a file
     * written to two places shows 2.345 as 2.34 and 2.355 as 2.36. Half-up -- the schoolbook
     * rule -- gives 2.35 and 2.36, which puts the app one hundredth away from the figure
     * Fava shows for the same posting, inside the same app.
     */
    @Test fun `rounding for display is the file's engine's, not the schoolbook one`() {
        val twoPlaces = LedgerFormat(precision = mapOf("USD" to 2))

        assertEquals("2.34 USD", twoPlaces.format(amount("2.345", "USD")))
        assertEquals("2.36 USD", twoPlaces.format(amount("2.355", "USD")))
        assertEquals("2.36 USD", twoPlaces.format(amount("2.365", "USD")))
        assertEquals("-2.34 USD", twoPlaces.format(amount("-2.345", "USD")))
        assertEquals("1234.57 USD", twoPlaces.format(amount("1234.567", "USD")))
    }

    /** A lot is a figure too: `{400.00 PKR}` follows the same places and separators. */
    @Test fun `a cost is written the way the file writes figures`() {
        val cost = Cost(amount("4000.5"), date = "2025-01-01", label = "lot-a")

        assertEquals("{4,000.500 PKR, 2025-01-01, \"lot-a\"}", pkr.format(cost))
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

/**
 * The rest of what a ledger says about how its figures read.
 *
 * Beancount's own options were already honoured; Fava's were not, and Fava's are where the
 * separators and the signs are decided. A German ledger displayed with the English scheme
 * turns one thousand two hundred into one point two, and a ledger that asks for income to
 * read positively and gets the raw credit sign shows every wage with a minus in front of it.
 */
class FavaFormatTest {

    private fun amount(value: String, currency: String = "EUR") =
        Amount(java.math.BigDecimal(value), currency)

    private val german = LedgerFormat(
        groupThousands = true,
        precision = mapOf("EUR" to 2),
        groupSeparator = '.',
        decimalSeparator = ',',
    )

    @Test fun `a german ledger groups with dots and points with a comma`() {
        kotlin.test.assertEquals("1.234,56 EUR", german.format(amount("1234.56")))
    }

    @Test fun `a german ledger keeps the comma without grouping`() {
        val ungrouped = german.copy(groupThousands = false)
        kotlin.test.assertEquals("1234,56 EUR", ungrouped.format(amount("1234.56")))
    }

    @Test fun `the english scheme is unchanged`() {
        val english = LedgerFormat(groupThousands = true, precision = mapOf("USD" to 2))
        kotlin.test.assertEquals("1,234.56 USD", english.format(amount("1234.56", "USD")))
    }

    @Test fun `a negative figure keeps its sign in front of the separators`() {
        kotlin.test.assertEquals("-1.234,56 EUR", german.format(amount("-1234.56")))
    }

    @Test fun `income reads positively when the ledger asks for it`() {
        val inverted = LedgerFormat(invertIncomeLiabilitiesEquity = true)
        kotlin.test.assertEquals(
            amount("1200.00"),
            inverted.signed(amount("-1200.00"), "Income:Salary"),
        )
    }

    @Test fun `assets and expenses are never turned around`() {
        val inverted = LedgerFormat(invertIncomeLiabilitiesEquity = true)
        kotlin.test.assertEquals(
            amount("-12.50"),
            inverted.signed(amount("-12.50"), "Assets:Cash"),
        )
        kotlin.test.assertEquals(
            amount("12.50"),
            inverted.signed(amount("12.50"), "Expenses:Food"),
        )
    }

    @Test fun `a ledger that does not ask is left alone`() {
        kotlin.test.assertEquals(
            amount("-1200.00"),
            LedgerFormat.PLAIN.signed(amount("-1200.00"), "Income:Salary"),
        )
    }

    @Test fun `the three inverted roots are named exactly`() {
        val inverted = LedgerFormat(invertIncomeLiabilitiesEquity = true)
        kotlin.test.assertTrue(inverted.isInverted("Liabilities:Card"))
        kotlin.test.assertTrue(inverted.isInverted("Equity:Opening-Balances"))
        // Not a prefix match on the bare string: "Incomes" is somebody else's account tree.
        kotlin.test.assertFalse(inverted.isInverted("Incomes:Salary"))
    }

    @Test fun `the write layout defaults to what fava writes`() {
        kotlin.test.assertEquals(61, LedgerFormat.PLAIN.currencyColumn)
        kotlin.test.assertEquals(2, LedgerFormat.PLAIN.indent)
    }
}
