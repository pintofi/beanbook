package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Guided entry is where the app could quietly corrupt a ledger, so the rendering and
 * validation rules are pinned down here rather than discovered on a user's phone.
 */
class TransactionDraftTest {

    private fun draft(vararg postings: PostingDraft) = TransactionDraft(
        date = "2026-09-01",
        payee = "Grocer",
        narration = "Weekly shop",
        postings = postings.toList(),
    )

    private val cash = "Assets:Cash"
    private val food = "Expenses:Food:Groceries"

    @Test
    fun `renders the text Beancount expects`() {
        val text = draft(
            PostingDraft(food, Amount.parse("42.30", "USD")),
            PostingDraft(cash, Amount.parse("-42.30", "USD")),
        ).render()

        val lines = text.lines()
        assertEquals("""2026-09-01 * "Grocer" "Weekly shop"""", lines[0])
        assertTrue(lines[1].startsWith("  $food"))
        assertTrue(lines[1].trimEnd().endsWith("42.30 USD"))
        assertTrue(text.endsWith("\n"))
    }

    @Test
    fun `amounts are column-aligned the way Fava writes them`() {
        val text = draft(
            PostingDraft(food, Amount.parse("42.30", "USD")),
            PostingDraft(cash, Amount.parse("-42.30", "USD")),
        ).render(currencyColumn = 50)

        val ends = text.lines().filter { it.contains("USD") }.map { it.length }
        assertEquals(1, ends.distinct().size, "amount columns must line up: $text")
        assertEquals(50, ends.first())
    }

    @Test
    fun `a quote in a payee cannot break the file`() {
        val text = TransactionDraft(
            date = "2026-09-01",
            payee = """Bob "The Grocer" Smith""",
            narration = """back\slash""",
            postings = listOf(
                PostingDraft(food, Amount.parse("1.00", "USD")),
                PostingDraft(cash, Amount.parse("-1.00", "USD")),
            ),
        ).render()

        assertTrue(text.contains("""\"The Grocer\""""), text)
        assertTrue(text.contains("""back\\slash"""), text)
    }

    @Test
    fun `a narration with no payee is written as a single string`() {
        val text = TransactionDraft(
            date = "2026-09-01",
            narration = "Just a note",
            postings = listOf(
                PostingDraft(food, Amount.parse("1.00", "USD")),
                PostingDraft(cash, Amount.parse("-1.00", "USD")),
            ),
        ).render()

        // Beancount reads one string as the narration; an empty payee string would change
        // the meaning of the entry.
        assertEquals("""2026-09-01 * "Just a note"""", text.lines().first())
        assertFalse(text.contains("""\"\""""))
    }

    @Test
    fun `tags, links, metadata and posting comments all survive`() {
        val text = TransactionDraft(
            date = "2026-09-01",
            payee = "Grocer",
            narration = "Shop",
            tags = listOf("food", "weekly"),
            links = listOf("receipt-1"),
            metadata = mapOf("invoice" to "documents/a.pdf"),
            postings = listOf(
                PostingDraft(food, Amount.parse("1.00", "USD"), comment = "half for the office"),
                PostingDraft(cash, Amount.parse("-1.00", "USD")),
            ),
        ).render()

        assertTrue(text.lines()[0].endsWith("#food #weekly ^receipt-1"), text)
        assertTrue(text.contains("""  invoice: "documents/a.pdf""""), text)
        assertTrue(text.contains("; half for the office"), text)
    }

    @Test
    fun `an elided posting is rendered with no amount at all`() {
        val text = draft(
            PostingDraft(food, Amount.parse("42.30", "USD")),
            PostingDraft(cash),
        ).render()

        assertEquals("  $cash", text.lines()[2])
    }

    // --- validation: the app never silently fixes anything ---------------------------

    @Test
    fun `an unbalanced transaction is reported, not corrected`() {
        val d = draft(
            PostingDraft(food, Amount.parse("42.30", "USD")),
            PostingDraft(cash, Amount.parse("-40.00", "USD")),
        )

        val problem = d.validate().filterIsInstance<DraftProblem.DoesNotBalance>().single()
        assertEquals(listOf(Amount.parse("2.30", "USD")), problem.residuals)
        assertTrue(problem.describe().contains("2.30 USD"))
        // The rendered text still contains exactly what the user typed.
        assertTrue(d.render().contains("-40.00 USD"))
    }

    @Test
    fun `one elided posting is fine, two is not`() {
        assertTrue(
            draft(PostingDraft(food, Amount.parse("42.30", "USD")), PostingDraft(cash)).validate().isEmpty()
        )
        val two = draft(PostingDraft(food), PostingDraft(cash))
        assertTrue(two.validate().any { it is DraftProblem.TooManyElidedPostings })
    }

    @Test
    fun `malformed accounts, dates, flags, tags and links are refused`() {
        val bad = TransactionDraft(
            date = "01-09-2026",
            flag = "?",
            tags = listOf("has space"),
            links = listOf("also space"),
            postings = listOf(PostingDraft("notanaccount", Amount.parse("1.00", "USD"))),
        )
        val kinds = bad.validate().map { it::class.simpleName }

        assertTrue(kinds.contains("InvalidDate"), "$kinds")
        assertTrue(kinds.contains("InvalidFlag"))
        assertTrue(kinds.contains("InvalidAccount"))
        assertTrue(kinds.contains("TooFewPostings"))
        assertTrue(kinds.contains("InvalidTag"))
        assertTrue(kinds.contains("InvalidLink"))
    }

    @Test
    fun `a flagged transaction is allowed, because needing review is normal`() {
        assertTrue(
            draft(
                PostingDraft(food, Amount.parse("1.00", "USD")),
                PostingDraft(cash, Amount.parse("-1.00", "USD")),
            ).copy(flag = "!").validate().isEmpty()
        )
    }

    @Test
    fun `multi-currency postings report a residual per currency`() {
        val d = draft(
            PostingDraft(food, Amount.parse("10.00", "USD")),
            PostingDraft(cash, Amount.parse("-5.00", "EUR")),
        )
        assertEquals(2, d.residuals().size)
    }
}

class LedgerVocabularyTest {

    private val vocabulary = LedgerVocabulary(
        accounts = listOf("Assets:Cash", "Assets:Bank:Checking", "Expenses:Food:Groceries"),
        payees = listOf("Grocer", "Bakery"),
        narrations = emptyList(),
        currencies = listOf("USD"),
        tags = listOf("food"),
    )

    @Test
    fun `vocabulary comes from the ledger itself`() {
        val snapshot = LedgerSnapshot(
            title = "t", operatingCurrencies = listOf("USD"), entryCount = 1,
            entryCounts = emptyMap(),
            accounts = listOf(AccountBalance("Assets:Cash", listOf(Amount.parse("1.00", "USD")))),
            journal = listOf(
                JournalEntry("2026-09-01", "*", "Grocer", "Shop", listOf("food"), emptyList(),
                    emptyList(), null, null)
            ),
            errors = emptyList(), includedFiles = emptyList(), loadMillis = 1,
        )

        val v = LedgerVocabulary.from(snapshot)
        assertEquals(listOf("Assets:Cash"), v.accounts)
        assertEquals(listOf("Grocer"), v.payees)
        assertEquals(listOf("food"), v.tags)
        assertEquals(listOf("USD"), v.currencies)
    }
}

/** "Use what I used last time" — the cheapest useful form of templates. */
class RecentValuesTest {

    private fun entry(payee: String, account: String, amount: String) = JournalEntry(
        date = "2026-09-01",
        flag = "*",
        payee = payee,
        narration = "Shop",
        tags = emptyList(),
        links = emptyList(),
        postings = listOf(
            JournalPosting(account, Amount.parse(amount, "USD")),
            JournalPosting("Assets:Cash", Amount.parse("-$amount", "USD")),
        ),
        file = null,
        line = null,
    )

    private val vocabulary = LedgerVocabulary(
        accounts = listOf("Expenses:Food:Groceries", "Assets:Cash"),
        payees = listOf("Grocer", "Bakery"),
        narrations = emptyList(),
        currencies = listOf("USD"),
        tags = emptyList(),
        recentEntries = listOf(
            entry("Grocer", "Expenses:Food:Groceries", "42.30"),
            entry("Bakery", "Expenses:Food:Restaurant", "3.50"),
            entry("Grocer", "Expenses:Food:Groceries", "39.10"),
        ),
    )

    @Test
    fun `the newest entry for a payee is offered back`() {
        val last = vocabulary.lastEntryFor("Grocer")
        assertEquals("42.30", last?.postings?.first()?.amount?.number?.toPlainString())
        assertEquals("Expenses:Food:Groceries", last?.postings?.first()?.account)
    }

    @Test
    fun `payee matching ignores case, because people type in a hurry`() {
        assertEquals("Grocer", vocabulary.lastEntryFor("grocer")?.payee)
    }

    @Test
    fun `an unknown payee offers nothing rather than the wrong thing`() {
        assertEquals(null, vocabulary.lastEntryFor("Someone New"))
    }
}
