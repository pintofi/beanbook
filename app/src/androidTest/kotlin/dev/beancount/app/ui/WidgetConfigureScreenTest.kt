package dev.beancount.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import dev.beancount.app.widget.ChooseAccount
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

/**
 * The screen a launcher shows while a balance widget is being placed (B1).
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
        var chosen: String? = null
        compose.setContent { ChooseAccount(accounts = accounts, onChoose = { chosen = it }) }

        accounts.forEach { compose.onNodeWithText(it).assertIsDisplayed() }

        compose.onNodeWithText("Assets:Bank:Savings").performClick()

        assertEquals("the tapped account was not the one reported", "Assets:Bank:Savings", chosen)
    }

    /**
     * A widget placed before the app has ever run has no cache to read.
     *
     * An empty list would say "this ledger has no accounts", which is false and unhelpful.
     * It says what to do instead.
     */
    @Test fun withNothingCachedItSaysWhatToDo() {
        compose.setContent { ChooseAccount(accounts = emptyList(), onChoose = {}) }

        compose.onNodeWithText("Open the app once", substring = true).assertIsDisplayed()
    }
}
