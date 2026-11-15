package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.undo.HeldOffer
import io.github.pintofi.beanbook.core.data.HeldWrite
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The question asked when a change adds errors: what it says, and which button does what. */
@RunWith(AndroidJUnit4::class)
class KeepWithErrorsDialogTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun held(vararg errors: String) =
        HeldWrite("p", "files/transactions.beancount", "sha", "x".toByteArray(), "quick-add", errors.toList())

    private fun show(offer: HeldOffer, answers: MutableList<String>) {
        compose.setContent {
            MaterialTheme {
                KeepWithErrorsDialog(
                    offer,
                    onKeep = { answers += "keep" },
                    onDiscard = { answers += "discard" },
                    onClose = { answers += "close" },
                )
            }
        }
        compose.waitForIdle()
    }

    @Test fun itSaysHowManyErrorsAndListsThemAndThatNothingIsSavedYet() {
        show(
            HeldOffer(held("a.beancount:3: no balance", "b.beancount:9: unknown account"), "Saving to a.beancount"),
            mutableListOf(),
        )

        compose.onNodeWithText("This change adds 2 errors to your ledger").assertIsDisplayed()
        compose.onNodeWithText("a.beancount:3: no balance").assertIsDisplayed()
        compose.onNodeWithText("Saving to a.beancount. Nothing has been saved yet: your ledger is as it was.")
            .assertIsDisplayed()
    }

    @Test fun oneErrorIsSaidInTheSingular() {
        show(HeldOffer(held("a:1: x"), "Saving to a"), mutableListOf())

        compose.onNodeWithText("This change adds 1 error to your ledger").assertIsDisplayed()
    }

    @Test fun moreThanFiveErrorsAreCutShortAndSaySoWithoutHidingTheCount() {
        show(HeldOffer(held(*Array(8) { "f:$it: e" }), "Saving"), mutableListOf())

        compose.onNodeWithText("This change adds 8 errors to your ledger").assertIsDisplayed()
        compose.onNodeWithText("and 3 more").assertIsDisplayed()
    }

    @Test fun dontSaveAndSaveAnywayAreSeparateButtons() {
        val answers = mutableListOf<String>()
        show(HeldOffer(held("a:1: x"), "Saving"), answers)

        compose.onNodeWithContentDescription("Save the change anyway, with the errors").performClick()
        compose.onNodeWithContentDescription("Don't save the change").performClick()

        assertEquals(listOf("keep", "discard"), answers)
    }

    @Test fun whileTheChangeIsBeingMadeTheButtonSaysSoAndCannotBePressedAgain() {
        show(HeldOffer(held("a:1: x"), "Saving", working = true), mutableListOf())

        compose.onNodeWithText("Saving…").assertIsNotEnabled()
    }

    @Test fun aChangeThatCouldNotBeMadeSaysSoAndOffersOnlyClose() {
        val answers = mutableListOf<String>()
        show(HeldOffer(held("a:1: x"), "Saving", problem = "The file changed since. Nothing was saved."), answers)

        compose.onNodeWithText("Nothing was saved").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()

        assertEquals(listOf("close"), answers)
        compose.onNodeWithText("Save anyway").assertDoesNotExist()
    }

    @Test fun atDoubleTextBothButtonsAndTheErrorsAreStillReachable() {
        compose.setContent {
            val base = androidx.compose.ui.platform.LocalDensity.current
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(base.density, 2f),
            ) {
                MaterialTheme {
                    KeepWithErrorsDialog(
                        HeldOffer(held(*Array(6) { "file.beancount:$it: something is wrong" }), "Saving to file"),
                        onKeep = {}, onDiscard = {}, onClose = {},
                    )
                }
            }
        }
        compose.waitForIdle()

        compose.onNodeWithContentDescription("Don't save the change").assertIsDisplayed()
        compose.onNodeWithContentDescription("Save the change anyway, with the errors").assertIsDisplayed()
        compose.onNodeWithText("This change adds 6 errors to your ledger").assertIsDisplayed()
    }
}
