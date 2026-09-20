package io.github.pintofi.beanbook.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.exclude
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.AccountBalance
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Tab
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.app.ui.EditableFile
import io.github.pintofi.beanbook.core.data.MirrorAction
import io.github.pintofi.beanbook.app.ui.DocumentCaptureHandle
import io.github.pintofi.beanbook.app.ui.ScannerScreen
import io.github.pintofi.beanbook.core.data.RestoreStaged
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.DirectiveDraft
import io.github.pintofi.beanbook.core.domain.DocumentRow
import java.io.File as JavaFile
import java.time.LocalDate
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.core.data.ProjectSummary
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import io.github.pintofi.beanbook.app.web.AllowlistWebViewClient
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.engine.EngineStatus
import androidx.compose.foundation.background
// The app shell: the destinations, and the scaffold that holds them.
//
// Split out of MainActivity.kt, which had grown to 2088 lines holding an activity and every
// composable in the app. Nothing moved here changed except visibility: a top-level `private`
// is file-scoped in Kotlin, so what these files share had to become `internal`.
/** The sections of the More hub. Projects first: it is the one people look for. */
internal enum class MoreSection(val label: String) {
    PROJECTS("Projects"),
    FILES("Files"),
    DOCUMENTS("Documents"),
    BACKUP("Backup"),
    MIRROR("Mirror"),
    REMOTE("Remote"),
    PLUGINS("Plugins"),
    APPEARANCE("Appearance"),
    SCRIPTS("Scripts"),
    LICENCES("Licences"),
    DIAGNOSTICS("Diagnostics"),
    ABOUT("About"),
}

/**
 * Everything the Reports surface needs to show a *remote* Fava instead of the embedded one.
 *
 * Non-null exactly when a remote server is connected, so the Reports tab does not have to
 * reason about connection state — if this is here, remote is what the user is looking at.
 */
class RemoteReports(
    val url: String,
    val origins: Set<String>,
    val auth: () -> AllowlistWebViewClient.HttpAuth?,
)


/**
 * A destination's icon, with the rule that marks the one you are on (B74).
 *
 * The rule grows from the centre rather than fading in: it is the same gesture as the column
 * rule drawing down a surface, at the size of a tab, so moving between destinations reads as
 * the same app doing the same thing. Material's filled pill is replaced rather than recoloured
 * -- a lozenge under a column of figures is the loudest thing on the screen, and this design
 * marks things with rules.
 */
@Composable
private fun DestinationMark(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    selected: Boolean,
) {
    val colour = io.github.pintofi.beanbook.app.ui.theme.BeancountTheme.ledger.rule
    val animate = io.github.pintofi.beanbook.app.ui.theme.rememberAnimationsEnabled()
    val extent by androidx.compose.animation.core.animateFloatAsState(
        targetValue = if (selected) 1f else 0f,
        animationSpec = if (animate) {
            io.github.pintofi.beanbook.app.ui.theme.Motion.standard(
                io.github.pintofi.beanbook.app.ui.theme.Motion.QUICK_MS,
            )
        } else {
            androidx.compose.animation.core.snap()
        },
        label = "destination",
    )
    Column(horizontalAlignment = androidx.compose.ui.Alignment.CenterHorizontally) {
        androidx.compose.foundation.Canvas(
            modifier = Modifier
                .width(20.dp)
                .height(2.dp)
                .clearAndSetSemantics { },
        ) {
            if (extent > 0f) {
                drawRect(
                    color = colour,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        x = size.width * (1f - extent) / 2f,
                        y = 0f,
                    ),
                    size = androidx.compose.ui.geometry.Size(size.width * extent, size.height),
                )
            }
        }
        androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
        Icon(icon, contentDescription = null)
    }
}

/**
 * Whether the add button belongs on screen.
 *
 * **This takes no layout, and that is the point.** The condition once included
 * `layout == PaneLayout.COMPACT`, copied from the bottom bar without thinking, and since Add
 * had stopped being a destination when it became this button, gating the button by screen
 * width left a tablet with no way to add an entry at all. Every test passed: they render
 * composables at one width, and none of them asks whether the app can reach its own primary
 * action.
 *
 * Keeping the decision here, with no width to consult, is what stops that being written
 * again — a test would only catch it afterwards.
 *
 * Add appears on the surfaces that show the ledger. Not on More, where it covered a row of
 * the list; not on Reports, which is a Fava page whose layout is not ours to float things
 * over; not while the form is already open, where a button that opens it is noise; and not
 * before there is a ledger to add to.
 */
internal fun showsAddAction(selected: Surface, adding: Boolean, hasLedger: Boolean): Boolean =
    !adding &&
        hasLedger &&
        selected in setOf(Surface.HOME, Surface.JOURNAL, Surface.BALANCES)

/**
 * The places the app can be. Adding an entry is deliberately not one of them: it is an
 * action, and Material puts an action of the highest importance in a FAB rather than in the
 * navigation bar, which is for destinations. Removing it is also what brings the bar to the
 * five destinations Material asks for (B26).
 */
internal enum class Surface(val label: String, val icon: androidx.compose.ui.graphics.vector.ImageVector) {
    HOME("Home", Icons.Outlined.Home),
    JOURNAL("Journal", Icons.AutoMirrored.Outlined.List),
    BALANCES("Balances", Icons.Outlined.AccountBalance),
    REPORTS("Reports", Icons.Outlined.BarChart),
    // The hub docs/ux.md 2 asks for: documents now, backup and settings as they arrive.
    // Projects lives here rather than in the bar so six destinations still fit a phone.
    MORE("More", Icons.Outlined.MoreHoriz),
}

/**
 * How one place in the app becomes another (B76).
 *
 * Three cases, in the order they take precedence. Opening or closing the entry form is
 * hierarchical whatever else changes, because the form is a thing on top of a destination.
 * Entering or leaving a More section is hierarchical too. Everything else is a move between
 * peers, and its direction is which way along the bar the destination sits -- so Home to
 * Reports travels the same way as Home to Journal, only further.
 */
internal fun surfaceMove(
    from: Triple<Surface, Boolean, MoreSection?>,
    to: Triple<Surface, Boolean, MoreSection?>,
): io.github.pintofi.beanbook.app.ui.Move {
    val (fromSurface, fromAdding, fromSection) = from
    val (toSurface, toAdding, toSection) = to
    return when {
        // The form comes out of the button that opened it and goes back into it.
        fromAdding != toAdding ->
            if (toAdding) {
                io.github.pintofi.beanbook.app.ui.Move.outOf(io.github.pintofi.beanbook.app.ui.Move.action)
            } else {
                io.github.pintofi.beanbook.app.ui.Move.backInto(io.github.pintofi.beanbook.app.ui.Move.action)
            }

        fromSection != toSection && (fromSection == null || toSection == null) ->
            if (toSection != null) {
                io.github.pintofi.beanbook.app.ui.Move.deeper()
            } else {
                io.github.pintofi.beanbook.app.ui.Move.back()
            }

        // Reports is Fava's own page in a WebView. Sliding a browser window costs a
        // full-screen layer for every frame of the transition and says nothing the
        // destination bar has not already said, so that one move is a cut.
        fromSurface == Surface.REPORTS || toSurface == Surface.REPORTS ->
            io.github.pintofi.beanbook.app.ui.Move.instant

        fromSurface != toSurface ->
            io.github.pintofi.beanbook.app.ui.Move.peer(forward = toSurface.ordinal > fromSurface.ordinal)

        // One More section replacing another, or nothing anyone can see: no axis, because
        // nothing about the two says which is to the left of the other.
        else -> io.github.pintofi.beanbook.app.ui.Move.none
    }
}

@Composable
internal fun LedgerApp(
    engineStatus: EngineStatus,
    ledger: LedgerSnapshot?,
    token: String?,
    /** The surface a launching widget asked for, if any (B2). */
    openOn: String? = null,
    debuggingAllowed: Boolean,
    onOpenExternally: (Uri) -> Unit,
    onSaveEntry: (TransactionDraft) -> Unit,
    onEditEntry: (JournalEntry) -> Unit = {},
    onDeleteEntry: (JournalEntry) -> Unit = {},
    templates: List<EntryTemplate>,
    onSaveTemplate: (EntryTemplate) -> Unit,
    onDeleteTemplate: (String) -> Unit,
    documentCapture: DocumentCaptureHandle,
    documents: List<DocumentRow>,
    documentMessage: String?,
    onSaveDocument: (account: String, label: String?) -> Unit,
    onDiscardPages: () -> Unit,
    onRefileDocument: (String, String, String?) -> Unit,
    onUnlinkDocument: (String, String?) -> Unit,
    onDeleteDocumentFile: (String) -> Unit,
    onShareDocument: (String) -> Unit,
    diagnostics: io.github.pintofi.beanbook.app.ui.DiagnosticsUi,
    fileForDocument: (String) -> java.io.File,
    pendingRestore: RestoreStaged.Ready?,
    passphraseNeeded: Boolean,
    onUnlockRestore: (CharArray) -> Unit,
    onBackUpNowWithPassphrase: (CharArray?) -> Unit,
    backupMessage: String?,
    scheduleEnabled: Boolean,
    scheduleFolder: String?,
    lastBackupRun: String?,
    rollbackPoints: List<String>,
    onChooseRestoreFile: () -> Unit,
    onAcceptRestore: () -> Unit,
    onDiscardRestore: () -> Unit,
    onChooseScheduleFolder: () -> Unit,
    onScheduleChanged: (Boolean) -> Unit,
    mirrorEnabled: Boolean,
    mirrorFolder: String?,
    mirrorConflicts: List<MirrorAction.Conflict>,
    mirrorStatus: io.github.pintofi.beanbook.app.mirror.MirrorStatus,
    mirrorMessage: String?,
    onChooseMirrorFolder: () -> Unit,
    onMirrorEnabledChanged: (Boolean) -> Unit,
    onMirrorNow: () -> Unit,
    onKeepMine: (String) -> Unit,
    onKeepTheirs: (String) -> Unit,
    remoteStatus: EngineStatus,
    remoteCapabilities: io.github.pintofi.beanbook.engine.remote.ServerCapabilities?,
    savedRemoteAddress: String?,
    savedRemoteUsername: String?,
    remotePendingRetry: Boolean,
    remoteMessage: String?,
    remoteReports: RemoteReports?,
    themeChoice: io.github.pintofi.beanbook.app.ui.theme.ThemeChoice,
    dynamicColour: Boolean,
    onThemeChoice: (io.github.pintofi.beanbook.app.ui.theme.ThemeChoice) -> Unit,
    onDynamicColour: (Boolean) -> Unit,
    hideWidgetAmounts: Boolean,
    onHideWidgetAmounts: (Boolean) -> Unit,
    pluginRows: List<io.github.pintofi.beanbook.app.plugins.PluginRow>,
    pluginSafeMode: Boolean,
    pluginMessage: String?,
    packages: io.github.pintofi.beanbook.app.packages.PackagesUi,
    /** The period Balances is showing, and the way to change it (B53). */
    dateFrom: String,
    dateTo: String,
    onDateRange: (String, String) -> Unit,
    onInstallPlugin: () -> Unit,
    scriptRunning: String?,
    scriptOutcome: io.github.pintofi.beanbook.app.plugins.ScriptOutcome?,
    scriptMessage: String?,
    onPickScript: () -> Unit,
    savedScripts: List<io.github.pintofi.beanbook.app.plugins.SavedScript>,
    onRunSavedScript: (String) -> Unit,
    onForgetSavedScript: (String) -> Unit,
    onCancelScript: () -> Unit,
    onSetPluginEnabled: (String, Boolean) -> Unit,
    onRemovePlugin: (String) -> Unit,
    onSetPluginSafeMode: (Boolean) -> Unit,
    /** Modules the open ledger names, and the way to add or remove one (B60). */
    recoveryNotice: String?,
    onDismissRecovery: () -> Unit,
    onRemoteConnect: (String, String?, CharArray?) -> Unit,
    onRemoteDisconnect: () -> Unit,
    onRemoteForget: () -> Unit,
    onRemoteRetry: () -> Unit,
    onRemoteDiscard: () -> Unit,
    onSaveDirective: (io.github.pintofi.beanbook.core.domain.DirectiveDraft) -> Unit,
    saveState: String?,
    /** Entries committed from the form this session; a change closes the form (B77). */
    entryCommits: Int = 0,
    /** An entry already in the file, open in the entry form (B79); null when none is. */
    editSession: EntryEditSession? = null,
    /** Ends the engine and the app and opens it again (B77). */
    onRestartApp: () -> Unit = {},
    onEngineRetry: () -> Unit = {},
    onDeleteEntries: (List<io.github.pintofi.beanbook.core.domain.JournalEntry>) -> Unit = {},
    onEngineUnreachable: () -> Unit = {},
    projects: List<ProjectSummary>,
    activeProjectId: String?,
    projectMessage: String?,
    onOpenProject: (String) -> Unit,
    onCreateProject: (String) -> Unit,
    onExportProject: () -> Unit,
    onImportFolder: () -> Unit,
    onImportArchive: () -> Unit,
    onDeleteProject: (String) -> Unit,
    editorState: io.github.pintofi.beanbook.app.ui.EditorState,
    editorMessage: String?,
    editorFiles: List<EditableFile>,
    onOpenFile: (EditableFile) -> Unit,
    onCloseFile: () -> Unit,
    onEditorTextChanged: (String) -> Unit,
    onSaveFile: () -> Unit,
    onDiscardDraft: () -> Unit,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Seeded from the widget that launched the app, when one did. Keyed on `openOn` so a
    // second widget tap while the app is already alive still lands where it asked.
    var selected by remember(openOn) {
        mutableStateOf(Surface.entries.firstOrNull { it.name == openOn } ?: Surface.HOME)
    }
    // The add form, shown over whichever destination is open. The widget's Quick Add tile
    // still asks for "ADD" by name, and that has to keep working now that it is no longer a
    // destination -- landing on Home instead of the form would be a silent regression.
    var adding by remember(openOn) { mutableStateOf(openOn == "ADD") }

    // Whether anything has been typed into the open form, and what to do once the user has
    // decided (B61). Leaving a half-written entry by tapping a destination is one tap away
    // now that the bar works while the form is open, so it asks first -- nothing is written
    // to the ledger either way, but retyping an entry is the user's time.
    var addDirty by remember { mutableStateOf(false) }
    var confirmingLeave by remember { mutableStateOf<(() -> Unit)?>(null) }

    /** Leaves the entry form, asking first when there is something to lose. */
    fun leavingAdd(then: () -> Unit) {
        if (adding && addDirty) confirmingLeave = then else then()
    }

    // A save that committed closes the form and shows the entry where it now lives (B77). The
    // form stayed open after every save and still counted as holding something, so closing
    // it asked whether to discard an entry that was already in the file.
    //
    // Keyed on the count of commits, never on the message: "Saving…" and a refusal both change
    // the message, and neither is a reason to leave. The only wait is the signature sweep over
    // the lines just written -- the write is already durable and re-parsed by the time this
    // runs, so the pause is choreography after the fact, not a guess about I/O -- and there is
    // no wait at all when the user has turned animation off.
    val motion = io.github.pintofi.beanbook.app.ui.theme.LocalMotionEnabled.current
    var seenCommits by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(entryCommits) }
    androidx.compose.runtime.LaunchedEffect(entryCommits) {
        if (entryCommits == seenCommits) return@LaunchedEffect
        seenCommits = entryCommits
        if (!adding) return@LaunchedEffect
        if (motion) {
            kotlinx.coroutines.delay(io.github.pintofi.beanbook.app.ui.theme.Motion.SAVE_ACKNOWLEDGE_MS.toLong())
        }
        addDirty = false
        confirmingLeave = null
        adding = false
        selected = Surface.JOURNAL
    }

    // Editing opens the entry form, the same one Add uses (B79), and leaving the form -- by
    // saving, discarding, Back or a destination -- ends the edit.
    var editOpen by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(editSession?.editing?.original) {
        if (editSession != null) {
            adding = true
            editOpen = true
        } else if (editOpen) {
            // The edit ended somewhere else -- "Edit as text instead" -- so the form goes too.
            editOpen = false
            adding = false
        }
    }
    androidx.compose.runtime.LaunchedEffect(adding) {
        if (!adding) editSession?.onClose?.invoke()
    }

    // An entry being entered again (B11). Held here because applying it means switching to
    // the Add surface, which this composable owns; cleared once the form has taken it.
    var pendingClone by remember { mutableStateOf<TransactionDraft?>(null) }

    // Where the user stands inside More: empty is the list of sections, and each further
    // entry is a section opened from the one before it -- About, then Licences from About.
    // A stack rather than one section, so Back goes up one page instead of all the way out,
    // and so tapping the More tab can put the user back at its list.
    var moreStack by remember { mutableStateOf<List<MoreSection>>(emptyList()) }
    val moreSection = moreStack.lastOrNull()
    // Opening the section already open would stack it twice; `null` closes the top one.
    fun openSection(section: MoreSection?) {
        moreStack = when {
            section == null -> moreStack.dropLast(1)
            moreStack.lastOrNull() == section -> moreStack
            else -> moreStack + section
        }
    }
    // A destination tap goes to that destination's own root. Leaving More and coming back to
    // a section opened minutes ago is not where anybody means to arrive (B92).
    fun goTo(surface: Surface) {
        adding = false
        moreStack = emptyList()
        selected = surface
    }

    // Back walks out the way the user came in, rather than leaving the app from wherever
    // they happen to be standing (B8). One handler over one stack: this was four handlers
    // whose `enabled` conditions each repeated every condition above them, so opening a
    // fifth thing meant editing the other four -- and forgetting one silently broke Back,
    // which is what happened when the add form became a button.
    //
    // Disabled once the stack has nothing left, so the system takes Back and the app closes
    // from Home. That is what the platform expects of a top-level destination, and what
    // predictive back animates against.
    val stack = Backstack(
        tab = selected,
        overlays = buildList {
            moreStack.forEach { add(Overlay.Section(it)) }
            if (adding) add(Overlay.AddEntry)
        },
    )
    BackHandler(enabled = stack.back() != null) {
        leavingAdd {
            stack.back()?.let { previous ->
                adding = previous.isAdding
                moreStack = previous.overlays.filterIsInstance<Overlay.Section>().map { it.section }
                selected = previous.tab
            }
        }
    }

    // Breakpoints from docs/ux.md §7. Measured from the window, not the device: a phone in
    // landscape, a foldable half-open and a freeform window are all "how wide is the window
    // right now", and asking the device instead gets every one of them wrong.
    val windowWidth = LocalConfiguration.current.screenWidthDp
    val layout = when {
        windowWidth < 600 -> PaneLayout.COMPACT
        windowWidth < 840 -> PaneLayout.MEDIUM
        else -> PaneLayout.EXPANDED
    }

    // Scanning takes the whole window, above the rail, the bars and the navigation. It is a
    // mode rather than a destination: you enter it, shoot a stack of pages, and leave, and
    // chrome around the viewfinder would make it look like a form with a camera in it.
    //
    // Drawn *over* the app rather than instead of it (B79). Returning early took the entry
    // form out of composition, and everything typed into it went with it: fill in a payee
    // and two postings, scan the receipt, and come back to an empty form holding one page.
    // Underneath, the app stays composed and keeps its state; it is hidden from accessibility
    // services while the scanner is up, so a screen reader does not wander into a form the
    // camera is covering.
    val scanning = documentCapture.scanning
    Box(modifier = modifier) {
    Row(
        modifier = Modifier
            .fillMaxSize()
            .then(if (scanning) Modifier.clearAndSetSemantics {} else Modifier),
    ) {
        if (layout != PaneLayout.COMPACT) {
            LedgerNavigationRail(selected = selected, onSelect = { selected = it })
        }

    // Whether the open ledger went from errors to none while the app was running (B87), for
    // the stamp in the top bar. Watched here, above the destinations,, because the last error is usually fixed
    // somewhere else -- the file editor, a plugin install -- and Home is not on screen then.
    // Not in SurfaceContent: each destination gets its own, so leaving Home forgot what it had
    // seen. Per project: opening a clean ledger after a broken one is not fixing anything.
    // Not keyed on the project: while the engine restarts -- after a plugin install, the very
    // moment this is for -- there is briefly no project and no ledger, and a keyed state would
    // forget the errors it had seen. Gaps are skipped; only a different project resets it.
    var watchedProject by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<String?>(null) }
    var lastErrors by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf<Int?>(null) }
    var balancedAgain by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    val errorCount = ledger?.errors?.size
    androidx.compose.runtime.LaunchedEffect(activeProjectId, errorCount) {
        val project = activeProjectId ?: return@LaunchedEffect
        if (errorCount == null) return@LaunchedEffect
        if (project != watchedProject) {
            watchedProject = project
            lastErrors = errorCount
            balancedAgain = false
            return@LaunchedEffect
        }
        val before = lastErrors
        if (before != null && before > 0 && errorCount == 0) balancedAgain = true
        if (errorCount > 0) balancedAgain = false
        lastErrors = errorCount
    }
    // Shown on Home, in the line that says nothing needs attention (B87): it takes no row of its
    // own and covers no figure. Leaving Home, opening the form or a new error takes it away.
    var stampShowing by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(balancedAgain, selected, adding, errorCount) {
        val onHome = selected == Surface.HOME && !adding
        if (balancedAgain && onHome && errorCount == 0) {
            stampShowing = true
            balancedAgain = false
        }
        if (!onHome || (errorCount ?: 0) > 0) stampShowing = false
    }
    Scaffold(
        // The keyboard is handled once, here, for every screen inside (B54). Each form used
        // to apply `imePadding()` itself while the Scaffold went on reserving space for a
        // bottom bar the keyboard had covered, so the inset was counted twice: a band of
        // empty screen the height of the navigation bar sat above the keyboard, and the last
        // field of a form could not be scrolled into it. Insetting the whole Scaffold lifts
        // the bar and the content together, which is what Material describes.
        modifier = Modifier.weight(1f).keyboardPadding(),
        // The keyboard's height is applied once, by `imePadding` above (B68). `Scaffold`'s
        // default content insets are `safeDrawing`, which *includes* the IME, so the content
        // was being pushed up by the keyboard twice: once by the modifier and again by the
        // padding the Scaffold handed its content. The second copy is the blank band that
        // appeared under the form -- roughly the height of the keyboard, left behind when the
        // keyboard went away and the animation settled.
        contentWindowInsets = ScaffoldDefaults.contentWindowInsets.exclude(WindowInsets.ime),
        topBar = {
            LedgerTopBar(
                projectName = projects.firstOrNull { it.id == activeProjectId }?.title
                    ?: ledger?.title
                    ?: "Beanbook",
                health = ledgerHealth(engineStatus, ledger),
                sectionTitle = if (selected == Surface.MORE) moreSection?.label else null,
                adding = adding,
                editing = adding && editSession != null,
                onBack = { if (adding) leavingAdd { adding = false } else openSection(null) },
                onSwitchProject = {
                    goTo(Surface.MORE)
                    openSection(MoreSection.PROJECTS)
                },
            )
        },
        floatingActionButton = {
            // The button grows in and shrinks back out rather than blinking: it disappears
            // whenever the form opens, and a control that vanishes between frames reads as a
            // glitch rather than as the thing that became the page in front of you (B76).
            io.github.pintofi.beanbook.app.ui.LedgerAction(
                visible = showsAddAction(selected, adding, hasLedger = ledger != null),
            ) {
                androidx.compose.material3.FloatingActionButton(
                    onClick = { adding = true },
                ) {
                    // The description is on the icon because there is no longer a label to
                    // carry it. Without it this is an unnamed button to a screen reader.
                    Icon(Icons.Outlined.Add, contentDescription = "Add an entry")
                }
            }
        },
        bottomBar = {
            if (layout != PaneLayout.COMPACT) return@Scaffold
            // Gone while the keyboard is up (B54). A destination bar above an open keyboard
            // is a row nobody can reach without dismissing the keyboard first, and it costs
            // a form the one thing it is short of on a phone: height.
            @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
            if (androidx.compose.foundation.layout.WindowInsets.isImeVisible) return@Scaffold
            // Six text-only destinations fit across a phone at normal text size and do not
            // at 200%: a NavigationBar there wrapped the labels mid-word and clipped
            // "Projects" off the screen edge. Below 130% the bar stays as it was; above it,
            // scrolling tabs keep every label whole and every destination reachable.
            //
            // "Six" is load-bearing. A seventh was added once and "Balances" wrapped to
            // "Balance" / "s" on every screen until it was found by looking at the app --
            // the a11y gate only exercises 200%, where the scrolling path is already in use.
            // Files moved into More to get back to six. Adding a destination here means
            // checking the bar by eye at normal text size, or moving another one out.
            if (LocalDensity.current.fontScale > 1.3f) {
                PrimaryScrollableTabRow(
                    selectedTabIndex = if (adding) -1 else Surface.entries.indexOf(selected),
                    edgePadding = 0.dp,
                ) {
                    Surface.entries.forEach { surface ->
                        Tab(
                            selected = !adding && selected == surface,
                            onClick = { leavingAdd { goTo(surface) } },
                            text = { Text(surface.label, softWrap = false) },
                            modifier = Modifier.semantics { contentDescription = surface.label },
                        )
                    }
                }
            } else {
                // The footer rule (B74). The bar sits on the same paper as the page, with a
                // hairline above it and no shadow, and the selected destination is marked by
                // a short rule over its icon rather than by Material's filled pill -- the
                // same vocabulary as every other rule in the app, and quieter under a column
                // of figures than a lozenge of colour.
                Column {
                    io.github.pintofi.beanbook.app.ui.LedgerHairline()
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                        tonalElevation = 0.dp,
                    ) {
                        Surface.entries.forEach { surface ->
                            // Nothing is selected while the form is open: the form is not a
                            // destination, and a bar highlighting Home while a person stands
                            // in the entry form is telling them where they are not (B61).
                            val chosen = !adding && selected == surface
                            NavigationBarItem(
                                selected = chosen,
                                onClick = { leavingAdd { goTo(surface) } },
                                label = { Text(surface.label) },
                                icon = { DestinationMark(surface.icon, chosen) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = androidx.compose.ui.graphics.Color.Transparent,
                                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                                // No contentDescription: the label already supplies one, and
                                // setting both made TalkBack say "Reports, Reports" on every
                                // destination.
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            // The banner makes room for itself rather than appearing in place, so the page
            // under it is pushed down by something arriving instead of being covered by
            // something that was always there (B76).
            io.github.pintofi.beanbook.app.ui.LedgerReveal(
                visible = recoveryNotice != null,
                label = "recovery",
            ) {
                // Held while it collapses, so the text does not vanish a frame before the
                // space it occupied does.
                val shown = androidx.compose.runtime.remember(recoveryNotice) { recoveryNotice }
                shown?.let { notice -> RecoveryBanner(notice, onDismiss = onDismissRecovery) }
            }
            // Where the app is: the destination, whether the form is over it, and which More
            // section is open. Peer destinations slide along the horizontal axis in the
            // direction of travel; the form and a More section rise from the page and sink
            // back into it. The pair being animated between is what decides that, so an
            // interrupted transition is still going the right way.
            val reportsPage = androidx.compose.runtime.remember { io.github.pintofi.beanbook.app.web.RetainedWebView() }
            // Reports draws the same ledger as the screens around it, so a file changed on
            // disk redraws the page too rather than leaving two answers on one device (B92).
            val application = androidx.compose.ui.platform.LocalContext.current.applicationContext
            (application as? BeancountApplication)?.let { owner ->
                androidx.compose.runtime.LaunchedEffect(owner) {
                    owner.ledgerChangedOnDisk.collect { change ->
                        if (change > 0L) reportsPage.reload()
                    }
                }
            }
            androidx.compose.runtime.CompositionLocalProvider(
                io.github.pintofi.beanbook.app.web.LocalReportsPage provides reportsPage,
            ) {
            io.github.pintofi.beanbook.app.ui.LedgerContent(
                targetState = Triple(selected, adding, moreSection),
                label = "destination",
                move = { from, to -> surfaceMove(from, to) },
            ) { (shownSurface, shownAdding, shownSection) ->
            // Painted on the app's own paper. Without a background the page being left shows
            // through the page arriving for the length of the transition, which on a screen
            // of figures reads as two ledgers at once.
            androidx.compose.foundation.layout.Box(
                Modifier.background(MaterialTheme.colorScheme.background),
            ) {
            SurfaceContent(
                stamped = stampShowing,
                selected = shownSurface,
                adding = shownAdding,
                moreSection = shownSection,
                onMoreSection = { openSection(it) },
                onSelectSurface = { leavingAdd { goTo(it) } },
                twoPane = layout == PaneLayout.EXPANDED,
                documentCapture = documentCapture,
                documents = documents,
                documentMessage = documentMessage,
                onSaveDocument = onSaveDocument,
                onDiscardPages = onDiscardPages,
                onRefileDocument = onRefileDocument,
                onUnlinkDocument = onUnlinkDocument,
                onDeleteDocumentFile = onDeleteDocumentFile,
                onShareDocument = onShareDocument,
                diagnostics = diagnostics,
                fileForDocument = fileForDocument,
                pendingRestore = pendingRestore,
                passphraseNeeded = passphraseNeeded,
                onUnlockRestore = onUnlockRestore,
                onBackUpNowWithPassphrase = onBackUpNowWithPassphrase,
                backupMessage = backupMessage,
                scheduleEnabled = scheduleEnabled,
                scheduleFolder = scheduleFolder,
                lastBackupRun = lastBackupRun,
                rollbackPoints = rollbackPoints,
                onChooseRestoreFile = onChooseRestoreFile,
                onAcceptRestore = onAcceptRestore,
                onDiscardRestore = onDiscardRestore,
                onChooseScheduleFolder = onChooseScheduleFolder,
                onScheduleChanged = onScheduleChanged,
                mirrorEnabled = mirrorEnabled,
                mirrorFolder = mirrorFolder,
                mirrorConflicts = mirrorConflicts,
                mirrorStatus = mirrorStatus,
                mirrorMessage = mirrorMessage,
                onChooseMirrorFolder = onChooseMirrorFolder,
                onMirrorEnabledChanged = onMirrorEnabledChanged,
                onMirrorNow = onMirrorNow,
                onKeepMine = onKeepMine,
                onKeepTheirs = onKeepTheirs,
                remoteStatus = remoteStatus,
                remoteCapabilities = remoteCapabilities,
                savedRemoteAddress = savedRemoteAddress,
                savedRemoteUsername = savedRemoteUsername,
                remotePendingRetry = remotePendingRetry,
                remoteMessage = remoteMessage,
                remoteReports = remoteReports,
                themeChoice = themeChoice,
                dynamicColour = dynamicColour,
                onThemeChoice = onThemeChoice,
                onDynamicColour = onDynamicColour,
                hideWidgetAmounts = hideWidgetAmounts,
                prefill = pendingClone,
                onPrefillApplied = { pendingClone = null },
                onEnterAgain = { entry ->
                    pendingClone = TransactionDraft.cloneOf(entry, LocalDate.now().toString())
                    adding = true
                },
                onEditEntry = onEditEntry,
                onDeleteEntry = onDeleteEntry,
                onHideWidgetAmounts = onHideWidgetAmounts,
                pluginRows = pluginRows,
                pluginSafeMode = pluginSafeMode,
                pluginMessage = pluginMessage,
                packages = packages,
                dateFrom = dateFrom,
                dateTo = dateTo,
                onDateRange = onDateRange,
                onInstallPlugin = onInstallPlugin,
                scriptRunning = scriptRunning,
                scriptOutcome = scriptOutcome,
                scriptMessage = scriptMessage,
                onPickScript = onPickScript,
                savedScripts = savedScripts,
                onRunSavedScript = onRunSavedScript,
                onForgetSavedScript = onForgetSavedScript,
                onCancelScript = onCancelScript,
                onSetPluginEnabled = onSetPluginEnabled,
                onRemovePlugin = onRemovePlugin,
                onSetPluginSafeMode = onSetPluginSafeMode,
                onRemoteConnect = onRemoteConnect,
                onRemoteDisconnect = onRemoteDisconnect,
                onRemoteForget = onRemoteForget,
                onRemoteRetry = onRemoteRetry,
                onRemoteDiscard = onRemoteDiscard,
                onSaveEntry = onSaveEntry,
                templates = templates,
                onSaveTemplate = onSaveTemplate,
                onDeleteTemplate = onDeleteTemplate,
                onSaveDirective = onSaveDirective,
                saveState = saveState,
                entryCommits = entryCommits,
                editSession = editSession,
                projects = projects,
                activeProjectId = activeProjectId,
                projectMessage = projectMessage,
                onOpenProject = onOpenProject,
                onCreateProject = onCreateProject,
                onExportProject = onExportProject,
                onImportFolder = onImportFolder,
                onImportArchive = onImportArchive,
                onDeleteProject = onDeleteProject,
                engineStatus = engineStatus,
                ledger = ledger,
                token = token,
                debuggingAllowed = debuggingAllowed,
                onOpenExternally = onOpenExternally,
                editorState = editorState,
                editorMessage = editorMessage,
                editorFiles = editorFiles,
                onOpenFile = onOpenFile,
                onCloseFile = onCloseFile,
                onEditorTextChanged = onEditorTextChanged,
                onSaveFile = onSaveFile,
                onDiscardDraft = onDiscardDraft,
                refreshing = refreshing,
                onRefresh = onRefresh,
                onAddDirtyChange = { addDirty = it },
                onRestartApp = onRestartApp,
                onEngineRetry = onEngineRetry,
                onDeleteEntries = onDeleteEntries,
                onEngineUnreachable = onEngineUnreachable,
            )
            }
            }
            }
        }
    }
    }
    if (scanning) {
        ScannerScreen(
            pages = documentCapture.pages.pages,
            fileFor = documentCapture.fileFor,
            captureFile = documentCapture.captureFile,
            onCaptured = documentCapture.onScanned,
            onRemovePage = documentCapture.onRemovePage,
            onDone = documentCapture.onStopScanning,
            onCancel = documentCapture.onStopScanning,
            onRotatePage = documentCapture.onRotateBy,
            onCropPage = documentCapture.onCrop,
            onRestylePage = documentCapture.onRestyle,
            onMovePage = documentCapture.onMove,
            modifier = Modifier.fillMaxSize(),
        )
    }
    }

    confirmingLeave?.let { leave ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmingLeave = null },
            title = { Text(if (editSession != null) "Discard your changes?" else "Discard this entry?") },
            text = {
                Text(
                    if (editSession != null) {
                        "The entry in your ledger is unchanged. Leaving now loses what you changed."
                    } else {
                        "What you have typed has not been written to your ledger. Leaving now loses it."
                    },
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmingLeave = null
                        addDirty = false
                        // The pages go with the words (B79). They live in the capture session,
                        // not in the form, so discarding the entry left them behind -- and the
                        // next Add opened already holding the receipt for the entry thrown away.
                        if (!documentCapture.pages.isEmpty) onDiscardPages()
                        leave()
                    },
                    modifier = Modifier.semantics {
                        contentDescription = if (editSession != null) {
                            "Discard your changes and leave the form"
                        } else {
                            "Discard the entry and leave the form"
                        }
                    },
                ) { Text("Discard") }
            },
            dismissButton = {
                TextButton(onClick = { confirmingLeave = null }) { Text("Keep editing") }
            },
        )
    }
}

/**
 * The keyboard's height, applied only while the keyboard is actually showing (B78).
 *
 * `imePadding()` follows the keyboard's *animated* inset, frame by frame. When that animation
 * does not reach its end -- the keyboard shown and hidden quickly across several fields, a
 * layout change landing mid-animation as the destination bar comes back -- the animated value
 * can stay where it stopped, and the app was left with a band the keyboard's size, or half of
 * it, painted in the window's own colour until the process was killed. That is the black area
 * people saw after tapping between fields.
 *
 * Whether the keyboard is visible comes from the final insets the window was given, not from
 * the animation. So the padding is taken from the animated inset while the keyboard is visible,
 * and is simply absent when it is not: a stuck animation cannot leave space behind, because the
 * moment the window says the keyboard is gone the padding goes with it. The same flag already
 * decides whether the destination bar is shown, so the two can no longer disagree for a frame
 * and float the bar in the middle of the screen.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun Modifier.keyboardPadding(): Modifier =
    if (WindowInsets.isImeVisible) this.imePadding() else this

/**
 * An entry being edited in the entry form (B79).
 *
 * @property editing what the form needs to know: the entry, and whether saving reformats it.
 * @property onSave writes the edited entry over the original.
 * @property onClose ends the edit, whether it was saved or not.
 */
data class EntryEditSession(
    val editing: io.github.pintofi.beanbook.app.ui.EntryEditing,
    val onSave: (TransactionDraft) -> Unit,
    val onClose: () -> Unit,
)
