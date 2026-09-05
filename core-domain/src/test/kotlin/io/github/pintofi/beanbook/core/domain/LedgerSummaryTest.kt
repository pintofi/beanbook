package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * The three figures the home surface and the widgets both show (B10).
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
}
