package io.github.pintofi.beanbook.core.domain

import java.math.BigDecimal
import java.time.LocalDate
import java.time.MonthDay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LedgerBlueprintTest {

    private fun eur(vararg names: String) = names.map { AccountPlan(it, listOf("EUR")) }

    /** The example in the design: EUR, books starting 2026-10-01, two opening balances. */
    private val household = LedgerBlueprint(
        title = "Household",
        start = LocalDate.of(2026, 10, 1),
        mainCurrency = "EUR",
        decimals = mapOf("EUR" to 2),
        defaultPage = "balance_sheet/",
        accounts = eur(
            "Assets:Cash", "Assets:Bank:Checking", "Assets:Bank:Savings", "Liabilities:CreditCard",
            "Income:Salary", "Expenses:Food:Groceries", "Expenses:Home:Rent", "Expenses:Transport",
            LedgerBlueprint.OPENING,
        ),
        openingBalances = mapOf(
            "Assets:Bank:Checking" to Amount(BigDecimal("1250.00"), "EUR"),
            "Liabilities:CreditCard" to Amount(BigDecimal("-310.40"), "EUR"),
        ),
        commodityNames = mapOf("EUR" to "Euro"),
    )

    @Test fun `the accounts file lines the currencies up and dates the opens the day before the start`() {
        val file = household.render().getValue("files/accounts.beancount")
        // Names are padded to a column at least 28 wide, so the currencies line up.
        assertTrue(file.contains("2026-09-30 open Assets:Cash" + " ".repeat(17) + "EUR\n"), file)
        assertTrue(file.contains("2026-09-30 open Equity:Opening-Balances" + " ".repeat(5) + "EUR\n"), file)
        // Every currency starts in the same column.
        val columns = file.lines().filter { it.startsWith("2026") }.map { it.indexOf("EUR") }.toSet()
        assertEquals(1, columns.size, file)
    }

    @Test fun `opening balances are a pad the day before and a balance on the start`() {
        val file = household.render().getValue("files/transactions.beancount")
        assertTrue(file.contains("2026-09-30 pad Assets:Bank:Checking Equity:Opening-Balances\n"))
        assertTrue(file.contains("2026-10-01 balance Assets:Bank:Checking"))
        assertTrue(file.contains("-310.40 EUR"))
    }

    @Test fun `the main file keeps the title near the top and names every file it includes`() {
        val rendered = household.render()
        val main = rendered.getValue("main.beancount")
        assertTrue(main.lines().indexOfFirst { it.startsWith("option \"title\"") } < 50)
        Regex("""include "([^"]+)"""").findAll(main).forEach {
            assertTrue(it.groupValues[1] in rendered, "${it.groupValues[1]} is included but not written")
        }
        assertTrue(main.contains("option \"operating_currency\" \"EUR\"\n"))
        assertTrue(main.contains("option \"documents\" \"documents\"\n"))
        assertTrue(main.contains("plugin \"fava.plugins.link_documents\"\n"))
        assertTrue(main.contains("\"default-page\" \"balance_sheet/\""))
        assertTrue(main.contains("option \"display_precision\" \"EUR:0.01\"\n"))
        assertTrue(main.contains("option \"inferred_tolerance_default\" \"*:0.005\"\n"))
    }

    @Test fun `every currency has the default tolerance and only a currency the person set has its own`() {
        val plain = household.render().getValue("main.beancount")
        assertEquals(1, Regex("inferred_tolerance_default").findAll(plain).count(), plain)

        val own = household.copy(decimals = mapOf("EUR" to 2, "JPY" to 0), explicitDecimals = setOf("JPY"))
            .render().getValue("main.beancount")
        assertTrue(own.contains("option \"inferred_tolerance_default\" \"*:0.005\"\n"))
        assertTrue(own.contains("option \"inferred_tolerance_default\" \"JPY:0.5\"\n"))
        assertFalse(own.contains("\"EUR:0.005\""))
    }

    @Test fun `a new ledger has every check on except the one that flags accounts not yet used`() {
        assertEquals(
            LedgerBlueprint.Check.entries.toSet() - LedgerBlueprint.Check.NOUNUSED,
            LedgerBlueprint.DEFAULT_CHECKS,
        )
        val main = LedgerBlueprint.defaults("D", "EUR", java.time.LocalDate.of(2026, 10, 21))
            .render().getValue("main.beancount")
        assertTrue(main.contains("\nplugin \"beancount.plugins.leafonly\"\n"))
        assertTrue(main.contains("\n; plugin \"beancount.plugins.nounused\"\n"))
    }

    @Test fun `a check that is not chosen is a commented line`() {
        val off = household.copy(checks = emptySet()).render().getValue("main.beancount")
        assertTrue(off.contains("\n; plugin \"beancount.plugins.leafonly\"\n"))
        val on = household.copy(checks = setOf(LedgerBlueprint.Check.LEAFONLY)).render().getValue("main.beancount")
        assertTrue(on.contains("\nplugin \"beancount.plugins.leafonly\"\n"))
        assertFalse(on.contains("\nplugin \"beancount.plugins.nounused\""))
    }

    @Test fun `opening balances may come from any currency an account accepts`() {
        val travel = household.copy(
            accounts = household.accounts + AccountPlan("Expenses:Travel", listOf("EUR", "USD")),
        )
        val file = travel.render().getValue("files/accounts.beancount")
        assertTrue(file.contains("Equity:Opening-Balances" + " ".repeat(5) + "EUR,USD\n"), file)
        assertTrue(travel.render().getValue("files/commodities.beancount").contains("commodity USD"))
    }

    @Test fun `an account that takes any currency makes the opening account take any currency`() {
        val any = household.copy(accounts = household.accounts + AccountPlan("Assets:Broker"))
        val line = any.render().getValue("files/accounts.beancount").lines().first { "Opening-Balances" in it }
        assertEquals("2026-09-30 open Equity:Opening-Balances", line.trimEnd())
    }

    @Test fun `a fiscal year end and extra report currencies are written`() {
        val main = household.copy(fiscalYearEnd = MonthDay.of(6, 30), reportCurrencies = listOf("USD"))
            .render().getValue("main.beancount")
        assertTrue(main.contains("\"fiscal-year-end\" \"06-30\""))
        assertTrue(main.contains("option \"operating_currency\" \"USD\"\n"))
    }

    @Test fun `a title with quotes and a newline is escaped and kept on one comment line`() {
        val main = household.copy(title = "My \"home\"\nbooks").render().getValue("main.beancount")
        assertEquals("; My \"home\" books", main.lines().first())
        assertTrue(main.contains("option \"title\" \"My \\\"home\\\" books\"\n"))
    }

    @Test fun `no trailing spaces, only line feeds, and a final newline`() {
        household.render().forEach { (path, text) ->
            assertTrue(text.endsWith("\n"), path)
            assertFalse('\r' in text, path)
            assertTrue(text.lines().none { it != it.trimEnd() }, "$path has trailing spaces")
        }
    }

    @Test fun `defaults reproduce the seven starter accounts in the chosen currency`() {
        val plain = LedgerBlueprint.defaults("Books", "PKR", LocalDate.of(2026, 10, 20), places = 2)
        assertEquals(LocalDate.of(2026, 10, 1), plain.start)
        assertEquals(
            listOf(
                "Assets:Cash", "Assets:Bank:Checking", "Income:Salary", "Expenses:Food:Groceries",
                "Expenses:Home:Rent", "Expenses:Transport", "Equity:Opening-Balances",
            ).sorted(),
            plain.plannedAccounts().map { it.name }.sorted(),
        )
        assertTrue(plain.plannedAccounts().all { it.currencies == listOf("PKR") })
        assertTrue(plain.problems().isEmpty(), plain.problems().toString())
    }

    @Test fun `a blueprint says what is wrong with it`() {
        val bad = household.copy(
            title = " ", mainCurrency = "eur",
            accounts = listOf(AccountPlan("Assets:cash"), AccountPlan("Assets:cash")),
        )
        val problems = bad.problems().joinToString("\n")
        assertTrue("needs a name" in problems)
        assertTrue("\"eur\"" in problems)
        assertTrue("twice" in problems)
        assertTrue("Each part starts with a capital" in problems)
        assertTrue(household.copy(accounts = eur("Expenses:Food")).problems().any { "Assets" in it })
    }

    @Test fun `the opening balance of an account that is not on the list is left out`() {
        val file = household.copy(accounts = household.accounts.filterNot { it.name == "Liabilities:CreditCard" })
            .render().getValue("files/transactions.beancount")
        assertFalse(file.contains("CreditCard"))
    }
}
