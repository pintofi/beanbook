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
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.JournalPosting
import io.github.pintofi.beanbook.core.domain.LedgerError
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.domain.SpendingPeriod
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Home answers what the Journal cannot, and shows what the user asked it to (B71).
 *
 * The old screen was three figures and five recent entries -- the Journal, shortened. These
 * are the three cards that replaced it and the dialog that decides which of them appear.
 */
@RunWith(AndroidJUnit4::class)
class HomeCardsTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val store = HomePreferences(context)
    private val today = LocalDate.parse("2026-09-11")

    @Before fun startFromTheDefaults() {
        context.filesDir.resolve("state/home-cards").delete()
    }

    private fun entry(date: String, account: String, value: String, flag: String = "*") = JournalEntry(
        date = date,
        flag = flag,
        payee = null,
        narration = "$account entry",
        tags = emptyList(),
        links = emptyList(),
        postings = listOf(
            JournalPosting(account, Amount(BigDecimal(value), "USD")),
            JournalPosting("Assets:Cash", Amount(BigDecimal("-$value"), "USD")),
        ),
        file = "main.beancount",
        line = 10,
    )

    private val ledger = LedgerSnapshot(
        title = "T",
        operatingCurrencies = listOf("USD"),
        entryCount = 4,
        entryCounts = emptyMap(),
        accounts = emptyList(),
        journal = listOf(
            entry("2026-09-02", "Expenses:Food:Groceries", "120.00"),
            entry("2026-09-05", "Expenses:Transport", "60.00"),
            entry("2026-09-06", "Expenses:Food:Coffee", "30.00", flag = "!"),
            entry("2026-08-14", "Expenses:Food:Groceries", "100.00"),
        ),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    private fun show(
        snapshot: LedgerSnapshot = ledger,
        unfiled: Int = 0,
        broken: Int = 0,
        conflicts: Int = 0,
        onOpenDocuments: () -> Unit = {},
    ) {
        compose.setContent {
            MaterialTheme {
                HomeScreen(
                    ledger = snapshot,
                    hideAmounts = false,
                    unfiledDocuments = unfiled,
                    brokenDocumentLinks = broken,
                    mirrorConflicts = conflicts,
                    onOpenDocuments = onOpenDocuments,
                    today = today,
                )
            }
        }
    }

    @Test fun spendingTotalsThePeriodAndNamesTheCategories() {
        show()

        compose.onNodeWithText("Where the money went").assertIsDisplayed()
        compose.onNodeWithText("210.00 USD").assertIsDisplayed()
        compose.onNodeWithContentDescription("Food, 150.00 USD").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("Transport, 60.00 USD").performScrollTo().assertIsDisplayed()
    }

    @Test fun spendingComparesWithThePeriodBefore() {
        show()

        compose.onNodeWithText("110.00 USD more than last month, which was 100.00 USD.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test fun pendingIsTheEntriesTheFileFlags() {
        show()

        compose.onNodeWithText("Uncleared and pending").performScrollTo().assertIsDisplayed()
        compose.onNodeWithContentDescription("2026-09-06, Expenses:Food:Coffee entry")
            .performScrollTo()
            .assertIsDisplayed()
        // A cleared entry is not on the to-do list.
        compose.onAllNodesWithText("Expenses:Transport entry").assertCountEquals(0)
    }

    @Test fun attentionSaysNothingWhenThereIsNothing() {
        show()

        compose.onNodeWithText("Nothing. The ledger loads, and every document is accounted for.")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test fun attentionGathersErrorsDocumentsAndTheMirror() {
        val broken = ledger.copy(
            errors = listOf(LedgerError("does not balance", "main.beancount", 10, "ValidationError")),
        )
        show(broken, unfiled = 2, broken = 1, conflicts = 1)

        compose.onNodeWithText("1 error in this ledger").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 link with no file").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("2 documents filed under nothing").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("1 file needs your decision").performScrollTo().assertIsDisplayed()
    }

    @Test fun anAttentionRowGoesWhereItPointsTo() {
        var opened = false
        show(unfiled = 1, onOpenDocuments = { opened = true })

        compose.onNodeWithContentDescription(
            "1 document filed under nothing. They are in the project; no entry or account claims them.",
        ).performScrollTo().performClick()

        assertTrue("the documents screen was not opened", opened)
    }

    @Test fun recentEntriesAreOffByDefault() {
        // The card that made Home a second Journal is available and not shown unless asked.
        show()

        compose.onAllNodesWithText("Recent entries").assertCountEquals(0)
    }

    @Test fun turningACardOffRemovesItAndIsRemembered() {
        show()
        compose.onNodeWithContentDescription("Choose what Home shows").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Show Uncleared and pending").performScrollTo().performClick()
        // The dialog's buttons sit outside its scrolling content, so there is nothing to scroll.
        compose.onNodeWithContentDescription("Save what Home shows").performClick()

        compose.onAllNodesWithText("Uncleared and pending").assertCountEquals(0)
        assertTrue(
            "the choice was not written down",
            HomeCard.PENDING !in store.read().cards,
        )
    }

    @Test fun theSpendingPeriodIsTheUsersChoice() {
        show()
        compose.onNodeWithContentDescription("Choose what Home shows").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Last 30 days").performScrollTo().performClick()
        // The dialog's buttons sit outside its scrolling content, so there is nothing to scroll.
        compose.onNodeWithContentDescription("Save what Home shows").performClick()

        // August's 100.00 is inside a 30-day window ending 2026-09-11 and outside "this
        // month", so the total changes to prove the period is really being used.
        compose.onNodeWithText("310.00 USD").performScrollTo().assertIsDisplayed()
        assertEquals(SpendingPeriod.LAST_30_DAYS, store.read().period)
    }

    @Test fun aCardCanBeMovedAndTheOrderIsKept() {
        show()
        compose.onNodeWithContentDescription("Choose what Home shows").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Move Needs attention up").performScrollTo().performClick()
        // The dialog's buttons sit outside its scrolling content, so there is nothing to scroll.
        compose.onNodeWithContentDescription("Save what Home shows").performClick()

        val cards = store.read().cards
        assertTrue(
            "attention did not move above spending: $cards",
            cards.indexOf(HomeCard.ATTENTION) < cards.indexOf(HomeCard.SPENDING),
        )
    }

    @Test fun amountsCanBeHidden() {
        compose.setContent {
            MaterialTheme {
                HomeScreen(ledger = ledger, hideAmounts = true, today = today)
            }
        }

        compose.onAllNodesWithText("210.00 USD").assertCountEquals(0)
    }
}
