package io.github.pintofi.beanbook.app

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import io.github.pintofi.beanbook.core.domain.filteredBy
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.pintofi.beanbook.app.ui.EntryDetailScreen
import io.github.pintofi.beanbook.core.domain.JournalEntry
import io.github.pintofi.beanbook.app.ui.HomeScreen
import io.github.pintofi.beanbook.app.ui.BalancesScreen
import io.github.pintofi.beanbook.app.ui.JournalScreen
import io.github.pintofi.beanbook.app.ui.ReportsPane
import io.github.pintofi.beanbook.app.ui.EditableFile
import io.github.pintofi.beanbook.app.ui.ProjectPickerScreen
import io.github.pintofi.beanbook.app.ui.RawEditorScreen
import io.github.pintofi.beanbook.app.ui.BackupScreen
import io.github.pintofi.beanbook.app.ui.MirrorScreen
import io.github.pintofi.beanbook.app.ui.AppearanceScreen
import io.github.pintofi.beanbook.app.ui.PluginsScreen
import io.github.pintofi.beanbook.app.ui.RemoteScreen
import io.github.pintofi.beanbook.core.data.MirrorAction
import io.github.pintofi.beanbook.app.ui.DocumentCaptureHandle
import io.github.pintofi.beanbook.app.ui.DocumentsScreen
import io.github.pintofi.beanbook.core.data.RestoreStaged
import io.github.pintofi.beanbook.core.domain.CapturedPages
import io.github.pintofi.beanbook.core.domain.DirectiveDraft
import io.github.pintofi.beanbook.core.domain.DocumentRow
import java.io.File as JavaFile
import io.github.pintofi.beanbook.app.ui.QuickAddScreen
import io.github.pintofi.beanbook.core.domain.EntryTemplate
import io.github.pintofi.beanbook.core.data.ProjectSummary
import io.github.pintofi.beanbook.core.domain.LedgerVocabulary
import io.github.pintofi.beanbook.core.domain.TransactionDraft
import io.github.pintofi.beanbook.app.web.FavaWebView
import io.github.pintofi.beanbook.app.web.installSessionToken
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.core.engine.OriginAllowlist
import io.github.pintofi.beanbook.core.engine.ReportPath
// What each destination renders. Split out of MainActivity.kt; see LedgerApp.kt.
@Composable
internal fun SurfaceContent(
    selected: Surface,
    /** The add form is open over [selected]; it is an action, not a destination. */
    adding: Boolean,
    /** Whether the open entry form holds anything that would be lost by leaving. */
    onAddDirtyChange: (Boolean) -> Unit = {},
    documentCapture: DocumentCaptureHandle,
    documents: List<DocumentRow>,
    documentMessage: String?,
    onSaveDocument: (account: String, label: String?) -> Unit,
    onDiscardPages: () -> Unit,
    onRefileDocument: (String, String, String?) -> Unit,
    onUnlinkDocument: (String, String?) -> Unit,
    onDeleteDocumentFile: (String) -> Unit,
    onShareDocument: (String) -> Unit,
    /** The app's own record of what it did, and what a person can do with it. */
    diagnostics: io.github.pintofi.beanbook.app.ui.DiagnosticsUi,
    /** A document's file inside the open project, for reading it in the app. */
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
    moreSection: MoreSection?,
    onMoreSection: (MoreSection?) -> Unit,
    /** Moving to another destination from inside one, which is what Home's rows do. */
    onSelectSurface: (Surface) -> Unit = {},
    twoPane: Boolean,
    themeChoice: io.github.pintofi.beanbook.app.ui.theme.ThemeChoice,
    dynamicColour: Boolean,
    onThemeChoice: (io.github.pintofi.beanbook.app.ui.theme.ThemeChoice) -> Unit,
    onDynamicColour: (Boolean) -> Unit,
    hideWidgetAmounts: Boolean,
    /** An entry being entered again, and the way to ask for one. */
    prefill: TransactionDraft? = null,
    onPrefillApplied: () -> Unit = {},
    onEnterAgain: (JournalEntry) -> Unit = {},
    onEditEntry: (JournalEntry) -> Unit = {},
    onDeleteEntry: (JournalEntry) -> Unit = {},
    onHideWidgetAmounts: (Boolean) -> Unit,
    pluginRows: List<io.github.pintofi.beanbook.app.plugins.PluginRow>,
    pluginSafeMode: Boolean,
    pluginMessage: String?,
    packages: io.github.pintofi.beanbook.app.packages.PackagesUi,
    /** The period Balances is showing, and the way to change it. */
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
    /** Modules the open ledger names, and the way to add or remove one. */
    onRemoteConnect: (String, String?, CharArray?) -> Unit,
    onRemoteDisconnect: () -> Unit,
    onRemoteForget: () -> Unit,
    onRemoteRetry: () -> Unit,
    onRemoteDiscard: () -> Unit,
    onSaveEntry: (TransactionDraft) -> Unit,
    templates: List<EntryTemplate>,
    onSaveTemplate: (EntryTemplate) -> Unit,
    onDeleteTemplate: (String) -> Unit,
    onSaveDirective: (io.github.pintofi.beanbook.core.domain.DirectiveDraft) -> Unit,
    saveState: String?,
    /** Entries committed from the form this session: what the save sweep marks. */
    entryCommits: Int = 0,
    /** An entry already in the file, open in the form. */
    editSession: EntryEditSession? = null,
    projects: List<ProjectSummary>,
    activeProjectId: String?,
    projectMessage: String?,
    onOpenProject: (String) -> Unit,
    onCreateProject: (String) -> Unit,
    onExportProject: () -> Unit,
    onImportFolder: () -> Unit,
    onImportArchive: () -> Unit,
    onDeleteProject: (String) -> Unit,
    projectTrash: io.github.pintofi.beanbook.app.ui.ProjectTrash = io.github.pintofi.beanbook.app.ui.ProjectTrash(),
    engineStatus: EngineStatus,
    ledger: LedgerSnapshot?,
    token: String?,
    debuggingAllowed: Boolean,
    onOpenExternally: (Uri) -> Unit,
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
    onRestartApp: () -> Unit = {},
    onDeleteEntries: (List<JournalEntry>) -> Unit = {},
    /** Starts the engine again after it failed, or restarts it when it stopped answering. */
    onEngineRetry: () -> Unit = {},
    onEngineUnreachable: () -> Unit = {},
    /** The ledger is in balance again: Home stamps it. */
    stamped: Boolean = false,
) {
    val padding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    // The ledger as the shared filter sees it, for the three screens that obey it. The entry open on
    // a detail page, deletes, edits and every other surface keep working on the whole ledger.
    val filter = io.github.pintofi.beanbook.app.ui.ledgerFilter().filter
    val shown = remember(ledger, filter) { ledger?.filteredBy(filter) }
    // The tags and links the ledger has, whatever is filtered, so the filter sheet can offer them.
    val tagSuggestions = remember(ledger) {
        ledger?.let { all ->
            all.journal.flatMap { it.tags }.distinct().sorted().map { "#$it" } +
                all.journal.flatMap { it.links }.distinct().sorted().map { "^$it" }
        }.orEmpty()
    }
    // The tab the Documents screen is on, held above this function so Home can send people to Organize.
    val documentsTabState = io.github.pintofi.beanbook.app.ui.documentsTabState()
    run {
        // `null` is the add form, open over `selected`. Written this way so the form is one
        // branch beside the destinations rather than a second copy of this dispatch.
        val showing: Surface? = if (adding) null else selected
        when (showing) {
            Surface.HOME -> Refreshable(refreshing, onRefresh) {
                HomeScreen(
                    header = { io.github.pintofi.beanbook.app.about.SupportNoticeHost() },
                    stamped = stamped,
                    ledger = shown,
                    hideAmounts = hideWidgetAmounts,
                    // Counted here rather than in the composable: these come from the
                    // documents reconciliation and the mirror, and Home only reports them.
                    unfiledDocuments = documents.count {
                        it.status == io.github.pintofi.beanbook.core.domain.DocumentStatus.UNFILED &&
                            it.attachedTo.isEmpty()
                    },
                    brokenDocumentLinks = documents.count {
                        it.status == io.github.pintofi.beanbook.core.domain.DocumentStatus.MISSING
                    },
                    mirrorConflicts = mirrorConflicts.size,
                    onOpenJournal = { onSelectSurface(Surface.JOURNAL) },
                    onOpenBalances = { onSelectSurface(Surface.BALANCES) },
                    onOpenEntry = { onSelectSurface(Surface.JOURNAL) },
                    onOpenDocuments = {
                        // Home only offers this for a document with a problem to fix (not
                        // linked, or its file gone), and that is done in Organize.
                        documentsTabState.tab = io.github.pintofi.beanbook.app.ui.DocumentsMode.ORGANIZE
                        onSelectSurface(Surface.MORE)
                        onMoreSection(MoreSection.DOCUMENTS)
                    },
                    onOpenPlugins = {
                        onSelectSurface(Surface.MORE)
                        onMoreSection(MoreSection.PLUGINS)
                    },
                    onOpenMirror = {
                        onSelectSurface(Surface.MORE)
                        onMoreSection(MoreSection.MIRROR)
                    },
                )
            }

            null -> {
                // Entry is free, always: adding entries is never gated
                // (docs/distribution.md 4.4).
                if (ledger == null) {
                    LoadingOrError(engineStatus, onRetry = onEngineRetry)
                } else {
                    var otherDirectives by androidx.compose.runtime.saveable.rememberSaveable {
                        androidx.compose.runtime.mutableStateOf(false)
                    }
                    if (editSession != null) {
                        // Keyed on the entry, so opening another one starts from it rather
                        // than from what the last edit left in the fields.
                        androidx.compose.runtime.key(editSession.editing.original) {
                            QuickAddScreen(
                                vocabulary = LedgerVocabulary.from(ledger),
                                format = ledger.format,
                                names = ledger.accountNames,
                                onDirtyChange = onAddDirtyChange,
                                onSave = editSession.onSave,
                                saveState = saveState,
                                committed = entryCommits,
                                prefill = editSession.editing.original,
                                templates = templates,
                                onSaveTemplate = onSaveTemplate,
                                onDeleteTemplate = onDeleteTemplate,
                                capture = documentCapture,
                                editing = editSession.editing,
                            )
                        }
                    } else androidx.compose.foundation.layout.Column {
                        // One or the other, never both: a connected choice rather than chips,
                        // matching the flag and valuation selectors elsewhere.
                        io.github.pintofi.beanbook.app.ui.ConnectedChoiceRow(
                            options = listOf(false, true),
                            selected = otherDirectives,
                            onSelect = { otherDirectives = it },
                            label = { if (it) "Other directives" else "Transaction" },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        if (otherDirectives) {
                            io.github.pintofi.beanbook.app.ui.DirectiveEntryScreen(
                                vocabulary = LedgerVocabulary.from(ledger),
                                onSave = onSaveDirective,
                                saveState = saveState,
                                committed = entryCommits,
                                onDirtyChange = onAddDirtyChange,
                            )
                        } else {
                            QuickAddScreen(
                                vocabulary = LedgerVocabulary.from(ledger),
                                format = ledger.format,
                                names = ledger.accountNames,
                                onDirtyChange = onAddDirtyChange,
                                onSave = onSaveEntry,
                                saveState = saveState,
                                committed = entryCommits,
                                prefill = prefill,
                                onPrefillApplied = onPrefillApplied,
                                templates = templates,
                                onSaveTemplate = onSaveTemplate,
                                onDeleteTemplate = onDeleteTemplate,
                                capture = documentCapture,
                            )
                        }
                    }
                }
            }

            Surface.MORE -> {
                val section = moreSection
                Column(modifier = Modifier.padding(padding)) {
                    // A list, not a row of chips. Seven sections never fitted across a phone,
                    // so the row scrolled horizontally and the last two were off-screen —
                    // reaching Plugins took three swipes, every time, and nothing on screen
                    // suggested they were there at all.
                    if (section == null) {
                        MoreMenu(
                            onOpen = onMoreSection,
                            onRestartApp = onRestartApp,
                        )
                        return@Column
                    }

                    when (section) {
                        MoreSection.PROJECTS -> ProjectPickerScreen(
                            projects = projects,
                            activeId = activeProjectId,
                            onOpen = onOpenProject,
                            onCreateNew = onCreateProject,
                            onOpenFolder = onImportFolder,
                            onOpenArchive = onImportArchive,
                            onExport = onExportProject,
                            onDelete = onDeleteProject,
                            trash = projectTrash,
                            busyMessage = projectMessage,
                        )

                        MoreSection.ABOUT -> io.github.pintofi.beanbook.app.about.AboutSection(
                            onOpenLicences = { onMoreSection(MoreSection.LICENCES) },
                        )

                        MoreSection.LICENCES -> io.github.pintofi.beanbook.app.ui.LicencesScreen()

                        MoreSection.DIAGNOSTICS -> io.github.pintofi.beanbook.app.ui.DiagnosticsScreen(
                            read = diagnostics.read,
                            onCopy = diagnostics.onCopy,
                            onShare = diagnostics.onShare,
                            onClear = diagnostics.onClear,
                            message = diagnostics.message,
                            modifier = Modifier.padding(padding),
                        )

                        MoreSection.FILES -> RawEditorScreen(
                            twoPane = twoPane,
                            files = editorFiles,
                            state = editorState,
                            errors = ledger?.errors.orEmpty(),
                            message = editorMessage,
                            onOpen = onOpenFile,
                            onClose = onCloseFile,
                            onTextChanged = onEditorTextChanged,
                            onSave = onSaveFile,
                            onDiscardDraft = onDiscardDraft,
                        )

                        MoreSection.APPEARANCE ->
                            AppearanceScreen(
                                choice = themeChoice,
                                dynamicColour = dynamicColour,
                                dynamicColourSupported =
                                    android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S,
                                onChoice = onThemeChoice,
                                onDynamicColour = onDynamicColour,
                                hideWidgetAmounts = hideWidgetAmounts,
                                onHideWidgetAmounts = onHideWidgetAmounts,
                            )

                        MoreSection.SCRIPTS ->
                            io.github.pintofi.beanbook.app.ui.ScriptRunnerScreen(
                                running = scriptRunning,
                                outcome = scriptOutcome,
                                message = scriptMessage,
                                bridgeAvailable = currentPluginPolicy().allowsAndroidBridge,
                                onPick = onPickScript,
                                onCancel = onCancelScript,
                                saved = savedScripts,
                                onRunSaved = onRunSavedScript,
                                onForgetSaved = onForgetSavedScript,
                            )


                        MoreSection.PLUGINS ->
                            PluginsScreen(
                                rows = pluginRows,
                                safeMode = pluginSafeMode,
                                policy = currentPluginPolicy(),
                                onInstall = onInstallPlugin,
                                onSetEnabled = onSetPluginEnabled,
                                onRemove = onRemovePlugin,
                                onSetSafeMode = onSetPluginSafeMode,
                                packages = packages,
                                message = pluginMessage,
                                // What the last load said about each catalogue plugin's
                                // modules: extension imports and plugin import errors.
                                status = { id ->
                                    // While the ledger or the engine is reloading, the last
                                    // answer may be stale: say so rather than flash an
                                    // error that is about to clear.
                                    val reloading = refreshing ||
                                        engineStatus !is io.github.pintofi.beanbook.core.engine.EngineStatus.Serving
                                    if (reloading) {
                                        return@PluginsScreen io.github.pintofi.beanbook.app.ui.PluginStatus.Checking
                                    }
                                    val modules = packages.catalogue.entries
                                        .firstOrNull { it.id == id }?.install?.modules.orEmpty()
                                    io.github.pintofi.beanbook.app.ui.pluginStatusFor(
                                        modules = modules,
                                        extensionHealth = ledger?.extensionHealth.orEmpty(),
                                        ledgerPlugins = ledger?.plugins.orEmpty().map { it.name },
                                        errors = ledger?.errors.orEmpty().map { it.message },
                                        safeMode = pluginSafeMode,
                                    )
                                },
                            )


                        MoreSection.REMOTE ->
                            RemoteScreen(
                                status = remoteStatus,
                                capabilities = remoteCapabilities,
                                savedAddress = savedRemoteAddress,
                                savedUsername = savedRemoteUsername,
                                pendingRetry = remotePendingRetry,
                                onConnect = onRemoteConnect,
                                onDisconnect = onRemoteDisconnect,
                                onForget = onRemoteForget,
                                onRetryPending = onRemoteRetry,
                                onDiscardPending = onRemoteDiscard,
                                message = remoteMessage,
                            )


                        MoreSection.SHARING ->
                            io.github.pintofi.beanbook.app.ui.SharingScreen()

                        MoreSection.MIRROR ->
                            MirrorScreen(
                                enabled = mirrorEnabled,
                                folder = mirrorFolder,
                                conflicts = mirrorConflicts,
                                status = mirrorStatus,
                                onChooseFolder = onChooseMirrorFolder,
                                onEnabledChanged = onMirrorEnabledChanged,
                                onSyncNow = onMirrorNow,
                                onKeepMine = onKeepMine,
                                onKeepTheirs = onKeepTheirs,
                                message = mirrorMessage,
                            )


                        MoreSection.BACKUP -> BackupScreen(
                            projectTitle = projects.firstOrNull { it.id == activeProjectId }
                                ?.title.orEmpty(),
                            pendingRestore = pendingRestore,
                            passphraseNeeded = passphraseNeeded,
                            onUnlockRestore = onUnlockRestore,
                            scheduleEnabled = scheduleEnabled,
                            scheduleFolder = scheduleFolder,
                            lastRun = lastBackupRun,
                            rollbackPoints = rollbackPoints,
                            onBackUpNow = onBackUpNowWithPassphrase,
                            onChooseRestoreFile = onChooseRestoreFile,
                            onAcceptRestore = onAcceptRestore,
                            onDiscardRestore = onDiscardRestore,
                            onChooseScheduleFolder = onChooseScheduleFolder,
                            onScheduleChanged = onScheduleChanged,
                            message = backupMessage,
                        )

                        // Pull down to read the folder and the ledger again: a receipt
                        // saved into the documents folder from elsewhere had no way in.
                        MoreSection.DOCUMENTS -> Refreshable(refreshing, onRefresh) {
                            DocumentsScreen(
                                capture = documentCapture,
                                names = ledger?.accountNames
                                    ?: io.github.pintofi.beanbook.core.domain.AccountNames.DEFAULT,
                                documents = documents,
                                vocabulary = ledger?.let { LedgerVocabulary.from(it) }
                                    ?: LedgerVocabulary(
                                        accounts = emptyList(),
                                        payees = emptyList(),
                                        narrations = emptyList(),
                                        currencies = emptyList(),
                                        tags = emptyList(),
                                    ),
                                onSave = onSaveDocument,
                                onDiscard = onDiscardPages,
                                onRefile = onRefileDocument,
                                onUnlink = onUnlinkDocument,
                                onDeleteFile = onDeleteDocumentFile,
                                onShare = onShareDocument,
                                fileForDocument = fileForDocument,
                                message = documentMessage,
                                mode = documentsTabState.tab,
                                onModeChange = { documentsTabState.tab = it },
                            )
                        }

                    }
                }
            }

            Surface.JOURNAL, Surface.BALANCES -> {
                if (ledger == null) {
                    LoadingOrError(engineStatus, Modifier.padding(padding), onRetry = onEngineRetry)
                } else if (selected == Surface.JOURNAL) {
                    // The open entry lives here rather than in LedgerApp: it means nothing
                    // unless the Journal is showing, and its Back behaviour belongs with it.
                    var openEntry by remember { mutableStateOf<JournalEntry?>(null) }
                    BackHandler(enabled = openEntry != null) { openEntry = null }

                    val entry = openEntry
                    if (entry == null) {
                        Refreshable(refreshing, onRefresh) {
                            JournalScreen(
                                shown ?: ledger,
                                Modifier.padding(padding),
                                onOpenEntry = { openEntry = it },
                                onDeleteEntries = onDeleteEntries,
                            )
                        }
                    } else {
                        EntryDetailScreen(
                            entry = entry,
                            onClone = { onEnterAgain(entry); openEntry = null },
                            onClose = { openEntry = null },
                            onEdit = { onEditEntry(entry); openEntry = null },
                            onDelete = { onDeleteEntry(entry); openEntry = null },
                            onOpenDocument = onShareDocument,
                            fileForDocument = fileForDocument,
                            format = ledger.format,
                            // What Beancount said about this entry, so the message is where
                            // the entry is rather than only in the list at the top.
                            errors = io.github.pintofi.beanbook.core.domain.JournalErrors
                                .attach(ledger.journal, ledger.errors)[
                                io.github.pintofi.beanbook.core.domain.JournalErrors.keyOf(entry)
                            ].orEmpty(),
                            modifier = Modifier.padding(padding),
                        )
                    }
                } else {
                    Refreshable(refreshing, onRefresh) {
                        BalancesScreen(
                            shown ?: ledger,
                            Modifier.padding(padding),
                            tagSuggestions = tagSuggestions,
                            dateFrom = dateFrom,
                            dateTo = dateTo,
                            onDateRange = onDateRange,
                        )
                    }
                }
            }

            Surface.REPORTS -> {
                val serving = engineStatus as? EngineStatus.Serving
                if (remoteReports != null) {
                    // Remote mode wins when it is connected: the user pointed the app at
                    // their own server, and showing the local ledger's reports instead
                    // would be showing them someone else's numbers.
                    var problem by remember(remoteReports.url) { mutableStateOf<String?>(null) }
                    if (problem != null) {
                        RemoteReportProblem(problem!!, Modifier.padding(padding))
                    } else {
                        FavaWebView(
                            url = remoteReports.url,
                            allowlist = OriginAllowlist(remoteReports.origins),
                            modifier = Modifier.fillMaxSize().padding(padding),
                            debuggingAllowed = debuggingAllowed,
                            onExternalLink = onOpenExternally,
                            httpAuth = remoteReports.auth,
                            onLoadProblem = { problem = it },
                        )
                    }
                } else if (serving == null) {
                    LoadingOrError(engineStatus, Modifier.padding(padding), onRetry = onEngineRetry)
                } else {
                    // Once per origin and token, not on every recomposition: the cookie has to
                    // be in place before the page loads, and setting it again each time the
                    // screen redraws only flushes the cookie store.
                    if (token != null) {
                        // Returns the token only because `remember` must not return Unit.
                        remember(serving.origin, token) {
                            installSessionToken(serving.origin, token)
                            token
                        }
                    }
                    // Starts at the ledger's own index so Fava serves whatever the file's
                    // default-page option asks for; picking a report from the list overrides
                    // it from then on.
                    var report by remember { mutableStateOf(ReportPath.LEDGER_DEFAULT) }
                    // Kept by the app, so returning to Reports finds the report that was open
                    // rather than starting Fava again. Back first returns to the page the
                    // ledger opens on -- its `default-page` Fava option -- then leaves Reports.
                    val page = io.github.pintofi.beanbook.app.web.LocalReportsPage.current
                    BackHandler(enabled = page?.awayFromLanding == true) {
                        page?.landing?.let { page.load(it) }
                    }
                    val web: @Composable () -> Unit = {
                        FavaWebView(
                            retained = page,
                            url = "${serving.origin}/${serving.ledgerSlug}/${report.path}"
                                .trimEnd('/') + "/",
                            allowlist = OriginAllowlist(setOf(serving.origin)),
                            modifier = Modifier.fillMaxSize(),
                            debuggingAllowed = debuggingAllowed,
                            onExternalLink = onOpenExternally,
                            // The local server stopped answering: its process is gone. A new
                            // one is started instead of leaving the browser's error page up.
                            onLoadProblem = { onEngineUnreachable() },
                        )
                    }
                    if (twoPane) {
                        // docs/ux.md section 7: on an expanded window the Fava WebView gets the wider
                        // pane, with the report list beside it.
                        ReportsPane(
                            selected = report,
                            onSelect = { report = it },
                            modifier = Modifier.fillMaxSize().padding(padding),
                            report = web,
                        )
                    } else {
                        androidx.compose.foundation.layout.Box(
                            Modifier.fillMaxSize().padding(padding),
                        ) { web() }
                    }
                }
            }
        }
    }
}

/**
 * Pull down to read the open ledger from disk again.
 *
 * The file watcher catches most outside changes by itself; this is for the ones it cannot see
 * (a folder that only changes while the app is closed, a provider that sends no events), and
 * for anyone who wants to be sure.
 *
 * **Only on the surfaces that draw ledger data, and Documents.** Reports is Fava's own page inside a
 * WebView and has its own reload; the file editor holds an unsaved draft that a reload would
 * be entitled to lose; and the entry detail view is about one entry, which a reload could
 * remove from under it. Adding the gesture there would offer a promise this cannot keep.
 *
 * The indicator is worth the wiring rather than being decoration: a reload is seconds, not
 * milliseconds, on a large ledger — far past the point where a user assumes the pull did
 * nothing and pulls again (the Doherty Threshold).
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun Refreshable(
    refreshing: Boolean,
    onRefresh: () -> Unit,
    content: @Composable () -> Unit,
) {
    androidx.compose.material3.pulltorefresh.PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = onRefresh,
        modifier = Modifier.fillMaxSize(),
    ) {
        content()
    }
}
