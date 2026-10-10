package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Every non-transaction directive the app can write, checked against the syntax Beancount
 * expects. Rendering is asserted exactly, because "close enough" text is what silently
 * corrupts a ledger.
 */
class DirectiveDraftTest {

    private val date = "2026-09-01"

    @Test
    fun `open renders with and without currencies`() {
        assertEquals(
            "2026-09-01 open Assets:Cash\n",
            DirectiveDraft.Open(date, "Assets:Cash").render(),
        )
        assertEquals(
            "2026-09-01 open Assets:Cash USD,EUR\n",
            DirectiveDraft.Open(date, "Assets:Cash", listOf("USD", "EUR")).render(),
        )
        assertEquals(
            "2026-09-01 open Assets:Invest \"FIFO\"\n",
            DirectiveDraft.Open(date, "Assets:Invest", bookingMethod = "FIFO").render(),
        )
    }

    @Test
    fun `close and commodity are one-liners`() {
        assertEquals("2026-09-01 close Assets:Cash\n", DirectiveDraft.Close(date, "Assets:Cash").render())
        assertEquals("2026-09-01 commodity ACME\n", DirectiveDraft.Commodity(date, "ACME").render())
    }

    @Test
    fun `a commodity writes the details a commodity_attr ledger requires`() {
        val draft = DirectiveDraft.Commodity(
            date, "SAR",
            linkedMapOf("name" to "Saudi Riyal", "precision" to "2", "initials" to ""),
        )

        assertEquals(
            "2026-09-01 commodity SAR\n  name: \"Saudi Riyal\"\n  precision: 2\n",
            draft.render(),
        )
        assertTrue(draft.validate().isEmpty())
    }

    @Test
    fun `balance and price align their amounts`() {
        val balance = DirectiveDraft.Balance(date, "Assets:Cash", Amount.parse("120.00", "USD")).render()
        val price = DirectiveDraft.Price(date, "ACME", Amount.parse("12.50", "USD")).render()

        assertTrue(balance.trimEnd().endsWith("120.00 USD"), balance)
        assertTrue(price.trimEnd().endsWith("12.50 USD"), price)
        assertEquals(balance.trimEnd().length, price.trimEnd().length, "both align to the same column")
    }

    @Test
    fun `a balance assertion never invents an amount`() {
        val problems = DirectiveDraft.Balance(date, "Assets:Cash", amount = null).validate()
        assertTrue(problems.any { it is DraftProblem.MissingAmount })
    }

    @Test
    fun `pad names both accounts`() {
        assertEquals(
            "2026-09-01 pad Assets:Cash Equity:Opening-Balances\n",
            DirectiveDraft.Pad(date, "Assets:Cash", "Equity:Opening-Balances").render(),
        )
        assertTrue(
            DirectiveDraft.Pad(date, "Assets:Cash", "not-an-account").validate()
                .any { it is DraftProblem.InvalidAccount },
        )
    }

    @Test
    fun `note, event and query quote their strings`() {
        assertEquals(
            """2026-09-01 note Assets:Cash "a \"quoted\" note"""" + "\n",
            DirectiveDraft.Note(date, "Assets:Cash", """a "quoted" note""").render(),
        )
        assertEquals(
            """2026-09-01 event "location" "Berlin"""" + "\n",
            DirectiveDraft.Event(date, "location", "Berlin").render(),
        )
        assertEquals(
            """2026-09-01 query "recent" "SELECT date, narration"""" + "\n",
            DirectiveDraft.Query(date, "recent", "SELECT date, narration").render(),
        )
    }

    @Test
    fun `a document link that escapes the project is refused`() {
        assertTrue(
            DirectiveDraft.Document(date, "Assets:Cash", "../../etc/passwd").validate()
                .any { it is DraftProblem.PathOutsideProject },
        )
        assertTrue(
            DirectiveDraft.Document(date, "Assets:Cash", "/etc/passwd").validate()
                .any { it is DraftProblem.PathOutsideProject },
        )
        assertTrue(
            DirectiveDraft.Document(date, "Assets:Cash", "documents/receipt.pdf").validate().isEmpty(),
        )
    }

    @Test
    fun `custom leaves accounts, numbers and dates bare but quotes prose`() {
        val text = DirectiveDraft.Custom(
            date,
            "budget",
            listOf("Expenses:Food", "monthly", "400.00", "USD", "with a space"),
        ).render()

        // Quoting an account would change its meaning; quoting prose is required.
        assertTrue(text.contains(" Expenses:Food "), text)
        assertTrue(text.contains(" 400.00 "), text)
        assertTrue(text.contains(" USD "), text)
        assertTrue(text.contains(""" "monthly""""), text)
        assertTrue(text.contains(""" "with a space""""), text)
        assertFalse(text.contains("\"Expenses:Food\""), text)
    }

    @Test
    fun `every directive type rejects a malformed date`() {
        val bad = "01-09-2026"
        val drafts = listOf(
            DirectiveDraft.Open(bad, "Assets:Cash"),
            DirectiveDraft.Close(bad, "Assets:Cash"),
            DirectiveDraft.Balance(bad, "Assets:Cash", Amount.parse("1", "USD")),
            DirectiveDraft.Pad(bad, "Assets:Cash", "Equity:Opening-Balances"),
            DirectiveDraft.Note(bad, "Assets:Cash", "text"),
            DirectiveDraft.Price(bad, "ACME", Amount.parse("1", "USD")),
            DirectiveDraft.Document(bad, "Assets:Cash", "documents/a.pdf"),
            DirectiveDraft.Event(bad, "location", "Berlin"),
            DirectiveDraft.Commodity(bad, "ACME"),
            DirectiveDraft.Query(bad, "n", "SELECT 1"),
            DirectiveDraft.Custom(bad, "budget", listOf("x")),
        )

        drafts.forEach { draft ->
            assertTrue(
                draft.validate().any { it is DraftProblem.InvalidDate },
                "${draft.label} accepted a malformed date",
            )
        }
        assertEquals(11, drafts.map { it.label }.size)
    }

    @Test
    fun `every directive renders exactly one line ending in a newline`() {
        val drafts = listOf(
            DirectiveDraft.Open(date, "Assets:Cash", listOf("USD")),
            DirectiveDraft.Close(date, "Assets:Cash"),
            DirectiveDraft.Balance(date, "Assets:Cash", Amount.parse("1.00", "USD")),
            DirectiveDraft.Pad(date, "Assets:Cash", "Equity:Opening-Balances"),
            DirectiveDraft.Note(date, "Assets:Cash", "text"),
            DirectiveDraft.Price(date, "ACME", Amount.parse("1.00", "USD")),
            DirectiveDraft.Document(date, "Assets:Cash", "documents/a.pdf"),
            DirectiveDraft.Event(date, "location", "Berlin"),
            DirectiveDraft.Commodity(date, "ACME"),
            DirectiveDraft.Query(date, "n", "SELECT 1"),
            DirectiveDraft.Custom(date, "budget", listOf("Expenses:Food")),
        )

        drafts.forEach { draft ->
            val text = draft.render()
            assertTrue(text.endsWith("\n"), "${draft.label} must end with a newline")
            assertEquals(1, text.trimEnd().lines().size, "${draft.label} must be one line")
            assertTrue(text.startsWith(date), "${draft.label} must start with its date")
        }
    }
}
