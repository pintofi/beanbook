package io.github.pintofi.beanbook.app.onboarding

import io.github.pintofi.beanbook.core.data.Json
import io.github.pintofi.beanbook.core.domain.LedgerBlueprint
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupDraftTest {

    private val today = LocalDate.of(2026, 10, 20)

    @Test fun `a device in the euro area starts on EUR, and one with no currency on USD`() {
        assertEquals("EUR", SetupDraft.deviceCurrency(Locale.GERMANY))
        assertEquals("PKR", SetupDraft.deviceCurrency(Locale.forLanguageTag("ur-PK")))
        assertEquals("USD", SetupDraft.deviceCurrency(Locale.ROOT))
    }

    @Test fun `usual places follow the currency`() {
        assertEquals(0, SetupDraft.usualPlaces("JPY"))
        assertEquals(3, SetupDraft.usualPlaces("KWD"))
        assertEquals(2, SetupDraft.usualPlaces("EUR"))
        assertEquals(2, SetupDraft.usualPlaces("VTI"))
    }

    @Test fun `the defaults make a blueprint with no problems and the usual seven accounts`() {
        val blueprint = SetupDraft(mainCurrency = "EUR", name = "Books").toBlueprint(today, emptyMap())
        assertEquals(emptyList<String>(), blueprint.problems())
        assertEquals(7, blueprint.plannedAccounts().size)
        assertTrue(blueprint.plannedAccounts().all { it.currencies == listOf("EUR") })
        assertEquals(mapOf("EUR" to 2), blueprint.decimals)
    }

    @Test fun `with the switch off each account keeps the currencies typed beside it`() {
        val draft = SetupDraft(
            mainCurrency = "EUR", otherCurrencies = listOf("USD"), everyAccountUsesMain = false,
            accounts = SetupDraft.starterChoices().map {
                when (it.name) {
                    "Assets:Cash" -> it.copy(currencies = "EUR, usd")
                    "Assets:Bank:Checking" -> it.copy(currencies = "")
                    else -> it.copy(currencies = "EUR")
                }
            },
        )
        val plans = draft.toBlueprint(today, emptyMap()).plannedAccounts().associateBy { it.name }
        assertEquals(listOf("EUR", "USD"), plans.getValue("Assets:Cash").currencies)
        assertEquals(emptyList<String>(), plans.getValue("Assets:Bank:Checking").currencies)
        // One account takes anything, so the opening account must too.
        assertEquals(emptyList<String>(), plans.getValue("Equity:Opening-Balances").currencies)
    }

    @Test fun `an account that is switched off is left out, and the locked one never is`() {
        val draft = SetupDraft(
            accounts = SetupDraft.starterChoices().map {
                if (it.name == "Assets:Cash" || it.name == "Equity:Opening-Balances") it.copy(on = false) else it
            },
        )
        val names = draft.toBlueprint(today, emptyMap()).plannedAccounts().map { it.name }
        assertTrue("Assets:Cash" !in names)
        assertTrue("Equity:Opening-Balances" in names)
    }

    @Test fun `an opening balance is written for a chosen account, and a liability is typed as owed`() {
        val draft = SetupDraft(
            mainCurrency = "EUR",
            accounts = SetupDraft.starterChoices()
                .map { if (it.name == "Liabilities:CreditCard") it.copy(on = true) else it },
            opening = mapOf(
                "Assets:Bank:Checking" to "1,250.00",
                "Liabilities:CreditCard" to "310.40",
                "Assets:Cash" to " ",
            ),
        )
        val balances = draft.toBlueprint(today, emptyMap()).openingBalances
        assertEquals(BigDecimal("1250.00"), balances.getValue("Assets:Bank:Checking").number)
        assertEquals(BigDecimal("-310.40"), balances.getValue("Liabilities:CreditCard").number)
        assertTrue("Assets:Cash" !in balances)
    }

    @Test fun `an unreadable amount is not an opening balance`() {
        val draft = SetupDraft(opening = mapOf("Assets:Bank:Checking" to "twelve"))
        assertTrue(draft.toBlueprint(today, emptyMap()).openingBalances.isEmpty())
    }

    @Test fun `typed currencies are cleaned up, and the report currencies are only ones that are used`() {
        val blueprint = SetupDraft(
            mainCurrency = " eur ", otherCurrencies = listOf("usd", "EUR"), reportIn = setOf("USD", "GBP"),
        ).toBlueprint(today, emptyMap())
        assertEquals("EUR", blueprint.mainCurrency)
        assertEquals(listOf("USD"), blueprint.otherCurrencies)
        assertEquals(listOf("USD"), blueprint.reportCurrencies)
    }

    @Test fun `problems name what is wrong in words`() {
        assertTrue(SetupDraft(name = " ").problems().any { "needs a name" in it })
        assertTrue(SetupDraft(mainCurrency = "1eu").problems().any { "\"1EU\"" in it })
    }

    @Test fun `a draft survives a round trip through JSON`() {
        val draft = SetupDraft(
            step = SetupStep.ACCOUNTS, name = "Home", mainCurrency = "PKR", otherCurrencies = listOf("SAR"),
            start = "2026-09-01", places = mapOf("PKR" to 0), booking = "FIFO", reportIn = setOf("SAR"),
            fiscalYearEnd = "06-30", reportsOpenOn = "balance_sheet/",
            checks = setOf(LedgerBlueprint.Check.LEAFONLY), everyAccountUsesMain = false,
            accounts = SetupDraft.starterChoices() +
                AccountChoice("Expenses:Coffee", on = true, currencies = "PKR", user = true),
            opening = mapOf("Assets:Cash" to "5000"),
        )
        val back = SetupDraft.fromJson(Json.parse(draft.toJson().write()))
        assertEquals(draft, back)
    }

    @Test fun `a draft from another version, or garbage, is discarded`() {
        assertNull(SetupDraft.fromJson(Json.parse("""{"schema": 99}""")))
        assertNull(SetupDraft.fromJson(Json.parse("[1,2]")))
        assertNull(SetupDraft.fromJson(null))
    }
}
