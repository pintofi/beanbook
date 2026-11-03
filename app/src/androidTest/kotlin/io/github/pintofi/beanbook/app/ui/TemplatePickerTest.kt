package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.core.domain.TemplatePosting
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A few templates are buttons on the form; many are one button that opens an alphabetical list, with a
 * search box once the list is long.
 */
@RunWith(AndroidJUnit4::class)
class TemplatePickerTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun template(name: String, payee: String? = null) = EntryTemplate(
        name = name,
        currency = "EUR",
        payee = payee,
        postings = listOf(TemplatePosting("Expenses:Food"), TemplatePosting("Assets:Cash")),
    )

    private var applied: EntryTemplate? = null
    private var deleted: String? = null

    private fun show(names: List<String>, fontScale: Float = 1f, payee: String? = null) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                MaterialTheme {
                    Box(Modifier.width(360.dp)) {
                        TemplatePicker(
                            templates = names.map { template(it, payee) },
                            onApply = { applied = it },
                            onDelete = { deleted = it },
                        )
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    private fun top(text: String) = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top

    @Test fun aFewTemplatesAreButtonsOnTheForm() {
        show(listOf("Rent", "Groceries", "Salary"))
        listOf("Rent", "Groceries", "Salary").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("Templates (3)").assertDoesNotExist()
    }

    @Test fun aButtonTapAppliesThatTemplate() {
        show(listOf("Rent", "Groceries"))
        compose.onNodeWithText("Rent").performClick()
        assertEquals("Rent", applied?.name)
    }

    @Test fun manyTemplatesAreOneButtonThatOpensAList() {
        show(listOf("Rent", "Groceries", "Salary", "Fuel", "Phone"))
        compose.onNodeWithText("Templates (5)").assertIsDisplayed()
        compose.onNodeWithText("Rent").assertDoesNotExist()
        compose.onNodeWithText("Templates (5)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Rent").assertIsDisplayed()
    }

    @Test fun theListIsAlphabeticalWhateverTheCase() {
        show(listOf("zeta", "Alpha", "mid", "Beta", "gamma"))
        compose.onNodeWithText("Templates (5)").performClick()
        compose.waitForIdle()
        val order = listOf("Alpha", "Beta", "gamma", "mid", "zeta")
        order.zipWithNext().forEach { (a, b) -> assertTrue("$a should be above $b", top(a) < top(b)) }
    }

    @Test fun choosingAFromTheListAppliesItAndClosesTheList() {
        show(listOf("Rent", "Groceries", "Salary", "Fuel", "Phone"))
        compose.onNodeWithText("Templates (5)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText("Fuel").performClick()
        compose.waitForIdle()
        assertEquals("Fuel", applied?.name)
        compose.onNodeWithText("Groceries").assertDoesNotExist()
    }

    @Test fun eachRowSaysWhoIsPaidAndTheFirstAccountSoSimilarNamesCanBeToldApart() {
        show(listOf("Rent", "Groceries", "Salary", "Fuel"), payee = "Landlord")
        compose.onNodeWithText("Templates (4)").performClick()
        compose.waitForIdle()
        compose.onAllNodesWithText("Landlord · Expenses:Food").assertCountEquals(4)
    }

    @Test fun aShortListHasNoSearchBoxAndALongOneDoes() {
        show(listOf("A1", "B2", "C3", "D4", "E5"))
        compose.onNodeWithText("Templates (5)").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Search the saved templates").assertDoesNotExist()
    }

    @Test fun aLongListCanBeSearched() {
        show((1..10).map { "Template $it" } + "Rent")
        compose.onNodeWithText("Templates (11)").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Search the saved templates").performTextInput("rent")
        compose.waitForIdle()
        compose.onNodeWithText("Rent").assertIsDisplayed()
        compose.onNodeWithText("Template 3").assertDoesNotExist()
    }

    @Test fun aTemplateCanBeDeletedAfterConfirming() {
        show(listOf("Rent", "Groceries", "Salary", "Fuel"))
        compose.onNodeWithText("Templates (4)").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Delete the template Fuel").performClick()
        compose.onNodeWithText("Delete the template Fuel?").assertIsDisplayed()
        assertNull("nothing is deleted before it is confirmed", deleted)
        compose.onNodeWithText("Delete").performClick()
        assertEquals("Fuel", deleted)
    }

    @Test fun keepingATemplateInTheDialogDeletesNothing() {
        show(listOf("Rent", "Groceries", "Salary", "Fuel"))
        compose.onNodeWithText("Templates (4)").performClick()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("Delete the template Fuel").performClick()
        compose.onNodeWithText("Keep it").performClick()
        assertNull(deleted)
    }

    private val longName = "Monthly rent for the flat paid to the landlord"

    @Test fun aLongNameStillShowsAtDoubleTextOnANarrowPhoneAsAButton() {
        show(listOf(longName, "B"), fontScale = 2f)
        compose.onNodeWithText(longName).assertIsDisplayed()
    }

    @Test fun aLongNameStillShowsAtDoubleTextInTheList() {
        show(listOf(longName, "B", "C", "D"), fontScale = 2f)
        compose.onNodeWithText("Templates (4)").performClick()
        compose.waitForIdle()
        compose.onNodeWithText(longName).assertIsDisplayed()
    }
}
