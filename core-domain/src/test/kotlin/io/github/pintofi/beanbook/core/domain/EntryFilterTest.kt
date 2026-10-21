package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class EntryFilterTest {

    private fun entry(payee: String, narration: String, tags: List<String> = emptyList(), amount: String = "10.00") =
        JournalEntry(
            date = "2026-08-01", flag = "*", payee = payee, narration = narration, tags = tags, links = emptyList(),
            postings = listOf(
                JournalPosting("Expenses:Misc", Amount(BigDecimal(amount), "PKR")),
                JournalPosting("Assets:Cash", Amount(BigDecimal(amount).negate(), "PKR")),
            ),
            file = "main.beancount", line = 1,
        )

    private val ledger = LedgerSnapshot(
        title = "T", operatingCurrencies = listOf("PKR"), entryCount = 3, entryCounts = mapOf("Transaction" to 3),
        accounts = listOf(AccountBalance("Assets:Cash", listOf(Amount(BigDecimal("-60.00"), "PKR")))),
        journal = listOf(
            entry("Samuel", "Lunch", amount = "10.00"),
            entry("Imtiaz", "Groceries", tags = listOf("food"), amount = "20.00"),
            entry("Samuel", "Taxi", tags = listOf("trip"), amount = "30.00"),
        ),
        errors = emptyList(), includedFiles = emptyList(), loadMillis = 0,
    )

    @Test fun `an inactive filter is the ledger itself`() {
        assertSame(ledger, ledger.filteredBy(EntryFilter()))
    }

    @Test fun `a search keeps the matching entries and totals only those`() {
        val shown = ledger.filteredBy(EntryFilter(query = "samuel"))
        assertEquals(listOf("Lunch", "Taxi"), shown.journal.map { it.narration })
        val cash = shown.accounts.first { it.name == "Assets:Cash" }
        assertEquals(listOf(Amount(BigDecimal("-40.00"), "PKR")), cash.positions)
    }

    @Test fun `a search and a tag both have to match`() {
        val shown = ledger.filteredBy(EntryFilter(query = "samuel", tags = "#trip"))
        assertEquals(listOf("Taxi"), shown.journal.map { it.narration })
    }

    @Test fun `the filtered figures are at cost, not at a market value that was never computed for them`() {
        val shown = ledger.filteredBy(EntryFilter(query = "imtiaz"))
        val cash = shown.accounts.first { it.name == "Assets:Cash" }
        assertEquals(cash.atCost, cash.atValue)
    }

    @Test fun `nothing matching leaves an empty ledger, not the whole one`() {
        val shown = ledger.filteredBy(EntryFilter(query = "nobody"))
        assertTrue(shown.journal.isEmpty())
        assertTrue(shown.accounts.isEmpty())
    }
}
