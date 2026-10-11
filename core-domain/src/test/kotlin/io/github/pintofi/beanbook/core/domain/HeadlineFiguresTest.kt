package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * A headline of many commodities, capped (device audit 2026-10-10: Home in Units stacked twenty
 * lines of net worth in its largest type).
 */
class HeadlineFiguresTest {

    private fun amount(value: String, currency: String) = Amount(BigDecimal(value), currency)

    @Test
    fun `a figure of three lines or fewer is shown whole`() {
        val figures = HeadlineFigures.of(
            listOf(amount("10", "USD"), amount("5", "EUR")),
        )

        assertEquals(2, figures.shown.size)
        assertEquals(0, figures.more)
    }

    @Test
    fun `more than three lines shows three and counts the rest`() {
        val figures = HeadlineFigures.of(
            listOf(
                amount("6", "APL"),
                amount("2000.00", "SAR"),
                amount("9.97", "AIRSF"),
                amount("-637.9074", "AMMF"),
                amount("50", "GOOG"),
            ),
        )

        assertEquals(3, figures.shown.size)
        assertEquals(2, figures.more)
    }

    @Test
    fun `operating currencies come first, in the order the file declares them`() {
        val figures = HeadlineFigures.of(
            listOf(
                amount("999999", "AAA"),
                amount("10", "USD"),
                amount("20", "PKR"),
            ),
            operating = listOf("PKR", "USD"),
        )

        assertEquals(listOf("PKR", "USD", "AAA"), figures.shown.map { it.currency })
    }

    @Test
    fun `the rest are largest first, by absolute size, so a large debt is not hidden`() {
        val figures = HeadlineFigures.of(
            listOf(
                amount("6", "APL"),
                amount("-5000", "LOAN"),
                amount("2000.00", "SAR"),
                amount("1", "BTC"),
            ),
        )

        assertEquals(listOf("LOAN", "SAR", "APL"), figures.shown.map { it.currency })
        assertEquals(1, figures.more)
    }

    @Test
    fun `zero amounts take no line and are not counted as more`() {
        val figures = HeadlineFigures.of(
            listOf(
                amount("0.00", "USD"),
                amount("1", "EUR"),
                amount("0", "GBP"),
            ),
        )

        assertEquals(listOf("EUR"), figures.shown.map { it.currency })
        assertEquals(0, figures.more)
    }

    @Test
    fun `ties keep a stable order by commodity name`() {
        val forward = HeadlineFigures.of(listOf(amount("5", "BBB"), amount("5", "AAA")))
        val backward = HeadlineFigures.of(listOf(amount("5", "AAA"), amount("5", "BBB")))

        assertEquals(listOf("AAA", "BBB"), forward.shown.map { it.currency })
        assertEquals(forward, backward)
    }

    @Test
    fun `amounts come out exactly as they went in`() {
        // Nothing is rounded or rescaled: the caller formats with the ledger's own format.
        val figures = HeadlineFigures.of(listOf(amount("-637.9074", "AMMF")))

        assertEquals(BigDecimal("-637.9074"), figures.shown.single().number)
    }

    @Test
    fun `the cap can be narrowed for a smaller space`() {
        val figures = HeadlineFigures.of(
            listOf(amount("1", "A"), amount("2", "B"), amount("3", "C")),
            max = 1,
        )

        assertEquals(listOf("C"), figures.shown.map { it.currency })
        assertEquals(2, figures.more)
    }

    @Test
    fun `a headline of no lines is refused`() {
        assertFailsWith<IllegalArgumentException> { HeadlineFigures.of(emptyList(), max = 0) }
    }

    @Test
    fun `totals and the formatted summary agree`() {
        val ledger = LedgerSnapshot(
            title = "Totals",
            operatingCurrencies = listOf("USD"),
            entryCount = 1,
            entryCounts = mapOf("Transaction" to 1),
            accounts = listOf(
                AccountBalance("Assets:Bank", listOf(amount("1240.00", "USD"))),
                AccountBalance("Liabilities:Card", listOf(amount("-300.00", "USD"))),
                AccountBalance("Income:Salary", listOf(amount("-3000.00", "USD"))),
            ),
            journal = emptyList(),
            errors = emptyList(),
            includedFiles = emptyList(),
            loadMillis = 0,
        )

        val totals = LedgerSummary.totals(ledger)
        val summary = LedgerSummary.of(ledger)

        assertEquals(LedgerSummary.format(totals.netWorth, ledger.format), summary.netWorth)
        assertEquals(LedgerSummary.format(totals.income, ledger.format), summary.income)
        assertEquals(listOf(amount("940.00", "USD")), totals.netWorth)
    }
}
