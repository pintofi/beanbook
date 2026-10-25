package io.github.pintofi.beanbook.app.ui

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.pintofi.beanbook.app.MoreMenu
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.theme.ThemeChoice
import io.github.pintofi.beanbook.core.domain.AccountBalance
import io.github.pintofi.beanbook.core.domain.Amount
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.DocumentRow
import io.github.pintofi.beanbook.core.domain.DocumentStatus
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.core.domain.JournalPosting
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import java.math.BigDecimal
import java.time.LocalDate
import org.junit.Assume
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The main screens, drawn in light and dark at an ordinary and at double text size, and compared
 * with the pictures kept in `src/androidTest/assets/screenshots` (see [Screenshots]).
 *
 * Everything the screens show is fixed here -- the ledger, the date, the width and the density --
 * so a difference means the code or the emulator's renderer changed, not the data.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private data class Variant(val name: String, val theme: ThemeChoice, val fontScale: Float)

    private val variants = listOf(
        Variant("light", ThemeChoice.LIGHT, 1f),
        Variant("dark", ThemeChoice.DARK, 1f),
        Variant("light-200", ThemeChoice.LIGHT, 2f),
        Variant("dark-200", ThemeChoice.DARK, 2f),
    )

    private fun usd(text: String) = Amount(BigDecimal(text), "USD")

    private val ledger = LedgerSnapshot(
        title = "Household",
        operatingCurrencies = listOf("USD"),
        entryCount = 4,
        entryCounts = mapOf("Transaction" to 3, "Open" to 1),
        accounts = listOf(
            AccountBalance("Assets:Bank:Checking", listOf(usd("1240.00"))),
            AccountBalance("Assets:Cash", listOf(usd("60.00"))),
            AccountBalance("Liabilities:Card", listOf(usd("-300.00"))),
            AccountBalance("Income:Salary", listOf(usd("-3000.00"))),
            AccountBalance("Expenses:Food:Groceries", listOf(usd("182.00"))),
            AccountBalance("Expenses:Food:Dining", listOf(usd("80.00"))),
        ),
        journal = listOf(
            entry("2026-10-24", "Grocer", "Weekly shop", "Expenses:Food:Groceries", "92.00", "Assets:Bank:Checking"),
            entry("2026-10-20", "Cafe", "Lunch with Sam", "Expenses:Food:Dining", "18.50", "Liabilities:Card"),
            entry("2026-10-01", "Employer", "October pay", "Assets:Bank:Checking", "3000.00", "Income:Salary"),
        ),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    private fun entry(date: String, payee: String, narration: String, debit: String, amount: String, credit: String) =
        JournalEntry(
            date = date,
            flag = "*",
            payee = payee,
            narration = narration,
            tags = emptyList(),
            links = emptyList(),
            postings = listOf(
                JournalPosting(debit, usd(amount)),
                JournalPosting(credit, usd("-$amount")),
            ),
            file = "main.beancount",
            line = 10,
        )

    private val vocabulary = LedgerVocabulary(
        accounts = ledger.accounts.map { it.name },
        payees = listOf("Grocer", "Cafe", "Employer"),
        narrations = listOf("Weekly shop", "Lunch with Sam"),
        currencies = listOf("USD"),
        tags = listOf("trip"),
    )

    @Test fun home() = shoot("home") {
        HomeScreen(ledger = ledger, hideAmounts = false)
    }

    @Test fun journal() = shoot("journal") { JournalScreen(snapshot = ledger) }

    @Test fun balances() = shoot("balances") { BalancesScreen(snapshot = ledger) }

    @Test fun more() = shoot("more") { MoreMenu(onOpen = {}) }

    @Test fun addEntry() = shoot("add-entry") {
        QuickAddScreen(vocabulary = vocabulary, onSave = {}, today = LocalDate.of(2026, 10, 25))
    }

    @Test fun documentsAdd() = shoot("documents-add") { documents(DocumentsMode.ADD) }

    @Test fun documentsOrganize() = shoot("documents-organize") { documents(DocumentsMode.ORGANIZE) }

    @Composable
    private fun documents(mode: DocumentsMode) {
        DocumentsScreen(
            capture = DocumentCaptureHandle(
                pages = CapturedPages(),
                captureFile = { java.io.File("unused") },
                onCapture = {},
                onRotate = {},
                onMove = { _, _ -> },
                onRemovePage = {},
                onFile = { _, _, _ -> },
            ),
            documents = listOf(
                DocumentRow(
                    path = "documents/Expenses/Food/2026-10-24.receipt.pdf",
                    status = DocumentStatus.FILED,
                    account = "Expenses:Food",
                    date = "2026-10-24",
                ),
                DocumentRow(path = "documents/stray.pdf", status = DocumentStatus.UNFILED),
            ),
            vocabulary = vocabulary,
            onSave = { _, _ -> },
            onDiscard = {},
            onRefile = { _, _, _ -> },
            onUnlink = { _, _ -> },
            onDeleteFile = {},
            onShare = {},
            hasCamera = false,
            mode = mode,
        )
    }

    /** Draws [content] once per variant and compares each with its kept picture. */
    private fun shoot(screen: String, content: @Composable () -> Unit) {
        // One Android version draws them all: another one draws text and some controls a little
        // differently, and a picture that is right on one is wrong on the other.
        Assume.assumeTrue("the pictures are drawn on Android 16 (API 36)", Build.VERSION.SDK_INT == PICTURE_API)
        var variant by mutableStateOf(variants.first())
        compose.setContent {
            // A fixed density as well as a fixed width, so the picture is the same size on any
            // emulator; only the text scale and the theme are what each variant changes.
            CompositionLocalProvider(LocalDensity provides Density(2f, variant.fontScale)) {
                BeancountTheme(choice = variant.theme, dynamicColour = false) {
                    Surface(color = MaterialTheme.colorScheme.background) {
                        Box(Modifier.testTag("shot").width(360.dp).height(760.dp)) { content() }
                    }
                }
            }
        }
        variants.forEach { next ->
            variant = next
            compose.waitForIdle()
            Screenshots.check("$screen-${next.name}", compose.onNodeWithTag("shot").captureToImage())
        }
    }

    private companion object {
        const val PICTURE_API = 36
    }
}
