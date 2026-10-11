package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import io.github.pintofi.beanbook.core.domain.PostingDraft
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import java.io.File
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The entry form's layout and its edit mode (B79). */
@RunWith(AndroidJUnit4::class)
class EntryFormLayoutTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val vocabulary = LedgerVocabulary(
        accounts = listOf("Expenses:Food:Groceries", "Assets:Current:Cash"),
        payees = listOf("Imtiaz"),
        narrations = emptyList(),
        currencies = listOf("PKR"),
        tags = emptyList(),
    )
    private val today = LocalDate.parse("2026-09-13")

    private val entry = TransactionDraft(
        date = "2026-09-01",
        payee = "Imtiaz",
        narration = "Groceries",
        tags = listOf("food"),
        metadata = mapOf("receipt" to "yes"),
        postings = listOf(
            PostingDraft("Expenses:Food:Groceries", Amount(BigDecimal("6200.00"), "PKR")),
            PostingDraft("Assets:Current:Cash"),
        ),
    )

    /** Tag, link, detail and scan belong to the entry, so they sit under its description. */
    @Test fun theEntrysOwnExtrasComeBeforeThePostings() {
        compose.setContent {
            MaterialTheme { QuickAddScreen(vocabulary, onSave = {}, today = today) }
        }

        val description = compose.onNodeWithContentDescription("Description").getUnclippedBoundsInRoot()
        val tag = compose.onNodeWithContentDescription("Add a tag to this transaction").getUnclippedBoundsInRoot()
        val postings = compose.onNodeWithText("Postings").getUnclippedBoundsInRoot()

        assertTrue("the extras should follow the description", tag.top >= description.bottom)
        assertTrue("the extras should come before the postings", tag.bottom <= postings.top)
    }

    @Test fun anEntryBeingEditedIsTheSameFormWithEverythingInIt() {
        var saved: TransactionDraft? = null
        var dirty: Boolean? = null
        compose.setContent {
            MaterialTheme {
                QuickAddScreen(
                    vocabulary = vocabulary,
                    onSave = { saved = it },
                    today = today,
                    prefill = entry,
                    onDirtyChange = { dirty = it },
                    editing = EntryEditing(entry, reformats = false, onEditAsText = {}),
                )
            }
        }
        compose.waitForIdle()

        // What the Add form has, filled in: the detail, the tag, the elided posting.
        compose.onNode(hasText("receipt")).assertExists()
        compose.onNode(hasText("food")).assertExists()
        assertEquals("opening an entry is not a change to it", false, dirty)

        // On the sticky save bar since B98: always on screen, and outside the scroll.
        compose.onNodeWithText("Save changes").performClick()
        assertEquals("an untouched entry is written back as it was", entry.render(), saved?.render())
    }

    @Test fun changingAnEntryIsSomethingToLose() {
        var dirty: Boolean? = null
        compose.setContent {
            MaterialTheme {
                QuickAddScreen(
                    vocabulary = vocabulary,
                    onSave = {},
                    today = today,
                    prefill = entry,
                    onDirtyChange = { dirty = it },
                    editing = EntryEditing(entry, reformats = false, onEditAsText = {}),
                )
            }
        }
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Description").performTextInput(" and milk")
        compose.waitForIdle()
        assertEquals(true, dirty)
    }

    @Test fun theTextEditorIsStillOneTapAway() {
        var asText = false
        compose.setContent {
            MaterialTheme {
                QuickAddScreen(
                    vocabulary = vocabulary,
                    onSave = {},
                    today = today,
                    prefill = entry,
                    editing = EntryEditing(entry, reformats = true, onEditAsText = { asText = true }),
                )
            }
        }
        compose.onNodeWithText("This entry was written by hand", substring = true).assertIsDisplayed()
        compose.onNodeWithContentDescription("Edit this entry as text instead").performScrollTo().performClick()
        assertTrue(asText)
    }

    /** Scanned pages show up in the preview as the link they will be, before saving. */
    @Test fun thePreviewNamesTheDocumentThePagesWillBecome() {
        val pages = CapturedPages().add("page-1.jpg")
        val capture = DocumentCaptureHandle(
            pages = pages,
            captureFile = { File("unused") },
            onCapture = {},
            onRotate = {},
            onMove = { _, _ -> },
            onRemovePage = {},
            onFile = { _, _, _ -> },
            plannedPaths = { account, _ ->
                listOf(
                    "documents/${account.replace(':', '/')}/2026-09-13.0123456789ab.pdf",
                    "documents/${account.replace(':', '/')}/2026-09-13.ba9876543210.jpg",
                )
            },
            attachedFiles = kotlinx.coroutines.flow.MutableStateFlow(listOf("invoice.jpg")),
        )
        compose.setContent {
            MaterialTheme {
                QuickAddScreen(
                    vocabulary = vocabulary,
                    onSave = {},
                    today = today,
                    prefill = entry.copy(metadata = emptyMap()),
                    capture = capture,
                )
            }
        }

        compose.waitUntil(5_000) {
            compose.onAllNodes(hasText("document: \"2026-09-13.0123456789ab.pdf\"", substring = true))
                .fetchSemanticsNodes().isNotEmpty()
        }
        // No separate `document` directive in the preview since B92: the attachment belongs
        // to this entry, by its `document:` line (checked above). This assertion still looked
        // for the directive and had been failing since then; found running the suite in B98.
        // An attached file is a second link, under its own key, and is listed in the form (B80).
        compose.onNode(hasText("document-2: \"2026-09-13.ba9876543210.jpg\"", substring = true)).assertExists()
        compose.onNodeWithContentDescription("Remove the attached file invoice.jpg").assertExists()
        // On the sticky save bar since B98: always on screen, and outside the scroll.
        compose.onNodeWithText("Save entry with 1 page and 1 file").assertExists()
    }

    @Test fun aFileCanBeAttachedFromTheEntrysOwnActions() {
        compose.setContent {
            MaterialTheme {
                QuickAddScreen(
                    vocabulary,
                    onSave = {},
                    today = today,
                    capture = DocumentCaptureHandle(
                        pages = CapturedPages(),
                        captureFile = { File("unused") },
                        onCapture = {},
                        onRotate = {},
                        onMove = { _, _ -> },
                        onRemovePage = {},
                        onFile = { _, _, _ -> },
                    ),
                )
            }
        }
        compose.onNodeWithContentDescription("Attach a file to this transaction").assertIsDisplayed()
    }
}
