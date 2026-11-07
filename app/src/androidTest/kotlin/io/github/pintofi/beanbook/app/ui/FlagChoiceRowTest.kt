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
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.FlagChoice
import io.github.pintofi.beanbook.core.domain.FlagOption
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Cleared, Pending and Other are one choice; Other reveals a field for the flag itself. */
@RunWith(AndroidJUnit4::class)
class FlagChoiceRowTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private var choice by mutableStateOf(FlagChoice())

    private fun show(start: FlagChoice = FlagChoice()) {
        choice = start
        compose.setContent { MaterialTheme { FlagChoiceRow(choice = choice, onChoice = { choice = it }) } }
        compose.waitForIdle()
    }

    @Test fun clearedIsChosenToStartWithAndNoOtherFieldShows() {
        show()
        assertEquals("*", choice.flag)
        compose.onNodeWithText("Other flag").assertDoesNotExist()
    }

    @Test fun pendingIsOneTap() {
        show()
        compose.onNodeWithText("Pending !").performClick()
        assertEquals("!", choice.flag)
    }

    @Test fun otherRevealsAFieldAndWhatIsTypedIsTheFlag() {
        show()
        compose.onNodeWithText("Other").performClick()
        compose.onNodeWithText("Other flag").assertIsDisplayed()
        compose.onNodeWithContentDescription("Other flag: one capital letter, or one of & # ? %").performTextInput("P")
        assertEquals(FlagOption.OTHER, choice.option)
        assertEquals("P", choice.flag)
    }

    @Test fun aLowerCaseFlagSaysHowToFixIt() {
        show(FlagChoice(FlagOption.OTHER, "p"))
        compose.onNodeWithText("capital letters, so type \"P\"", substring = true).assertIsDisplayed()
    }

    @Test fun anEntryWithAnUnusualFlagOpensOnOtherShowingIt() {
        show(FlagChoice.of("T"))
        compose.onNodeWithText("Other T").assertIsDisplayed()
    }
}
