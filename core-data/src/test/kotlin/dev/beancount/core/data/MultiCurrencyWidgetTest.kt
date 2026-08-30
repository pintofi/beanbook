package dev.beancount.core.data

import dev.beancount.core.domain.AccountBalance
import dev.beancount.core.domain.Amount
import dev.beancount.core.domain.LedgerSnapshot
import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A widget figure with more than one currency in it (B3).
 *
 * `format` joined every currency onto one line with two spaces between them, and the widget
 * renders that string into a single `TextView` a few centimetres wide. With one currency it
 * reads fine; with two or more it is a run-on the user cannot parse at a glance — which is
 * the only way a widget is ever read.
 *
 * One currency per line instead. The widget layout already wraps, and the period widget has
 * always used a newline for the same reason.
 */
class MultiCurrencyWidgetTest {

    private fun amount(value: String, currency: String) = Amount(BigDecimal(value), currency)

    private fun ledgerWith(vararg positions: Amount) = LedgerSnapshot(
        title = "Multi",
        operatingCurrencies = listOf("USD"),
        entryCount = 1,
        entryCounts = mapOf("Transaction" to 1),
        accounts = listOf(AccountBalance("Assets:Bank", positions.toList())),
        journal = emptyList(),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    @Test
    fun `a multi-currency figure puts each currency on its own line`() {
        val snapshot = WidgetCache.from(
            projectId = "p",
            ledger = ledgerWith(amount("1240.00", "USD"), amount("567.00", "EUR")),
            entitled = true,
            nowEpochMs = 0,
        )

        val netWorth = snapshot.netWorth
        assertTrue(netWorth != null, "no net worth was computed")
        assertTrue(
            netWorth!!.contains("\n"),
            "currencies are run together on one line: \"$netWorth\"",
        )
        assertEquals(2, netWorth.lines().size, "expected one line per currency: \"$netWorth\"")
        assertTrue(netWorth.contains("USD") && netWorth.contains("EUR"), netWorth)
    }

    @Test
    fun `a single-currency figure is still one line`() {
        val snapshot = WidgetCache.from(
            projectId = "p",
            ledger = ledgerWith(amount("1240.00", "USD")),
            entitled = true,
            nowEpochMs = 0,
        )

        assertEquals("1240.00 USD", snapshot.netWorth, "the common case changed shape")
    }
}

/**
 * Which balance the balance widget shows (B1).
 *
 * It took `balances.entries.firstOrNull()`, an arbitrary entry of a map, and rendered the
 * figure with no account name — so the widget showed an unlabelled number that could change
 * account between refreshes. Whichever account is chosen, the user has to be told which one
 * it is, and the choice has to be the same one every time.
 *
 * Real selection is a per-widget setting and needs a configuration activity; this is the
 * deterministic default until that exists.
 */
class PrimaryBalanceTest {

    private fun amount(value: String, currency: String = "USD") =
        Amount(BigDecimal(value), currency)

    private fun ledgerOf(vararg accounts: Pair<String, String>) = LedgerSnapshot(
        title = "Picked",
        operatingCurrencies = listOf("USD"),
        entryCount = 1,
        entryCounts = mapOf("Transaction" to 1),
        accounts = accounts.map { (name, value) -> AccountBalance(name, listOf(amount(value))) },
        journal = emptyList(),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    private fun snapshotOf(vararg accounts: Pair<String, String>) =
        WidgetCache.from("p", ledgerOf(*accounts), entitled = true, nowEpochMs = 0)

    @Test
    fun `an asset account is preferred over expenses and income`() {
        val snapshot = snapshotOf(
            "Expenses:Food" to "262.00",
            "Assets:Bank:Checking" to "1240.00",
            "Income:Salary" to "-3000.00",
        )

        val primary = snapshot.primaryBalance
        assertTrue(primary != null, "no balance was chosen")
        assertEquals("Assets:Bank:Checking", primary!!.first)
    }

    @Test
    fun `the same ledger always chooses the same account`() {
        val first = snapshotOf("Assets:B" to "2.00", "Assets:A" to "1.00").primaryBalance
        val second = snapshotOf("Assets:A" to "1.00", "Assets:B" to "2.00").primaryBalance

        assertEquals(first?.first, second?.first, "the choice depends on input order")
        assertEquals("Assets:A", first?.first)
    }

    @Test
    fun `a ledger with no assets still shows something rather than nothing`() {
        val primary = snapshotOf("Expenses:Food" to "262.00").primaryBalance

        assertEquals("Expenses:Food", primary?.first, "a ledger without assets showed nothing")
    }

    @Test
    fun `a ledger with no balances at all chooses nothing`() {
        assertEquals(null, snapshotOf().primaryBalance)
    }
}
