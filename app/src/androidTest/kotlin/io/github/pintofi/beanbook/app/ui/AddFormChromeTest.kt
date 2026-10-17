package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.LedgerTopBar
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The entry form is a task, and now says so.
 *
 * It is opened by the action button and is not a destination, but the bar of destinations
 * went on highlighting whichever one the user had come from — telling them they were on Home
 * while they stood in a form — and the only way out was the system back gesture. The form now
 * carries its own bar with a close control, and the destination bar deselects.
 */
@RunWith(AndroidJUnit4::class)
class AddFormChromeTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val vocabulary = LedgerVocabulary(
        accounts = listOf("Expenses:Food", "Assets:Cash"),
        payees = listOf("Grocer"),
        narrations = emptyList(),
        currencies = listOf("USD"),
        tags = emptyList(),
    )

    @Test fun theFormsBarNamesItAndOffersAWayOut() {
        var closed = false
        compose.setContent {
            MaterialTheme {
                LedgerTopBar(
                    projectName = "My Ledger",
                    health = "8 entries · no errors",
                    sectionTitle = null,
                    adding = true,
                    onBack = { closed = true },
                    onSwitchProject = {},
                )
            }
        }

        compose.onNodeWithText("New entry").assertIsDisplayed()
        // The project's own switcher belongs to the destinations, not to a task: the title
        // of a form is a name, not a control.
        compose.onAllNodesWithContentDescription(
            "Switch project. Currently open: My Ledger",
        ).assertCountEquals(0)
        compose.onNodeWithContentDescription("Close the entry form").performClick()
        assertTrue("closing the form did nothing", closed)
    }

    @Test fun outsideTheFormTheBarIsTheProjectsOwn() {
        compose.setContent {
            MaterialTheme {
                LedgerTopBar(
                    projectName = "My Ledger",
                    health = "8 entries · no errors",
                    sectionTitle = null,
                    adding = false,
                    onBack = {},
                    onSwitchProject = {},
                )
            }
        }
        compose.onNodeWithText("My Ledger").assertIsDisplayed()
        compose.onNodeWithContentDescription("Close the entry form").assertDoesNotExist()
    }

    /**
     * The name of the open ledger is the control that changes it.
     *
     * There used to be a "Switch" button in the right-hand corner of every screen saying
     * the same thing the title already said. Tapping the title does it now, so the corner
     * is free and the thing you press is the thing you are changing.
     */
    @Test fun tappingTheProjectsNameSwitchesProject() {
        var switched = false
        compose.setContent {
            MaterialTheme {
                LedgerTopBar(
                    projectName = "My Ledger",
                    health = "8 entries · no errors",
                    sectionTitle = null,
                    adding = false,
                    onBack = {},
                    onSwitchProject = { switched = true },
                )
            }
        }

        compose.onNodeWithText("Switch").assertDoesNotExist()
        compose.onNodeWithContentDescription("Switch project. Currently open: My Ledger")
            .performClick()

        assertTrue("tapping the ledger's name did not offer the other projects", switched)
    }

    /** Inside a section the title is a heading, not a way out of the project. */
    @Test fun aSectionsTitleIsNotASwitcher() {
        compose.setContent {
            MaterialTheme {
                LedgerTopBar(
                    projectName = "My Ledger",
                    health = "8 entries · no errors",
                    sectionTitle = "Backup",
                    adding = false,
                    onBack = {},
                    onSwitchProject = {},
                )
            }
        }

        compose.onNodeWithText("Backup").assertIsDisplayed()
        compose.onAllNodesWithContentDescription(
            "Switch project. Currently open: My Ledger",
        ).assertCountEquals(0)
    }

    /**
     * The form says whether it holds anything, so whoever closes it can ask first. Without
     * this the destination bar — now live while the form is open — is one tap from losing a
     * half-typed entry.
     */
    @Test fun theFormReportsWhatWouldBeLost() {
        var dirty: Boolean? = null
        compose.setContent {
            MaterialTheme {
                QuickAddScreen(
                    vocabulary = vocabulary,
                    onSave = {},
                    today = LocalDate.parse("2026-09-09"),
                    onDirtyChange = { dirty = it },
                )
            }
        }
        compose.waitForIdle()
        assertEquals("an untouched form has nothing to lose", false, dirty)

        compose.onNodeWithContentDescription("Payee").performTextInput("Grocer")
        compose.waitForIdle()
        assertEquals("a typed payee was not reported", true, dirty)
    }

    @Test fun aDateTheFormFilledInIsNotSomethingToLose() {
        var dirty: Boolean? = null
        compose.setContent {
            MaterialTheme {
                QuickAddScreen(
                    vocabulary = vocabulary,
                    onSave = {},
                    today = LocalDate.parse("2026-09-09"),
                    onDirtyChange = { dirty = it },
                )
            }
        }
        compose.waitForIdle()
        // The date and the pre-selected accounts are the form's own doing. Asking "discard?"
        // over them would train people to dismiss the question that matters.
        assertFalse("the form's own defaults counted as typed input", dirty == true)
    }
}
