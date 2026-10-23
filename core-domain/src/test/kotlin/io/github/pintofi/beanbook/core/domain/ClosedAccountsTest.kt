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
    fun aPostingToAClosedAccountIsRefusedAsUnknown() {
        val open = LedgerVocabulary.from(ledger()).openAccounts
        assertTrue("Assets:Cash" in open!!)
        assertFalse("Assets:Old" in open)
    }

    @Test
    fun closedIsReadFromTheCloseDay() {
        assertTrue(AccountBalance("Assets:Old", emptyList(), closed = "2026-03-01").isClosed)
        assertFalse(AccountBalance("Assets:Cash", emptyList()).isClosed)
    }
}
