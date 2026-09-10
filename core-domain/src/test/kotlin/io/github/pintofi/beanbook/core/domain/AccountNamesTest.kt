package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A ledger may call its five trees whatever it likes (B70).
 *
 * `option "name_expenses" "Ausgaben"` is ordinary Beancount, and the app had the English five
 * written into six places: the Balances headings and their totals, the home figures, the
 * accounts the two forms pre-select, and which trees an inverted-sign option turns around. A
 * renamed ledger got five empty headings and a home screen of dashes.
 */
class AccountNamesTest {

    private fun amount(value: String) = Amount(BigDecimal(value), "EUR")

    private val german = AccountNames(
        assets = "Aktiva",
        liabilities = "Passiva",
        equity = "Eigenkapital",
        income = "Einnahmen",
        expenses = "Ausgaben",
    )

    private fun ledger(names: AccountNames) = LedgerSnapshot(
        title = "T",
        operatingCurrencies = listOf("EUR"),
        entryCount = 2,
        entryCounts = emptyMap(),
        accounts = listOf(
            AccountBalance("Aktiva:Kasse", listOf(amount("100.00"))),
            AccountBalance("Einnahmen:Lohn", listOf(amount("-300.00"))),
            AccountBalance("Ausgaben:Essen", listOf(amount("200.00"))),
        ),
        journal = emptyList(),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
        accountNames = names,
    )

    @Test fun `the five names default to beancount's own`() {
        assertEquals(
            listOf("Assets", "Liabilities", "Equity", "Income", "Expenses"),
            AccountNames.DEFAULT.roots,
        )
    }

    @Test fun `a renamed ledger totals its own trees`() {
        val summary = LedgerSummary.of(ledger(german))

        assertEquals("300.00 EUR", summary.income)
        assertEquals("200.00 EUR", summary.expenses)
        assertEquals("100.00 EUR", summary.netWorth)
    }

    @Test fun `the english names find nothing in a renamed ledger`() {
        // The failure this exists to stop: reading a German ledger with English roots is not
        // an error, it is five totals of nothing, which looks like an empty ledger.
        val summary = LedgerSummary.of(ledger(AccountNames.DEFAULT))

        // Nothing at all: no tree matches, so there is no figure to show.
        assertEquals(null, summary.income)
        assertEquals(null, summary.expenses)
    }

    @Test fun `the roots read in balance-sheet order`() {
        assertEquals(
            listOf("Aktiva", "Passiva", "Eigenkapital", "Einnahmen", "Ausgaben"),
            german.roots,
        )
    }

    @Test fun `inversion follows the file's own names`() {
        val format = LedgerFormat(
            invertIncomeLiabilitiesEquity = true,
            invertedRoots = listOf(german.income, german.liabilities, german.equity),
        )

        assertTrue(format.isInverted("Einnahmen:Lohn"))
        assertTrue(format.isInverted("Passiva:Karte"))
        assertFalse(format.isInverted("Aktiva:Kasse"))
        // And the English word is not special once the ledger has renamed the tree.
        assertFalse(format.isInverted("Income:Salary"))
    }

    @Test fun `a renamed income tree reads positively when the ledger asks`() {
        val format = LedgerFormat(
            invertIncomeLiabilitiesEquity = true,
            invertedRoots = listOf(german.income, german.liabilities, german.equity),
        )

        assertEquals(amount("300.00"), format.signed(amount("-300.00"), "Einnahmen:Lohn"))
    }
}
