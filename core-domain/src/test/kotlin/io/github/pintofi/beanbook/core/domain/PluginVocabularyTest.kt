package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/** What the Add forms offer follows the plugins a ledger turns on. */
class PluginVocabularyTest {

    private fun ledger(vararg plugins: String) = LedgerSnapshot(
        title = "T", operatingCurrencies = listOf("USD"), entryCount = 0, entryCounts = emptyMap(),
        accounts = listOf(
            AccountBalance("Assets:Bank", emptyList()),
            AccountBalance("Assets:Bank:Checking", listOf(Amount(BigDecimal.ONE, "EUR"))),
        ),
        journal = emptyList(), errors = emptyList(), includedFiles = emptyList(), loadMillis = 0,
        commodities = listOf("USD"),
        plugins = plugins.map { LedgerPlugin(it) },
    )

    @Test
    fun `leafonly stops the form offering an account that has sub-accounts`() {
        val leaf = LedgerVocabulary.from(ledger("beancount.plugins.leafonly"))
        assertEquals(listOf("Assets:Bank:Checking"), leaf.accounts)
        assertEquals(2, LedgerVocabulary.from(ledger()).accounts.size)
    }

    @Test
    fun `check_commodity offers only declared commodities`() {
        val checked = LedgerVocabulary.from(ledger("beancount.plugins.check_commodity"))
        assertEquals(listOf("USD"), checked.currencies)
        assertEquals(listOf("EUR", "USD"), LedgerVocabulary.from(ledger()).currencies)
    }

    @Test
    fun `an account the ledger has not opened is refused before the write`() {
        val vocabulary = LedgerVocabulary.from(ledger())
        val draft = TransactionDraft(
            date = "2026-10-10", narration = "x",
            postings = listOf(
                PostingDraft(account = "Assets:Bank:Ca", amount = Amount(BigDecimal.ONE, "USD")),
                PostingDraft(account = "Assets:Bank:Checking", amount = null),
            ),
        )

        val problems = draft.validate(openAccounts = vocabulary.openAccounts)

        assertEquals(listOf(DraftProblem.UnknownAccount("Assets:Bank:Ca")), problems)
    }
}
