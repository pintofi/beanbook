package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A root total shows the figure the rows under it show.
 *
 * The Balances screen offers units, cost and market value. The rows honoured the choice and
 * `totalFor` did not — it summed `positions` whatever was selected — so switching to At cost
 * changed every row and left the Assets, Liabilities, Equity, Income and Expenses headings
 * showing units. A heading that disagrees with its own contents reads as an arithmetic fault
 * in the ledger, which is the worst thing an accounting app can imply.
 *
 * The three figures are deliberately different here. If they were equal the test would pass
 * whichever one `totalFor` picked, which is how the original defect survived.
 */
class RootTotalValuationTest {

    private fun usd(value: String) = listOf(Amount(BigDecimal(value), "USD"))

    private val ledger = LedgerSnapshot(
        title = "Valuation",
        operatingCurrencies = listOf("USD"),
        entryCount = 1,
        entryCounts = mapOf("Transaction" to 1),
        accounts = listOf(
            AccountBalance(
                name = "Assets:Broker:Shares",
                positions = usd("10"),
                atCost = usd("100"),
                atValue = usd("250"),
            ),
            AccountBalance(
                name = "Assets:Bank:Checking",
                positions = usd("5"),
                atCost = usd("5"),
                atValue = usd("5"),
            ),
        ),
        journal = emptyList(),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    private fun total(select: (AccountBalance) -> List<Amount>) =
        ledger.totalFor("Assets", select).single().number

    @Test fun `the default is units, as it always was`() {
        assertEquals(BigDecimal("15"), ledger.totalFor("Assets").single().number)
    }

    @Test fun `cost totals cost`() {
        assertEquals(BigDecimal("105"), total(AccountBalance::atCost))
    }

    @Test fun `market value totals market value`() {
        assertEquals(BigDecimal("255"), total(AccountBalance::atValue))
    }
}
