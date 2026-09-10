package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
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
 * An error is shown on the entry it is about, and that entry is shown first (B64).
 *
 * The ledger's errors were a list at the top of the screen naming a file and a line. The
 * entry they were about sat in date order among thousands of entries that were fine, looking
 * exactly like them.
 *
 * The lot on the last posting is here for B63: a journal row that shows units alone cannot
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
                    account = "Assets:Investments:ASSF",
                    amount = Amount(BigDecimal("151.88"), "ASSF"),
                    cost = Cost(Amount(BigDecimal("400.00"), "PKR"), "2025-01-01", "avcoassf"),
                    price = Amount(BigDecimal("483.0500"), "PKR"),
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

    @Test fun anErrorIsShownOnTheEntryItIsAbout() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(listOf(onEntry))) } }

        compose.onNodeWithText("Transaction does not balance: (-1.00 USD)").assertIsDisplayed()
        compose.onNodeWithText("1 error is on an entry, shown first below.").assertIsDisplayed()
    }

    @Test fun theEntryWithAnErrorComesFirst() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(listOf(onEntry))) } }

        // Newest first is the ordinary order, so "The broken one" is second by date. Its
        // error message being above the newest entry's description is what proves it moved.
        val broken = compose.onNodeWithText("The broken one").fetchSemanticsNode()
        val newest = compose.onNodeWithText("Newest and fine").fetchSemanticsNode()
        assert(broken.positionInRoot.y < newest.positionInRoot.y) {
            "the entry with an error was not shown first"
        }
    }

    @Test fun anErrorNoEntryOwnsStaysInTheLedgersOwnList() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(listOf(onNothing))) } }

        compose.onNodeWithText("1 error in this ledger").assertIsDisplayed()
        compose.onAllNodesWithText("1 error is on an entry, shown first below.")
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

        compose.onNodeWithText("{400.00 PKR, 2025-01-01, \"avcoassf\"} @ 483.0500 PKR")
            .assertIsDisplayed()
    }

    @Test fun aMarkedEntryAnnouncesItsError() {
        compose.setContent { MaterialTheme { JournalScreen(snapshot(listOf(onEntry))) } }

        compose.onNodeWithContentDescription(
            "2026-02-02, The broken one. Has an error: Transaction does not balance: (-1.00 USD)",
        ).assertIsDisplayed()
    }
}
