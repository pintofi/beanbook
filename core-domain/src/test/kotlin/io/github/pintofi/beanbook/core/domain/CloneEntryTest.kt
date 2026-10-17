package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Turning an entry already in the ledger back into a draft, so it can be entered again.
 *
 * The one decision worth arguing about is the date. A clone is a *new* transaction — the rent
 * paid this month, not a second copy of last month's — so it takes today's date rather than
 * the original's. Carrying the old date over would produce a duplicate that looks deliberate,
 * which is the failure invariant 6 exists to prevent, only reached by hand instead of by a
 * retry.
 */
class CloneEntryTest {

    private fun amount(value: String, currency: String = "USD") =
        Amount(BigDecimal(value), currency)

    private val entry = JournalEntry(
        date = "2026-01-15",
        flag = "!",
        payee = "Landlord",
        narration = "January rent",
        tags = listOf("housing"),
        links = listOf("lease-2026"),
        postings = listOf(
            JournalPosting("Expenses:Rent", amount("1200.00")),
            JournalPosting("Assets:Bank:Checking", amount("-1200.00")),
        ),
        file = "main.beancount",
        line = 42,
    )

    @Test
    fun `a clone takes today's date, not the original's`() {
        val draft = TransactionDraft.cloneOf(entry, today = "2026-08-29")

        assertEquals("2026-08-29", draft.date)
    }

    @Test
    fun `payee, narration, tags and links carry over`() {
        val draft = TransactionDraft.cloneOf(entry, today = "2026-08-29")

        assertEquals("Landlord", draft.payee)
        assertEquals("January rent", draft.narration)
        assertEquals(listOf("housing"), draft.tags)
        assertEquals(listOf("lease-2026"), draft.links)
    }

    @Test
    fun `postings carry over with their amounts`() {
        val draft = TransactionDraft.cloneOf(entry, today = "2026-08-29")

        assertEquals(2, draft.postings.size)
        assertEquals("Expenses:Rent", draft.postings[0].account)
        assertEquals(BigDecimal("1200.00"), draft.postings[0].amount?.number)
        assertEquals(BigDecimal("-1200.00"), draft.postings[1].amount?.number)
    }

    @Test
    fun `a pending flag carries over rather than being silently cleared`() {
        // "!" means somebody wanted to look at this again. Cloning it as "*" would quietly
        // assert the copy is settled.
        assertEquals("!", TransactionDraft.cloneOf(entry, today = "2026-08-29").flag)
    }

    @Test
    fun `an entry with no flag clones as cleared`() {
        val unflagged = entry.copy(flag = null)

        assertEquals("*", TransactionDraft.cloneOf(unflagged, today = "2026-08-29").flag)
    }

    @Test
    fun `a clone validates, so it can be saved without further editing`() {
        val draft = TransactionDraft.cloneOf(entry, today = "2026-08-29")

        assertTrue(draft.validate().isEmpty(), "a clone should be ready to save: ${draft.validate()}")
    }
}
