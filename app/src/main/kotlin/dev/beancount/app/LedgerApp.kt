package dev.beancount.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
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
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Tab
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import dev.beancount.core.domain.JournalEntry
import dev.beancount.app.ui.EditableFile
import dev.beancount.app.ui.ContributeScreen
import dev.beancount.core.data.MirrorAction
import dev.beancount.app.ui.DocumentCaptureHandle
import dev.beancount.core.data.RestoreStaged
import dev.beancount.core.domain.CapturedPages
import dev.beancount.core.domain.DirectiveDraft
import dev.beancount.core.domain.DocumentRow
import java.io.File as JavaFile
import java.time.LocalDate
import dev.beancount.core.domain.EntryTemplate
import dev.beancount.core.data.ProjectSummary
import dev.beancount.core.domain.TransactionDraft
import dev.beancount.app.web.AllowlistWebViewClient
import dev.beancount.core.domain.LedgerSnapshot
import dev.beancount.core.engine.EngineStatus
import dev.beancount.core.entitlement.Entitlement
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
    UPDATES("Updates"),
    SCRIPTS("Scripts"),
    LICENCES("Licences"),
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

@Composable
internal fun LedgerApp(
    engineStatus: EngineStatus,
    ledger: LedgerSnapshot?,
    editionName: String,
    entitlement: Entitlement,
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
    onApplyKey: (String) -> Unit,
    onRemoveKey: () -> Unit,
    onClearEntitlementMessage: () -> Unit,
    entitlementMessage: String?,
    documentCapture: DocumentCaptureHandle,
    documents: List<DocumentRow>,
    documentMessage: String?,
    onSaveDocument: (String) -> Unit,
    onDiscardPages: () -> Unit,
    onRefileDocument: (String, String, String?) -> Unit,
    onUnlinkDocument: (String, String?) -> Unit,
    onDeleteDocumentFile: (String) -> Unit,
    onShareDocument: (String) -> Unit,
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
    mirrorMessage: String?,
    onChooseMirrorFolder: () -> Unit,
    onMirrorEnabledChanged: (Boolean) -> Unit,
    onMirrorNow: () -> Unit,
    onKeepMine: (String) -> Unit,
    onKeepTheirs: (String) -> Unit,
    remoteStatus: EngineStatus,
    remoteCapabilities: dev.beancount.engine.remote.ServerCapabilities?,
    savedRemoteAddress: String?,
    savedRemoteUsername: String?,
    remotePendingRetry: Boolean,
    remoteMessage: String?,
    remoteReports: RemoteReports?,
    themeChoice: dev.beancount.app.ui.theme.ThemeChoice,
    dynamicColour: Boolean,
    onThemeChoice: (dev.beancount.app.ui.theme.ThemeChoice) -> Unit,
    onDynamicColour: (Boolean) -> Unit,
    hideWidgetAmounts: Boolean,
    onHideWidgetAmounts: (Boolean) -> Unit,
    pluginRows: List<dev.beancount.app.plugins.PluginRow>,
    pluginSafeMode: Boolean,
    pluginMessage: String?,
    packages: dev.beancount.app.packages.PackagesUi,
    onInstallPlugin: () -> Unit,
    scriptRunning: String?,
    scriptOutcome: dev.beancount.app.plugins.ScriptOutcome?,
    scriptMessage: String?,
    onPickScript: () -> Unit,
    onCancelScript: () -> Unit,
    onSetPluginEnabled: (String, Boolean) -> Unit,
    onRemovePlugin: (String) -> Unit,
    onSetPluginSafeMode: (Boolean) -> Unit,
    recoveryNotice: String?,
    onDismissRecovery: () -> Unit,
    onRemoteConnect: (String, String?, CharArray?) -> Unit,
    onRemoteDisconnect: () -> Unit,
    onRemoteForget: () -> Unit,
    onRemoteRetry: () -> Unit,
    onRemoteDiscard: () -> Unit,
    onSaveDirective: (dev.beancount.core.domain.DirectiveDraft) -> Unit,
    saveState: String?,
    projects: List<ProjectSummary>,
    activeProjectId: String?,
    projectMessage: String?,
    onOpenProject: (String) -> Unit,
    onCreateProject: (String) -> Unit,
    onExportProject: () -> Unit,
    onImportFolder: () -> Unit,
    onImportArchive: () -> Unit,
    onDeleteProject: (String) -> Unit,
    editorState: dev.beancount.app.ui.EditorState,
    editorMessage: String?,
    editorFiles: List<EditableFile>,
    onOpenFile: (EditableFile) -> Unit,
    onCloseFile: () -> Unit,
    onEditorTextChanged: (String) -> Unit,
    onSaveFile: () -> Unit,
    onDiscardDraft: () -> Unit,
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
    var showContribute by remember { mutableStateOf(false) }

    // An entry being entered again (B11). Held here because applying it means switching to
    // the Add surface, which this composable owns; cleared once the form has taken it.
    var pendingClone by remember { mutableStateOf<TransactionDraft?>(null) }

    // null means "show the list of sections". Hoisted out of the More branch so the app bar
    // can send the user straight to Projects.
    var moreSection by remember { mutableStateOf<MoreSection?>(null) }

    // Back walks out the way the user came in, rather than leaving the app from wherever
    // they happen to be standing (B8). Innermost first: a More section, then the Contribute
    // sheet, then any surface other than the Journal. Once on the Journal it is not enabled,
    // so the system takes Back and the app closes -- which is what the platform expects of a
    // top-level destination, and what predictive back animates against.
    BackHandler(enabled = adding) { adding = false }
    BackHandler(enabled = !adding && moreSection != null) { moreSection = null }
    BackHandler(enabled = !adding && moreSection == null && showContribute) { showContribute = false }
    BackHandler(
        enabled = !adding && moreSection == null && !showContribute && selected != Surface.HOME,
    ) {
        selected = Surface.HOME
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

    Row(modifier = modifier) {
        if (layout != PaneLayout.COMPACT) {
            LedgerNavigationRail(selected = selected, onSelect = { selected = it })
        }

    Scaffold(
        modifier = Modifier.weight(1f),
        topBar = {
            LedgerTopBar(
                projectName = projects.firstOrNull { it.id == activeProjectId }?.title
                    ?: ledger?.title
                    ?: "Beancount",
                health = ledgerHealth(engineStatus, ledger),
                sectionTitle = if (selected == Surface.MORE) moreSection?.label else null,
                onBack = { moreSection = null },
                onSwitchProject = {
                    selected = Surface.MORE
                    moreSection = MoreSection.PROJECTS
                },
            )
        },
        floatingActionButton = {
            // Adding an entry is the app's primary action, so it gets the one control
            // Material reserves for that, rather than a sixth slot in a bar meant for
            // destinations (B26). Hidden while the form is open -- a button that opens what
            // is already open is noise -- and while there is no ledger to add to.
            if (showsAddAction(selected, adding, hasLedger = ledger != null)) {
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
                    selectedTabIndex = Surface.entries.indexOf(selected),
                    edgePadding = 0.dp,
                ) {
                    Surface.entries.forEach { surface ->
                        Tab(
                            selected = selected == surface,
                            onClick = { selected = surface },
                            text = { Text(surface.label, softWrap = false) },
                            modifier = Modifier.semantics { contentDescription = surface.label },
                        )
                    }
                }
            } else {
                NavigationBar {
                    Surface.entries.forEach { surface ->
                        NavigationBarItem(
                            selected = selected == surface,
                            onClick = { selected = surface },
                            label = { Text(surface.label) },
                            icon = { Icon(surface.icon, contentDescription = null) },
                            // No contentDescription: the label already supplies one, and
                            // setting both made TalkBack say "Reports, Reports" on every
                            // destination.
                        )
                    }
                }
            }
        },
    ) { padding ->
        if (showContribute) {
            ContributeScreen(
                entitlement = entitlement,
                onApplyKey = onApplyKey,
                onRemoveKey = onRemoveKey,
                onClose = {
                    showContribute = false
                    onClearEntitlementMessage()
                },
                message = entitlementMessage,
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }

        Column(modifier = Modifier.padding(padding)) {
            recoveryNotice?.let { notice ->
                RecoveryBanner(notice, onDismiss = onDismissRecovery)
            }
            SurfaceContent(
                selected = selected,
                adding = adding,
                moreSection = moreSection,
                onMoreSection = { moreSection = it },
                twoPane = layout == PaneLayout.EXPANDED,
                onContribute = { showContribute = true },
                documentCapture = documentCapture,
                documents = documents,
                documentMessage = documentMessage,
                onSaveDocument = onSaveDocument,
                onDiscardPages = onDiscardPages,
                onRefileDocument = onRefileDocument,
                onUnlinkDocument = onUnlinkDocument,
                onDeleteDocumentFile = onDeleteDocumentFile,
                onShareDocument = onShareDocument,
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
                onInstallPlugin = onInstallPlugin,
                scriptRunning = scriptRunning,
                scriptOutcome = scriptOutcome,
                scriptMessage = scriptMessage,
                onPickScript = onPickScript,
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
                editionName = editionName,
                entitlement = entitlement,
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
            )
        }
    }
    }
}
