package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The standard number, amount and password fields behave the same wherever they are used. */
@RunWith(AndroidJUnit4::class)
class StandardFieldsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun aNumberBoxCanBeClearedAndRetypedAndOnlyTakesNumbersInItsRange() {
        var places by mutableStateOf(2)
        compose.setContent { MaterialTheme { NumberField(places, { places = it }, 0..8, "Decimal places") } }
        val box = compose.onNode(hasSetTextAction())

        box.performTextClearance()
        compose.waitForIdle()
        // Empty while it is retyped: the stored number is not thrown away.
        assertEquals(2, places)

        box.performTextInput("5")
        compose.waitForIdle()
        assertEquals(5, places)

        // Nine is out of range and a second digit is longer than the range allows; neither changes it.
        box.performTextClearance()
        box.performTextInput("9")
        box.performTextInput("1")
        compose.waitForIdle()
        assertEquals(5, places)

        box.performTextClearance()
        box.performTextInput("0")
        compose.waitForIdle()
        assertEquals(0, places)
    }

    @Test fun aNumberBoxFollowsTheNumberWhenSomethingElseChangesIt() {
        var places by mutableStateOf(2)
        compose.setContent { MaterialTheme { NumberField(places, { places = it }, 0..8, "Decimal places") } }
        places = 7
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).assertTextEqualsText("7")
    }

    @Test fun theSignButtonKeepsTheCursorOnItsCharacter() {
        var amount by mutableStateOf("1230")
        compose.setContent { MaterialTheme { AmountField(amount, { amount = it }, label = "Amount") } }
        val box = compose.onNode(hasSetTextAction())
        box.performTextInputSelection(TextRange(4))
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Switch the Amount between positive and negative").performClick()
        compose.waitForIdle()

        assertEquals("-1230", amount)
        val selection = box.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange]
        assertEquals(5, selection.start)

        compose.onNodeWithContentDescription("Switch the Amount between positive and negative").performClick()
        compose.waitForIdle()
        assertEquals("1230", amount)
        assertEquals(4, box.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange].start)
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextEqualsText(expected: String) {
        val shown = fetchSemanticsNode().config[SemanticsProperties.EditableText].text
        assertEquals(expected, shown)
    }
}
