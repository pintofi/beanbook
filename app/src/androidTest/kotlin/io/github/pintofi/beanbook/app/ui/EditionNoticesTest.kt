package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.about.EditionNotice
import io.github.pintofi.beanbook.app.about.OtherEdition
import io.github.pintofi.beanbook.app.about.SupportNotice
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** What the app says about the other edition and about donations. */
@RunWith(AndroidJUnit4::class)
class EditionNoticesTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun theOtherEditionIsDescribedWithWhatItAdds() {
        compose.setContent {
            MaterialTheme {
                EditionNotice(
                    other = OtherEdition(
                        name = "Expert",
                        summary = "A separate edition.",
                        differences = listOf("Native packages"),
                        url = "https://example.invalid/editions",
                    ),
                )
            }
        }
        compose.onNodeWithText("There is also an Expert edition", substring = true).assertIsDisplayed()
        compose.onNodeWithText("Native packages", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Read how the Expert edition differs, opens in your browser",
        ).assertIsDisplayed()
    }

    @Test fun noOtherEditionMeansNoNotice() {
        compose.setContent { MaterialTheme { EditionNotice(other = null) } }
        compose.onNodeWithText("There is also", substring = true).assertDoesNotExist()
    }

    @Test fun supportIsOfferedOnceAndEitherAnswerClosesIt() {
        var answered = false
        compose.setContent {
            MaterialTheme { SupportNotice(onAnswered = { answered = true }, url = "https://example.invalid/") }
        }
        compose.onNodeWithText("Beanbook is free").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close this and do not show it again").performClick()
        assertTrue(answered)
    }

    /** Without a support page -- Play, and every edition until the page exists -- nothing. */
    @Test fun noSupportPageMeansNoNotice() {
        compose.setContent { MaterialTheme { SupportNotice(onAnswered = {}, url = null) } }
        compose.onNodeWithText("Beanbook is free").assertDoesNotExist()
    }
}
