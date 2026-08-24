package dev.beancount.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import dev.beancount.core.entitlement.Entitlement
import dev.beancount.core.entitlement.Grant
import dev.beancount.core.entitlement.KeyCheck
import dev.beancount.core.entitlement.KeyVerifier
import dev.beancount.core.entitlement.Tier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The Contribute screen: entering a key, being told why one was refused, and removing one. */
@RunWith(AndroidJUnit4::class)
class ContributeScreenTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val keyed = Entitlement(
        Tier.POWER,
        Grant.Key(Tier.POWER, "developer@example.com", "DEV-1"),
    )

    @Test fun aPastedKeyIsHandedToTheVerifierWhole() {
        var applied: String? = null
        show(Entitlement.FREE, onApplyKey = { applied = it })

        compose.onNodeWithContentDescription("Contribution key").performTextInput("BCA1.abc.def")
        compose.onNodeWithText("Apply key").performScrollTo().performClick()

        assertEquals("BCA1.abc.def", applied)
    }

    @Test fun applyIsDisabledUntilSomethingIsTyped() {
        show(Entitlement.FREE)

        compose.onNodeWithText("Apply key").performScrollTo().assertIsNotEnabled()
    }

    @Test fun aRefusalSaysWhatIsWrongWithoutQuotingTheKeyBack() {
        val problem = (KeyVerifier().check("BCA1.not.akey") as KeyCheck.Rejected).problem
        show(Entitlement.FREE, message = problem.describe())

        compose.onNodeWithText(problem.describe(), substring = true).performScrollTo().assertExists()
        // The refusal explains the fault; it never prints the key back onto a screen.
        assertTrue(
            "a refusal must not quote the key: ${problem.describe()}",
            !problem.describe().contains("BCA1.not.akey"),
        )
    }

    @Test fun aHeldKeyIsIdentifiedByEmailAndTierButIsNotPrintedInFull() {
        show(keyed)

        // Two lines mention the tier — the status card and the key's own line — and both
        // should: what you have, and which key gave it to you.
        assertEquals(
            listOf("Power, unlocked by your key.", "Power · issued to developer@example.com · order DEV-1"),
            textContaining("Power"),
        )
    }

    @Test fun removingAKeyNamesItAndSaysNothingElseIsLost() {
        var removed = false
        show(keyed, onRemoveKey = { removed = true })

        compose.onNodeWithText("Remove this key").performScrollTo().performClick()
        compose.onNodeWithText("Remove this key?").assertExists()
        // The scope, and the reassurance that matters: the key is not consumed.
        compose.onNodeWithText("keeps working", substring = true).assertExists()
        compose.onNodeWithText("Your ledger is untouched", substring = true).assertExists()

        compose.onNodeWithText("Keep it").performClick()
        assertTrue("dismissing must not remove anything", !removed)

        compose.onNodeWithText("Remove this key").performScrollTo().performClick()
        compose.onNodeWithText("Remove").performClick()
        assertTrue(removed)
    }

    /**
     * The banner sits above the ledger on every screen, so it says the tier and nothing more.
     * The email a key was issued to is identifying information, and a line that is in every
     * screenshot someone shares is the wrong place for it (docs/distribution.md §4.5).
     */
    @Test fun theBannerShowsTheTierButNotTheEmail() {
        compose.setContent { MaterialTheme { dev.beancount.app.TrialBanner(keyed) } }
        compose.waitForIdle()

        compose.onNodeWithText("Power key", substring = true).assertExists()
        assertEquals(emptyList<String>(), textContaining("developer@example.com"))
        assertEquals(
            "an uninterpolated string template leaked into the UI",
            emptyList<String>(),
            textContaining("\${"),
        )
    }

    @Test fun theScreenNamesNoPaymentMethodAndOffersNoWayOut() {
        // Play's anti-steering rules, and our own promise: the app takes no money and points
        // at no payment page (docs/distribution.md §4.1). This is that, asserted.
        show(Entitlement.FREE)

        listOf("PayPal", "Payoneer", "Sponsor", "Buy", "Purchase", "http", "Subscribe")
            .forEach { forbidden ->
                assertNull(
                    "the Contribute screen must not mention \"$forbidden\"",
                    textContaining(forbidden).firstOrNull(),
                )
            }
    }

    /** Every visible line of text containing [text], for the "says nothing about payment" check. */
    private fun textContaining(text: String): List<String> = compose
        .onAllNodes(
            SemanticsMatcher("text contains $text") { node ->
                node.config.getOrNull(SemanticsProperties.Text)
                    ?.any { line -> line.text.contains(text, ignoreCase = true) } == true
            },
        )
        .fetchSemanticsNodes()
        .map { node ->
            node.config.getOrNull(SemanticsProperties.Text)
                .orEmpty()
                .joinToString(" ") { line -> line.text }
        }

    private fun show(
        entitlement: Entitlement,
        onApplyKey: (String) -> Unit = {},
        onRemoveKey: () -> Unit = {},
        message: String? = null,
    ) {
        compose.setContent {
            MaterialTheme {
                ContributeScreen(
                    entitlement = entitlement,
                    onApplyKey = onApplyKey,
                    onRemoveKey = onRemoveKey,
                    onClose = {},
                    message = message,
                )
            }
        }
        compose.waitForIdle()
    }
}
