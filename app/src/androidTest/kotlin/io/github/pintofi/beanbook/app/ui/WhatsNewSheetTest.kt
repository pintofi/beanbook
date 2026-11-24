package io.github.pintofi.beanbook.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.app.about.AboutSection
import io.github.pintofi.beanbook.app.about.WhatsNewHost
import io.github.pintofi.beanbook.app.about.WhatsNewSheet
import io.github.pintofi.beanbook.app.about.loadWhatsNew
import io.github.pintofi.beanbook.core.data.WhatsNewGroup
import io.github.pintofi.beanbook.core.data.WhatsNewRelease
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The sheet that lists what changed in each version. */
@RunWith(AndroidJUnit4::class)
class WhatsNewSheetTest {

    @get:Rule val compose = createComposeRule()

    private val releases = listOf(
        WhatsNewRelease("1.0.6", "2026-11-24", listOf(WhatsNewGroup("Added", listOf("A **bold** thing and `code`.")))),
        WhatsNewRelease("1.0.5", "2026-11-16", listOf(WhatsNewGroup("Fixed", listOf("An older fix.")))),
    )

    @Test fun theNewestReleaseIsOpenAndOlderOnesAreFoldedUntilTapped() {
        compose.setContent { MaterialTheme { WhatsNewSheet(onDismiss = {}, releases = releases) } }

        compose.onNodeWithText("What's new").assertIsDisplayed()
        compose.onNodeWithText("A bold thing and code.").assertIsDisplayed()
        compose.onNodeWithText("An older fix.").assertDoesNotExistOrHidden()

        compose.onNode(hasText("1.0.5", substring = true)).performScrollTo().performClick()
        compose.onNodeWithText("An older fix.").assertIsDisplayed()
    }

    @Test fun closeDismissesTheSheet() {
        var closed = false
        compose.setContent { MaterialTheme { WhatsNewSheet(onDismiss = { closed = true }, releases = releases) } }

        compose.onNodeWithText("Close").performClick()

        assertTrue(closed)
    }

    @Test fun anEmptyListSaysSoInsteadOfShowingNothing() {
        compose.setContent { MaterialTheme { WhatsNewSheet(onDismiss = {}, releases = emptyList()) } }
        compose.onNodeWithText("The list of changes could not be read.").assertIsDisplayed()
    }

    @Test fun theBundledChangelogIsReadUpToTheInstalledVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bundled = loadWhatsNew(context)

        assertTrue("no releases were read from the assets", bundled.isNotEmpty())
        assertEquals(io.github.pintofi.beanbook.app.BuildConfig.VERSION_NAME, bundled.first().version)
        assertTrue(bundled.flatMap { it.groups }.flatMap { it.entries }.none { it.contains("[#") })
    }

    @Test fun theHostOpensOnceAfterAnUpdateAndStaysClosedAfterClose() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as io.github.pintofi.beanbook.app.BeancountApplication
        java.io.File(context.filesDir, "state/whats-new").delete()

        compose.setContent {
            MaterialTheme { androidx.compose.foundation.layout.Column { WhatsNewHost(freshInstall = false) } }
        }
        compose.onNodeWithText("Close").assertIsDisplayed()
        compose.onNodeWithText("Close").performClick()
        compose.waitForIdle()

        assertEquals(io.github.pintofi.beanbook.app.BuildConfig.VERSION_NAME, app.whatsNewRecord.seen())
        val current = io.github.pintofi.beanbook.app.BuildConfig.VERSION_NAME
        assertTrue(!app.whatsNewRecord.isDue(current, freshInstall = false))
    }

    @Test fun aFreshInstallShowsNothingAndRecordsTheVersion() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = context.applicationContext as io.github.pintofi.beanbook.app.BeancountApplication
        java.io.File(context.filesDir, "state/whats-new").delete()

        compose.setContent {
            MaterialTheme { androidx.compose.foundation.layout.Column { WhatsNewHost(freshInstall = true) } }
        }

        compose.onNodeWithText("Close").assertDoesNotExist()
        assertEquals(io.github.pintofi.beanbook.app.BuildConfig.VERSION_NAME, app.whatsNewRecord.seen())
    }

    @Test fun aboutOpensTheSheetFromItsWhatsNewRow() {
        compose.setContent { MaterialTheme { AboutSection(onOpenLicences = {}) } }

        compose.onNodeWithText("What's new").performScrollTo().performClick()

        compose.onNodeWithText("Close").assertIsDisplayed()
    }
}

private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertDoesNotExistOrHidden() {
    val exists = runCatching { assertIsDisplayed() }.isSuccess
    assertTrue("expected the folded entry not to be on screen", !exists)
}
