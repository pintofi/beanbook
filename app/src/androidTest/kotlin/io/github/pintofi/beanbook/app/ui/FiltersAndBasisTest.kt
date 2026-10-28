package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.Cost
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.JournalPosting
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.domain.PriceRate
import io.github.pintofi.beanbook.core.domain.Valuation
import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Home, Journal and Balances offer the same Filters button and the same choice of basis. */
@RunWith(AndroidJUnit4::class)
class FiltersAndBasisTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun usd(n: String) = Amount(BigDecimal(n), "USD")

    private val ledger = LedgerSnapshot(
        title = "Brokerage",
        operatingCurrencies = listOf("USD"),
        entryCount = 1,
        entryCounts = mapOf("Transaction" to 1),
        accounts = listOf(AccountBalance("Assets:Broker", listOf(Amount(BigDecimal("10"), "AAPL")))),
        journal = listOf(
            JournalEntry(
                date = "2026-03-01", flag = "*", payee = "Broker", narration = "Buy shares",
                tags = emptyList(), links = emptyList(),
                postings = listOf(
                    JournalPosting(
                        "Assets:Broker", Amount(BigDecimal("10"), "AAPL"),
                        cost = Cost(usd("100"), null, null),
                    ),
                    JournalPosting("Assets:Cash", usd("-1000")),
                ),
                file = "main.beancount", line = 3,
            ),
        ),
        errors = emptyList(), includedFiles = emptyList(), loadMillis = 0,
        rates = listOf(PriceRate("AAPL", "USD", BigDecimal("150"))),
    )

    private fun chooseBasis(basis: Valuation) {
        ValuationPreference(InstrumentationRegistry.getInstrumentation().targetContext).write(basis)
    }

    @Test fun theJournalShowsItsAmountsOnTheChosenBasis() {
        compose.setContent { MaterialTheme { JournalScreen(ledger) } }

        compose.onNodeWithContentDescription("Show amounts Units").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("10 AAPL", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Show amounts At cost").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("1000", substring = true).assertIsDisplayed()

        compose.onNodeWithContentDescription("Show amounts Market value").performClick()
        compose.waitForIdle()
        // 10 AAPL at 150 USD; the units stay beneath it.
        compose.onNodeWithText("1500", substring = true).assertIsDisplayed()
        compose.onNodeWithText("10 AAPL", substring = true).assertIsDisplayed()
    }

    @Test fun theChoiceIsTheSameOneHomeAndBalancesUse() {
        chooseBasis(Valuation.COST)
        compose.setContent { MaterialTheme { JournalScreen(ledger) } }
        compose.onNodeWithText("1000", substring = true).assertIsDisplayed()
        // Changing it here is changing it for the app.
        compose.onNodeWithContentDescription("Show amounts Units").performClick()
        compose.waitForIdle()
        assertEquals(
            Valuation.UNITS,
            ValuationPreference(InstrumentationRegistry.getInstrumentation().targetContext).read(),
        )
        chooseBasis(Valuation.VALUE)
    }

    @Test fun theJournalOpensTheFiltersSheet() {
        compose.setContent { MaterialTheme { JournalScreen(ledger) } }
        compose.onNodeWithContentDescription("Filters: period, tags and links").performClick()
        compose.onNodeWithText("Period").assertIsDisplayed()
        compose.onNodeWithText("From").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Filter by tags or links").performScrollTo().assertIsDisplayed()
    }

    @Test fun homeHasTheFiltersButtonToo() {
        compose.setContent { MaterialTheme { HomeScreen(ledger = ledger, hideAmounts = false) } }
        compose.onNodeWithContentDescription("Filters: period, tags and links").performClick()
        compose.onNodeWithText("Period").assertIsDisplayed()
    }

    @Test fun aPeriodIsSaidAndCanBeCleared() {
        var from by mutableStateOf("2026-01-01")
        compose.setContent {
            MaterialTheme {
                JournalScreen(ledger, dateFrom = from, onDateRange = { f, _ -> from = f })
            }
        }
        compose.onNodeWithText("Showing from 2026-01-01", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Clear the period").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Showing from", substring = true).assertDoesNotExist()
    }
}
