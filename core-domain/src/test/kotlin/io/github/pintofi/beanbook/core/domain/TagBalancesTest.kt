package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Balances narrowed by tags and links, as Fava's filter bar narrows its reports. */
class TagBalancesTest {

    private fun entry(tags: List<String>, links: List<String>, vararg postings: Pair<String, String>) =
        JournalEntry(
            date = "2026-09-01", flag = "*", payee = null, narration = "x",
            tags = tags, links = links,
            postings = postings.map { (account, number) ->
                JournalPosting(account, Amount(BigDecimal(number), "USD"))
            },
            file = null, line = null,
        )

    private val journal = listOf(
        entry(listOf("trip"), emptyList(), "Expenses:Hotel" to "200.00", "Assets:Bank" to "-200.00"),
        entry(listOf("trip"), listOf("inv-1"), "Expenses:Food" to "30.00", "Assets:Bank" to "-30.00"),
        entry(emptyList(), listOf("inv-1"), "Expenses:Food" to "12.00", "Assets:Bank" to "-12.00"),
        entry(emptyList(), emptyList(), "Expenses:Rent" to "900.00", "Assets:Bank" to "-900.00"),
    )

    @Test fun `a tag totals only the entries that carry it`() {
        val balances = TagBalances.of(journal, TagBalances.Filter.parse("#trip")).associateBy { it.name }

        assertEquals(listOf(Amount(BigDecimal("-230.00"), "USD")), balances.getValue("Assets:Bank").positions)
        assertTrue("Expenses:Rent" !in balances)
    }

    @Test fun `a link filters the same way, and terms combine`() {
        val byLink = TagBalances.of(journal, TagBalances.Filter.parse("^inv-1")).associateBy { it.name }
        assertEquals(listOf(Amount(BigDecimal("42.00"), "USD")), byLink.getValue("Expenses:Food").positions)

        val both = TagBalances.of(journal, TagBalances.Filter.parse("trip ^inv-1")).associateBy { it.name }
        assertEquals(listOf(Amount(BigDecimal("30.00"), "USD")), both.getValue("Expenses:Food").positions)
    }

    @Test fun `a posting held at cost is weighed at cost for the cost basis`() {
        val held = JournalEntry(
            date = "2026-09-01", flag = "*", payee = null, narration = "buy",
            tags = listOf("fund"), links = emptyList(),
            postings = listOf(
                JournalPosting(
                    "Assets:Fund",
                    Amount(BigDecimal("10"), "ACME"),
                    cost = Cost(Amount(BigDecimal("12.50"), "USD")),
                ),
                JournalPosting("Assets:Bank", Amount(BigDecimal("-125.00"), "USD")),
            ),
            file = null, line = null,
        )

        val fund = TagBalances.of(listOf(held), TagBalances.Filter.parse("#fund")).single { it.name == "Assets:Fund" }

        assertEquals(listOf(Amount(BigDecimal("10"), "ACME")), fund.positions)
        assertEquals(listOf(Amount(BigDecimal("125.00"), "USD")), fund.atCost)
    }
}
