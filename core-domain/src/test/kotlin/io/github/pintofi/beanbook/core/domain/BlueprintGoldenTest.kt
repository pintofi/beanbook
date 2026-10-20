package io.github.pintofi.beanbook.core.domain

import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The files a new ledger starts with, held in `fixtures/new-ledger/<case>/`.
 *
 * The Python suite loads the same folders with real Beancount and Fava and asserts that they read
 * without an error, so a generator change that Beancount would not accept fails there, and a
 * change to the text that nobody meant fails here. Run with `UPDATE_GOLDEN=1` to rewrite them.
 */
class BlueprintGoldenTest {

    private val root = File("../fixtures/new-ledger")

    private val cases: Map<String, LedgerBlueprint> = mapOf(
        "defaults-eur" to LedgerBlueprint.defaults(
            "My ledger", "EUR", LocalDate.of(2026, 10, 20), mapOf("EUR" to "Euro"),
        ),
        "household" to LedgerBlueprint(
            title = "Household",
            start = LocalDate.of(2026, 10, 1),
            mainCurrency = "EUR",
            otherCurrencies = listOf("USD"),
            decimals = mapOf("EUR" to 2, "USD" to 2),
            defaultPage = "balance_sheet/",
            checks = setOf(LedgerBlueprint.Check.CHECK_COMMODITY, LedgerBlueprint.Check.UNIQUE_PRICES),
            accounts = listOf(
                AccountPlan("Assets:Cash", listOf("EUR")),
                AccountPlan("Assets:Bank:Checking", listOf("EUR")),
                AccountPlan("Assets:Bank:Savings", listOf("EUR", "USD")),
                AccountPlan("Liabilities:CreditCard", listOf("EUR")),
                AccountPlan("Income:Salary", listOf("EUR")),
                AccountPlan("Expenses:Food:Groceries", listOf("EUR")),
                AccountPlan("Expenses:Travel", listOf("EUR", "USD")),
                AccountPlan(LedgerBlueprint.OPENING, locked = true, origin = AccountPlan.Origin.STARTER),
            ),
            openingBalances = mapOf(
                "Assets:Bank:Checking" to Amount(BigDecimal("1250.00"), "EUR"),
                "Liabilities:CreditCard" to Amount(BigDecimal("-310.40"), "EUR"),
            ),
            commodityNames = mapOf("EUR" to "Euro", "USD" to "US Dollar"),
        ),
        "jpy-no-decimals" to LedgerBlueprint.defaults(
            "Yen books", "JPY", LocalDate.of(2026, 4, 3), mapOf("JPY" to "Japanese Yen"), places = 0,
        ),
    )

    @Test fun `every case renders the files held in fixtures`() {
        val update = System.getenv("UPDATE_GOLDEN") == "1"
        cases.forEach { (name, blueprint) ->
            assertEquals(emptyList(), blueprint.problems(), name)
            blueprint.render().forEach { (path, text) ->
                val file = File(root, "$name/$path")
                if (update) {
                    file.parentFile.mkdirs()
                    file.writeText(text)
                }
                assertEquals(file.readText(), text, "$name/$path differs from the held file")
            }
        }
    }
}
