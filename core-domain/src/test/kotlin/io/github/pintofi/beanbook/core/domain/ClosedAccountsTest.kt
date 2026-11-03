package io.github.pintofi.beanbook.core.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A closed account takes no more postings, so the entry form neither offers nor accepts it. */
class ClosedAccountsTest {

    private fun ledger() = LedgerSnapshot(
        title = "C",
        operatingCurrencies = listOf("USD"),
        entryCount = 0,
        entryCounts = emptyMap(),
        accounts = listOf(
            AccountBalance("Assets:Cash", emptyList(), opened = "2026-01-01"),
            AccountBalance("Assets:Old", emptyList(), opened = "2026-01-01", closed = "2026-03-01"),
        ),
        journal = emptyList(),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    @Test
    fun aClosedAccountIsNotOffered() {
        assertEquals(listOf("Assets:Cash"), LedgerVocabulary.from(ledger()).accounts)
    }

    @Test
    fun aClosedAccountIsStillDeclaredAndKnowsWhenItClosed() {
        val vocabulary = LedgerVocabulary.from(ledger())
        assertTrue("Assets:Old" in vocabulary.declaredAccounts!!)
        assertEquals(mapOf("Assets:Old" to "2026-03-01"), vocabulary.closedOn)
    }

    private fun draft(date: String) = TransactionDraft(
        date = date,
        postings = listOf(
            PostingDraft("Assets:Old", Amount(java.math.BigDecimal("5"), "USD")),
            PostingDraft("Assets:Cash"),
        ),
    )

    @Test
    fun anEntryDatedAfterTheCloseIsRefusedAsClosed() {
        val vocabulary = LedgerVocabulary.from(ledger())
        val problems = draft("2026-03-02").validate(
            declaredAccounts = vocabulary.declaredAccounts, closedOn = vocabulary.closedOn,
        )
        assertEquals(listOf(DraftProblem.ClosedAccount("Assets:Old", "2026-03-01")), problems)
    }

    @Test
    fun anEntryOnOrBeforeTheCloseDayIsAccepted() {
        val vocabulary = LedgerVocabulary.from(ledger())
        listOf("2026-02-01", "2026-03-01").forEach { day ->
            val problems = draft(day).validate(
                declaredAccounts = vocabulary.declaredAccounts, closedOn = vocabulary.closedOn,
            )
            assertEquals(emptyList(), problems, "an entry dated $day")
        }
    }

    @Test
    fun theFormOffersTheClosedAccountOnlyUpToItsCloseDay() {
        val vocabulary = LedgerVocabulary.from(ledger())
        assertTrue("Assets:Old" in vocabulary.accountsFor("2026-02-01", null))
        assertTrue("Assets:Old" in vocabulary.accountsFor("2026-03-01", null))
        assertFalse("Assets:Old" in vocabulary.accountsFor("2026-03-02", null))
        assertTrue("Assets:Cash" in vocabulary.accountsFor("2026-03-02", null))
    }

    @Test
    fun closedIsReadFromTheCloseDay() {
        assertTrue(AccountBalance("Assets:Old", emptyList(), closed = "2026-03-01").isClosed)
        assertFalse(AccountBalance("Assets:Cash", emptyList()).isClosed)
    }
}
