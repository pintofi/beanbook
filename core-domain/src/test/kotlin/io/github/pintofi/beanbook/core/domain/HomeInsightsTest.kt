package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What a home screen can say that a journal cannot (B71).
 *
 * The Journal lists entries; these are the totals, the comparison and the to-do list that a
 * person would otherwise work out by eye. The arithmetic lives here, in pure Kotlin, because
 * a figure somebody makes a decision on must not be computed inside a composable.
 */
class HomeInsightsTest {

    private val today = LocalDate.parse("2026-09-11")

    private fun usd(value: String) = Amount(BigDecimal(value), "USD")

    private fun entry(
        date: String,
        account: String,
        value: String,
        flag: String = "*",
        currency: String = "USD",
        line: Int = 1,
    ) = JournalEntry(
        date = date,
        flag = flag,
        payee = null,
        narration = "n",
        tags = emptyList(),
        links = emptyList(),
        postings = listOf(
            JournalPosting(account, Amount(BigDecimal(value), currency)),
            JournalPosting("Assets:Cash", Amount(BigDecimal("-$value"), currency)),
        ),
        file = "main.beancount",
        line = line,
    )

    private fun ledger(vararg entries: JournalEntry) = LedgerSnapshot(
        title = "T",
        operatingCurrencies = listOf("USD"),
        entryCount = entries.size,
        entryCounts = emptyMap(),
        accounts = emptyList(),
        journal = entries.toList(),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    private val month = ledger(
        entry("2026-09-02", "Expenses:Food:Groceries", "120.00"),
        entry("2026-09-05", "Expenses:Food:Coffee", "30.00"),
        entry("2026-09-09", "Expenses:Transport", "60.00"),
        entry("2026-08-14", "Expenses:Food:Groceries", "100.00"),
        entry("2026-08-20", "Expenses:Rent", "900.00"),
        // Outside both windows, and must not be counted in either.
        entry("2026-07-01", "Expenses:Food:Groceries", "999.00"),
    )

    @Test fun `spending totals this month by category`() {
        val report = HomeInsights.spending(month, SpendingPeriod.THIS_MONTH, today)

        assertEquals(usd("210.00"), report.total)
        assertEquals(
            listOf("Expenses:Food" to "150.00", "Expenses:Transport" to "60.00"),
            report.categories.map { it.account to it.amount.number.toPlainString() },
        )
    }

    @Test fun `categories are grouped two segments deep`() {
        // Groceries and Coffee are one category, because a home screen with a row per leaf
        // account is the account tree, which is what Balances is for.
        val food = HomeInsights.spending(month, SpendingPeriod.THIS_MONTH, today)
            .categories.first()

        assertEquals("Expenses:Food", food.account)
        assertEquals(usd("150.00"), food.amount)
    }

    @Test fun `last month is the whole of last month`() {
        val report = HomeInsights.spending(month, SpendingPeriod.THIS_MONTH, today)

        assertEquals(usd("1000.00"), report.previousTotal)
        assertEquals(usd("100.00"), report.categories.first().previous)
    }

    @Test fun `a category with nothing last month compares against zero`() {
        val transport = HomeInsights.spending(month, SpendingPeriod.THIS_MONTH, today)
            .categories.single { it.account == "Expenses:Transport" }

        assertEquals(usd("0"), transport.previous)
        assertEquals(usd("60.00"), transport.change)
    }

    @Test fun `the biggest mover is the largest change either way`() {
        val report = HomeInsights.spending(month, SpendingPeriod.THIS_MONTH, today)

        // Food moved +50, Transport +60.
        assertEquals("Expenses:Transport", report.biggestMover?.account)
    }

    @Test fun `a share is the category against the total`() {
        val report = HomeInsights.spending(month, SpendingPeriod.THIS_MONTH, today)
        val food = report.categories.first()

        assertEquals(0.714f, food.shareOf(report.total), 0.001f)
    }

    @Test fun `the last thirty days is a rolling window`() {
        val report = HomeInsights.spending(month, SpendingPeriod.LAST_30_DAYS, today)

        // Both August entries are inside a 30-day window ending 2026-09-11 and neither is
        // inside "this month". The two periods are genuinely different questions, and the
        // July one is outside both.
        assertEquals(usd("1210.00"), report.total)
    }

    @Test fun `a second currency is named rather than silently dropped`() {
        val mixed = ledger(
            entry("2026-09-02", "Expenses:Food", "100.00"),
            entry("2026-09-03", "Expenses:Food", "50.00", currency = "EUR"),
        )

        val report = HomeInsights.spending(mixed, SpendingPeriod.THIS_MONTH, today)

        assertEquals(usd("100.00"), report.total)
        assertEquals(listOf("EUR"), report.ignoredCurrencies)
    }

    @Test fun `a ledger with no spending says so`() {
        val report = HomeInsights.spending(ledger(), SpendingPeriod.THIS_MONTH, today)

        assertTrue(report.isEmpty)
        assertNull(report.previousTotal)
    }

    @Test fun `only the expenses tree counts`() {
        // The helper's second posting is the counter-leg, so this ledger holds Assets and
        // Income postings as well as the expense.
        val withIncome = ledger(
            entry("2026-09-02", "Expenses:Food", "10.00"),
            entry("2026-09-02", "Income:Salary", "3000.00"),
        )

        val report = HomeInsights.spending(withIncome, SpendingPeriod.THIS_MONTH, today)

        assertEquals(listOf("Expenses:Food"), report.categories.map { it.account })
    }

    @Test fun `a renamed expenses tree is followed`() {
        val german = ledger(entry("2026-09-02", "Ausgaben:Essen", "10.00"))
            .copy(accountNames = AccountNames(expenses = "Ausgaben"))

        val report = HomeInsights.spending(german, SpendingPeriod.THIS_MONTH, today)

        assertEquals(listOf("Ausgaben:Essen"), report.categories.map { it.account })
    }

    @Test fun `pending entries are the ones the file flags`() {
        val mixed = ledger(
            entry("2026-09-02", "Expenses:Food", "10.00", flag = "!"),
            entry("2026-09-03", "Expenses:Food", "20.00"),
            entry("2026-09-04", "Expenses:Food", "30.00", flag = "!"),
        )

        assertEquals(2, HomeInsights.pendingCount(mixed))
        assertEquals(listOf("!", "!"), HomeInsights.pending(mixed).map { it.flag })
    }

    @Test fun `attention names the ledger's errors and where they are`() {
        val broken = ledger(entry("2026-09-02", "Expenses:Food", "10.00", line = 4)).copy(
            errors = listOf(
                LedgerError("Transaction does not balance", "main.beancount", 4, "ValidationError"),
            ),
        )

        val items = HomeInsights.attention(broken)

        assertEquals(1, items.size)
        assertEquals("1 error in this ledger", items.single().headline)
        assertEquals(AttentionItem.Destination.JOURNAL, items.single().destination)
    }

    @Test fun `attention gathers documents and the mirror too`() {
        val items = HomeInsights.attention(
            ledger(),
            unfiledDocuments = 2,
            brokenDocumentLinks = 1,
            mirrorConflicts = 3,
        )

        assertEquals(
            listOf(
                AttentionItem.Destination.DOCUMENTS,
                AttentionItem.Destination.DOCUMENTS,
                AttentionItem.Destination.MIRROR,
            ),
            items.map { it.destination },
        )
        assertEquals("1 document is missing", items.first().headline)
    }

    @Test fun `a report extension that does not import needs attention, one that does not`() {
        val withExtensions = ledger().copy(
            extensionHealth = mapOf(
                "fava_portfolio_returns" to "No module named 'scipy'",
                "fava_investor" to null,
            ),
        )

        val item = HomeInsights.attention(withExtensions).single()

        assertEquals("1 report extension did not load", item.headline)
        assertEquals(AttentionItem.Destination.PLUGINS, item.destination)
        assertTrue(item.detail.startsWith("fava_portfolio_returns. Its pages are missing"))
        assertEquals(1, withExtensions.extensionFailures)
    }

    @Test fun `extensions that all import need no attention`() {
        val healthy = ledger().copy(extensionHealth = mapOf("fava_investor" to null))

        assertTrue(HomeInsights.attention(healthy).isEmpty())
    }

    @Test fun `a clean ledger needs no attention`() {
        assertTrue(HomeInsights.attention(ledger()).isEmpty())
    }

    @Test fun `the periods before are the same length and immediately before`() {
        assertEquals(
            LocalDate.parse("2026-08-01")..LocalDate.parse("2026-08-31"),
            SpendingPeriod.THIS_MONTH.previousRange(today),
        )
        assertEquals(
            LocalDate.parse("2026-07-14")..LocalDate.parse("2026-08-12"),
            SpendingPeriod.LAST_30_DAYS.previousRange(today),
        )
        assertEquals(
            LocalDate.parse("2025-01-01")..LocalDate.parse("2025-12-31"),
            SpendingPeriod.THIS_YEAR.previousRange(today),
        )
    }
}
