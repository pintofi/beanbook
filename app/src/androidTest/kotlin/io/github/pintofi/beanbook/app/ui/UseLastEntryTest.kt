package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.semantics.getOrNull
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.JournalPosting
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import io.github.pintofi.beanbook.core.domain.PostingDraft
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Use last" brings back the last entry for a payee. Without the entry's own text, the first
 * posting keeps its amount and commodity; the posting whose amount is left for Beancount to work out
 * keeps neither, so the form and the preview say the same thing. With the text, the form is filled
 * as the text writes it: every amount it writes, and none it leaves out.
 */
@RunWith(AndroidJUnit4::class)
class UseLastEntryTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val last = JournalEntry(
        date = "2026-08-01",
        flag = "*",
        payee = "Landlord",
        narration = "Monthly rent",
        tags = emptyList(),
        links = emptyList(),
        postings = listOf(
            JournalPosting("Expenses:Rent", Amount(BigDecimal("750.00"), "EUR")),
            // Beancount reports the posting it worked out with its amount; the form must not copy it.
            JournalPosting("Assets:Bank:Giro", Amount(BigDecimal("-750.00"), "EUR")),
        ),
        file = null,
        line = null,
    )

    private val vocabulary = LedgerVocabulary(
        accounts = listOf("Expenses:Rent", "Assets:Bank:Giro"),
        payees = listOf("Landlord"),
        narrations = listOf("Monthly rent"),
        currencies = listOf("EUR"),
        tags = emptyList(),
        recentEntries = listOf(last),
    )

    private fun reuseTheLastEntry() {
        compose.setContent {
            MaterialTheme { QuickAddScreen(vocabulary, onSave = {}, today = LocalDate.parse("2026-09-01")) }
        }
        compose.onNodeWithText("Payee").performTextInput("Landlord")
        compose.onNodeWithContentDescription("Reuse the last entry for Landlord").performScrollTo().performClick()
        compose.waitForIdle()
    }

    @Test fun theFirstPostingKeepsItsAmountAndCommodity() {
        reuseTheLastEntry()
        compose.onNodeWithContentDescription("Commodity for posting 1").performScrollTo().assert(hasText("EUR"))
    }

    @Test fun thePostingLeftForBeancountToWorkOutGetsNoCommodity() {
        reuseTheLastEntry()
        compose.onNodeWithContentDescription("Commodity for posting 2").performScrollTo()
            .assert(!hasText("EUR"))
    }

    @Test fun thePreviewShowsNoCurrencyOnThatPostingEither() {
        reuseTheLastEntry()
        val preview = compose.onNodeWithContentDescription("Preview of the entry to be saved").performScrollTo()
        preview.assertTextContains("Expenses:Rent", substring = true)
        preview.assertTextContains("750.00 EUR", substring = true)
        val text = preview.fetchSemanticsNode().config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty()
            .joinToString("") { it.text }
        val second = text.lines().firstOrNull { "Assets:Bank:Giro" in it }.orEmpty()
        assertFalse("the second posting should show no currency: \"$second\"", "EUR" in second)
    }

    private fun reuseAsWritten(written: TransactionDraft) {
        compose.setContent {
            MaterialTheme {
                QuickAddScreen(
                    vocabulary,
                    onSave = {},
                    today = LocalDate.parse("2026-09-01"),
                    entryAsWritten = { written },
                )
            }
        }
        compose.onNodeWithText("Payee").performTextInput("Landlord")
        compose.onNodeWithContentDescription("Reuse the last entry for Landlord").performScrollTo().performClick()
        compose.waitForIdle()
    }

    private fun previewText(): String = compose.onNodeWithContentDescription("Preview of the entry to be saved")
        .performScrollTo().fetchSemanticsNode().config
        .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text).orEmpty().joinToString("") { it.text }

    @Test fun everyAmountTheTextWritesIsKept() {
        reuseAsWritten(
            TransactionDraft(
                date = "2026-08-01", payee = "Landlord", narration = "Monthly rent",
                postings = listOf(
                    PostingDraft("Expenses:Rent", Amount(BigDecimal("750.00"), "EUR")),
                    PostingDraft("Assets:Bank:Giro", Amount(BigDecimal("-750.00"), "EUR")),
                ),
            ),
        )
        compose.onNodeWithContentDescription("Commodity for posting 2").performScrollTo().assert(hasText("EUR"))
        val second = previewText().lines().firstOrNull { "Assets:Bank:Giro" in it }.orEmpty()
        assertTrue("the second amount was written in the text: \"$second\"", "-750.00 EUR" in second)
    }

    @Test fun anAmountTheTextLeftOutStaysLeftOut() {
        reuseAsWritten(
            TransactionDraft(
                date = "2026-08-01", payee = "Landlord", narration = "Monthly rent",
                postings = listOf(
                    PostingDraft("Expenses:Rent", Amount(BigDecimal("750.00"), "EUR")),
                    PostingDraft("Assets:Bank:Giro", null),
                ),
            ),
        )
        val second = previewText().lines().firstOrNull { "Assets:Bank:Giro" in it }.orEmpty()
        assertFalse("the text left the second amount out: \"$second\"", "EUR" in second)
    }

    @Test fun theFlagAndTheTagsTheTextWritesComeToo() {
        reuseAsWritten(
            TransactionDraft(
                date = "2026-08-01", flag = "!", payee = "Landlord", narration = "Monthly rent", tags = listOf("rent"),
                postings = listOf(
                    PostingDraft("Expenses:Rent", Amount(BigDecimal("750.00"), "EUR")),
                    PostingDraft("Assets:Bank:Giro", null),
                ),
            ),
        )
        val text = previewText()
        assertTrue("the date stays today's and the flag is the text's: $text", text.startsWith("2026-09-01 !"))
        assertTrue("the tag was written in the text: $text", "#rent" in text)
    }
}
