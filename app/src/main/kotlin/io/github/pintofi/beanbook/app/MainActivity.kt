package io.github.pintofi.beanbook.app

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.automirrored.outlined.List
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pintofi.beanbook.app.engine.LedgerState
import io.github.pintofi.beanbook.app.plugins.collectUi
import io.github.pintofi.beanbook.app.engine.RecoveryOnStart
import io.github.pintofi.beanbook.app.ui.ScheduleEncryption
import io.github.pintofi.beanbook.app.ui.trashUi
import io.github.pintofi.beanbook.app.ui.onboarding.OnboardingFlow
import io.github.pintofi.beanbook.app.ui.onboarding.SetupActions
import io.github.pintofi.beanbook.app.backup.ScheduledBackupWorker
import io.github.pintofi.beanbook.app.ui.theme.BeancountTheme
import io.github.pintofi.beanbook.app.ui.DocumentCaptureHandle
import io.github.pintofi.beanbook.core.domain.DocumentIndex
import io.github.pintofi.beanbook.app.web.AllowlistWebViewClient
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.core.engine.EngineStatus
import io.github.pintofi.beanbook.core.engine.ReportPath

/**
 * The app's single activity: three surfaces over one ledger — the native journal and
 * balances, and Fava's own reports in the hardened WebView.
 */
class MainActivity : ComponentActivity() {

    override fun onResume() {
        super.onResume()
        // Mirroring is two ways, and only one of them had a trigger: a pass ran after a
        // local commit, so an edit made in the folder on a computer sat there until somebody
        // wrote something on the phone or tapped Mirror now. Coming back to the app is the
        // moment those edits are most likely to be waiting.
        val app = application as BeancountApplication
        if (app.mirror.enabled) {
            app.projectsState.activeId.value?.let { app.mirror.syncAfterCommit(it) }
        }
    }

    /**
     * What had to be put right at startup — an interrupted write, or a plugin that stopped
     * the app last time.
     *
     * `mutableStateOf`, and actually rendered. It used to be a plain field: computed on
     * every launch, assigned, and never read by anything, so the app silently recovered from
     * an interrupted write and told nobody.
     */
    private var hideWidgetAmounts: Boolean by mutableStateOf(false)

    private var recoveryMessage: String? by mutableStateOf(null)
    internal var saveMessage: String? by mutableStateOf(null)

    /**
     * A change the app wants to make to one of the ledger's own files, waiting to be shown to
     * the person and approved.
     *
     * The app writes entries and document links because the person asked for those. Anything
     * else -- an `include` line that makes a new file part of the ledger -- is a change to how
     * their ledger is put together, and they are told what it is before it happens.
     */
    internal data class LedgerEdit(
        val file: String,
        val line: String,
        val why: String,
        val apply: () -> Unit,
    )

    internal var pendingLedgerEdit: LedgerEdit? by mutableStateOf(null)

    /** A link out of a report, waiting for the person to confirm it. */
    internal var pendingExternalLink: Uri? by mutableStateOf(null)

    /**
     * How many entries this session has written and committed from the entry form.
     *
     * A count rather than a flag, so two saves in a row are two changes the form can react
     * to. It moves only on `WriteResult.Committed` -- after the durable write and the
     * re-parse -- which is what makes it safe for the form to close on: invariant 2 says no
     * success before both, and a form that closed on "Saving…" would be a success message
     * sent early.
     */
    internal var entryCommits: Int by mutableStateOf(0)

    /** The entry whose own source is open for editing, if any. */
    internal var editingEntry: io.github.pintofi.beanbook.engine.local.EntrySlice? by mutableStateOf(null)

    /** The same entry as fields, when a draft describes it fully. */
    internal var editingDraft: io.github.pintofi.beanbook.core.domain.StructuredEdit.Editable?
        by mutableStateOf(null)

    /** The documents already filed in the open project; refreshed after every change. */
    internal var documents: List<String> by mutableStateOf(emptyList())

    /**
     * SAF pickers. These are the only way a project enters the app, and every byte they
     * hand over is validated before anything is written (docs/storage-and-backup.md §4).
     */
    /**
     * A folder the user picked, with the grant taken persistably.
     *
     * Persistably in both directions: without it the grant dies with the activity, so an
     * import that resumed or an export that half-ran would have no way to finish.
     */
    private fun treePicker(grant: Int, use: (BeancountApplication, android.net.Uri) -> Unit) =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            uri?.let {
                contentResolver.takePersistableUriPermission(it, grant)
                use(application as BeancountApplication, it)
            }
        }

    internal val folderPicker = treePicker(
        android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION,
    ) { app, uri ->
        app.projectsState.importFolder(contentResolver, uri) { id -> afterImport(app, id) }
    }

    private val exportPicker = treePicker(
        android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
    ) { app, uri ->
        app.projectsState.exportFolder(contentResolver, uri)
    }

    internal val archivePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val app = application as BeancountApplication
        uri?.let {
            app.projectsState.importArchive(contentResolver, it, cacheDir) { id -> afterImport(app, id) }
        }
    }

    /**
     * The passphrase for the backup being written, if the user asked for one.
     *
     * Held only between tapping "Back up" and the picker returning a destination, then
     * wiped: a passphrase in a field for longer than it is needed is a passphrase in a heap
     * dump.
     */
    private var pendingBackupPassphrase: CharArray? = null

    private val backupWriter = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri ->
        val app = application as BeancountApplication
        val projectId = app.projectsState.activeId.value
        val passphrase = pendingBackupPassphrase
        pendingBackupPassphrase = null
        if (uri != null && projectId != null) {
            app.backupController.backupTo(contentResolver, uri, projectId, passphrase)
        }
        passphrase?.fill('\u0000')
    }

    /**
     * Picks a plugin to install: a single `.py`, or a `.whl`/`.zip` package.
     *
     * `OpenDocument` rather than `OpenDocumentTree`, because a folder grant would give the
     * app standing access to wherever the user keeps their files, and installing a plugin
     * needs one read of one thing.
     */
    private val pluginPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val app = application as BeancountApplication
        uri?.let { app.pluginsState.installFrom(it, displayNameOf(it)) }
    }

    /** One read of one script, for the same reason the plugin picker is not a tree grant. */
    private val scriptPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val app = application as BeancountApplication
        uri?.let { app.scriptRunner.runFrom(it, displayNameOf(it)) }
    }

    private val backupReader = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        val app = application as BeancountApplication
        val projectId = app.projectsState.activeId.value
        if (uri != null && projectId != null) {
            app.backupController.stageRestore(contentResolver, uri, projectId)
        }
    }

    /** The folder scheduled backups are written into. Persisted, so the schedule survives. */
    private val backupFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val app = application as BeancountApplication
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            app.backupSettings.setFolder(it.toString())
            backupFolder = it.toString()
        }
    }

    private var backupFolder: String? by mutableStateOf(null)
    private var scheduleEnabled: Boolean by mutableStateOf(false)
    private var mirrorFolder: String? by mutableStateOf(null)
    private var mirrorEnabled: Boolean by mutableStateOf(false)

    /** The folder the project is mirrored into. Persisted so mirroring survives a restart. */
    internal val mirrorFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val app = application as BeancountApplication
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
            // Before any ledger exists this is the setup choosing where the new ledger's copy goes.
            if (app.projectsState.activeId.value == null) {
                app.newLedgerSetup.update { d -> d.copy(mirrorFolder = it.toString()) }
                return@registerForActivityResult
            }
            app.mirror.setFolder(it.toString())
            mirrorFolder = it.toString()
        }
    }


    /** Opens a project: engine, ledger, and the active-project record all move together. */
    /** The name the picker shows, so an installed plugin is recognisable later. */
    private fun displayNameOf(uri: android.net.Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst()) cursor.getString(column) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty()

    /**
     * The project a widget asked to open, if it may be honoured.
     *
     * A widget is long-lived and the intent it carries was built whenever it was last drawn,
     * possibly before the project it names was deleted. `WidgetLink` checks the id against
     * the projects that exist rather than taking the intent's word for it.
     */
    private fun widgetTarget(app: BeancountApplication): io.github.pintofi.beanbook.core.data.WidgetLink.Target? {
        val requested = intent?.getStringExtra(
            io.github.pintofi.beanbook.app.widget.WidgetRenderer.EXTRA_PROJECT,
        ) ?: return null
        return io.github.pintofi.beanbook.core.data.WidgetLink.resolve(
            requestedProject = requested,
            requestedSurface = intent?.getStringExtra(
                io.github.pintofi.beanbook.app.widget.WidgetRenderer.EXTRA_SURFACE,
            ),
            knownProjects = app.projectsState.projects.value.map { it.id },
            activeProject = app.projectsState.activeId.value,
        )
    }

    /**
     * Which surface a widget asked the app to open on, if it was launched by one.
     *
     * `WidgetLink.resolve` has always returned this and the caller has always thrown it away
     * by taking `.projectId`, so the add-entry widget -- which correctly sends `ADD` -- landed
     * on whatever the app opens by default.
     */
    internal var requestedSurface: String? by mutableStateOf(null)

    internal fun openProject(app: BeancountApplication, projectId: String) {
        val ledgerPath = app.workspace.mainFile(projectId).absolutePath
        app.projectsState.open(projectId)
        // Before anything can sync: the mirror answers for this project only.
        app.mirror.useProject(projectId)
        app.undo.dismiss()
        askForNotifications()   // once, and only when there is a ledger for it to be about
        mirrorFolder = app.mirror.folderUri
        mirrorEnabled = app.mirror.enabled
        app.templatesState.open(projectId)
        app.pluginsState.openProject(app.workspace.projectDir(projectId))
        app.packagesState.refresh()   // shared by every ledger; only needs what is on disk
        app.scriptRunner.onProjectOpened(app.workspace.mainFile(projectId))
        refreshDocuments(app, projectId)

        // Asked at load time, not captured now: which plugins are on can change between
        // loads, and a stale answer would either run something the user turned off or miss
        // something they turned on.
        app.ledgerState.pluginContext = LedgerState.PluginContext {
            app.pluginsState.enabledDirectories()
        }
        app.ledgerState.allowBridge = currentPluginPolicy().allowsAndroidBridge

        app.engineManager.start(ledgerPath)
        app.ledgerState.load(ledgerPath)

        // The widgets' entire refresh mechanism: the app writes what it just parsed and asks
        // them to redraw. No alarm, no worker, nothing for a background limit to restrict —
        // there is no background work (docs/ux.md §8).
        lifecycleScope.launch {
            // Republished when the basis changes too, so the widgets follow the same switch
            // as Home and Balances.
            kotlinx.coroutines.flow.combine(
                app.ledgerState.snapshot,
                io.github.pintofi.beanbook.app.ui.ValuationPreference(this@MainActivity).flow,
            ) { snapshot, valuation -> snapshot to valuation }.collect { (snapshot, valuation) ->
                if (snapshot != null) publishWidgets(app, projectId, snapshot, valuation)
            }
        }
    }

    /** Writes the widget cache from a parsed ledger, then asks the widgets to redraw. */
    private suspend fun publishWidgets(
        app: BeancountApplication,
        projectId: String,
        ledger: io.github.pintofi.beanbook.core.domain.LedgerSnapshot,
        valuation: io.github.pintofi.beanbook.core.domain.Valuation,
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                io.github.pintofi.beanbook.core.data.WidgetCache(filesDir.resolve("state")).write(
                    io.github.pintofi.beanbook.core.data.WidgetCache.from(
                        projectId = projectId,
                        ledger = ledger,
                        nowEpochMs = System.currentTimeMillis(),
                        // Every account, not a shortlist. It used to be the first twelve
                        // names under Assets: or Liabilities:, so a widget could not be
                        // pointed at an expense category, at a top-level account, or at the
                        // thirteenth bank account -- and the configuration screen offered
                        // only what this had cached, so those accounts did not exist as far
                        // as the home screen was concerned. The cache is app-private
                        // storage that never enters a backup or a mirror, and it already
                        // held account names; the cap bought no privacy it did not have.
                        accounts = ledger.accounts.map { it.name },
                        valuation = valuation,
                    ),
                )
                io.github.pintofi.beanbook.app.widget.WidgetRefresh.refreshAll(this@MainActivity)
            }
        }
    }

    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { noteNotificationAnswer(it) }

    /**
     * Android 13+ shows no notification, not even a foreground service's, until the user
     * allows it, and the app never asked. Asked once: a refusal is respected, and the
     * work still runs -- only the "Beanbook keeps working" notice is not shown.
     */
    internal fun askForNotifications(force: Boolean = false) {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU) return
        val granted = checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val prefs = getSharedPreferences("prompts", MODE_PRIVATE)
        if (granted || (!force && prefs.getBoolean("asked_notifications", false))) return
        prefs.edit().putBoolean("asked_notifications", true).apply()
        notificationPermission.launch(android.Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Says out loud what API 35+ already does to a targetSdk-35+ app: draw it behind the
        // system bars. Measured, this line changes nothing on API 36 — the platform had
        // forced it either way. It matters below 35, where the window is not edge to edge
        // unless asked, so that one arrangement holds from 29 to 36 rather than the layout
        // depending on which Android is running it. The clearance itself comes from
        // safeDrawingPadding at the root, not from here.
        enableEdgeToEdge()

        val app = application as BeancountApplication

        // A setup killed mid-creation leaves a staging folder; nothing else would ever remove it.
        lifecycleScope.launch(kotlinx.coroutines.Dispatchers.IO) { runCatching { app.workspace.sweepStaleStaging() } }
        app.projectsState.refresh()
        hideWidgetAmounts = io.github.pintofi.beanbook.app.widget.WidgetPreferences(this).hideAmounts()
        backupFolder = app.backupSettings.folderUri()
        mirrorFolder = app.mirror.folderUri
        mirrorEnabled = app.mirror.enabled

        lifecycleScope.launch {
            // Recovery first: an interrupted write is resolved before the ledger is shown
            // or served, never after (docs/architecture.md 5).
            app.projectsState.activeId.value?.let { active ->
                val report = RecoveryOnStart(app.writer).run(active)
                if (!report.isClean) recoveryMessage = report.describe()
                openProject(app, active)

                // A plugin that stopped the app last time is turned off before anything
                // tries to load it again, or the app never gets far enough to be told to.
                recoverFromPluginCrash(app, active)
            }

            // Debug-only: begin a write and stall before committing, so a process kill
            // can be timed to land mid-write (the process-death test).
            val stall = intent.getStringExtra("m4_test")
            val active = widgetTarget(app)?.projectId ?: app.projectsState.activeId.value
            if (BuildConfig.DEBUG && active != null && stall != null &&
                stall.startsWith("stall_mid_write")
            ) {
                stallMidWrite(app, active, introducesErrors = stall.endsWith("_bad"))
            }

            // Debug-only: stall *inside* a plugin load, so a process kill lands in the
            // window safe mode has to survive.
            if (BuildConfig.DEBUG && active != null &&
                intent.getStringExtra("m11_test") == "stall_mid_plugin_load"
            ) {
                stallMidPluginLoad(app, active)
            }
        }

        requestedSurface = widgetTarget(app)?.surface

        setContent {
            val themeChoice by app.themeSettings.choice.collectAsStateWithLifecycle()
            val dynamicColour by app.themeSettings.dynamicColour.collectAsStateWithLifecycle()
            BeancountTheme(choice = themeChoice, dynamicColour = dynamicColour) {
            androidx.compose.runtime.CompositionLocalProvider(
                io.github.pintofi.beanbook.app.ui.LocalPageRenderer provides { page, _ ->
                    app.documentCapture.preview(page)
                },
                io.github.pintofi.beanbook.app.ui.LocalLedgerFilter provides app.ledgerFilter,
                io.github.pintofi.beanbook.app.ui.LocalDocumentsTab provides app.documentsTab,
            ) {
                // What actually keeps content clear of the bars. At the root rather than per
                // screen, because onboarding and the project picker render outside the
                // Scaffold that would otherwise have handled it — and those are the first
                // things anybody sees. Consuming here also stops the Scaffold's top bar
                // adding the same inset a second time.
                //
                // The symptom was not overlap. On the API 36 emulator the title began at
                // exactly 63px, which is both the status bar height and this screen's own
                // 24dp padding: flush against the bar with no clearance whatsoever. It now
                // starts at 126px. WindowInsetsTest measures that, and fails if this is
                // removed.
                // The app's own background reaches the screen edges, under the status and
                // navigation bars, which is what edge-to-edge means and what every current
                // Android app does. Insetting the whole Surface — which is what this used to
                // do — put the window behind the bars and then pulled the app back out of
                // them again, so the platform saw an edge-to-edge app and the user saw a
                // letterboxed one.
                //
                // Clearance is now each component's own business, which is where Material
                // already puts it: Scaffold gives its top bar the status bar inset and its
                // bottom bar the navigation bar inset, and hands content the rest. What is
                // *not* inside a Scaffold — onboarding, and the dialogs — asks for its own,
                // below.
                Surface(modifier = Modifier.fillMaxSize()) {
                    val engineStatus by app.engineManager.status.collectAsStateWithLifecycle()
                    val ledger by app.ledgerState.snapshot.collectAsStateWithLifecycle()
                    val ledgerLoading by app.ledgerState.loading.collectAsStateWithLifecycle()
                    val projects by app.projectsState.projects.collectAsStateWithLifecycle()
                    val trashedProjects by app.projectsState.trash.collectAsStateWithLifecycle()
                    val activeId by app.projectsState.activeId.collectAsStateWithLifecycle()
                    val projectMessage by app.projectsState.message.collectAsStateWithLifecycle()
                    val editorState by app.editorController.state.collectAsStateWithLifecycle()
                    val editorMessage by app.editorController.message.collectAsStateWithLifecycle()
                    val templates by app.templatesState.templates.collectAsStateWithLifecycle()
                    val documentPages by app.documentCapture.pages.collectAsStateWithLifecycle()
                    // Survives rotation: the scanner is a mode, and turning the phone to fit a
                    // wide receipt must not drop you back into the form mid-stack.
                    var scanning by androidx.compose.runtime.saveable.rememberSaveable {
                        androidx.compose.runtime.mutableStateOf(false)
                    }
                    val documentMessage by
                        app.documentCapture.message.collectAsStateWithLifecycle()
                    val pendingRestore by
                        app.backupController.pendingRestore.collectAsStateWithLifecycle()
                    val backupMessage by
                        app.backupController.message.collectAsStateWithLifecycle()
                    val passphraseNeeded by
                        app.backupController.passphraseNeeded.collectAsStateWithLifecycle()
                    val mirrorConflicts by app.mirror.conflicts.collectAsStateWithLifecycle()
                    val mirrorStatus by app.mirror.status.collectAsStateWithLifecycle()
                    val mirrorMessage by app.mirror.message.collectAsStateWithLifecycle()
                    val remoteStatus by app.remoteState.status.collectAsStateWithLifecycle()
                    val remoteCapabilities by
                        app.remoteState.capabilities.collectAsStateWithLifecycle()
                    val remoteMessage by app.remoteState.message.collectAsStateWithLifecycle()
                    val remotePending by app.remoteState.pendingRetry.collectAsStateWithLifecycle()
                    val pluginRows by app.pluginsState.rows.collectAsStateWithLifecycle()
                    val pluginSafeMode by app.pluginsState.safeMode.collectAsStateWithLifecycle()
                    val pluginMessage by app.pluginsState.message.collectAsStateWithLifecycle()
                    // Read from the ledger itself rather than remembered beside it: the file
                    // is the record of what it loads, and an edit in the raw editor has to
                    // show here too.
                    val catalogue = app.catalogueState.collectUi()
                    val packagesInstalled by app.packagesState.installed.collectAsStateWithLifecycle()
                    val packagesStatus by app.packagesState.status.collectAsStateWithLifecycle()
                    val packagesMessage by app.packagesState.message.collectAsStateWithLifecycle()
                    val packages = app.packagesState
                        .ui(packagesInstalled, packagesStatus, packagesMessage)
                        .copy(catalogue = catalogue)
                    val dateRange by app.ledgerState.dateRange.collectAsStateWithLifecycle()
                    val restartNeeded by app.engineRestart.needed.collectAsStateWithLifecycle()
                    // Whatever changed under the running engine -- a plugin or package installed
                    // or removed, safe mode switched -- is picked up by restarting it at once.
                    // It used to wait behind a "Restart needed" card.
                    androidx.compose.runtime.LaunchedEffect(restartNeeded, activeId) {
                        if (restartNeeded && activeId != null) restartEngine(app, activeId)
                    }
                    FollowDiskChanges(app, activeId)
                    val scriptRunning by app.scriptRunner.running.collectAsStateWithLifecycle()
                    val scriptOutcome by app.scriptRunner.outcome.collectAsStateWithLifecycle()
                    val scriptMessage by app.scriptRunner.message.collectAsStateWithLifecycle()
                    val savedScripts by app.scriptRunner.saved.collectAsStateWithLifecycle()

                    if (activeId == null) {
                        // No project yet: onboarding, not an empty ledger screen.
                        // Outside the Scaffold, so it takes the insets itself. This is the
                        // first screen anybody sees and the one where the title once sat
                        // flush against the status bar.
                        OnboardingFlow(
                            setup = app.newLedgerSetup,
                            modifier = Modifier.safeDrawingPadding(),
                            themeChoice = themeChoice,
                            actions = setupActions(app),
                            busyMessage = projectMessage,
                            remoteNote = remoteNote(app, remoteStatus, remoteMessage),
                            trash = app.projectsState.trashUi(trashedProjects) { openProject(app, it) },
                        )
                        return@Surface
                    }

                    LedgerApp(
                        openOn = requestedSurface,
                        onEditEntry = { entry ->
                            activeId?.let { beginEntryEdit(app, it, entry) }
                        },
                        onDeleteEntry = { entry ->
                            activeId?.let { deleteEntry(app, it, entry) }
                        },
                        engineStatus = engineStatus,
                        ledger = ledger,
                        token = app.engine.sessionToken,
                        debuggingAllowed = BuildConfig.DEBUG,
                        onOpenExternally = ::askToOpenExternally,
                        onSaveEntry = { draft ->
                            // Connected to a remote server means the ledger is over there.
                            // Writing to the local project instead would put the entry
                            // somewhere the user is not looking and cannot see it.
                            if (remoteStatus is EngineStatus.Serving) {
                                app.remoteState.submit(draft)
                            } else {
                                // Written the way this ledger writes: Fava's `currency-column`
                                // and `indent` are the file's own layout, and a file the user
                                // keeps at column 80 should not come back at 61.
                                saveText(
                                    app,
                                    activeId!!,
                                    draft.render(
                                        currencyColumn = (ledger?.format
                                            ?: io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN)
                                            .currencyColumn,
                                        indent = (ledger?.format
                                            ?: io.github.pintofi.beanbook.core.domain.LedgerFormat.PLAIN)
                                            .indent,
                                    ),
                                    ENTRY_OPERATION,
                                    target = entryTarget(app, activeId!!, ledger),
                                )
                            }
                        },
                        // Built once and passed whole, and built *outside* this class: the
                        // same callbacks are needed by the documents screen and by
                        // transaction entry, and MainActivity is already the largest class
                        // in the app.
                        documentCapture = captureHandle(app, activeId, documentPages, scanning) {
                            scanning = it
                        },
                        // The folder alone cannot say what a document is filed under, nor
                        // tell one the ledger knows from a stray file; the directives alone
                        // cannot say whether the file is still there. Reconciled here,
                        // where both are in hand.
                        documents = DocumentIndex.reconcile(
                            root = activeId?.let { app.workspace.projectDir(it).absolutePath }
                                .orEmpty(),
                            onDisk = documents,
                            directives = ledger?.documents.orEmpty(),
                            attachments = documentAttachments(ledger),
                        ),
                        documentMessage = documentMessage,
                        onSaveDocument = { account, label -> attachDocument(app, activeId!!, account, label) },
                        onDiscardPages = { app.documentCapture.reset(discardCaptures = true) },
                        onRefileDocument = { path, account, from ->
                            refileDocument(app, activeId!!, path, account, from)
                        },
                        onUnlinkDocument = { path, from -> unlinkDocument(app, activeId!!, path, from) },
                        onDeleteDocumentFile = { path -> deleteDocumentFile(app, activeId!!, path) },
                        onShareDocument = { path -> shareDocument(app, activeId!!, path) },
                        diagnostics = diagnosticsUi(),
                        fileForDocument = { path -> documentFile(app, activeId, path) },
                        pendingRestore = pendingRestore,
                        passphraseNeeded = passphraseNeeded,
                        onUnlockRestore = { typed ->
                            app.backupController.retryRestoreWithPassphrase(activeId!!, typed)
                        },
                        onBackUpNowWithPassphrase = { typed ->
                            // Held only until the save-file picker comes back with a target.
                            pendingBackupPassphrase = typed
                            backupWriter.launch(app.backupController.suggestedName(activeId!!))
                        },
                        backupMessage = backupMessage,
                        scheduleEnabled = scheduleEnabled,
                        scheduleFolder = backupFolder,
                        lastBackupRun = app.backupSettings.lastRun(),
                        rollbackPoints = app.backupController.rollbackPoints(activeId!!),
                        onChooseRestoreFile = {
                            backupReader.launch(arrayOf("application/octet-stream", "*/*"))
                        },
                        onAcceptRestore = {
                            app.backupController.acceptRestore(activeId!!) {
                                openProject(app, activeId!!)
                            }
                        },
                        onDiscardRestore = { app.backupController.discardRestore(activeId!!) },
                        mirrorEnabled = mirrorEnabled,
                        mirrorFolder = mirrorFolder,
                        mirrorConflicts = mirrorConflicts,
                        mirrorStatus = mirrorStatus,
                        mirrorMessage = mirrorMessage,
                        onChooseMirrorFolder = { mirrorFolderPicker.launch(null) },
                        onMirrorEnabledChanged = { on ->
                            mirrorEnabled = on
                            app.mirror.setEnabled(on)
                            if (on) app.mirror.sync(activeId!!)
                        },
                        onMirrorNow = { app.mirror.sync(activeId!!) },
                        onKeepMine = { path -> app.mirror.keepMine(activeId!!, path) },
                        onKeepTheirs = { path -> app.mirror.keepTheirs(activeId!!, path) },
                        remoteStatus = remoteStatus,
                        remoteCapabilities = remoteCapabilities,
                        savedRemoteAddress = app.remoteState.savedAddress,
                        savedRemoteUsername = app.remoteState.savedUsername,
                        remotePendingRetry = remotePending != null,
                        remoteMessage = remoteMessage,
                        themeChoice = themeChoice,
                        dynamicColour = dynamicColour,
                        // The widgets are drawn in these colours too, and nothing else
                        // would tell them the setting had changed.
                        onThemeChoice = {
                            app.themeSettings.setChoice(it)
                            io.github.pintofi.beanbook.app.widget.WidgetRefresh
                                .refreshAll(this@MainActivity)
                        },
                        onDynamicColour = {
                            app.themeSettings.setDynamicColour(it)
                            io.github.pintofi.beanbook.app.widget.WidgetRefresh
                                .refreshAll(this@MainActivity)
                        },
                        hideWidgetAmounts = hideWidgetAmounts,
                        onHideWidgetAmounts = { hide ->
                            io.github.pintofi.beanbook.app.widget.WidgetPreferences(this@MainActivity)
                                .setHideAmounts(hide)
                            hideWidgetAmounts = hide
                            io.github.pintofi.beanbook.app.widget.WidgetRefresh.refreshAll(this@MainActivity)
                        },
                        pluginRows = pluginRows,
                        pluginSafeMode = pluginSafeMode,
                        pluginMessage = pluginMessage,
                        packages = packages,
                        dateFrom = dateRange.first,
                        dateTo = dateRange.second,
                        onDateRange = { from, to -> app.ledgerState.setDateRange(from, to) },
                        onInstallPlugin = { pluginPicker.launch(arrayOf("*/*")) },
                        scriptRunning = scriptRunning,
                        scriptOutcome = scriptOutcome,
                        scriptMessage = scriptMessage,
                        onPickScript = { scriptPicker.launch(arrayOf("*/*")) },
                        savedScripts = savedScripts,
                        onRunSavedScript = { app.scriptRunner.runSaved(it) },
                        onForgetSavedScript = { app.scriptRunner.forget(it) },
                        onCancelScript = { app.scriptRunner.cancel() },
                        onSetPluginEnabled = { id, on -> app.pluginsState.setEnabled(id, on) },
                        onRemovePlugin = { app.pluginsState.remove(it) },
                        onSetPluginSafeMode = { app.pluginsState.setSafeMode(it) },
                        recoveryNotice = recoveryMessage,
                        onDismissRecovery = { recoveryMessage = null },
                        remoteReports = (remoteStatus as? EngineStatus.Serving)?.let {
                            val url = app.remoteState.reportUrl(ReportPath.INCOME_STATEMENT)
                            val origins = app.remoteState.allowedOrigins()
                            if (url == null || origins.isEmpty()) {
                                null
                            } else {
                                RemoteReports(url, origins) {
                                    app.remoteState.httpAuth()?.let { (user, secret) ->
                                        AllowlistWebViewClient.HttpAuth(user, secret)
                                    }
                                }
                            }
                        },
                        onRemoteConnect = { address, username, secret ->
                            app.remoteState.connect(address, username, secret)
                            secret?.fill(' ')
                        },
                        onRemoteDisconnect = { app.remoteState.disconnect() },
                        onRemoteForget = { app.remoteState.forget() },
                        onRemoteRetry = { app.remoteState.retryPending() },
                        onRemoteDiscard = { app.remoteState.discardPending() },
                        onChooseScheduleFolder = { backupFolderPicker.launch(null) },
                        scheduleEncryption = ScheduleEncryption(
                            app.backupSettings.encryptsScheduled(),
                            app.backupSettings::setScheduledPassphrase,
                        ),
                        onScheduleChanged = { enabled ->
                            scheduleEnabled = enabled
                            if (enabled) {
                                ScheduledBackupWorker.schedule(this@MainActivity)
                            } else {
                                ScheduledBackupWorker.cancel(this@MainActivity)
                            }
                        },
                        templates = templates,
                        onSaveTemplate = { app.templatesState.save(it) },
                        onDeleteTemplate = { app.templatesState.delete(it) },
                        onSaveDirective = { draft ->
                            saveText(
                                app,
                                activeId!!,
                                draft.render(currencyColumnOf(ledger)),
                                DIRECTIVE_OPERATION,
                                target = directiveTarget(app, activeId!!, ledger, draft),
                            )
                        },
                        onOpenAccount = if (remoteStatus is EngineStatus.Serving) null else { draft ->
                            writeAccountDirective(app, activeId!!, ledger, draft)
                        },
                        // In remote mode the outcome of a save comes from the server, so
                        // that is what the Add screen has to report — otherwise a remote
                        // save looks like nothing happened at all.
                        entryCommits = entryCommits,
                        editSession = entryEditSession(app, activeId),
                        onRestartApp = { restartApp() },
                        onEngineRetry = { app.engineManager.retry() },
                        onDeleteEntries = { entries -> activeId?.let { deleteEntries(app, it, entries) } },
                        // Only from a serving engine: a restart already under way would
                        // otherwise be ended by the error page it causes.
                        onEngineUnreachable = {
                            if (app.engineManager.status.value is EngineStatus.Serving) {
                                app.engineManager.restart()
                            }
                        },
                        saveState = if (remoteStatus is EngineStatus.Serving) {
                            remoteMessage
                        } else {
                            saveMessage
                        },
                        projects = projects,
                        activeProjectId = activeId,
                        projectMessage = projectMessage,
                        onOpenProject = { id -> openProject(app, id) },
                        onCreateProject = { title ->
                            app.projectsState.createNew(title) { openProject(app, it) }
                        },
                        onExportProject = { exportPicker.launch(null) },
                        onImportFolder = { folderPicker.launch(null) },
                        onImportArchive = { archivePicker.launch(arrayOf("application/zip", "*/*")) },
                        onDeleteProject = { id -> app.projectsState.delete(id) },
                        projectTrash = app.projectsState.trashUi(trashedProjects),
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
                        refreshing = ledgerLoading,
                        onRefresh = {
                            app.ledgerState.reload()
                            // Documents too: Organize is refreshed by the same pull.
                            activeId?.let { refreshDocuments(app, it) }
                        },
                    )

                    // Over the app rather than inside a screen: an entry can be opened for
                    // editing from the journal or from its detail view, and the dialog
                    // should not belong to whichever one happened to open it.
                    EntryEditor(app, activeId)

                    LedgerEditDialog()
                    ExternalLinkDialog()
                }
            }
            }
        }
    }

    /**
     * Turns off whatever was mid-load when the app last died, and says so.
     *
     * Runs before any plugin is loaded. The marker it reads is written from Python
     * immediately before each import and flushed, so it survives a kill that gives the app
     * no chance to clean up.
     */
    private suspend fun recoverFromPluginCrash(app: BeancountApplication, projectId: String) {
        withContext(Dispatchers.IO) {
            val projectRoot = app.workspace.projectDir(projectId)
            val recovery = io.github.pintofi.beanbook.core.data.PluginSafeMode(projectRoot)
                .recoverFromCrash(io.github.pintofi.beanbook.core.data.PluginStore(projectRoot))
                ?: return@withContext
            recoveryMessage = listOfNotNull(recoveryMessage, recovery.describe())
                .joinToString("\n\n")
        }
    }

    /**
     * Writes a plugin that never finishes importing, then loads it.
     *
     * Debug builds only. The point is to be killed: a plugin that hangs or crashes the
     * process leaves the app unable to start, and the marker Python writes before each
     * import is the only thing that survives to say which plugin it was. Stalling rather
     * than crashing outright means the kill can be timed from outside, exactly as the
     * mid-write process-death test does.
     */
    private suspend fun stallMidPluginLoad(app: BeancountApplication, projectId: String) {
        withContext(Dispatchers.IO) {
            val projectRoot = app.workspace.projectDir(projectId)
            val store = io.github.pintofi.beanbook.core.data.PluginStore(projectRoot)
            val safeMode = io.github.pintofi.beanbook.core.data.PluginSafeMode(projectRoot)
            safeMode.beginLoad()

            // Installed through the store, exactly as a user's own plugin would be, so the
            // recovery has a real registered plugin to turn off rather than a stray folder.
            val candidate = java.io.File(cacheDir, "stalls_forever.py").apply {
                writeText("import time\nwhile True:\n    time.sleep(1)\n")
            }
            val installed = store.install(
                candidate = candidate,
                policy = currentPluginPolicy(),
                sourceLabel = "debug harness",
            ) as? io.github.pintofi.beanbook.core.data.InstallResult.Installed ?: return@withContext
            store.setEnabled(installed.plugin.id, true)

            android.util.Log.i("M11", "stalling inside a plugin load; kill me now")
            // Through the binder, like everything else that touches Python. This harness
            // used to start an interpreter in the app process, which is precisely the hole
            // the engine process split closes (docs/architecture.md).
            io.github.pintofi.beanbook.engine.local.PluginLoader(this@MainActivity).load(
                pluginDir = store.directoryFor(installed.plugin).absolutePath,
                modules = "stalls_forever",
                allowBridge = currentPluginPolicy().allowsAndroidBridge,
                journalPath = safeMode.journalPath(),
                journalLabel = installed.plugin.id,
            )
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
        val record = app.journal.begin(
            projectId = projectId,
            operation = if (introducesErrors) "stall-test-bad" else "stall-test",
            files = listOf(file),
            baselineErrors = 0,
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
}
