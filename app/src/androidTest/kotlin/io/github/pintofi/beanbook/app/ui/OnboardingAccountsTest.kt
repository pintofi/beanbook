package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.onboarding.AccountChoice
import io.github.pintofi.beanbook.app.onboarding.SetupDraft
import io.github.pintofi.beanbook.app.ui.onboarding.AccountsStep
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The accounts page of the setup: what a person added can be taken out again, and the page says how names start. */
@RunWith(AndroidJUnit4::class)
class OnboardingAccountsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun anAccountThePersonAddedCanBeRemovedAndTheStarterOnesCannot() {
        var draft by mutableStateOf(
            SetupDraft(
                accounts = SetupDraft().accounts +
                    AccountChoice("Assets:Savings", on = true, user = true),
                opening = mapOf("Assets:Savings" to "100"),
            ),
        )
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column(Modifier.verticalScroll(rememberScrollState())) {
                    AccountsStep(draft) { change -> draft = change(draft) }
                }
            }
        }

        compose.onNodeWithText("A custom account always starts with its type", substring = true)
            .performScrollTo().assertExists()
        // The starter accounts have no bin; the one that was added does.
        compose.onNodeWithContentDescription("Remove Assets:Cash").assertDoesNotExist()

        compose.onNodeWithContentDescription("Remove Assets:Savings").performScrollTo().performClick()
        compose.waitForIdle()

        assertTrue(draft.accounts.none { it.name == "Assets:Savings" })
        assertEquals(emptyMap<String, String>(), draft.opening)
        assertTrue("the starter accounts stay", draft.accounts.any { it.name.startsWith("Assets:") })
    }
}
