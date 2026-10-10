package io.github.pintofi.beanbook.app.ui

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled
import io.github.pintofi.beanbook.app.ui.theme.ThemeChoice
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The Material 3 Expressive controls keep what the controls they replaced said to a screen
 * reader (docs/material-expressive.md).
 */
@RunWith(AndroidJUnit4::class)
class ExpressiveControlsTest {

    @get:Rule val compose = createComposeRule()

    private val options = listOf("Units", "At cost", "Market value")

    /**
     * A connected choice reads as radio buttons: each segment says what it does, says whether
     * it is the chosen one, and choosing another moves the selection.
     */
    @Test fun aConnectedChoiceReadsAsRadioButtonsWithTheirDescriptions() {
        var chosen by mutableStateOf("At cost")
        compose.setContent {
            BeancountTheme(choice = ThemeChoice.LIGHT, dynamicColour = false) {
                ConnectedChoiceRow(
                    options = options,
                    selected = chosen,
                    onSelect = { chosen = it },
                    label = { it },
                    describe = { "Show figures $it" },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        val radio = SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.RadioButton)
        compose.onNodeWithContentDescription("Show figures At cost").assert(radio).assertIsSelected()
        compose.onNodeWithContentDescription("Show figures Units").assert(radio).assertIsNotSelected()

        compose.onNodeWithContentDescription("Show figures Units").performClick()
        assertEquals("Units", chosen)
        compose.onNodeWithContentDescription("Show figures Units").assertIsSelected()
        compose.onNodeWithContentDescription("Show figures At cost").assertIsNotSelected()
    }

    /** Tapping the chosen segment again keeps it chosen: one option is always on. */
    @Test fun theChosenSegmentStaysChosenWhenTappedAgain() {
        var chosen by mutableStateOf("Market value")
        compose.setContent {
            MaterialTheme {
                ConnectedChoiceRow(
                    options = options,
                    selected = chosen,
                    onSelect = { chosen = it },
                    label = { it },
                    describe = { "Show balances $it" },
                )
            }
        }
        compose.onNodeWithContentDescription("Show balances Market value").performClick()
        assertEquals("Market value", chosen)
    }

    /**
     * With animation off the loading indicator is a still shape, and is still announced as
     * work in progress rather than disappearing from the semantics tree.
     */
    @Test fun withMotionOffTheLoadingIndicatorIsStillButStillAnnounced() {
        compose.setContent {
            CompositionLocalProvider(LocalMotionEnabled provides false) {
                MaterialTheme { LedgerLoadingIndicator(modifier = Modifier) }
            }
        }
        compose.mainClock.autoAdvance = false
        compose.mainClock.advanceTimeByFrame()
        compose.onNode(
            SemanticsMatcher.expectValue(
                SemanticsProperties.ProgressBarRangeInfo,
                ProgressBarRangeInfo.Indeterminate,
            ),
        ).assertExists()
    }
}
