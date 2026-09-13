package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.JournalPosting
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import java.math.BigDecimal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Typing less (B53): a calendar on every date, the ledger's own words under every field that
 * is not an amount, and a search over the journal.
 */
@RunWith(AndroidJUnit4::class)
class InputFieldsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun entry(
        date: String,
        payee: String,
        account: String,
        value: String,
        line: Int,
    ) = JournalEntry(
        date = date,
        flag = "*",
        payee = payee,
        narration = "$payee visit",
        tags = emptyList(),
        links = emptyList(),
        postings = listOf(
            JournalPosting(account, Amount(BigDecimal(value), "USD")),
            JournalPosting("Assets:Bank:Checking", Amount(BigDecimal("-$value"), "USD")),
        ),
        file = "main.beancount",
        line = line,
    )

    private val snapshot = LedgerSnapshot(
        title = "Test",
        operatingCurrencies = listOf("USD"),
        entryCount = 2,
        entryCounts = emptyMap(),
        accounts = listOf(
            AccountBalance("Expenses:Food:Coffee", listOf(Amount(BigDecimal("3.40"), "USD"))),
            AccountBalance("Assets:Bank:Checking", listOf(Amount(BigDecimal("-3.40"), "USD"))),
        ),
        journal = listOf(
            entry("2026-03-14", "Corner Coffee", "Expenses:Food:Coffee", "3.40", line = 12),
            entry("2026-02-02", "City Garage", "Expenses:Car:Repairs", "120.00", line = 20),
        ),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    @Test fun aDateFieldOffersACalendarAndWritesWhatWasPicked() {
        var value = "2026-03-14"
        compose.setContent {
            MaterialTheme { DateField(value = value, onValueChange = { value = it }) }
        }

        compose.onNodeWithContentDescription("Pick Date from a calendar").performClick()
        compose.onNodeWithText("Use this date").assertIsDisplayed()
        compose.onNodeWithText("Use this date").performClick()

        // The field keeps a real date. What matters is that the picker writes an ISO string
        // rather than a locale-formatted one, which the ledger could not parse.
        assert(Regex("\\d{4}-\\d{2}-\\d{2}").matches(value)) { "the picker wrote \"$value\"" }
    }

    @Test fun aSuggestingFieldNarrowsAsYouType() {
        var value = ""
        compose.setContent {
            MaterialTheme {
                SuggestingField(
                    value = value,
                    onValueChange = { value = it },
                    label = "Account",
                    suggestions = listOf("Expenses:Food:Coffee", "Assets:Bank:Checking"),
                )
            }
        }

        compose.onNodeWithContentDescription("Account").performTextInput("coff")
        compose.onNodeWithContentDescription("Use Expenses:Food:Coffee").performClick()

        assert(value == "Expenses:Food:Coffee") { "the tapped suggestion was not used: $value" }
    }

    /**
     * A chosen suggestion leaves the cursor after its last character (B77).
     *
     * The field held a plain string, so choosing "Expenses:Food:Coffee" after typing "coff"
     * kept the cursor at index 4 -- in the middle of the word the person had just finished.
     */
    @Test fun choosingASuggestionPutsTheCursorAtTheEnd() {
        var value by androidx.compose.runtime.mutableStateOf("")
        compose.setContent {
            MaterialTheme {
                SuggestingField(
                    value = value,
                    onValueChange = { value = it },
                    label = "Account",
                    suggestions = listOf("Expenses:Food:Coffee"),
                )
            }
        }

        compose.onNodeWithContentDescription("Account").performTextInput("coff")
        compose.onNodeWithContentDescription("Use Expenses:Food:Coffee").performClick()

        val selection = compose.onNodeWithContentDescription("Account").fetchSemanticsNode()
            .config[androidx.compose.ui.semantics.SemanticsProperties.TextSelectionRange]
        assert(selection.start == "Expenses:Food:Coffee".length && selection.collapsed) {
            "the cursor was left at $selection"
        }
        // And the list closes under what was chosen, instead of offering it back.
        compose.onAllNodesWithContentDescription("Use Expenses:Food:Coffee").assertCountEqualsZero()
    }

    /** A tag cannot contain a space in Beancount, so a typed space never reaches the file (B77). */
    @Test fun aFieldCanRefuseCharactersAsTheyAreTyped() {
        var value = ""
        compose.setContent {
            MaterialTheme {
                SuggestingField(
                    value = value,
                    onValueChange = { value = it },
                    label = "Tag",
                    suggestions = emptyList(),
                    transform = { it.removePrefix("#").filterNot(Char::isWhitespace) },
                )
            }
        }

        compose.onNodeWithContentDescription("Tag").performTextInput("#trip 2026")

        assert(value == "trip2026") { "the tag reached the form as \"$value\"" }
    }

    @Test fun theJournalIsSearchable() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot) } }

        compose.onNodeWithText("Corner Coffee · Corner Coffee visit").assertIsDisplayed()
        compose.onNodeWithContentDescription(
            "Search entries by date, payee, description or account",
        ).performTextInput("garage")

        compose.onAllNodesWithText("Corner Coffee · Corner Coffee visit").assertCountEqualsZero()
        compose.onNodeWithText("City Garage · City Garage visit").assertIsDisplayed()
    }

    @Test fun balancesTakesADateRange() {
        var from = ""
        var to = ""
        compose.setContent {
            MaterialTheme {
                BalancesScreen(
                    snapshot = snapshot,
                    dateFrom = from,
                    dateTo = to,
                    onDateRange = { f, t -> from = f; to = t },
                )
            }
        }

        compose.onNodeWithContentDescription("Pick From from a calendar").assertIsDisplayed()
        compose.onNodeWithContentDescription("Pick To from a calendar").assertIsDisplayed()
    }
}

/** Reads better than `fetchSemanticsNodes().isEmpty()` at the call site. */
private fun androidx.compose.ui.test.SemanticsNodeInteractionCollection.assertCountEqualsZero() {
    val found = fetchSemanticsNodes().size
    assert(found == 0) { "expected the filtered-out row to be gone, found $found" }
}
