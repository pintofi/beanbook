package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import io.github.pintofi.beanbook.core.domain.TemplatePosting
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Templates, driven through the real form.
 *
 * The property that matters is not that a chip fills some fields — it is that a template
 * goes through the same preview and validation as anything typed by hand, and that it never
 * carries a date of its own into the ledger.
 */
@RunWith(AndroidJUnit4::class)
class TemplateEntryTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val vocabulary = LedgerVocabulary(
        accounts = listOf("Expenses:Rent", "Assets:Bank:Giro"),
        payees = listOf("Landlord"),
        narrations = listOf("Monthly rent"),
        currencies = listOf("EUR"),
        tags = emptyList(),
    )

    private val rent = EntryTemplate(
        name = "Rent",
        currency = "EUR",
        payee = "Landlord",
        narration = "Monthly rent",
        postings = listOf(
            TemplatePosting("Expenses:Rent", BigDecimal("750.00")),
            TemplatePosting("Assets:Bank:Giro"),
        ),
    )

    private val today = LocalDate.parse("2026-09-01")

    @Test fun applyingATemplateFillsTheFormAndPreviewsTheExactText() {
        var saved: TransactionDraft? = null
        show(templates = listOf(rent), onSave = { saved = it })

        compose.onNodeWithContentDescription("Start from the template Rent").performClick()

        // The preview is the contract: what it shows is what gets written.
        preview()
            .assertTextContains("2026-09-01 * \"Landlord\" \"Monthly rent\"", substring = true)
        preview().assertTextContains("750.00 EUR", substring = true)

        // On the sticky save bar: always on screen, and outside the scroll.
        compose.onNodeWithText("Save entry").assertIsEnabled()
        compose.onNodeWithText("Save entry").performClick()

        val draft = requireNotNull(saved) { "the form did not save" }
        assertEquals("2026-09-01", draft.date)
        assertEquals(2, draft.postings.size)
        assertNull("the elided posting must stay elided", draft.postings.last().amount)
    }

    @Test fun aTemplateIsAppliedToTheDateOnTheFormRatherThanCarryingItsOwn() {
        show(templates = listOf(rent))

        compose.onNodeWithText("2026-09-01").performTextClearance()
        compose.onNodeWithText("Date").performTextInput("2026-12-24")
        compose.onNodeWithContentDescription("Start from the template Rent").performClick()

        preview().assertTextContains("2026-12-24 * \"Landlord\" \"Monthly rent\"", substring = true)
    }

    @Test fun savingATemplateCapturesTheFormWithoutItsDate() {
        var captured: EntryTemplate? = null
        show(onSaveTemplate = { captured = it })

        // The form no longer preselects accounts, so they are typed, the way someone
        // building a template would pick them.
        compose.onNodeWithText("Payee").performTextInput("Landlord")
        compose.onNodeWithContentDescription("Account for posting 1").performTextInput("Expenses:Rent")
        compose.onNodeWithContentDescription("Account for posting 2").performTextInput("Assets:Bank:Giro")
        // The first posting's amount. Every posting's field is labelled "Amount" now — the
        // second used to say "Amount (optional)", which made an exact match unique by
        // accident, until that qualifier moved to supporting text because it was long enough
        // to wrap inside the field.
        compose.onAllNodesWithText("Amount", substring = false)[0].performTextInput("750.00")

        compose.onNodeWithText("Save as template").performScrollTo().performClick()
        compose.onNodeWithText("Template name").performTextInput("Rent")
        compose.onNodeWithText("Save template").performClick()

        val template = requireNotNull(captured) { "no template was saved" }
        assertEquals("Rent", template.name)
        assertEquals("Landlord", template.payee)
        assertEquals("EUR", template.currency)
        assertEquals(
            listOf("Expenses:Rent", "Assets:Bank:Giro"),
            template.postings.map { it.account },
        )
        assertEquals(BigDecimal("750.00"), template.postings.first().amount)
        assertNull(template.postings.last().amount)
    }

    @Test fun aTemplateThatCouldNeverProduceAValidEntryCannotBeSaved() {
        var captured: EntryTemplate? = null
        show(onSaveTemplate = { captured = it })

        // One posting only: a transaction needs two, so this template would be a trap. The
        // refusal happens now, with a reason, not on the day it is applied.
        compose.onNodeWithContentDescription("Account for posting 1").performTextInput("Expenses:Rent")
        compose.onNodeWithText("Save as template").performScrollTo().performClick()
        compose.onNodeWithText("Template name").performTextInput("Broken")

        // The same wording appears on the form behind the dialog, so both are expected.
        // Case-blind: the save bar sets the same check as a sentence.
        compose.onAllNodesWithText("a transaction needs at least two postings", substring = true, ignoreCase = true)
            .assertCountEquals(2)
        compose.onNodeWithText("Save template").assertIsNotEnabled()
        assertNull("an unusable template must not be saved", captured)
    }

    @Test fun deletingATemplateNamesTheOneItWillRemove() {
        var deleted: String? = null
        show(templates = listOf(rent), onDeleteTemplate = { deleted = it })

        compose.onNodeWithText("Save as template").performScrollTo().performClick()
        // The control names its scope, so nobody deletes "some template" by accident.
        compose.onNodeWithContentDescription("Delete the template Rent").performClick()

        assertEquals("Rent", deleted)
    }

    /** The generated-text preview, addressed by its label so nothing else can match it. */
    private fun preview() = compose
        .onNodeWithContentDescription("Preview of the entry to be saved")
        .performScrollTo()

    private fun show(
        templates: List<EntryTemplate> = emptyList(),
        onSave: (TransactionDraft) -> Unit = {},
        onSaveTemplate: (EntryTemplate) -> Unit = {},
        onDeleteTemplate: (String) -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                QuickAddScreen(
                    vocabulary = vocabulary,
                    onSave = onSave,
                    today = today,
                    templates = templates,
                    onSaveTemplate = onSaveTemplate,
                    onDeleteTemplate = onDeleteTemplate,
                )
            }
        }
        compose.waitForIdle()
    }

}
