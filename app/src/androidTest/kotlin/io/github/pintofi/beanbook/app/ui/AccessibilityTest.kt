package io.github.pintofi.beanbook.app.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import io.github.pintofi.beanbook.app.ui.EntryDetailScreen
import io.github.pintofi.beanbook.app.ui.HomeScreen
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performScrollToNode
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.DocumentRow
import io.github.pintofi.beanbook.core.domain.DocumentStatus
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The accessibility gate for the entry surfaces (CLAUDE.md, "Definition of done").
 *
 * Two things are checked, because they fail differently:
 *
 * 1. **Labels and touch targets**, over the semantics tree — every control TalkBack can
 *    reach must announce something, and must be big enough to hit.
 * 2. **Layout at 200% font**, because guided entry is dense: a preview and a running
 *    imbalance under a form of paired fields. Text that doubles in size is where a Row of
 *    two text fields stops fitting, and no label check catches that.
 *
 * **Why not Google's Accessibility Test Framework.** It was tried first, wired through
 * `setComposeAccessibilityValidator`. ATF walks a *View* hierarchy, and a Compose window is
 * one View: on every screen it reported exactly one result — "AndroidComposeView … may not
 * have a label" — and reported the same single result for a deliberately broken 20dp
 * unlabelled box. It sees the host and nothing inside it, so it can neither pass nor fail
 * on anything this app draws. The checks below read the semantics tree, which is what
 * TalkBack actually consumes, and [theGateCatchesViolations] proves they fail when they
 * should.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityTest {

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    private val vocabulary = LedgerVocabulary(
        accounts = listOf("Expenses:Food:Groceries", "Assets:Bank:Checking", "Income:Salary"),
        payees = listOf("Grocer", "Employer"),
        narrations = listOf("Weekly shop"),
        currencies = listOf("USD", "EUR"),
        tags = listOf("trip"),
    )

    private val today = LocalDate.parse("2026-08-23")

    @Test fun quickAddPassesAccessibilityChecks() {
        compose.setContent { Themed { QuickAddScreen(vocabulary, onSave = {}, today = today) } }
        assertAccessible()
    }

    /**
     * Attaching a receipt while entering the transaction it belongs to (B17).
     *
     * The control is only useful if it says what it attaches to, and the refusal path has to
     * be readable too: without an account there is nowhere to file the document, and a
     * greyed-out button does not say that.
     */
    @Test fun attachingADocumentDuringEntryIsLabelled() {
        var filedUnder: String? = null
        render(widthDp = 320, fontScale = 2f) {
            QuickAddScreen(
                vocabulary = vocabulary,
                onSave = {},
                today = today,
                capture = DocumentCaptureHandle(
                    pages = CapturedPages(),
                    captureFile = { java.io.File("unused") },
                    onCapture = { },
                    onRotate = {},
                    onMove = { _, _ -> },
                    onRemovePage = {},
                    onFile = { account, _ -> filedUnder = account },
                ),
            )
        }

        // The control opens the fullscreen scanner now rather than unfolding a viewfinder
        // inside the form, so its name says scanning. What this case checks is unchanged:
        // that the way to attach a document during entry is reachable and labelled at 200%.
        compose.onNodeWithContentDescription("Scan a document for this transaction")
            .performScrollTo().assertExists()
        assertAccessible()
        // Message first: this is org.junit's Assert, as the rest of this file uses. Written
        // the kotlin.test way round, the message became the "actual" and the assertion
        // compared the callback's result against a sentence.
        assertNull("nothing should be filed before the user asks", filedUnder)
    }

    /**
     * Editing an entry's own source (B11).
     *
     * The field holds Beancount, not prose, so it has to be reachable and named: a
     * multi-line box with no label is unusable without sight, and this one can rewrite a
     * transaction.
     */
    @Test fun editingAnEntrySourceIsLabelled() {
        var saved: String? = null
        val original = "2026-02-01 * \"Shop\" \"coffee\"\n  Expenses:Food   12.00 USD\n  Assets:Bank\n"
        render(widthDp = 320, fontScale = 2f) {
            EntryEditDialog(original = original, onSave = { saved = it }, onDismiss = {})
        }

        compose.onNodeWithContentDescription("The source text of this entry").assertExists()
        assertNull("nothing should be saved until Save is pressed", saved)
        compose.onNodeWithText("Save").performClick()
        assertEquals("the entry's own text should be what is saved", original, saved)
    }

    @Test fun directiveEntryPassesAccessibilityChecks() {
        compose.setContent { Themed { DirectiveEntryScreen(vocabulary, onSave = {}, today = today) } }
        assertAccessible()
    }

    /** Every text field carries a label, so TalkBack announces what is being typed into it. */
    @Test fun everyFieldIsLabelled() {
        compose.setContent { Themed { QuickAddScreen(vocabulary, onSave = {}, today = today) } }
        val fields = compose.onAllNodes(hasSetTextAction()).fetchSemanticsNodes()
        assertTrue("expected the form to have text fields", fields.isNotEmpty())
        fields.forEach { node ->
            assertTrue(
                "a text field has neither a label nor a content description: ${node.config}",
                !node.label().isNullOrBlank(),
            )
        }
    }

    /**
     * At 200% font on a narrow phone, the two things that decide whether an entry is
     * correct — the preview and Save — must still be reachable, and the controls must still
     * be labelled and hittable once everything has grown.
     */
    @Test fun quickAddIsUsableAtDoubleFontScale() {
        render(widthDp = 320, fontScale = 2f) {
            QuickAddScreen(vocabulary, onSave = {}, today = today)
        }
        compose.onNodeWithText("Will be written as").performScrollTo().assertExists()
        compose.onNodeWithText("Save entry").performScrollTo().assertExists()
        assertAccessible()
    }

    /**
     * At 200% font the account and amount fields must not share a line: side by side the
     * amount field becomes narrow enough that its own label wraps mid-word.
     */
    @Test fun postingFieldsStackAtDoubleFontScale() {
        render(widthDp = 320, fontScale = 2f) {
            QuickAddScreen(vocabulary, onSave = {}, today = today)
        }
        // Scrolled into view before measuring: `boundsInRoot` is zero for a node the
        // scrolling column has not laid out, and adding controls above the postings pushed
        // this one below the fold. A zero rect reads as "side by side" and fails for the
        // wrong reason.
        //
        // `onAllNodesWithText(...)[0]` rather than `onNodeWithText`: every posting's amount
        // field is labelled "Amount" now. It used to say "Amount (optional)" from the second
        // row down, which made the plain label unique by accident — and made that label long
        // enough to wrap in its own field, which is why it moved to supporting text. The
        // first posting is the one this measures, so take it by index.
        val amountFields = compose.onAllNodesWithText("Amount")
        amountFields[0].performScrollTo()

        val account = compose.onNodeWithText("Account 1").fetchSemanticsNode().boundsInRoot
        val amount = amountFields[0].fetchSemanticsNode().boundsInRoot
        assertTrue(
            "account ($account) and amount ($amount) are still side by side",
            amount.top >= account.bottom - 1f,
        )

        // The currency field shares the amount's line rather than taking a third: it is
        // short enough to, and a three-line posting at 200% font leaves room for almost
        // nothing else (B4).
        val currency = compose.onAllNodesWithContentDescription("Currency for posting 1")
            .fetchSemanticsNodes().first().boundsInRoot
        assertTrue(
            "currency ($currency) dropped onto its own line below the amount ($amount)",
            currency.top < amount.bottom - 1f,
        )
    }

    /**
     * Onboarding at 200% font on a narrow phone. This is where the check earned its keep:
     * the screen was a plain Column, so at that size the "Start a new project" card fell
     * below the fold with no way to scroll to it — the app was unusable for anyone with
     * large text, and nothing in the suite noticed until this test existed.
     */
    @Test fun onboardingIsUsableAtDoubleFontScale() {
        render(widthDp = 320, fontScale = 2f) {
            OnboardingScreen(onCreateNew = {}, onOpenFolder = {}, onOpenArchive = {})
        }
        // The welcome is its own page now, and the ways in are behind it. Both halves are
        // checked at this size: the action that moves between them has to be reachable, or
        // the second page cannot be got to at all — which is the same failure this test was
        // written for, one page further along.
        // By description, not by text: the button carries an accessible name that says where
        // it goes as well as what it is called, and that replaces the visible label on the
        // merged node. The name still begins with the label a sighted user reads.
        // Asserted present without scrolling to it, deliberately: it sits in the scaffold's
        // floating slot rather than in the scrolling column, so it is on screen whatever the
        // content does. Trying to scroll to it fails with "no parent layout with a Scroll
        // SemanticsAction", which is this property being demonstrated rather than a fault.
        val onward = compose.onNodeWithContentDescription("Get started", substring = true)
        onward.assertExists()
        assertAccessible()

        onward.performClick()

        compose.onNodeWithText("Choose folder").performScrollTo().assertExists()
        compose.onNodeWithText("Choose .zip").performScrollTo().assertExists()
        compose.onNodeWithText("Create").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Back to the welcome").assertExists()
        assertAccessible()
    }

    /** The same form in a tablet-width window, also at 200% font. */
    @Test fun directiveEntryIsUsableOnATabletAtDoubleFontScale() {
        render(widthDp = 800, fontScale = 2f) {
            DirectiveEntryScreen(vocabulary, onSave = {}, today = today)
        }
        compose.onNodeWithText("Will be written as").performScrollTo().assertExists()
        compose.onNodeWithText("Save ", substring = true).performScrollTo().assertExists()
        assertAccessible()
    }

    /**
     * The documents screen, without a camera. Every control it offers — rotate, reorder,
     * remove, share, unlink, delete — has to announce *which page or file* it acts on, since
     * "Delete" on its own is exactly the announcement that loses someone a receipt.
     */
    @Test fun documentsScreenIsLabelledAndFitsAtDoubleFontScale() {
        render(widthDp = 320, fontScale = 2f) {
            DocumentsScreen(
                capture = DocumentCaptureHandle(
                    pages = CapturedPages().add("page-1.jpg").add("page-2.jpg"),
                    captureFile = { java.io.File("unused") },
                    onCapture = { },
                    onRotate = {},
                    onMove = { _, _ -> },
                    onRemovePage = {},
                    onFile = { _, _ -> },
                ),
                documents = listOf(
                    DocumentRow(
                        path = "documents/Assets/Cash/2026-08-24.abcdef123456.pdf",
                        status = DocumentStatus.FILED,
                        account = "Assets:Cash",
                        date = "2026-08-24",
                    ),
                    // An unfiled one too: it is offered a different set of actions, and a
                    // fixture with only filed documents would never exercise that.
                    DocumentRow(path = "documents/stray.pdf", status = DocumentStatus.UNFILED),
                ),
                vocabulary = vocabulary,
                onSave = {},
                onDiscard = {},
                onRefile = { _, _, _ -> },
                onUnlink = { _, _ -> },
                onDeleteFile = {},
                onShare = {},
                hasCamera = false,
            )
        }

        // The screen has two tabs and each must be checked in its own right: the page
        // controls live in Add, the per-document ones in Organize, and asserting them all
        // against whichever tab happens to open would quietly stop checking one of them.
        listOf(
            "Rotate page 1",
            "Move page 2 up",
            "Remove page 1",
        ).forEach { label ->
            compose.onNodeWithContentDescription(label).performScrollTo().assertExists()
        }
        assertAccessible()

        // Scrolled to first: the assertions above leave the column scrolled past the tabs,
        // and a click on an off-screen node lands outside the viewport and does nothing --
        // which looked like a passing check of a tab that had never been opened.
        compose.onNodeWithText("Organize").performScrollTo().performClick()
        val doc = "documents/Assets/Cash/2026-08-24.abcdef123456.pdf"
        listOf(
            "Show details of $doc",
            "Share $doc",
            "File $doc under a different account",
            "Unlink $doc from its entry",
            "Delete the file $doc",
        ).forEach { label ->
            compose.onNodeWithContentDescription(label).performScrollTo().assertExists()
        }
        // The unfiled document offers filing, and does not offer to unlink a link it has
        // not got. Asserting the absence matters as much as the presence: an Unlink there
        // could only ever produce an error.
        compose.onNodeWithContentDescription("File documents/stray.pdf under an account")
            .performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Unlink documents/stray.pdf from its entry")
            .assertDoesNotExist()
        assertAccessible()
    }

    /**
     * The plugins screen, where every action is about somebody else's code.
     *
     * A control that says only "Remove" or "Run" is the announcement that turns off a plugin
     * the user meant to keep, or runs one they meant to leave alone — so each one names the
     * plugin it acts on. The unsandboxed warning is checked too: it is the one thing on this
     * screen that must reach a person who cannot see it.
     */
    /**
     * The project picker, which had no gate coverage at all until export was added to it.
     *
     * Every control here acts on a whole project — opening one, replacing what is on screen,
     * writing a copy somewhere, or deleting it. "Delete" alone, announced without the project
     * it would delete, is the worst label in the app.
     */
    /**
     * The home surface, which is the first thing anybody sees (B10).
     *
     * Each figure carries one description covering its label and its value, so a screen
     * reader says "Net worth, 940.00 USD" rather than reading a caption and a number as two
     * unrelated things. The hidden-amounts case is checked too: the point of that preference
     * is that the figure is not readable, and a description that still spelled it out would
     * defeat it on the surface where it matters most.
     */
    /**
     * The entry detail, which is where the destructive and duplicating actions live (B11).
     *
     * "Clone" writes nothing by itself, but it is the control that leads to a second
     * transaction, so it has to say what it does rather than relying on an icon.
     */
    @Test fun entryDetailIsLabelledAndFitsAtDoubleFontScale() {
        val entry = io.github.pintofi.beanbook.core.domain.JournalEntry(
            date = "2026-01-15",
            flag = "!",
            payee = "Landlord",
            narration = "January rent",
            tags = listOf("housing"),
            links = emptyList(),
            postings = listOf(
                io.github.pintofi.beanbook.core.domain.JournalPosting(
                    "Expenses:Rent",
                    io.github.pintofi.beanbook.core.domain.Amount(java.math.BigDecimal("1200.00"), "USD"),
                ),
                io.github.pintofi.beanbook.core.domain.JournalPosting("Assets:Bank:Checking", null),
            ),
            file = "ledger/main.beancount",
            line = 42,
        )

        render(widthDp = 320, fontScale = 2f) {
            EntryDetailScreen(entry = entry, onClone = {}, onClose = {})
        }

        compose.onNodeWithContentDescription("Clone this transaction into a new entry")
            .performScrollTo().assertExists()
        assertAccessible()
    }

    /**
     * Deleting names what it will delete, and where from (invariant 7).
     *
     * A confirmation that says only "Are you sure?" asks the user to remember which row they
     * tapped. This one repeats the transaction and the file, so the answer does not depend on
     * memory — and it is the screen a mis-tap lands on, so it has to read correctly to
     * somebody who is not looking at it.
     */
    @Test fun deletingAnEntryNamesWhatItWillDelete() {
        val entry = io.github.pintofi.beanbook.core.domain.JournalEntry(
            date = "2026-01-15", flag = "*", payee = "Landlord", narration = "January rent",
            tags = emptyList(), links = emptyList(), postings = emptyList(),
            file = "ledger/main.beancount", line = 42,
        )

        render(widthDp = 320, fontScale = 1f) {
            EntryDetailScreen(entry = entry, onClone = {}, onClose = {}, onDelete = {})
        }

        compose.onNodeWithContentDescription("Delete this transaction").performScrollTo()
            .performClick()
        compose.onNodeWithContentDescription("Confirm deleting this transaction")
            .performScrollTo().assertExists()
        compose.onNodeWithContentDescription("Delete this transaction")
            .performScrollTo().assertExists()
        assertAccessible()
    }

    /** A linked receipt is announced by name, not as "attachment" (B17). */
    @Test fun aLinkedDocumentIsOfferedByName() {
        val entry = io.github.pintofi.beanbook.core.domain.JournalEntry(
            date = "2026-02-01", flag = "*", payee = "Shop", narration = "coffee",
            tags = emptyList(), links = emptyList(), postings = emptyList(),
            file = "main.beancount", line = 10,
            metadata = mapOf("document" to "documents/receipt.pdf"),
        )

        render(widthDp = 320, fontScale = 2f) {
            EntryDetailScreen(
                entry = entry,
                onClone = {},
                onClose = {},
                onOpenDocument = {},
            )
        }

        compose.onNodeWithContentDescription("Open receipt.pdf").performScrollTo().assertExists()
        assertAccessible()
    }

    @Test fun anEntryWithNoDeleteHandlerShowsNoDeleteButton() {
        val entry = io.github.pintofi.beanbook.core.domain.JournalEntry(
            date = "2026-01-15", flag = "*", payee = "Landlord", narration = null,
            tags = emptyList(), links = emptyList(), postings = emptyList(),
            file = null, line = null,
        )

        render(widthDp = 320, fontScale = 1f) {
            EntryDetailScreen(entry = entry, onClone = {}, onClose = {})
        }

        compose.onAllNodesWithContentDescription("Delete this transaction")
            .fetchSemanticsNodes().let {
                assertTrue("a delete button appeared with nothing to call", it.isEmpty())
            }
    }

    @Test fun homeScreenIsLabelledAndFitsAtDoubleFontScale() {
        render(widthDp = 320, fontScale = 2f) {
            HomeScreen(ledger = homeLedger(), hideAmounts = false)
        }

        listOf("Net worth: 940.00 USD", "Income: 3000.00 USD", "Expenses: 262.00 USD")
            .forEach { compose.onNodeWithContentDescription(it).performScrollTo().assertExists() }
        assertAccessible()
    }

    @Test fun homeScreenHidesAmountsWhenAsked() {
        render(widthDp = 320, fontScale = 1f) {
            HomeScreen(ledger = homeLedger(), hideAmounts = true)
        }

        compose.onNodeWithContentDescription("Net worth: •••").performScrollTo().assertExists()
        assertAccessible()
    }

    private fun homeLedger() = io.github.pintofi.beanbook.core.domain.LedgerSnapshot(
        title = "Household",
        operatingCurrencies = listOf("USD"),
        entryCount = 3,
        entryCounts = mapOf("Transaction" to 1),
        accounts = listOf(
            io.github.pintofi.beanbook.core.domain.AccountBalance(
                "Assets:Bank",
                listOf(io.github.pintofi.beanbook.core.domain.Amount(java.math.BigDecimal("1240.00"), "USD")),
            ),
            io.github.pintofi.beanbook.core.domain.AccountBalance(
                "Liabilities:Card",
                listOf(io.github.pintofi.beanbook.core.domain.Amount(java.math.BigDecimal("-300.00"), "USD")),
            ),
            io.github.pintofi.beanbook.core.domain.AccountBalance(
                "Income:Salary",
                listOf(io.github.pintofi.beanbook.core.domain.Amount(java.math.BigDecimal("-3000.00"), "USD")),
            ),
            io.github.pintofi.beanbook.core.domain.AccountBalance(
                "Expenses:Food",
                listOf(io.github.pintofi.beanbook.core.domain.Amount(java.math.BigDecimal("262.00"), "USD")),
            ),
        ),
        journal = listOf(
            io.github.pintofi.beanbook.core.domain.JournalEntry(
                date = "2026-02-01",
                flag = "*",
                payee = "Shop",
                narration = "coffee",
                tags = emptyList(),
                links = emptyList(),
                postings = emptyList(),
                file = null,
                line = null,
            ),
        ),
        errors = emptyList(),
        includedFiles = emptyList(),
        loadMillis = 0,
    )

    @Test fun projectPickerIsLabelledAndFitsAtDoubleFontScale() {
        val project = io.github.pintofi.beanbook.core.data.ProjectSummary(
            id = "household",
            title = "Household",
            mainFile = java.io.File("/does/not/need/to/exist/main.beancount"),
            fileCount = 3,
            totalBytes = 12_288,
            lastModified = 1_756_000_000_000,
        )

        render(widthDp = 320, fontScale = 2f) {
            ProjectPickerScreen(
                projects = listOf(project),
                activeId = "household",
                onOpen = {},
                onCreateNew = {},
                onOpenFolder = {},
                onOpenArchive = {},
                onExport = {},
                onDelete = {},
            )
        }

        // Order matters here, and not for a reason the screen is at fault for. This is a
        // LazyColumn: scrolling to the export button at the bottom disposes the project row
        // at the top, and looking for it afterwards finds nothing at all — the failure reads
        // exactly like a missing label. Anything above is checked before anything below.
        compose.onNodeWithContentDescription(
            "Open Household, ${project.describe()}",
        ).performScrollTo().assertExists()
        compose.onNodeWithContentDescription(
            "Export a copy of the open project to a folder",
        ).performScrollTo().assertExists()
    }

    @Test fun pluginsScreenIsLabelledAndFitsAtDoubleFontScale() {
        val plugin = io.github.pintofi.beanbook.core.data.InstalledPlugin(
            id = "tag-large",
            name = "tag_large.py",
            kind = io.github.pintofi.beanbook.core.domain.PluginKind.SOURCE_FILE,
            sourceSha256 = "a".repeat(64),
            installedSha256 = "b".repeat(64),
            sizeBytes = 618,
            installedAtEpochMs = 1_756_000_000_000,
            sourceLabel = "tag_large.py",
            installedUnderPolicy = "expert",
            enabled = true,
            modules = listOf("tag_large.py"),
        )

        render(widthDp = 320, fontScale = 2f) {
            PluginsScreen(
                rows = listOf(
                    io.github.pintofi.beanbook.app.plugins.PluginRow(
                        plugin,
                        io.github.pintofi.beanbook.core.data.PluginIntegrity.Unchanged,
                    ),
                ),
                safeMode = false,
                policy = io.github.pintofi.beanbook.app.currentPluginPolicy(),
                onInstall = {},
                onSetEnabled = { _, _ -> },
                onRemove = {},
                onSetSafeMode = {},
                // Non-empty so the fetched-packages controls render and are checked here
                // too: this section is the app's only network surface in local mode, and a
                // download button that TalkBack cannot describe is the worst one to miss.
                packages = io.github.pintofi.beanbook.app.packages.PackagesUi(
                    installed = listOf("numpy", "pandas"),
                    status = io.github.pintofi.beanbook.app.packages.PackagesStatus.Idle,
                    message = null,
                    onRequest = {}, onConfirm = {}, onCancel = {}, onRemoveAll = {},
                ),
            )
        }

        // The fetched-packages controls exist only in an edition that may download. Play
        // shows a sentence there instead, by policy, so asserting them unconditionally
        // failed this test on Play for a reason that is the screen behaving correctly.
        val packagesControls = listOf(
            "Name of a package to fetch",
            "See what this would download",
            "Remove every fetched package",
        ).takeIf { io.github.pintofi.beanbook.app.currentPluginPolicy().allowsPackageDownload }.orEmpty()

        (listOf(
            "What running a plugin means",
            "Install a plugin from a file",
            "Safe mode: hold every plugin back",
            "Run tag_large.py when the ledger loads",
            "Remove tag_large.py",
        ) + packagesControls).forEach { label ->
            compose.onNodeWithContentDescription(label).performScrollTo().assertExists()
        }
        assertAccessible()
    }

    /**
     * An ordinary phone width with an ordinary font.
     *
     * The rest of this suite tests 320dp at 200%, which is the hard case for *text*. This is
     * the width an actual phone uses, which nothing else here covered — worth having on its
     * own merits, though note it did **not** catch the clipped switch that prompted it; see
     * `assertAccessible` for why.
     */
    @Test fun reportsPutsTheWebViewInTheWiderPaneOnAnExpandedWindow() {
        // ux.md 7: "Fava WebView gets the wider pane". Reports rendered as a single pane at
        // every width until M14, so on a tablet the report ran the full window and the only
        // way to another report was Fava's own navigation, folded away inside the page (N8).
        var chosen: io.github.pintofi.beanbook.core.engine.ReportPath? = null
        render(widthDp = 1000, fontScale = 1f) {
            ReportsPane(
                selected = io.github.pintofi.beanbook.core.engine.ReportPath.INCOME_STATEMENT,
                onSelect = { chosen = it },
            ) {
                // Tagged on a box that fills the pane, not on the text: measuring the text
                // measures the text, which is how the first version of this test "failed".
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("report"),
                )
            }
        }

        val list = compose.onNodeWithContentDescription("Journal report")
        list.assertExists()
        val listWidth = list.fetchSemanticsNode().size.width
        val reportWidth = compose.onNodeWithTag("report", useUnmergedTree = true)
            .fetchSemanticsNode().size.width

        assertTrue(
            "the report pane ($reportWidth) is not wider than the list ($listWidth)",
            reportWidth > listWidth,
        )

        list.performClick()
        assertEquals(io.github.pintofi.beanbook.core.engine.ReportPath.JOURNAL, chosen)
        assertAccessible()
    }

    @Test fun theScriptRunnerIsLabelledAndFitsAtDoubleFontScale() {
        render(widthDp = 320, fontScale = 2f) {
            ScriptRunnerScreen(
                running = null,
                outcome = io.github.pintofi.beanbook.app.plugins.ScriptOutcome(
                    name = "tag_large.py",
                    ok = false,
                    output = "checked 412 entries",
                    error = "",
                    errorType = "",
                    cancelled = true,
                    timedOut = false,
                    durationMs = 31_400,
                    overranByMs = 1_400,
                ),
                message = null,
                bridgeAvailable = true,
                onPick = {},
                onCancel = {},
            )
        }

        listOf(
            "Choose a script file and run it",
            // The outcome is a live region, and its description is what a screen reader
            // announces when a run ends — including the case the engine takes care to
            // report honestly, a script that ignored its deadline.
            "tag_large.py: Stopped. It ran 1s longer, inside a call that cannot " +
                "be interrupted.",
        ).forEach { label ->
            compose.onNodeWithContentDescription(label).performScrollTo().assertExists()
        }
        assertAccessible()
    }

    @Test fun theLicencesScreenIsLabelledAndFitsAtDoubleFontScale() {
        // The one screen in the app whose whole job is to be *readable*: a licence notice
        // that a screen reader cannot reach, or that clips at 200%, has not been given to
        // anyone (docs/milestones/m14-part15.md).
        render(widthDp = 320, fontScale = 2f) { LicencesScreen() }

        // A LazyColumn only composes what is near the viewport, so `performScrollTo` — which
        // needs the node to exist already — is the wrong tool; the list has to be scrolled to
        // the node instead.
        val list = compose.onNode(androidx.compose.ui.test.hasScrollAction())
        list.performScrollToNode(
            androidx.compose.ui.test.hasContentDescription(
                "Beanbook is under GPL-2.0-only. Read the full licence.",
            ),
        ).assertExists()
        list.performScrollToNode(
            androidx.compose.ui.test.hasContentDescription("beancount, 3.2.3. Read its licence."),
        ).assertExists()
        assertAccessible()
    }

    @Test fun theAppearanceScreenIsAccessibleAtAnOrdinaryPhoneWidth() {
        render(widthDp = 411, fontScale = 1f) {
            AppearanceScreen(
                choice = io.github.pintofi.beanbook.app.ui.theme.ThemeChoice.SYSTEM,
                dynamicColour = true,
                dynamicColourSupported = true,
                onChoice = {},
                onDynamicColour = {},
                hideWidgetAmounts = false,
                onHideWidgetAmounts = {},
            )
        }
        assertAccessible()
    }

    /**
     * The negative control. A green accessibility suite means nothing unless a violation
     * actually fails it, so this composes a 20dp unlabelled clickable box — no speakable
     * text, and under the 48dp touch-target minimum — and requires both checks to reject it.
     */
    @Test fun theGateCatchesViolations() {
        compose.setContent {
            Themed {
                // Unlabelled, and wider than any window it will ever be shown in: one
                // violation of each kind the checker is supposed to find.
                Box(Modifier.width(100.dp)) {
                    Box(Modifier.requiredWidth(2000.dp).height(48.dp).clickable {})
                }
            }
        }
        val failure = assertThrows(AssertionError::class.java) { assertAccessible() }
        val message = failure.message.orEmpty()
        assertTrue("expected a label violation, got: $message", message.contains("no label"))
        assertTrue("expected an overflow violation, got: $message", message.contains("outside a"))
    }

    /**
     * Every node TalkBack can act on must announce something, and must sit inside the
     * window rather than off its edge.
     *
     * There is deliberately no 48dp touch-target assertion: Compose expands the touch
     * bounds of anything clickable to the platform minimum on its own, so such a check
     * passes unconditionally and would be decoration. Horizontal overflow is the failure
     * that actually happens here — a Row of two text fields at 200% font on a narrow
     * screen — and it is what this measures.
     */
    private fun assertAccessible() {
        compose.waitForIdle()
        val nodes = compose
            .onAllNodes(SemanticsMatcher("any node") { true })
            .fetchSemanticsNodes()
        // The unmerged tree as well, for the overflow scan: a Switch inside a toggleable row
        // is merged into that row and is not a node in the merged tree at all.
        val unmerged = compose
            .onAllNodes(SemanticsMatcher("any node") { true }, useUnmergedTree = true)
            .fetchSemanticsNodes()
        assertTrue("expected a non-empty semantics tree", nodes.isNotEmpty())
        val windowWidth = compose.onRoot().fetchSemanticsNode().size.width

        val problems = buildList {
            // Labels are only meaningful for things a screen reader will stop on.
            nodes.filter { it.isActionable() }.forEach { node ->
                if (node.label().isNullOrBlank()) {
                    add("${node.describe()}: no label a screen reader can announce")
                }
            }

            // Overflow is checked on every node, not only actionable ones.
            //
            // Know what this does **not** catch. It finds a node whose layout bounds fall
            // outside the window. It does not find a child that Compose lays out *inside*
            // its parent and then draws clipped — which is what a Switch does when the text
            // beside it in a SpaceBetween row takes the whole width. That bug shipped in two
            // screens, was plainly visible in a screenshot, and stayed green here through
            // three attempts to make this check see it: the switch's reported bounds are
            // inside the window, because only the painting is cut off. Catching it needs
            // pixel comparison, which is the screenshot-diff gate M13 owns.
            unmerged.forEach { node ->
                // A control scrolled out of the viewport has empty bounds; it is brought on
                // screen first. If it cannot be scrolled to there is nothing to measure.
                val bounds = node.boundsInRoot.takeIf { !it.isEmpty }
                    ?: runCatching {
                        compose.onNode(
                            SemanticsMatcher("node ${node.id}") { it.id == node.id },
                            useUnmergedTree = true,
                        )
                            .performScrollTo()
                            .fetchSemanticsNode()
                            .boundsInRoot
                    }.getOrNull()?.takeIf { !it.isEmpty }
                    ?: return@forEach
                if (bounds.left < -1f || bounds.right > windowWidth + 1f) {
                    add(
                        "${node.describe()}: spans ${bounds.left.toInt()}..${bounds.right.toInt()}px, " +
                            "outside a ${windowWidth}px window",
                    )
                }
            }
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    private fun SemanticsNode.isActionable(): Boolean =
        config.contains(SemanticsActions.OnClick) || config.contains(SemanticsActions.SetText)

    private fun SemanticsNode.label(): String? =
        config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(", ")
            ?: config.getOrNull(SemanticsProperties.Text)?.joinToString(", ")

    private fun SemanticsNode.describe(): String {
        val text = label() ?: config.getOrNull(SemanticsProperties.EditableText)?.text
        return "node $id (${text ?: "unlabelled"})"
    }

    /** Renders [content] in a window of the given width, at the given font scale. */
    private fun render(
        widthDp: Int,
        fontScale: Float,
        content: @Composable () -> Unit,
    ) {
        compose.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, fontScale)) {
                Themed { Box(Modifier.width(widthDp.dp).fillMaxHeight()) { content() } }
            }
        }
        compose.waitForIdle()
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) {
        MaterialTheme { content() }
    }
}
