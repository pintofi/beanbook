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

    // The ledger's journal is newest first, as the app holds it; Fava's positions count oldest first.
    private val hashed = ledger.copy(inputHash = "abc")
    private fun kept(expression: String, vararg positions: Int, hash: String? = "abc", count: Int = 3) =
        ExpressionAnswer.Kept(expression, positions.toSet(), count, hash)

    @Test fun `Fava's answer decides the entries, counted oldest first, and the figures follow`() {
        // The fixture lists newest first, so by Fava's count Taxi is 0, Groceries 1 and Lunch 2.
        val shown = hashed.filteredBy(EntryFilter(expression = "-#trip"), kept("-#trip", 1, 2))
        assertEquals(setOf("Lunch", "Groceries"), shown.journal.map { it.narration }.toSet())
        val cash = shown.accounts.first { it.name == "Assets:Cash" }
        assertEquals(listOf(Amount(BigDecimal("-30.00"), "PKR")), cash.positions)
    }

    @Test fun `a search and an expression both have to match`() {
        val shown = hashed.filteredBy(EntryFilter(query = "samuel", expression = "#trip"), kept("#trip", 0))
        assertEquals(listOf("Taxi"), shown.journal.map { it.narration })
    }

    @Test fun `no answer, a refusal, another expression or other files show nothing, never everything`() {
        val filter = EntryFilter(expression = "#trip")
        val answers = listOf(
            null,
            ExpressionAnswer.Rejected("#trip", "Failed to parse filter: #trip"),
            kept("#other", 0),
            kept("#trip", 0, hash = "different"),
            kept("#trip", 0, hash = null),
            kept("#trip", 0, count = 99),
        )
        for (answer in answers) {
            val shown = hashed.filteredBy(filter, answer)
            assertTrue(shown.journal.isEmpty(), "$answer")
            assertTrue(shown.accounts.isEmpty(), "$answer")
        }
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
