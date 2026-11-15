package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.AccountNames
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Accounts page can be searched: only the accounts whose name has every typed word stay listed. */
@RunWith(AndroidJUnit4::class)
class AccountsSearchTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val accounts = listOf(
        AccountBalance("Assets:Cash", emptyList(), opened = "2026-01-01"),
        AccountBalance("Expenses:Food:Groceries", emptyList(), opened = "2026-01-01"),
        AccountBalance("Expenses:Rent", emptyList(), opened = "2026-01-01"),
        AccountBalance("Assets:Old", emptyList(), opened = "2026-01-01", closed = "2026-03-01"),
    )

    private fun show() {
        compose.setContent {
            MaterialTheme {
                AccountsScreen(accounts, AccountNames.DEFAULT, onSave = null, message = null)
            }
        }
        compose.waitForIdle()
    }

    private fun search(text: String) =
        compose.onNodeWithContentDescription("Search accounts by name").performTextInput(text)

    @Test fun everyAccountIsListedUntilSomethingIsTyped() {
        show()
        compose.onNodeWithText("Assets:Cash").assertIsDisplayed()
        compose.onNodeWithText("Expenses:Rent").assertIsDisplayed()
    }

    @Test fun typingNarrowsTheListToTheAccountsThatMatch() {
        show()
        search("food groc")
        compose.onNodeWithText("Expenses:Food:Groceries").assertIsDisplayed()
        compose.onNodeWithText("Assets:Cash").assertDoesNotExist()
        compose.onNodeWithText("Expenses:Rent").assertDoesNotExist()
    }

    @Test fun aClosedAccountIsFoundToo() {
        show()
        search("old")
        compose.onNodeWithText("Assets:Old").assertIsDisplayed()
        compose.onNodeWithText("Assets:Cash").assertDoesNotExist()
    }

    @Test fun whenNothingMatchesTheListSaysSo() {
        show()
        search("zebra")
        compose.onNodeWithText("No account matches \"zebra\".").assertIsDisplayed()
    }

    @Test fun theClearButtonBringsEveryAccountBack() {
        show()
        search("rent")
        compose.onNodeWithText("Assets:Cash").assertDoesNotExist()
        compose.onNodeWithContentDescription("Clear the account search").performClick()
        compose.onNodeWithText("Assets:Cash").assertIsDisplayed()
    }

    // -- the page's order and its collapsible groups ---------------------------------------------------

    private fun showEditable() {
        compose.setContent {
            MaterialTheme {
                AccountsScreen(accounts, AccountNames.DEFAULT, onSave = {}, message = null, projectKey = "a")
            }
        }
        compose.waitForIdle()
    }

    @Test fun theOpenAnAccountFormComesFirstThenTheSearchThenTheAccounts() {
        showEditable()

        val form = compose.onNodeWithText("Open an account").fetchSemanticsNode().positionInRoot.y
        val search = compose.onNodeWithContentDescription("Search accounts by name")
            .fetchSemanticsNode().positionInRoot.y
        val assets = compose.onNode(
            hasText("Assets") and SemanticsMatcher.keyIsDefined(SemanticsProperties.StateDescription),
        ).fetchSemanticsNode().positionInRoot.y

        assertTrue("form $form above search $search", form < search)
        assertTrue("search $search above the first group $assets", search < assets)
    }

    @Test fun aGroupCollapsesAndOpensAgainAndSaysWhichItIs() {
        show()
        compose.onNode(hasText("Assets") and hasClickAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Expanded"),
        )

        compose.onNode(hasText("Assets") and hasClickAction()).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Assets:Cash").assertDoesNotExist()
        compose.onNodeWithText("Expenses:Rent").assertIsDisplayed()
        compose.onNode(hasText("Assets") and hasClickAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Collapsed"),
        )

        compose.onNode(hasText("Assets") and hasClickAction()).performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Assets:Cash").assertIsDisplayed()
    }

    @Test fun theClosedAccountsCollapseToo() {
        show()

        compose.onNode(hasText("Closed accounts") and hasClickAction()).performClick()
        compose.waitForIdle()

        compose.onNodeWithText("Assets:Old").assertDoesNotExist()
    }

    @Test fun aMatchInACollapsedGroupIsShownAndTheChoiceReturnsWhenTheSearchIsCleared() {
        show()
        compose.onNode(hasText("Assets") and hasClickAction()).performClick()
        compose.waitForIdle()

        search("cash")
        compose.waitForIdle()
        compose.onNodeWithText("Assets:Cash").assertIsDisplayed()

        compose.onNodeWithContentDescription("Clear the account search").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Assets:Cash").assertDoesNotExist()
    }

    @Test fun theCollapsedGroupsSurviveARotation() {
        val restoration = androidx.compose.ui.test.junit4.StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                AccountsScreen(accounts, AccountNames.DEFAULT, onSave = null, message = null, projectKey = "a")
            }
        }
        compose.onNode(hasText("Assets") and hasClickAction()).performClick()
        compose.waitForIdle()

        restoration.emulateSavedInstanceStateRestore()
        compose.waitForIdle()

        compose.onNodeWithText("Assets:Cash").assertDoesNotExist()
    }

    @Test fun aGroupCountSaysHowManyAccountsAreInIt() {
        show()

        compose.onNode(hasText("Expenses") and hasClickAction()).assertTextContains("2", substring = true)
    }
}
