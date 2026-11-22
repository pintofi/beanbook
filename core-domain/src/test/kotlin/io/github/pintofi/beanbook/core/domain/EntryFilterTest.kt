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

    private val hashed = ledger.copy(inputHash = "abc")
    private val cash = "Assets:Cash"

    private fun ready(
        request: ViewRequest,
        vararg positions: Int,
        summary: List<JournalEntry> = emptyList(),
        accounts: List<AccountBalance> = listOf(AccountBalance(cash, listOf(Amount(BigDecimal("-7.00"), "PKR")))),
        hash: String? = "abc",
        count: Int = 3,
    ) = ViewAnswer.Ready(request, positions.toSet(), summary, accounts, emptyList(), count, hash, null, null)

    @Test fun `an inactive filter is the ledger itself`() {
        assertSame(ledger, ledger.filteredBy(EntryFilter()))
    }

    @Test fun `a search keeps the matching entries and totals only those`() {
        val shown = ledger.filteredBy(EntryFilter(query = "samuel"))
        assertEquals(listOf("Lunch", "Taxi"), shown.journal.map { it.narration })
        val total = shown.accounts.first { it.name == cash }
        assertEquals(listOf(Amount(BigDecimal("-40.00"), "PKR")), total.positions)
    }

    // The fixture lists newest first, so by Fava's count Taxi is 0, Groceries 1 and Lunch 2.
    @Test fun `Fava's answer decides the entries, counted oldest first, and the balances are Fava's`() {
        val request = ViewRequest(tags = "-#trip")
        val shown = hashed.filteredBy(EntryFilter(view = request), ready(request, 1, 2))
        assertEquals(setOf("Lunch", "Groceries"), shown.journal.map { it.narration }.toSet())
        assertEquals(listOf(Amount(BigDecimal("-7.00"), "PKR")), shown.accounts.single().positions)
    }

    @Test fun `the opening rows Fava makes up for a period come last and have no file or line`() {
        val opening = entry("", "Opening balance", amount = "5.00").copy(file = null, line = null)
        val request = ViewRequest(time = "2026-08")
        val shown = hashed.filteredBy(EntryFilter(view = request), ready(request, 0, summary = listOf(opening)))
        assertEquals(listOf("Taxi", "Opening balance"), shown.journal.map { it.narration })
        assertEquals(null, shown.journal.last().file)
    }

    @Test fun `a search and a request both have to match`() {
        val request = ViewRequest(tags = "#trip")
        val shown = hashed.filteredBy(EntryFilter(query = "samuel", view = request), ready(request, 0))
        assertEquals(listOf("Taxi"), shown.journal.map { it.narration })
    }

    @Test fun `an account name lists only the entries that touch it and leaves every figure alone`() {
        val shown = ledger.filteredBy(EntryFilter(account = "misc"))
        assertEquals(3, shown.journal.size)
        assertSame(ledger.accounts, shown.accounts)
        assertEquals(0, ledger.filteredBy(EntryFilter(account = "Liabilities")).journal.size)
    }

    @Test fun `no answer, a refusal, another request or other files show nothing, never everything`() {
        val request = ViewRequest(tags = "#trip")
        val filter = EntryFilter(view = request)
        val answers = listOf(
            null,
            ViewAnswer.Refused(request, "tags", "Failed to parse filter: #trip,"),
            ready(ViewRequest(tags = "#other"), 0),
            ready(request, 0, hash = "different"),
            ready(request, 0, hash = null),
            ready(request, 0, count = 99),
        )
        for (answer in answers) {
            val shown = hashed.filteredBy(filter, answer)
            assertTrue(shown.journal.isEmpty(), "$answer")
            assertTrue(shown.accounts.isEmpty(), "$answer")
        }
    }

    @Test fun `the request is active for any of its parts and for none of the live ones`() {
        assertEquals(false, ViewRequest().isActive)
        assertEquals(true, ViewRequest(time = "2020").isActive)
        assertEquals(true, ViewRequest(amountMax = "5").isActive)
        assertEquals(false, EntryFilter(account = "x").view.isActive)
        assertEquals(true, EntryFilter(account = "x").isActive)
    }
}
