package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.JournalPosting
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import java.time.LocalDate
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The account field offers what Fava's entry form would: the accounts used most and most recently
 * first, the payee's own ahead of those, typed letters matched in order, and a closed account only
 * for an entry dated up to its closing day.
 */
@RunWith(AndroidJUnit4::class)
class AccountSuggestionsFormTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun entry(date: String, payee: String, vararg accounts: String) = JournalEntry(
        date = date, flag = "*", payee = payee, narration = null, tags = emptyList(), links = emptyList(),
        postings = accounts.map { JournalPosting(it, null) }, file = null, line = null,
    )

    private val all = listOf(
        "Assets:Bank:Checking", "Assets:Old", "Expenses:Food:Dining", "Expenses:Food:Groceries", "Expenses:Rent",
    )

    private val vocabulary = LedgerVocabulary(
        accounts = all.filter { it != "Assets:Old" },
        payees = listOf("Grocer", "Landlord"),
        narrations = emptyList(),
        currencies = listOf("EUR"),
        tags = emptyList(),
        recentEntries = listOf(
            entry("2026-09-01", "Landlord", "Expenses:Rent", "Assets:Bank:Checking"),
            entry("2026-08-01", "Landlord", "Expenses:Rent", "Assets:Bank:Checking"),
            entry("2026-02-01", "Grocer", "Expenses:Food:Groceries", "Assets:Old"),
            entry("2024-01-01", "Cafe", "Expenses:Food:Dining", "Assets:Bank:Checking"),
        ),
        declaredAccounts = all.toSet(),
        closedOn = mapOf("Assets:Old" to "2026-03-01"),
        entryAccounts = all,
    )

    private fun show(day: String = "2026-09-15") {
        compose.setContent {
            MaterialTheme { QuickAddScreen(vocabulary, onSave = {}, today = LocalDate.parse(day)) }
        }
        compose.waitForIdle()
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    private fun openFirstAccountField() {
        compose.onNodeWithContentDescription("Account for posting 1").performClick()
        compose.waitForIdle()
    }

    @Test fun theMostUsedAccountsComeFirstNotTheAlphabeticalOnes() {
        show()
        openFirstAccountField()
        // Checking and Rent were used most recently; Dining last in 2024. Alphabetically Assets:Old would
        // be second and Dining third.
        assertTrue(top("Assets:Bank:Checking") < top("Expenses:Rent"))
        assertTrue(top("Expenses:Rent") < top("Expenses:Food:Dining"))
    }

    @Test fun theChosenPayeesAccountsComeAheadOfTheRest() {
        show()
        compose.onNodeWithText("Payee").performTextInput("Grocer")
        openFirstAccountField()
        assertTrue(top("Expenses:Food:Groceries") < top("Assets:Bank:Checking"))
    }

    @Test fun letterTypedInOrderFindAnAccountEvenWhenTheyAreNotNextToEachOther() {
        show()
        compose.onNodeWithContentDescription("Account for posting 1").performTextInput("fgr")
        compose.onNodeWithText("Expenses:Food:Groceries").assertIsDisplayed()
        compose.onNodeWithText("Assets:Bank:Checking").assertDoesNotExist()
    }

    @Test fun aClosedAccountIsNotOfferedForAnEntryDatedAfterItClosed() {
        show("2026-09-15")
        openFirstAccountField()
        compose.onNodeWithText("Assets:Old").assertDoesNotExist()
    }

    @Test fun aClosedAccountIsOfferedForAnEntryDatedBeforeItClosed() {
        show("2026-09-15")
        compose.onNodeWithText("2026-09-15").performTextClearance()
        compose.onNodeWithText("Date").performTextInput("2026-02-15")
        openFirstAccountField()
        compose.onNodeWithText("Assets:Old").assertIsDisplayed()
    }

    @Test fun typingAClosedAccountForALaterDateNamesTheReasonAndNotThatItDoesNotExist() {
        show("2026-09-15")
        compose.onNodeWithContentDescription("Account for posting 1").performTextInput("Assets:Old")
        compose.onNodeWithContentDescription("Account for posting 2").performTextInput("Expenses:Rent")
        compose.onNodeWithText("was closed on 2026-03-01", substring = true).assertExists()
    }
}
