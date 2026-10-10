package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Units, At cost and Market value, as Fava's switch reads them (B95). */
class ValuationTest {

    private fun amount(value: String, currency: String) = Amount(BigDecimal(value), currency)

    private val book = PriceBook(listOf(PriceRate("SAR", "PKR", BigDecimal("75"))))

    @Test
    fun `a price converts directly, by its inverse, and not at all without a route`() {
        assertEquals(BigDecimal("7500"), book.convert(amount("100", "SAR"), "PKR")?.number)
        assertEquals(0, BigDecimal("75").compareTo(book.convert(amount("5625", "PKR"), "SAR")?.number))
        assertNull(book.convert(amount("1", "EUR"), "PKR"))
    }

    @Test
    fun `a posting is valued on each basis`() {
        val bought = JournalPosting(
            "Assets:Shares", amount("10", "HOOL"), cost = Cost(amount("100", "USD")),
        )
        val shares = PriceBook(listOf(PriceRate("HOOL", "USD", BigDecimal("120"))))

        assertEquals(amount("10", "HOOL"), Valuation.UNITS.ofPosting(bought, shares, "USD"))
        assertEquals(amount("1000", "USD"), Valuation.COST.ofPosting(bought, shares, "USD"))
        assertEquals(amount("1200", "USD"), Valuation.VALUE.ofPosting(bought, shares, "USD"))
    }

    @Test
    fun `market value without a price falls back to cost, then to units`() {
        val bought = JournalPosting("Assets:Art", amount("1", "PAINTING"), cost = Cost(amount("500", "USD")))
        val loose = JournalPosting("Assets:Foreign", amount("2", "EUR"))

        assertEquals(amount("500", "USD"), Valuation.VALUE.ofPosting(bought, PriceBook.EMPTY, "USD"))
        assertEquals(amount("2", "EUR"), Valuation.VALUE.ofPosting(loose, PriceBook.EMPTY, "USD"))
    }

    @Test
    fun `spending follows the basis, so a riyal expense counts in rupees at market value`() {
        val entry = JournalEntry(
            date = "2026-09-02", flag = "*", payee = null, narration = "n",
            tags = emptyList(), links = emptyList(),
            postings = listOf(JournalPosting("Expenses:Travel", amount("100", "SAR"))),
            file = "m", line = 1,
        )
        val ledger = LedgerSnapshot(
            title = "T", operatingCurrencies = listOf("PKR"), entryCount = 1, entryCounts = emptyMap(),
            accounts = emptyList(), journal = listOf(entry), errors = emptyList(),
            includedFiles = emptyList(), loadMillis = 0,
            rates = listOf(PriceRate("SAR", "PKR", BigDecimal("75"))),
        )
        val today = LocalDate.parse("2026-09-11")

        val units = HomeInsights.spending(ledger, SpendingPeriod.THIS_MONTH, today, valuation = Valuation.UNITS)
        val value = HomeInsights.spending(ledger, SpendingPeriod.THIS_MONTH, today, valuation = Valuation.VALUE)

        assertEquals(listOf("SAR"), units.ignoredCurrencies)
        assertEquals(BigDecimal("7500"), value.total.number)
    }
}
