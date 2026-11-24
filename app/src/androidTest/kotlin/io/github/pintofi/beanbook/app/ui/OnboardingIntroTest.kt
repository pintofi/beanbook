package io.github.pintofi.beanbook.app.ui

import android.content.ContentValues
import android.provider.MediaStore
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.about.AboutSection
import io.github.pintofi.beanbook.app.onboarding.NewLedgerSetup
import io.github.pintofi.beanbook.app.ui.onboarding.OnboardingFlow
import io.github.pintofi.beanbook.app.ui.onboarding.SetupActions
import io.github.pintofi.beanbook.app.ui.theme.ThemeChoice
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The two pages on Beancount and Fava that sit between the welcome and the ways to add a ledger. */
@RunWith(AndroidJUnit4::class)
class OnboardingIntroTest {

    @get:Rule val compose = createComposeRule()

    private val actions = SetupActions(
        onCreate = {}, onCreateWithDefaults = {}, onOpenFolder = {}, onOpenArchive = {},
        onPickMirrorFolder = {}, onAskNotifications = {}, onThemeChoice = {}, onFinish = { _, _ -> },
    )

    private fun newSetup() = NewLedgerSetup(File.createTempFile("setup", ".json").also { it.delete() })

    private fun flow(setup: NewLedgerSetup) {
        compose.setContent {
            MaterialTheme { OnboardingFlow(setup = setup, actions = actions, themeChoice = ThemeChoice.SYSTEM) }
        }
    }

    private fun picture(name: String) {
        if (InstrumentationRegistry.getArguments().getString("introPictures") != "true") return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "$name.png")
            put(MediaStore.Downloads.MIME_TYPE, "image/png")
            put(MediaStore.Downloads.RELATIVE_PATH, "Download/beanbook-intro-pictures")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return
        val format = android.graphics.Bitmap.CompressFormat.PNG
        context.contentResolver.openOutputStream(uri)?.use { bitmap.compress(format, 100, it) }
    }

    @Test fun getStartedLeadsThroughBothPagesToTheWaysIn() {
        flow(newSetup())
        picture("intro-welcome")
        compose.onNodeWithContentDescription("Get started", substring = true).performClick()
        compose.onNodeWithText("Free, open-source accounting software", substring = true).assertIsDisplayed()
        picture("intro-beancount")
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithText("The web interface made for Beancount", substring = true).assertIsDisplayed()
        picture("intro-fava")
        compose.onNodeWithContentDescription("Set up a ledger").performClick()
        compose.onNodeWithText("Select your Beancount file").performScrollTo().assertIsDisplayed()
    }

    @Test fun aPersonWhoKeepsALedgerIsOneTapFromTheWaysIn() {
        flow(newSetup())
        compose.onNodeWithContentDescription("I already keep a Beancount ledger").performScrollTo().performClick()
        compose.onNodeWithText("Select your Beancount file").performScrollTo().assertIsDisplayed()
    }

    @Test fun skipToSetupWorksFromTheBeancountPage() {
        flow(newSetup())
        compose.onNodeWithContentDescription("Get started", substring = true).performClick()
        compose.onNodeWithContentDescription("Skip to setup").performClick()
        compose.onNodeWithText("Select your Beancount file").performScrollTo().assertIsDisplayed()
    }

    @Test fun skipToSetupWorksFromTheFavaPage() {
        flow(newSetup())
        compose.onNodeWithContentDescription("Get started", substring = true).performClick()
        compose.onNodeWithText("Free, open-source accounting software", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Skip to setup").performClick()
        compose.onNodeWithText("Select your Beancount file").performScrollTo().assertIsDisplayed()
    }

    @Test fun backFromFavaGoesToBeancountAndTheIntroIsNotASetupInProgress() {
        val setup = newSetup()
        flow(setup)
        compose.onNodeWithContentDescription("Get started", substring = true).performClick()
        compose.onNodeWithText("Free, open-source accounting software", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Free, open-source accounting software", substring = true).assertIsDisplayed()
        assertFalse("reading the pages is not a setup to pick up", setup.resumable)
    }

    @Test fun thePageIsKeptAcrossRecreation() {
        val setup = newSetup()
        val tester = StateRestorationTester(compose)
        tester.setContent {
            MaterialTheme { OnboardingFlow(setup = setup, actions = actions, themeChoice = ThemeChoice.SYSTEM) }
        }
        compose.onNodeWithContentDescription("Get started", substring = true).performClick()
        compose.onNodeWithText("Free, open-source accounting software", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Next").performClick()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("The web interface made for Beancount", substring = true).assertIsDisplayed()
    }

    @Test fun aboutReopensThePagesAndBackReturns() {
        compose.setContent { MaterialTheme { AboutSection(onOpenLicences = {}) } }
        compose.onNodeWithText("Beancount and Fava").performScrollTo().performClick()
        compose.onNodeWithText("Free, open-source accounting software", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Next").performClick()
        compose.onNodeWithContentDescription("Done").performClick()
        compose.onNodeWithText("Beancount and Fava").assertIsDisplayed()
    }
}
