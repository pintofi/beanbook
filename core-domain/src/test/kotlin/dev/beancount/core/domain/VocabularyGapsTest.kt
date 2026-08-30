package dev.beancount.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What the Add form offers, and where it was offering too little (B5, B6, B7).
 *
 * Three separate complaints with one shape: the vocabulary knows more than the form shows.
 */
class VocabularyGapsTest {

    private fun amount(value: String, currency: String) = Amount(BigDecimal(value), currency)

    private fun ledger(
        operating: List<String> = listOf("PKR"),
        commodities: List<String> = emptyList(),
        payees: List<String> = emptyList(),
    ) = LedgerSnapshot(
        title = "V",
        operatingCurrencies = operating,
        entryCount = payees.size,
        entryCounts = mapOf("Transaction" to payees.size),
        accounts = listOf(
            AccountBalance("Assets:Bank", listOf(amount("1.00", "USD"))),
            AccountBalance("Assets:Euro", listOf(amount("1.00", "EUR"))),
        ),
        journal = payees.map { payee ->
            JournalEntry(
                date = "2026-01-01", flag = "*", payee = payee, narration = null,
                tags = emptyList(), links = emptyList(), postings = emptyList(),
                file = null, line = null,
            )
        },
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
        commodities = commodities,
    )

    // --- B5: the default currency is the ledger's operating currency ------------------

    @Test
    fun `the default currency is the operating currency, not whatever sorts first`() {
        // Accounts hold EUR and USD; the ledger declares PKR. Sorting gave "EUR".
        assertEquals("PKR", LedgerVocabulary.from(ledger()).defaultCurrency)
    }

    @Test
    fun `a ledger with no operating currency falls back to one that is actually used`() {
        val vocabulary = LedgerVocabulary.from(ledger(operating = emptyList()))

        assertTrue(
            vocabulary.defaultCurrency in listOf("EUR", "USD"),
            "expected a currency the ledger uses, got ${vocabulary.defaultCurrency}",
        )
    }

    // --- B6: every commodity in the files, not only the ones already holding a balance --

    @Test
    fun `a declared commodity is offered even before anything is held in it`() {
        val vocabulary = LedgerVocabulary.from(ledger(commodities = listOf("BTC", "USD")))

        assertTrue("BTC" in vocabulary.currencies, "declared commodities: ${vocabulary.currencies}")
        assertTrue("EUR" in vocabulary.currencies, "held currencies were lost")
    }

    @Test
    fun `currencies can be matched like any other suggestion`() {
        val vocabulary = LedgerVocabulary.from(ledger(commodities = listOf("USD", "EUR", "PKR")))

        assertEquals(listOf("USD"), vocabulary.currenciesMatching("us"))
    }

    // --- B7: matches are not silently truncated ----------------------------------------

    @Test
    fun `more than a dozen matching payees are offered`() {
        val many = (1..40).map { "Shop %02d".format(it) }

        val matches = LedgerVocabulary.from(ledger(payees = many)).payeesMatching("Shop")

        assertTrue(
            matches.size > 12,
            "only ${matches.size} of ${many.size} matching payees were offered",
        )
    }
}
