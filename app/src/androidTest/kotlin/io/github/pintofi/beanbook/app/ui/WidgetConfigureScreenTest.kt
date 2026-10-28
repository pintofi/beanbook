package io.github.pintofi.beanbook.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import io.github.pintofi.beanbook.app.widget.ChooseAccounts
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The screen a launcher shows while a balance widget is being placed.
 *
 * Tested as a composable rather than through the activity, because the activity's guard
 * requires a real bound app-widget id and the system refuses `bindAppWidgetIdIfAllowed` to
 * anything that is not a launcher (see `WidgetHostTest`). The guard itself is exercised
 * separately by launching the exported activity with an id that is not ours; what is left
 * for here is what the person actually sees and touches.
 */
class WidgetConfigureScreenTest {

    @get:Rule val compose = createComposeRule()

    private val accounts = listOf("Assets:Bank:Current", "Assets:Bank:Savings", "Liabilities:Card")

    @Test fun everyAccountIsOfferedAndTheChoiceIsReported() {
        var chosen: List<String>? = null
        compose.setContent { ChooseAccounts(accounts = accounts, onChoose = { list, _ -> chosen = list }) }

        accounts.forEach { compose.onNodeWithText(it).assertIsDisplayed() }

        compose.onNodeWithText("Assets:Bank:Savings").performClick()
        compose.onNodeWithText("Add this widget").performClick()

        assertEquals(
            "the tapped account was not the one reported",
            listOf("Assets:Bank:Savings"),
            chosen,
        )
    }

    /** Several balances on one widget is the point of the screen being a list. */
    @Test fun severalAccountsCanBeChosenAtOnce() {
        var chosen: List<String>? = null
        compose.setContent { ChooseAccounts(accounts = accounts, onChoose = { list, _ -> chosen = list }) }

        compose.onNodeWithText("Assets:Bank:Current").performClick()
        compose.onNodeWithText("Liabilities:Card").performClick()
        compose.onNodeWithText("Add this widget with 2 balances").performClick()

        assertEquals(listOf("Assets:Bank:Current", "Liabilities:Card"), chosen)
    }

    /** A long chart of accounts is found rather than scrolled. */
    @Test fun theListCanBeFiltered() {
        compose.setContent { ChooseAccounts(accounts = accounts, onChoose = { _, _ -> }) }

        compose.onNodeWithContentDescription("Filter accounts by name").performTextInput("Card")

        compose.onNodeWithText("Liabilities:Card").assertIsDisplayed()
        compose.onNodeWithText("Assets:Bank:Current").assertDoesNotExist()
    }

    /**
     * A widget placed before the app has ever run has no cache to read.
     *
     * An empty list would say "this ledger has no accounts", which is false and unhelpful.
     * It says what to do instead.
     */
    @Test fun withNothingCachedItSaysWhatToDo() {
        compose.setContent { ChooseAccounts(accounts = emptyList(), onChoose = { _, _ -> }) }

        compose.onNodeWithText("Open the app once", substring = true).assertIsDisplayed()
    }

    /** An account holding two commodities lets either be chosen, like an account. */
    @Test fun anAccountWithTwoCommoditiesLetsOneBePicked() {
        var option: io.github.pintofi.beanbook.app.widget.WidgetOption? = null
        var chosen: List<String>? = null
        compose.setContent {
            ChooseAccounts(
                accounts = listOf("Assets:Cash", "Assets:Bank"),
                commodities = mapOf("Assets:Cash" to listOf("PKR", "SAR")),
                onChoose = { list, o -> chosen = list; option = o },
            )
        }
        // Not offered until the account is chosen.
        compose.onNodeWithContentDescription("Show only SAR for Assets:Cash").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show the balance of Assets:Cash").performClick()
        compose.onNodeWithContentDescription("Show only SAR for Assets:Cash").assertIsDisplayed()
        // Untick PKR: only SAR is left.
        compose.onNodeWithContentDescription("Show only PKR for Assets:Cash").performClick()
        compose.onNodeWithText("Add this widget").performClick()

        assertEquals(listOf("Assets:Cash"), chosen)
        assertEquals(setOf("SAR"), option!!.commodities["Assets:Cash"])
    }

    @Test fun theBasisIsChosenOnTheWidgetNotOnThisScreen() {
        var option: io.github.pintofi.beanbook.app.widget.WidgetOption? = null
        compose.setContent {
            ChooseAccounts(
                accounts = accounts,
                onChoose = { _, o -> option = o },
                initialOption = io.github.pintofi.beanbook.app.widget.WidgetOption(
                    basis = io.github.pintofi.beanbook.core.domain.Valuation.COST,
                ),
            )
        }
        // No choice of basis here; what the widget already had is kept.
        compose.onNodeWithContentDescription("Show figures: At cost").assertDoesNotExist()
        compose.onNodeWithText("Assets:Bank:Current").performClick()
        compose.onNodeWithText("Add this widget").performClick()
        assertEquals(io.github.pintofi.beanbook.core.domain.Valuation.COST, option!!.basis)
    }
}
