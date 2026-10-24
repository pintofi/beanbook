package io.github.pintofi.beanbook.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.AccountNames
import io.github.pintofi.beanbook.core.domain.DirectiveDraft
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * An account is opened with as many details as the person gives (name and value pairs, written
 * as metadata under the `open` line), and opening and closing live on the Accounts page only.
 */
@RunWith(AndroidJUnit4::class)
class AccountDetailsTest {

    @get:Rule val compose = createComposeRule()

    private val today = LocalDate.parse("2026-09-01")

    private val vocabulary = LedgerVocabulary(
        accounts = listOf("Assets:Cash"),
        payees = emptyList(),
        narrations = emptyList(),
        currencies = listOf("USD"),
        tags = emptyList(),
    )

    @Test fun anAccountIsOpenedWithSeveralDetails() {
        val saved = mutableListOf<DirectiveDraft>()
        compose.setContent {
            MaterialTheme {
                AccountsScreen(
                    accounts = emptyList(),
                    names = AccountNames.DEFAULT,
                    onSave = { saved += it },
                    message = null,
                    today = today,
                )
            }
        }

        compose.onNodeWithText("Name under Assets").performScrollTo().performTextInput("Bank:Checking")
        compose.onNodeWithText("Add a detail").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Detail name 1").performScrollTo().performTextInput("institution")
        compose.onNodeWithContentDescription("Detail value 1").performScrollTo().performTextInput("Corner Bank")
        compose.onNodeWithText("Add a detail").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Detail name 2").performScrollTo().performTextInput("iban-last4")
        compose.onNodeWithContentDescription("Detail value 2").performScrollTo().performTextInput("4321")

        compose.onNodeWithText("Open account").performScrollTo().assertIsEnabled().performClick()

        assertEquals(1, saved.size)
        val open = saved.single() as DirectiveDraft.Open
        assertEquals("Assets:Bank:Checking", open.account)
        assertEquals(listOf("institution" to "Corner Bank", "iban-last4" to "4321"), open.metadata.toList())
        assertEquals(
            "2026-09-01 open Assets:Bank:Checking\n  institution: \"Corner Bank\"\n  iban-last4: 4321\n",
            open.render(),
        )
    }

    @Test fun aDetailCanBeRemovedBeforeSaving() {
        compose.setContent {
            MaterialTheme {
                AccountsScreen(emptyList(), AccountNames.DEFAULT, onSave = {}, message = null, today = today)
            }
        }
        compose.onNodeWithText("Add a detail").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Detail name 1").assertExists()
        compose.onNodeWithContentDescription("Remove detail 1").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Detail name 1").assertDoesNotExist()
    }

    @Test fun closingAnAccountAsksForADate() {
        val saved = mutableListOf<DirectiveDraft>()
        val empty = AccountBalance(
            name = "Assets:Old",
            positions = emptyList(),
            opened = "2020-01-01",
        )
        compose.setContent {
            MaterialTheme {
                AccountsScreen(
                    listOf(empty), AccountNames.DEFAULT, onSave = { saved += it }, message = null, today = today,
                )
            }
        }
        compose.onNodeWithContentDescription("Close the account Assets:Old").performClick()
        compose.onNodeWithText("Close it").assertIsEnabled().performClick()
        assertEquals(listOf<DirectiveDraft>(DirectiveDraft.Close("2026-09-01", "Assets:Old")), saved)
    }

    @Test fun theDirectivesFormNoLongerOpensOrClosesAccounts() {
        compose.setContent {
            MaterialTheme {
                DirectiveEntryScreen(vocabulary, onSave = {}, today = today)
            }
        }
        compose.onNode(hasContentDescription("Open directive")).assertDoesNotExist()
        compose.onNode(hasContentDescription("Close directive")).assertDoesNotExist()
        compose.onNodeWithContentDescription("Balance directive").assertExists()
        compose.onNodeWithText("To open or close an account, use More, then Accounts.").assertExists()
    }
}
