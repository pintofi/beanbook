package dev.beancount.app.ui

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
import dev.beancount.core.domain.CapturedPages
import dev.beancount.core.domain.LedgerVocabulary
import java.time.LocalDate
import org.junit.Assert.assertThrows
import org.junit.Assert.assertEquals
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
        val account = compose.onNodeWithText("Account 1").fetchSemanticsNode().boundsInRoot
        val amount = compose.onNodeWithText("Amount").fetchSemanticsNode().boundsInRoot
        assertTrue(
            "account ($account) and amount ($amount) are still side by side",
            amount.top >= account.bottom - 1f,
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
        compose.onNodeWithText("Choose folder").performScrollTo().assertExists()
        compose.onNodeWithText("Choose .zip").performScrollTo().assertExists()
        compose.onNodeWithText("Create").performScrollTo().assertExists()
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
                pages = CapturedPages().add("page-1.jpg").add("page-2.jpg"),
                documents = listOf("documents/Assets/Cash/2026-08-24.abcdef123456.pdf"),
                vocabulary = vocabulary,
                onCapture = { _, _ -> },
                captureFile = { java.io.File("unused") },
                onRotate = {},
                onMove = { _, _ -> },
                onRemovePage = {},
                onSave = {},
                onDiscard = {},
                onUnlink = {},
                onDeleteFile = {},
                onShare = {},
                hasCamera = false,
            )
        }

        listOf(
            "Rotate page 1",
            "Move page 2 up",
            "Remove page 1",
            "Share documents/Assets/Cash/2026-08-24.abcdef123456.pdf",
            "Unlink documents/Assets/Cash/2026-08-24.abcdef123456.pdf from its entry",
            "Delete the file documents/Assets/Cash/2026-08-24.abcdef123456.pdf",
        ).forEach { label ->
            compose.onNodeWithContentDescription(label).performScrollTo().assertExists()
        }
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
    @Test fun projectPickerIsLabelledAndFitsAtDoubleFontScale() {
        val project = dev.beancount.core.data.ProjectSummary(
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
        val plugin = dev.beancount.core.data.InstalledPlugin(
            id = "tag-large",
            name = "tag_large.py",
            kind = dev.beancount.core.domain.PluginKind.SOURCE_FILE,
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
                    dev.beancount.app.plugins.PluginRow(
                        plugin,
                        dev.beancount.core.data.PluginIntegrity.Unchanged,
                    ),
                ),
                safeMode = false,
                policy = dev.beancount.app.currentPluginPolicy(),
                onInstall = {},
                onSetEnabled = { _, _ -> },
                onRemove = {},
                onSetSafeMode = {},
                // Non-empty so the fetched-packages controls render and are checked here
                // too: this section is the app's only network surface in local mode, and a
                // download button that TalkBack cannot describe is the worst one to miss.
                packages = dev.beancount.app.packages.PackagesUi(
                    installed = listOf("numpy", "pandas"),
                    status = dev.beancount.app.packages.PackagesStatus.Idle,
                    message = null,
                    onRequest = {}, onConfirm = {}, onCancel = {}, onRemoveAll = {},
                ),
            )
        }

        listOf(
            "What running a plugin means",
            "Install a plugin from a file",
            "Safe mode: hold every plugin back",
            "Run tag_large.py when the ledger loads",
            "Remove tag_large.py",
            "Name of a package to fetch",
            "See what this would download",
            "Remove every fetched package",
        ).forEach { label ->
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
        var chosen: dev.beancount.core.engine.ReportPath? = null
        render(widthDp = 1000, fontScale = 1f) {
            ReportsPane(
                selected = dev.beancount.core.engine.ReportPath.INCOME_STATEMENT,
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
        assertEquals(dev.beancount.core.engine.ReportPath.JOURNAL, chosen)
        assertAccessible()
    }

    @Test fun theScriptRunnerIsLabelledAndFitsAtDoubleFontScale() {
        render(widthDp = 320, fontScale = 2f) {
            ScriptRunnerScreen(
                running = null,
                outcome = dev.beancount.app.plugins.ScriptOutcome(
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
            "tag_large.py: Stopped — but it kept running for 1s after being asked, " +
                "because it was inside a call that cannot be interrupted.",
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
                "Beancount for Android is under GPL-2.0-only. Read the full licence.",
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
                choice = dev.beancount.app.ui.theme.ThemeChoice.SYSTEM,
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
