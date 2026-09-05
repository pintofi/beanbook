package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.LedgerError
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import java.io.File
import java.math.BigDecimal
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cache a widget reads instead of starting an engine.
 */
class WidgetCacheTest {

    private val dir = File(System.getProperty("java.io.tmpdir"), "widgets-${System.nanoTime()}")
        .apply { mkdirs() }
    private val cache = WidgetCache(dir)

    @AfterTest fun cleanUp() {
        dir.deleteRecursively()
    }

    private fun amount(value: String, currency: String = "USD") =
        Amount(BigDecimal(value), currency)

    private fun ledger(errors: List<LedgerError> = emptyList()) = LedgerSnapshot(
        title = "My Ledger",
        operatingCurrencies = listOf("USD"),
        entryCount = 12,
        entryCounts = mapOf("Transaction" to 4),
        accounts = listOf(
            AccountBalance("Assets:Bank:Checking", listOf(amount("1240.00"))),
            AccountBalance("Liabilities:Card", listOf(amount("-300.00"))),
            AccountBalance("Income:Salary", listOf(amount("-3000.00"))),
            AccountBalance("Expenses:Food", listOf(amount("262.00"))),
            AccountBalance("Assets:Empty", emptyList()),
        ),
        journal = emptyList(),
        errors = errors,
        includedFiles = emptyList(),
        loadMillis = 5,
    )

    @Test
    fun `a snapshot survives a round trip through disk`() {
        val snapshot = WidgetCache.from("my-ledger", ledger(), entitled = true, nowEpochMs = 1_700_000)

        cache.write(snapshot)

        assertEquals(snapshot, WidgetCache(dir).read())
    }

    @Test
    fun `income is shown the way a person reads it, not the way the ledger stores it`() {
        // Income is a credit balance, so it is negative in double-entry terms. A widget
        // headed "Income" showing "-3000.00" would be correct and useless.
        val snapshot = WidgetCache.from("my-ledger", ledger(), entitled = true, nowEpochMs = 0)

        assertTrue(snapshot.periodIncome!!.startsWith("3000.00"), snapshot.periodIncome!!)
        assertTrue(snapshot.periodExpense!!.startsWith("262.00"), snapshot.periodExpense!!)
    }

    @Test
    fun `net worth is assets less liabilities`() {
        val snapshot = WidgetCache.from("my-ledger", ledger(), entitled = true, nowEpochMs = 0)

        // 1240.00 in assets, 300.00 owed. The liability is already stored negative, so
        // this also catches negating it a second time — which made debt *raise* net worth.
        assertEquals("940.00 USD", snapshot.netWorth)
    }

    @Test
    fun `two currencies stay two figures rather than being added together`() {
        // Adding them would need an exchange rate this app does not have and must not invent.
        val multi = ledger().copy(
            accounts = listOf(
                AccountBalance("Assets:USD", listOf(amount("100.00"))),
                AccountBalance("Assets:EUR", listOf(amount("50.00", "EUR"))),
            ),
        )

        val snapshot = WidgetCache.from("my-ledger", multi, entitled = true, nowEpochMs = 0)

        // One per line since B3: the property under test is that they are not added
        // together, which is unchanged. Only the separator moved, because two spaces made a
        // multi-currency figure a run-on inside a widget's single TextView.
        assertEquals("50.00 EUR\n100.00 USD", snapshot.netWorth)
    }

    @Test
    fun `an account with nothing in it is left out rather than shown blank`() {
        val snapshot = WidgetCache.from("my-ledger", ledger(), entitled = true, nowEpochMs = 0)

        assertFalse("Assets:Empty" in snapshot.balances, snapshot.balances.keys.toString())
        assertTrue("Assets:Bank:Checking" in snapshot.balances)
    }

    @Test
    fun `only the requested accounts are written`() {
        // A widget shows one account. Writing every account into a file the home screen reads
        // would put the whole chart of accounts somewhere it has no reason to be.
        val snapshot = WidgetCache.from(
            "my-ledger",
            ledger(),
            entitled = true,
            nowEpochMs = 0,
            accounts = listOf("Assets:Bank:Checking"),
        )

        assertEquals(setOf("Assets:Bank:Checking"), snapshot.balances.keys)
    }

    @Test
    fun `a ledger with errors is recorded as not clean, so a widget can say so`() {
        val broken = WidgetCache.from(
            "my-ledger",
            ledger(listOf(LedgerError("something is wrong", null, null, "ParserError"))),
            entitled = true,
            nowEpochMs = 0,
        )

        assertFalse(broken.clean)
        assertTrue(WidgetCache.from("my-ledger", ledger(), true, 0).clean)
    }

    @Test
    fun `staleness is measured from when the snapshot was taken`() {
        val snapshot = WidgetCache.from("my-ledger", ledger(), entitled = true, nowEpochMs = 1_000_000)

        assertFalse(snapshot.isStale(nowEpochMs = 1_000_000 + 60_000))
        assertTrue(snapshot.isStale(nowEpochMs = 1_000_000 + WidgetSnapshot.DEFAULT_MAX_AGE_MS + 1))
    }

    @Test
    fun `a missing or damaged cache reads as absent rather than throwing`() {
        assertNull(cache.read())

        File(dir, "widgets.json").writeText("{ this is not json")
        assertNull(cache.read())

        File(dir, "widgets.json").writeText("""{"schema":1}""")
        assertNull(cache.read(), "a cache missing its fields must not half-load")
    }

    @Test
    fun `clearing removes it`() {
        cache.write(WidgetCache.from("my-ledger", ledger(), entitled = true, nowEpochMs = 0))
        cache.clear()

        assertNull(cache.read())
    }
}
