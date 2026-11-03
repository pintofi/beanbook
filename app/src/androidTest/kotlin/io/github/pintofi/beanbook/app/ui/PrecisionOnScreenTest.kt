package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.AccountNames
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.LedgerFormat
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the ledger says about places, separators and tolerance reaches the figures on screen. */
@RunWith(AndroidJUnit4::class)
class PrecisionOnScreenTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val format = LedgerFormat(groupThousands = true, precision = mapOf("USD" to 2, "JPY" to 0))

    @Test fun theAccountsPageShowsABalanceWithTheLedgersPlacesAndSeparators() {
        val cash = AccountBalance(
            "Assets:Cash",
            listOf(Amount(BigDecimal("1234.5"), "USD"), Amount(BigDecimal("15000"), "JPY")),
            opened = "2024-01-01",
        )
        compose.setContent {
            MaterialTheme {
                AccountsScreen(listOf(cash), AccountNames.DEFAULT, onSave = null, message = null, format = format)
            }
        }
        compose.onNodeWithText("1,234.50 USD, 15,000 JPY", substring = true).assertIsDisplayed()
    }

    @Test fun theAccountsPageRoundsAsTheLedgerDoes() {
        val cash = AccountBalance("Assets:Cash", listOf(Amount(BigDecimal("2.345"), "USD")), opened = "2024-01-01")
        compose.setContent {
            MaterialTheme {
                AccountsScreen(listOf(cash), AccountNames.DEFAULT, onSave = null, message = null, format = format)
            }
        }
        // Half to even, as Beancount: 2.345 is 2.34 and not 2.35.
        compose.onNodeWithText("2.34 USD", substring = true).assertIsDisplayed()
    }

    @Test fun theFormNamesTheFigureBeancountWillWriteOnTheBlankPostingNotTheExactLeftover() {
        val vocabulary = LedgerVocabulary(
            accounts = listOf("Assets:Cash", "Expenses:Food", "Expenses:Fuel"),
            payees = emptyList(), narrations = emptyList(), currencies = listOf("USD"), tags = emptyList(),
            defaultCurrency = "USD",
        )
        compose.setContent {
            MaterialTheme {
                QuickAddScreen(vocabulary, onSave = {}, today = LocalDate.parse("2026-09-01"), format = format)
            }
        }
        // 10.1 and 2.22: the least precise figure says one place, so Beancount writes -12.3 (shown to the
        // ledger's two places as -12.30), not the exact -12.32.
        compose.onNodeWithContentDescription("Account for posting 1").performTextInput("Expenses:Food")
        compose.onNodeWithContentDescription("Account for posting 2").performTextInput("Expenses:Fuel")
        compose.onAllNodesWithText("Amount")[0].performTextInput("10.1")
        compose.onAllNodesWithText("Amount")[1].performTextInput("2.22")
        compose.onNodeWithText("Add posting").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Account for posting 3").performTextInput("Assets:Cash")
        compose.waitForIdle()
        compose.onNodeWithText("Beancount will put -12.30 USD", substring = true).assertExists()
    }
}
