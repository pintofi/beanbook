package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.ui.theme.LedgerType
import io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The loud moments: the in-balance stamp, rolling figures and empty-state drawings (B87). */
@RunWith(AndroidJUnit4::class)
class LedgerFlourishTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val clean = io.github.pintofi.beanbook.core.domain.LedgerSnapshot(
        title = "T",
        operatingCurrencies = listOf("USD"),
        entryCount = 0,
        entryCounts = emptyMap(),
        accounts = emptyList(),
        journal = emptyList(),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    /** The stamp is on the line that says nothing needs attention, and says what it means. */
    @Test fun theStampSitsOnTheCleanLedgerLine() {
        var stamped by mutableStateOf(false)
        compose.setContent {
            MaterialTheme { HomeScreen(ledger = clean, hideAmounts = false, stamped = stamped) }
        }
        val stamp = "In balance. The ledger loads with no errors again."
        compose.onNodeWithText("Nothing. The ledger has no errors, and every document is linked.")
            .performScrollTo()
            .assertIsDisplayed()
        compose.onAllNodesWithContentDescription(stamp).assertCountEquals(0)
        stamped = true
        compose.waitForIdle()
        compose.onNodeWithContentDescription(stamp).performScrollTo().assertIsDisplayed()
    }

    @Test fun aRollingFigureIsReadAsTheWholeFigure() {
        var value by mutableStateOf("116423.20 PKR")
        compose.setContent {
            MaterialTheme { RollingFigure(text = value, style = LedgerType.display, rollInFrom = zeroed(value)) }
        }
        compose.onNodeWithText("116423.20 PKR").assertIsDisplayed()
        value = "116500.00 PKR"
        compose.waitForIdle()
        compose.onNodeWithText("116500.00 PKR").assertIsDisplayed()
        // Never announced digit by digit.
        compose.onAllNodesWithText("6").assertCountEquals(0)
    }

    @Test fun withAnimationOffTheFigureIsPlainText() {
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false) {
                MaterialTheme { RollingFigure(text = "42.50 EUR", style = LedgerType.figure, rollInFrom = "00.00 EUR") }
            }
        }
        compose.onNodeWithText("42.50 EUR").assertIsDisplayed()
    }

    @Test fun anEmptyStateWithADrawingStillSaysWhatToDo() {
        compose.setContent {
            MaterialTheme {
                EmptyState(title = "No documents yet", detail = "Photograph a receipt.", art = EmptyArt.RECEIPT)
            }
        }
        compose.onNodeWithText("No documents yet").assertIsDisplayed()
        compose.onNodeWithText("Photograph a receipt.").assertIsDisplayed()
    }
}
