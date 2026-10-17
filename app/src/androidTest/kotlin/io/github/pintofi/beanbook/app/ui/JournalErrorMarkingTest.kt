package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.Cost
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.JournalPosting
import io.github.pintofi.beanbook.core.domain.LedgerError
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import java.math.BigDecimal
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An error is marked on the entry it is about, in date order, with a filter.
 *
 * The ledger's errors were a list at the top of the screen naming a file and a line. The
 * entry they were about sat in date order among thousands of entries that were fine, looking
 * exactly like them. For a while the fix was to move those entries to the top; that moved the
 * journal's own order whenever the file had a problem, so now they stay where they belong,
 * marked, and a filter shows only them.
 *
 * The lot on the last posting is here because a journal row that shows units alone cannot
 * tell two lots of the same fund apart.
 */
@RunWith(AndroidJUnit4::class)
class JournalErrorMarkingTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private fun entry(
        date: String,
        narration: String,
        line: Int,
        posting: JournalPosting = JournalPosting("Assets:Bank", Amount(BigDecimal("1.00"), "USD")),
    ) = JournalEntry(
        date = date,
        flag = "*",
        payee = null,
        narration = narration,
        tags = emptyList(),
        links = emptyList(),
        postings = listOf(posting),
        file = "main.beancount",
        line = line,
    )

    private fun snapshot(errors: List<LedgerError>) = LedgerSnapshot(
        title = "Test",
        operatingCurrencies = listOf("USD"),
        entryCount = 3,
        entryCounts = emptyMap(),
        accounts = emptyList(),
        journal = listOf(
            entry("2026-03-14", "Newest and fine", line = 30),
            entry("2026-02-02", "The broken one", line = 20),
            entry(
                "2026-01-01",
                "Bought a fund",
                line = 10,
                posting = JournalPosting(
                    account = "Assets:Investments:EIF",
                    amount = Amount(BigDecimal("150.00"), "EIF"),
                    cost = Cost(Amount(BigDecimal("400.00"), "PKR"), "2025-01-01", "lot-a"),
                    price = Amount(BigDecimal("120.0500"), "PKR"),
                ),
            ),
        ),
        errors = errors,
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    private val onEntry = LedgerError(
        "Transaction does not balance: (-1.00 USD)",
        "main.beancount",
        21,
        "ValidationError",
    )

    private val onNothing = LedgerError(
        "Balance failed for 'Assets:Bank'",
        "main.beancount",
        5,
        "BalanceError",
    )

    @Test fun anErrorIsMarkedOnTheEntryItIsAbout() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(listOf(onEntry))) } }

        compose.onNodeWithText("Has an error").assertIsDisplayed()
        compose.onNodeWithText("1 error is on an entry.").assertIsDisplayed()
        // The sentence itself belongs on the entry's own page, not in the list.
        compose.onAllNodesWithText("Transaction does not balance: (-1.00 USD)").assertCountEquals(0)
    }

    @Test fun theEntryWithAnErrorStaysInDateOrder() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(listOf(onEntry))) } }

        // Newest first is the ordinary order, and "The broken one" is older.
        val broken = compose.onNodeWithText("The broken one").fetchSemanticsNode()
        val newest = compose.onNodeWithText("Newest and fine").fetchSemanticsNode()
        assert(newest.positionInRoot.y < broken.positionInRoot.y) {
            "the entry with an error was moved out of date order"
        }
    }

    @Test fun theFilterShowsOnlyEntriesWithErrors() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(listOf(onEntry))) } }

        compose.onNodeWithContentDescription("Show only entries with errors").performClick()

        compose.onNodeWithText("The broken one").assertIsDisplayed()
        compose.onAllNodesWithText("Newest and fine").assertCountEquals(0)

        compose.onNodeWithContentDescription(
            "Showing only entries with errors. Tap to show every entry",
        ).performClick()
        compose.onNodeWithText("Newest and fine").assertIsDisplayed()
    }

    @Test fun aCleanLedgerOffersNoErrorFilter() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(emptyList())) } }

        compose.onAllNodesWithText("Only errors").assertCountEquals(0)
    }

    @Test fun anErrorNoEntryOwnsStaysInTheLedgersOwnList() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(listOf(onNothing))) } }

        compose.onNodeWithText("1 error in this ledger").assertIsDisplayed()
        compose.onAllNodesWithText("1 error is on an entry.")
            .assertCountEquals(0)
    }

    @Test fun anErrorOnAnEntryIsNotAlsoListedAtTheTop() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(listOf(onEntry))) } }

        compose.onAllNodesWithText("1 error in this ledger").assertCountEquals(0)
    }

    @Test fun aCleanLedgerSaysNothingAboutErrors() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(emptyList())) } }

        compose.onAllNodesWithText("1 error in this ledger").assertCountEquals(0)
        compose.onNodeWithText("Newest and fine").assertIsDisplayed()
    }

    @Test fun aPostingShowsWhatItWasHeldAndExchangedAt() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(emptyList())) } }

        compose.onNodeWithText("{400.00 PKR, 2025-01-01, \"lot-a\"} @ 120.0500 PKR")
            .assertIsDisplayed()
    }

    @Test fun aMarkedEntryAnnouncesItsError() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(listOf(onEntry))) } }

        compose.onNodeWithContentDescription(
            "2026-02-02, The broken one. Has an error: Transaction does not balance: (-1.00 USD)",
        ).assertIsDisplayed()
    }
}
