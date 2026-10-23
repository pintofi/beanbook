package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The three figures the home surface and the widgets both show.
 *
 * One definition, in one place. `WidgetCache` computed these privately, and its own comment
 * warned that formatting them somewhere else is "how two places start disagreeing about what
 * a number looks like" — which is exactly what adding a home screen would have done.
 */
class LedgerSummaryTest {

    private fun amount(value: String, currency: String = "USD") =
        Amount(BigDecimal(value), currency)

    private fun ledgerOf(vararg accounts: Pair<String, List<Amount>>) = LedgerSnapshot(
        title = "Summary",
        operatingCurrencies = listOf("USD"),
        entryCount = 1,
        entryCounts = mapOf("Transaction" to 1),
        accounts = accounts.map { (name, positions) -> AccountBalance(name, positions) },
        journal = emptyList(),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    @Test
    fun `net worth adds liabilities rather than subtracting them`() {
        // Beancount already stores what you owe as a negative balance. Negating it made debt
        // raise net worth, which is the most cheerful possible way to be wrong.
        val summary = LedgerSummary.of(
            ledgerOf(
                "Assets:Bank" to listOf(amount("1240.00")),
                "Liabilities:Card" to listOf(amount("-300.00")),
            )
        )

        assertEquals("940.00 USD", summary.netWorth)
    }

    @Test
    fun `income is shown as a positive figure`() {
        // Income is stored negative too; a home screen saying "-3000.00" earned is nonsense.
        val summary = LedgerSummary.of(ledgerOf("Income:Salary" to listOf(amount("-3000.00"))))

        assertEquals("3000.00 USD", summary.income)
    }

    @Test
    fun `expenses are shown as they are stored`() {
        val summary = LedgerSummary.of(ledgerOf("Expenses:Food" to listOf(amount("262.00"))))

        assertEquals("262.00 USD", summary.expenses)
    }

    @Test
    fun `two currencies stay two figures on two lines`() {
        val summary = LedgerSummary.of(
            ledgerOf(
                "Assets:USD" to listOf(amount("100.00")),
                "Assets:EUR" to listOf(amount("50.00", "EUR")),
            )
        )

        assertEquals("50.00 EUR\n100.00 USD", summary.netWorth)
    }

    @Test
    fun `an empty ledger reports nothing rather than zero`() {
        // Zero is a claim about someone's finances. Nothing is the truth here.
        val summary = LedgerSummary.of(ledgerOf())

        assertNull(summary.netWorth)
        assertNull(summary.income)
        assertNull(summary.expenses)
    }

    // The engine used to round each account's value to two places and the totals added the
    // rounded parts, so three accounts worth 1.005 each read 3.00 where Fava, which adds exact
    // values and rounds the sum, reads 3.02 (#60). Accounts now arrive exact and the sum is
    // rounded once, to the places the ledger shows the currency in.
    @Test
    fun `a total is the exact sum rounded once, not the sum of rounded parts`() {
        val ledger = ledgerOf(
            "Assets:A" to listOf(amount("1.005")),
            "Assets:B" to listOf(amount("1.005")),
            "Assets:C" to listOf(amount("1.005")),
        ).copy(format = LedgerFormat(precision = mapOf("USD" to 2)))

        assertEquals("3.02 USD", LedgerSummary.of(ledger).netWorth)
    }

    @Test
    fun `income and expenses are rounded once too`() {
        val ledger = ledgerOf(
            "Income:A" to listOf(amount("-1.005")),
            "Income:B" to listOf(amount("-1.005")),
            "Income:C" to listOf(amount("-1.005")),
            "Expenses:A" to listOf(amount("0.335")),
            "Expenses:B" to listOf(amount("0.335")),
            "Expenses:C" to listOf(amount("0.335")),
        ).copy(format = LedgerFormat(precision = mapOf("USD" to 2)))

        val summary = LedgerSummary.of(ledger)

        assertEquals("3.02 USD", summary.income)
        // 3 x 0.335 is exactly 1.005, which half to even reads as 1.00. Rounding the parts first
        // (0.34 each) would have said 1.02.
        assertEquals("1.00 USD", summary.expenses)
    }
}
