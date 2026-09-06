package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import java.math.BigDecimal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Balances folds, and folding hides rows rather than money (B46).
 *
 * The dangerous failure is not a branch that will not open — it is a collapsed branch whose
 * figures vanish from the screen, which reads as a ledger that has lost something.
 */
@RunWith(AndroidJUnit4::class)
class BalancesTreeTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun balance(name: String, value: String) =
        AccountBalance(name, listOf(Amount(BigDecimal(value), "USD")))

    private val snapshot = LedgerSnapshot(
        title = "Test",
        operatingCurrencies = listOf("USD"),
        entryCount = 0,
        entryCounts = emptyMap(),
        accounts = listOf(
            balance("Assets:Bank:Checking", "100.00"),
            balance("Assets:Bank:Savings", "250.00"),
            balance("Assets:Cash", "20.00"),
            balance("Expenses:Food", "5.00"),
        ),
        journal = emptyList(),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    private fun render() {
        compose.setContent { MaterialTheme { BalancesScreen(snapshot) } }
        compose.waitForIdle()
    }

    @Test fun aBranchOpensAndTheAccountsUnderItAppear() {
        render()
        compose.onNodeWithText("Checking").assertDoesNotExist()
        compose.onNodeWithContentDescription("Assets:Bank, collapsed. Tap to expand").performClick()
        compose.onNodeWithText("Checking").assertIsDisplayed()
        compose.onNodeWithText("Savings").assertIsDisplayed()
    }

    @Test fun aFoldedBranchStillShowsWhatIsInsideIt() {
        render()
        // Assets:Bank is collapsed, and 100.00 + 250.00 is the figure standing in for the
        // two rows it hides. The root above it totals 370.00, so this figure is the folded
        // branch's own and nothing else's.
        compose.onNodeWithText("350.00 USD").assertIsDisplayed()
        compose.onNodeWithText("370.00 USD").assertIsDisplayed()
    }

    @Test fun aRootCanBeFoldedAway() {
        render()
        compose.onNodeWithText("Food").assertIsDisplayed()
        compose.onNodeWithContentDescription("Expenses, expanded. Tap to collapse").performClick()
        compose.onNodeWithText("Food").assertDoesNotExist()
        // The root's own total stays: the money is folded out of sight, not out of the books.
        compose.onNodeWithText("Expenses").assertIsDisplayed()
    }

    @Test fun filteringOpensEveryBranchItMatches() {
        render()
        compose.onNodeWithContentDescription("Filter accounts by name").performTextInput("Savings")
        compose.waitForIdle()
        // A search that hid its own result behind a fold would have answered nothing. By
        // content description, because the filter field now holds the word "Savings" too.
        compose.onNodeWithContentDescription("Assets:Bank:Savings").assertIsDisplayed()
    }
}
