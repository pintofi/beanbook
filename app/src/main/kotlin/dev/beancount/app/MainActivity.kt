package dev.beancount.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import dev.beancount.core.data.WriteJournal
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.PrimaryScrollableTabRow
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beancount.app.engine.RecoveryOnStart
import dev.beancount.app.ui.BalancesScreen
import dev.beancount.app.ui.JournalScreen
import dev.beancount.app.ui.OnboardingScreen
import dev.beancount.app.ui.EditableFile
import dev.beancount.app.ui.ProjectPickerScreen
import dev.beancount.app.ui.RawEditorScreen
import dev.beancount.app.ui.QuickAddScreen
import dev.beancount.core.data.ProjectSummary
import dev.beancount.core.domain.LedgerVocabulary
import dev.beancount.core.domain.TransactionDraft
import dev.beancount.app.web.FavaWebView
import dev.beancount.app.web.installSessionToken
import dev.beancount.core.domain.LedgerSnapshot
import dev.beancount.core.engine.EngineStatus
import dev.beancount.core.engine.OriginAllowlist
import dev.beancount.core.engine.ReportPath
import dev.beancount.core.entitlement.Entitlement
import dev.beancount.core.entitlement.Feature
import dev.beancount.core.entitlement.Grant

/**
 * M3 shell: three surfaces over one ledger — the native journal and balances (free tier)
 * and Fava's own reports in the hardened WebView.
 *
 * The project picker and guided entry arrive in M6; this screen still uses a single
 * starter project and says so rather than pretending otherwise.
 */
class MainActivity : ComponentActivity() {

    private var recoveryMessage: String? = null
    private var saveMessage: String? by mutableStateOf(null)

    /**
     * SAF pickers. These are the only way a project enters the app, and every byte they
     * hand over is validated before anything is written (docs/storage-and-backup.md §4).
     */
    private val folderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val app = application as BeancountApplication
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
            app.projectsState.importFolder(contentResolver, it) { id -> openProject(app, id) }
        }
    }

    private val archivePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val app = application as BeancountApplication
        uri?.let {
            app.projectsState.importArchive(contentResolver, it, cacheDir) { id ->
                openProject(app, id)
            }
        }
    }

    /** Opens a project: engine, ledger, and the active-project record all move together. */
    private fun openProject(app: BeancountApplication, projectId: String) {
        val ledgerPath = app.workspace.mainFile(projectId).absolutePath
        app.projectsState.open(projectId)
        app.engineManager.start(ledgerPath)
        app.ledgerState.load(ledgerPath)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BeancountApplication

        app.projectsState.refresh()

        lifecycleScope.launch {
            // Recovery first: an interrupted write is resolved before the ledger is shown
            // or served, never after (docs/architecture.md 5).
            app.projectsState.activeId.value?.let { active ->
                val report = RecoveryOnStart(app.writer).run(active)
                if (!report.isClean) recoveryMessage = report.describe()
                openProject(app, active)
            }

            // Debug-only: begin a write and stall before committing, so a process kill
            // can be timed to land mid-write (docs/milestones/m4.md, process-death test).
            val stall = intent.getStringExtra("m4_test")
            val active = app.projectsState.activeId.value
            if (BuildConfig.DEBUG && active != null && stall != null &&
                stall.startsWith("stall_mid_write")
            ) {
                stallMidWrite(app, active, introducesErrors = stall.endsWith("_bad"))
            }
        }

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val engineStatus by app.engineManager.status.collectAsStateWithLifecycle()
                    val ledger by app.ledgerState.snapshot.collectAsStateWithLifecycle()
                    val projects by app.projectsState.projects.collectAsStateWithLifecycle()
                    val activeId by app.projectsState.activeId.collectAsStateWithLifecycle()
                    val projectMessage by app.projectsState.message.collectAsStateWithLifecycle()
                    val editorState by app.editorController.state.collectAsStateWithLifecycle()
                    val editorMessage by app.editorController.message.collectAsStateWithLifecycle()

                    if (activeId == null) {
                        // No project yet: onboarding, not an empty ledger screen.
                        OnboardingScreen(
                            onCreateNew = { title ->
                                app.projectsState.createNew(title) { openProject(app, it) }
                            },
                            onOpenFolder = { folderPicker.launch(null) },
                            onOpenArchive = { archivePicker.launch(arrayOf("application/zip", "*/*")) },
                            busyMessage = projectMessage,
                        )
                        return@Surface
                    }

                    LedgerApp(
                        engineStatus = engineStatus,
                        ledger = ledger,
                        editionName = app.pluginPolicy.name,
                        entitlement = app.entitlement,
                        token = app.engine.sessionToken,
                        debuggingAllowed = BuildConfig.DEBUG,
                        onOpenExternally = ::openInBrowser,
                        onSaveEntry = { draft -> saveText(app, activeId!!, draft.render(), "quick-add") },
                        onSaveDirective = { draft ->
                            saveText(app, activeId!!, draft.render(), "add-directive")
                        },
                        saveState = saveMessage,
                        projects = projects,
                        activeProjectId = activeId,
                        projectMessage = projectMessage,
                        onOpenProject = { id -> openProject(app, id) },
                        onCreateProject = { title ->
                            app.projectsState.createNew(title) { openProject(app, it) }
                        },
                        onImportFolder = { folderPicker.launch(null) },
                        onImportArchive = { archivePicker.launch(arrayOf("application/zip", "*/*")) },
                        onDeleteProject = { id -> app.projectsState.delete(id) },
                        editorState = editorState,
                        editorMessage = editorMessage,
                        editorFiles = app.editorController.filesFor(
                            activeId!!,
                            ledger?.includedFiles.orEmpty(),
                        ),
                        onOpenFile = { file -> app.editorController.open(activeId!!, file) },
                        onCloseFile = { app.editorController.close(activeId!!) },
                        onEditorTextChanged = { text ->
                            app.editorController.updateText(activeId!!, text)
                        },
                        onSaveFile = {
                            app.editorController.save(activeId!!) {
                                app.ledgerState.load(app.workspace.mainFile(activeId!!).absolutePath)
                            }
                        },
                        onDiscardDraft = { app.editorController.discardDraft(activeId!!) },
                    )
                }
            }
        }
    }

    /**
     * Writes a real entry and then stops short of committing, leaving an open journal
     * record on disk. Debug builds only — this exists so `adb shell am kill` can be
     * timed to land in the window the write protocol has to survive.
     */
    private suspend fun stallMidWrite(
        app: BeancountApplication,
        projectId: String,
        introducesErrors: Boolean,
    ) {
        val file = app.workspace.projectDir(projectId).resolve("files/transactions.beancount")
        val baseline = if (introducesErrors) 0 else 0
        val record = app.journal.begin(
            projectId = projectId,
            operation = if (introducesErrors) "stall-test-bad" else "stall-test",
            files = listOf(file),
            baselineErrors = baseline,
            rollbackOnNewErrors = true,
        )
        // A date before the account was opened is an error; a later one is not.
        val entry = if (introducesErrors) {
            "\n2020-02-02 note Assets:Cash \"before the account existed\"\n"
        } else {
            "\n2027-02-02 note Assets:Cash \"written but never committed\"\n"
        }
        file.appendText(entry)
        app.journal.markRenamed(record)
        android.util.Log.w("M4Test", "stalled mid-write txn=" + record.txnId + "; kill me now")
        delay(10 * 60 * 1000)
    }

    /**
     * Saves through the M4 protocol: snapshot, journal, atomic rename, re-parse with
     * Beancount, and only then report success. A generated entry that introduces errors is
     * rolled back rather than kept, because broken output from guided entry is our bug.
     */
    private fun saveText(
        app: BeancountApplication,
        projectId: String,
        text: String,
        operation: String,
    ) {
        val ledgerPath = app.workspace.mainFile(projectId).absolutePath
        lifecycleScope.launch {
            saveMessage = "Saving…"
            val file = app.workspace.projectDir(projectId).resolve("files/transactions.beancount")
            val result = app.writer.append(projectId, file, text, operation = operation)
            saveMessage = when (result) {
                is dev.beancount.core.data.WriteResult.Committed ->
                    if (result.warnings.isEmpty()) "Saved."
                    else "Saved, with ${'$'}{result.warnings.size} ledger error(s) to review."
                is dev.beancount.core.data.WriteResult.RolledBack ->
                    "Not saved: ${'$'}{result.reason}. Your ledger is unchanged."
                is dev.beancount.core.data.WriteResult.Refused ->
                    "Not saved: ${'$'}{result.reason}"
            }
            // Re-read so the journal and balances show the new entry.
            app.ledgerState.load(ledgerPath)
        }
    }

    private fun openInBrowser(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

}

private enum class Surface(val label: String) {
    ADD("Add"),
    JOURNAL("Journal"),
    BALANCES("Balances"),
    REPORTS("Reports"),
    FILES("Files"),
    PROJECTS("Projects"),
}

@Composable
internal fun LedgerApp(
    engineStatus: EngineStatus,
    ledger: LedgerSnapshot?,
    editionName: String,
    entitlement: Entitlement,
    token: String?,
    debuggingAllowed: Boolean,
    onOpenExternally: (Uri) -> Unit,
    onSaveEntry: (TransactionDraft) -> Unit,
    onSaveDirective: (dev.beancount.core.domain.DirectiveDraft) -> Unit,
    saveState: String?,
    projects: List<ProjectSummary>,
    activeProjectId: String?,
    projectMessage: String?,
    onOpenProject: (String) -> Unit,
    onCreateProject: (String) -> Unit,
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
    var selected by remember { mutableStateOf(Surface.JOURNAL) }

    Scaffold(
        modifier = modifier,
        bottomBar = {
            // Six text-only destinations fit across a phone at normal text size and do not
            // at 200%: a NavigationBar there wrapped the labels mid-word and clipped
            // "Projects" off the screen edge. Below 130% the bar stays as it was; above it,
            // scrolling tabs keep every label whole and every destination reachable.
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
                            icon = {},
                            modifier = Modifier.semantics {
                                contentDescription = surface.label
                            },
                        )
                    }
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TrialBanner(entitlement)
            SurfaceContent(
                selected = selected,
                onSaveEntry = onSaveEntry,
                onSaveDirective = onSaveDirective,
                saveState = saveState,
                projects = projects,
                activeProjectId = activeProjectId,
                projectMessage = projectMessage,
                onOpenProject = onOpenProject,
                onCreateProject = onCreateProject,
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

@Composable
private fun SurfaceContent(
    selected: Surface,
    onSaveEntry: (TransactionDraft) -> Unit,
    onSaveDirective: (dev.beancount.core.domain.DirectiveDraft) -> Unit,
    saveState: String?,
    projects: List<ProjectSummary>,
    activeProjectId: String?,
    projectMessage: String?,
    onOpenProject: (String) -> Unit,
    onCreateProject: (String) -> Unit,
    onImportFolder: () -> Unit,
    onImportArchive: () -> Unit,
    onDeleteProject: (String) -> Unit,
    engineStatus: EngineStatus,
    ledger: LedgerSnapshot?,
    editionName: String,
    entitlement: Entitlement,
    token: String?,
    debuggingAllowed: Boolean,
    onOpenExternally: (Uri) -> Unit,
    editorState: dev.beancount.app.ui.EditorState,
    editorMessage: String?,
    editorFiles: List<EditableFile>,
    onOpenFile: (EditableFile) -> Unit,
    onCloseFile: () -> Unit,
    onEditorTextChanged: (String) -> Unit,
    onSaveFile: () -> Unit,
    onDiscardDraft: () -> Unit,
) {
    val padding = androidx.compose.foundation.layout.PaddingValues(0.dp)
    run {
        when (selected) {
            Surface.ADD -> {
                // Entry is free, always: adding entries is never gated
                // (docs/distribution.md 4.4).
                if (ledger == null) {
                    LoadingOrError(engineStatus, editionName)
                } else {
                    var otherDirectives by androidx.compose.runtime.saveable.rememberSaveable {
                        androidx.compose.runtime.mutableStateOf(false)
                    }
                    androidx.compose.foundation.layout.Column {
                        androidx.compose.foundation.layout.Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            androidx.compose.material3.FilterChip(
                                selected = !otherDirectives,
                                onClick = { otherDirectives = false },
                                label = { Text("Transaction") },
                            )
                            androidx.compose.material3.FilterChip(
                                selected = otherDirectives,
                                onClick = { otherDirectives = true },
                                label = { Text("Other directives") },
                            )
                        }
                        if (otherDirectives) {
                            dev.beancount.app.ui.DirectiveEntryScreen(
                                vocabulary = LedgerVocabulary.from(ledger),
                                onSave = onSaveDirective,
                                saveState = saveState,
                            )
                        } else {
                            QuickAddScreen(
                                vocabulary = LedgerVocabulary.from(ledger),
                                onSave = onSaveEntry,
                                saveState = saveState,
                            )
                        }
                    }
                }
            }

            Surface.FILES -> RawEditorScreen(
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

            Surface.PROJECTS -> ProjectPickerScreen(
                projects = projects,
                activeId = activeProjectId,
                onOpen = onOpenProject,
                onCreateNew = onCreateProject,
                onOpenFolder = onImportFolder,
                onOpenArchive = onImportArchive,
                onDelete = onDeleteProject,
                busyMessage = projectMessage,
            )

            Surface.JOURNAL, Surface.BALANCES -> {
                if (ledger == null) {
                    LoadingOrError(engineStatus, editionName, Modifier.padding(padding))
                } else if (selected == Surface.JOURNAL) {
                    JournalScreen(ledger, Modifier.padding(padding))
                } else {
                    BalancesScreen(ledger, Modifier.padding(padding))
                }
            }

            Surface.REPORTS -> {
                val serving = engineStatus as? EngineStatus.Serving
                if (!entitlement.allows(Feature.FAVA_REPORTS)) {
                    // Tier 1. The journal, balances and raw editing stay free, and the
                    // ledger is untouched — only this surface is locked.
                    LockedFeature(
                        title = "Reports and charts",
                        tier = Feature.FAVA_REPORTS.required.label,
                        modifier = Modifier.padding(padding),
                    )
                } else if (serving == null) {
                    LoadingOrError(engineStatus, editionName, Modifier.padding(padding))
                } else {
                    if (token != null) installSessionToken(serving.origin, token)
                    FavaWebView(
                        url = "${serving.origin}/${serving.ledgerSlug}/${ReportPath.INCOME_STATEMENT.path}/",
                        allowlist = OriginAllowlist(setOf(serving.origin)),
                        modifier = Modifier.fillMaxSize().padding(padding),
                        debuggingAllowed = debuggingAllowed,
                        onExternalLink = onOpenExternally,
                    )
                }
            }
        }
    }
}

/**
 * A locked feature says what it is, which tier unlocks it, and nothing else. No countdown,
 * no modal, no nagging (docs/ux.md §10) — and the user's data is never involved.
 */
@Composable
private fun LockedFeature(title: String, tier: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Text("Unlocked by the $tier contribution key.")
        Text(
            "Your ledger, the journal, balances, raw editing, and import and export all " +
                "stay free. This app is open source: you can also build it yourself.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

/** One quiet line about the trial. Shown while it runs, and once when it has ended. */
@Composable
internal fun TrialBanner(entitlement: Entitlement, modifier: Modifier = Modifier) {
    val text = when (val grant = entitlement.grant) {
        is Grant.Trial -> {
            val days = entitlement.trialDaysRemaining ?: 0
            if (days <= 1) "Trial: last day — everything is unlocked"
            else "Trial: $days days left — everything is unlocked"
        }
        is Grant.TrialEnded ->
            "Trial ended. Your ledger is untouched and stays fully editable."
        is Grant.Key -> "${'$'}{grant.tier.label} key · ${'$'}{grant.email}"
        Grant.Free -> return
    }
    Text(
        text = text,
        modifier = modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.bodySmall,
    )
}

@Composable
private fun LoadingOrError(
    status: EngineStatus,
    editionName: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Beancount for Android", style = MaterialTheme.typography.headlineSmall)
        Text(
            text = "Edition: $editionName",
            modifier = Modifier.semantics { contentDescription = "Edition $editionName" },
        )
        when (status) {
            is EngineStatus.Failed -> Text("The engine could not start: ${status.message}")
            else -> {
                CircularProgressIndicator()
                Text("Reading your ledger…")
            }
        }
    }
}
