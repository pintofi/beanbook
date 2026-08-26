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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.TextButton
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.beancount.app.engine.LedgerState
import dev.beancount.app.engine.RecoveryOnStart
import dev.beancount.app.ui.BalancesScreen
import dev.beancount.app.ui.JournalScreen
import dev.beancount.app.ui.OnboardingScreen
import dev.beancount.app.ui.EditableFile
import dev.beancount.app.ui.ProjectPickerScreen
import dev.beancount.app.ui.RawEditorScreen
import dev.beancount.app.ui.ContributeScreen
import dev.beancount.app.backup.ScheduledBackupWorker
import dev.beancount.app.ui.BackupScreen
import dev.beancount.app.ui.MirrorScreen
import dev.beancount.app.ui.AppearanceScreen
import dev.beancount.app.ui.PluginsScreen
import dev.beancount.app.ui.theme.BeancountTheme
import dev.beancount.app.ui.RemoteScreen
import dev.beancount.core.data.MirrorAction
import dev.beancount.app.ui.DocumentsScreen
import dev.beancount.core.data.RestoreStaged
import dev.beancount.core.domain.CapturedPages
import dev.beancount.core.domain.DirectiveDraft
import java.io.File as JavaFile
import java.time.LocalDate
import dev.beancount.app.ui.QuickAddScreen
import dev.beancount.core.domain.EntryTemplate
import dev.beancount.core.data.ProjectSummary
import dev.beancount.core.domain.LedgerVocabulary
import dev.beancount.core.domain.TransactionDraft
import dev.beancount.app.web.AllowlistWebViewClient
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

    /**
     * What had to be put right at startup — an interrupted write, or a plugin that stopped
     * the app last time.
     *
     * `mutableStateOf`, and actually rendered. It was a plain field until M11: computed on
     * every launch, assigned, and never read by anything, so the app silently recovered from
     * an interrupted write and told nobody.
     */
    /**
     * How many accounts reach the widget cache.
     *
     * A cap, not everything: the cache lives outside the project tree so a widget can read
     * it, and a full chart of accounts is a description of somebody's life.
     */
    private val widgetAccountLimit = 12

    private var hideWidgetAmounts: Boolean by mutableStateOf(false)

    private var recoveryMessage: String? by mutableStateOf(null)
    private var saveMessage: String? by mutableStateOf(null)

    /** The documents already filed in the open project; refreshed after every change. */
    private var documents: List<String> by mutableStateOf(emptyList())

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
    private val mirrorFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        val app = application as BeancountApplication
        uri?.let {
            contentResolver.takePersistableUriPermission(
                it,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
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
    private fun widgetTarget(app: BeancountApplication): String? {
        val requested = intent?.getStringExtra(
            dev.beancount.app.widget.WidgetRenderer.EXTRA_PROJECT,
        ) ?: return null
        return dev.beancount.core.data.WidgetLink.resolve(
            requestedProject = requested,
            requestedSurface = intent?.getStringExtra(
                dev.beancount.app.widget.WidgetRenderer.EXTRA_SURFACE,
            ),
            knownProjects = app.projectsState.projects.value.map { it.id },
            activeProject = app.projectsState.activeId.value,
        )?.projectId
    }

    private fun openProject(app: BeancountApplication, projectId: String) {
        val ledgerPath = app.workspace.mainFile(projectId).absolutePath
        app.projectsState.open(projectId)
        app.templatesState.open(projectId)
        app.pluginsState.openProject(app.workspace.projectDir(projectId))
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
            app.ledgerState.snapshot.collect { snapshot ->
                if (snapshot != null) publishWidgets(app, projectId, snapshot)
            }
        }
    }

    /** Writes the widget cache from a parsed ledger, then asks the widgets to redraw. */
    private suspend fun publishWidgets(
        app: BeancountApplication,
        projectId: String,
        ledger: dev.beancount.core.domain.LedgerSnapshot,
    ) {
        withContext(Dispatchers.IO) {
            runCatching {
                val entitled = app.entitlementState.entitlement.value.allows(Feature.WIDGETS)
                dev.beancount.core.data.WidgetCache(filesDir.resolve("state")).write(
                    dev.beancount.core.data.WidgetCache.from(
                        projectId = projectId,
                        ledger = ledger,
                        entitled = entitled,
                        nowEpochMs = System.currentTimeMillis(),
                        // Only what a widget can show. The cache lives outside the project
                        // tree so the home screen can read it, and a full chart of accounts
                        // is a description of somebody's life.
                        accounts = ledger.accounts
                            .map { it.name }
                            .filter {
                                it.startsWith("Assets:") || it.startsWith("Liabilities:")
                            }
                            .take(widgetAccountLimit),
                    ),
                )
                dev.beancount.app.widget.WidgetRefresh.refreshAll(this@MainActivity)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val app = application as BeancountApplication

        app.projectsState.refresh()
        hideWidgetAmounts = dev.beancount.app.widget.WidgetPreferences(this).hideAmounts()
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
            // can be timed to land mid-write (docs/milestones/m4.md, process-death test).
            val stall = intent.getStringExtra("m4_test")
            val active = widgetTarget(app) ?: app.projectsState.activeId.value
            if (BuildConfig.DEBUG && active != null && stall != null &&
                stall.startsWith("stall_mid_write")
            ) {
                stallMidWrite(app, active, introducesErrors = stall.endsWith("_bad"))
            }

            // Debug-only: stall *inside* a plugin load, so a process kill lands in the
            // window safe mode has to survive (docs/milestones/m11-part2.md).
            if (BuildConfig.DEBUG && active != null &&
                intent.getStringExtra("m11_test") == "stall_mid_plugin_load"
            ) {
                stallMidPluginLoad(app, active)
            }
        }

        setContent {
            val themeChoice by app.themeSettings.choice.collectAsStateWithLifecycle()
            val dynamicColour by app.themeSettings.dynamicColour.collectAsStateWithLifecycle()
            BeancountTheme(choice = themeChoice, dynamicColour = dynamicColour) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    val engineStatus by app.engineManager.status.collectAsStateWithLifecycle()
                    val ledger by app.ledgerState.snapshot.collectAsStateWithLifecycle()
                    val projects by app.projectsState.projects.collectAsStateWithLifecycle()
                    val activeId by app.projectsState.activeId.collectAsStateWithLifecycle()
                    val projectMessage by app.projectsState.message.collectAsStateWithLifecycle()
                    val editorState by app.editorController.state.collectAsStateWithLifecycle()
                    val editorMessage by app.editorController.message.collectAsStateWithLifecycle()
                    val templates by app.templatesState.templates.collectAsStateWithLifecycle()
                    val entitlement by app.entitlementState.entitlement.collectAsStateWithLifecycle()
                    val entitlementMessage by
                        app.entitlementState.message.collectAsStateWithLifecycle()
                    val documentPages by app.documentCapture.pages.collectAsStateWithLifecycle()
                    val documentMessage by
                        app.documentCapture.message.collectAsStateWithLifecycle()
                    val pendingRestore by
                        app.backupController.pendingRestore.collectAsStateWithLifecycle()
                    val backupMessage by
                        app.backupController.message.collectAsStateWithLifecycle()
                    val passphraseNeeded by
                        app.backupController.passphraseNeeded.collectAsStateWithLifecycle()
                    val mirrorConflicts by app.mirror.conflicts.collectAsStateWithLifecycle()
                    val mirrorMessage by app.mirror.message.collectAsStateWithLifecycle()
                    val remoteStatus by app.remoteState.status.collectAsStateWithLifecycle()
                    val remoteCapabilities by
                        app.remoteState.capabilities.collectAsStateWithLifecycle()
                    val remoteMessage by app.remoteState.message.collectAsStateWithLifecycle()
                    val remotePending by app.remoteState.pendingRetry.collectAsStateWithLifecycle()
                    val pluginRows by app.pluginsState.rows.collectAsStateWithLifecycle()
                    val pluginSafeMode by app.pluginsState.safeMode.collectAsStateWithLifecycle()
                    val pluginMessage by app.pluginsState.message.collectAsStateWithLifecycle()

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
                        entitlement = entitlement,
                        token = app.engine.sessionToken,
                        debuggingAllowed = BuildConfig.DEBUG,
                        onOpenExternally = ::openInBrowser,
                        onSaveEntry = { draft ->
                            // Connected to a remote server means the ledger is over there.
                            // Writing to the local project instead would put the entry
                            // somewhere the user is not looking and cannot see it.
                            if (remoteStatus is EngineStatus.Serving) {
                                app.remoteState.submit(draft)
                            } else {
                                saveText(app, activeId!!, draft.render(), "quick-add")
                            }
                        },
                        documentPages = documentPages,
                        documents = documents,
                        documentMessage = documentMessage,
                        captureFile = { app.documentCapture.nextCaptureFile() },
                        onCapture = { file, _ ->
                            if (file.length() > 0) app.documentCapture.add(file)
                        },
                        onRotatePage = { app.documentCapture.rotate(it) },
                        onMovePage = { from, to -> app.documentCapture.move(from, to) },
                        onRemovePage = { app.documentCapture.remove(it) },
                        onSaveDocument = { account -> attachDocument(app, activeId!!, account) },
                        onDiscardPages = { app.documentCapture.reset(discardCaptures = true) },
                        onUnlinkDocument = { path -> unlinkDocument(app, activeId!!, path) },
                        onDeleteDocumentFile = { path ->
                            app.documentCapture.deleteFile(activeId!!, path) {
                                refreshDocuments(app, activeId!!)
                            }
                        },
                        onShareDocument = { path -> shareDocument(app, activeId!!, path) },
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
                        onThemeChoice = { app.themeSettings.setChoice(it) },
                        onDynamicColour = { app.themeSettings.setDynamicColour(it) },
                        hideWidgetAmounts = hideWidgetAmounts,
                        onHideWidgetAmounts = { hide ->
                            dev.beancount.app.widget.WidgetPreferences(this@MainActivity)
                                .setHideAmounts(hide)
                            hideWidgetAmounts = hide
                            dev.beancount.app.widget.WidgetRefresh.refreshAll(this@MainActivity)
                        },
                        pluginRows = pluginRows,
                        pluginSafeMode = pluginSafeMode,
                        pluginMessage = pluginMessage,
                        onInstallPlugin = { pluginPicker.launch(arrayOf("*/*")) },
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
                        onScheduleChanged = { enabled ->
                            scheduleEnabled = enabled
                            if (enabled) {
                                ScheduledBackupWorker.schedule(this@MainActivity)
                            } else {
                                ScheduledBackupWorker.cancel(this@MainActivity)
                            }
                        },
                        onApplyKey = { app.entitlementState.applyKey(it) },
                        onRemoveKey = { app.entitlementState.removeKey() },
                        onClearEntitlementMessage = { app.entitlementState.clearMessage() },
                        entitlementMessage = entitlementMessage,
                        templates = templates,
                        onSaveTemplate = { app.templatesState.save(it) },
                        onDeleteTemplate = { app.templatesState.delete(it) },
                        onSaveDirective = { draft ->
                            saveText(app, activeId!!, draft.render(), "add-directive")
                        },
                        // In remote mode the outcome of a save comes from the server, so
                        // that is what the Add screen has to report — otherwise a remote
                        // save looks like nothing happened at all.
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
    /**
     * Writes a plugin that never finishes importing, then loads it.
     *
     * Debug builds only. The point is to be killed: a plugin that hangs or crashes the
     * process leaves the app unable to start, and the marker Python writes before each
     * import is the only thing that survives to say which plugin it was. Stalling rather
     * than crashing outright means the kill can be timed from outside, exactly as the M4
     * process-death test does.
     */
    /**
     * Turns off whatever was mid-load when the app last died, and says so.
     *
     * Runs before any plugin is loaded. The marker it reads is written from Python
     * immediately before each import and flushed, so it survives a kill that gives the app
     * no chance to clean up (docs/milestones/m11-part2.md).
     */
    private suspend fun recoverFromPluginCrash(app: BeancountApplication, projectId: String) {
        withContext(Dispatchers.IO) {
            val projectRoot = app.workspace.projectDir(projectId)
            val recovery = dev.beancount.core.data.PluginSafeMode(projectRoot)
                .recoverFromCrash(dev.beancount.core.data.PluginStore(projectRoot))
                ?: return@withContext
            recoveryMessage = listOfNotNull(recoveryMessage, recovery.describe())
                .joinToString("\n\n")
        }
    }

    private suspend fun stallMidPluginLoad(app: BeancountApplication, projectId: String) {
        withContext(Dispatchers.IO) {
            val projectRoot = app.workspace.projectDir(projectId)
            val store = dev.beancount.core.data.PluginStore(projectRoot)
            val safeMode = dev.beancount.core.data.PluginSafeMode(projectRoot)
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
            ) as? dev.beancount.core.data.InstallResult.Installed ?: return@withContext
            store.setEnabled(installed.plugin.id, true)

            android.util.Log.i("M11", "stalling inside a plugin load; kill me now")
            dev.beancount.engine.local.PluginLoader.load(
                context = this@MainActivity,
                pluginDir = store.directoryFor(installed.plugin).absolutePath,
                modules = "stalls_forever",
                allowBridge = currentPluginPolicy().allowsAndroidBridge,
                journalPath = safeMode.journalPath(),
                journalLabel = installed.plugin.id,
            )
        }
    }

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
        // Which file the text is appended to. Entries go to the transactions file; a
        // `document` directive goes to the main file, because its path is relative to the
        // file that holds it and the documents directory sits beside the main file.
        target: java.io.File = app.workspace.projectDir(projectId).resolve("files/transactions.beancount"),
        onResult: (String) -> Unit = { saveMessage = it },
    ) {
        val ledgerPath = app.workspace.mainFile(projectId).absolutePath
        lifecycleScope.launch {
            saveMessage = "Saving…"
            val file = target
            val result = app.writer.append(projectId, file, text, operation = operation)
            onResult(
                when (result) {
                    is dev.beancount.core.data.WriteResult.Committed ->
                        if (result.warnings.isEmpty()) "Saved."
                        else "Saved, with ${result.warnings.size} ledger error(s) to review."
                    is dev.beancount.core.data.WriteResult.RolledBack ->
                        "Not saved: ${result.reason}. Your ledger is unchanged."
                    is dev.beancount.core.data.WriteResult.Refused ->
                        "Not saved: ${result.reason}"
                },
            )
            // Re-read so the journal and balances show the new entry, then mirror: a pass
            // only ever runs after a commit has landed (docs/storage-and-backup.md 6).
            app.ledgerState.load(ledgerPath)
            app.mirror.syncAfterCommit(projectId)
        }
    }

    private fun refreshDocuments(app: BeancountApplication, projectId: String) {
        lifecycleScope.launch { documents = app.documentCapture.existing(projectId) }
    }

    /**
     * Files the captured pages as a PDF, then writes the `document` directive that points at
     * it — in that order, so the ledger never names a file that does not exist yet
     * (CLAUDE.md, invariant 5).
     */
    private fun attachDocument(app: BeancountApplication, projectId: String, account: String) {
        app.documentCapture.save(projectId, account) { relativePath ->
            val directive = DirectiveDraft.Document(
                date = LocalDate.now().toString(),
                account = account,
                path = relativePath,
            )
            saveText(
                app,
                projectId,
                directive.render(),
                operation = "attach-document",
                target = app.workspace.mainFile(projectId),
            ) { outcome ->
                saveMessage = outcome
                // Said where the user is standing, not on a screen they have left.
                app.documentCapture.report("Filed ${relativePath.substringAfterLast('/')}. $outcome")
            }
            app.documentCapture.reset()
            refreshDocuments(app, projectId)
        }
    }

    /**
     * Removes the link, not the file.
     *
     * The directive is deleted from whichever included file holds it and the write goes
     * through the ordinary protocol, so an unlink is snapshotted and undoable like any other
     * edit. The PDF stays exactly where it was.
     */
    private fun unlinkDocument(app: BeancountApplication, projectId: String, path: String) {
        lifecycleScope.launch {
            val projectDir = app.workspace.projectDir(projectId)
            val candidates = projectDir.walkTopDown()
                .filter { it.isFile && (it.extension == "beancount" || it.extension == "bean") }
                .toList()

            val holder = candidates.firstOrNull { it.readText().contains(path) }
            if (holder == null) {
                saveMessage = "Nothing in the ledger links to $path."
                return@launch
            }

            val kept = holder.readLines().filterNot { line ->
                line.contains(path) && line.contains("document")
            }
            val result = app.writer.replace(
                projectId = projectId,
                file = holder,
                expectedSha = dev.beancount.core.data.WriteJournal.sha256(holder),
                newContent = kept.joinToString("\n").let { if (it.endsWith("\n")) it else it + "\n" },
                operation = "unlink-document",
            )
            saveMessage = when (result) {
                is dev.beancount.core.data.WriteResult.Committed ->
                    "Unlinked $path. The file is still in the project."
                is dev.beancount.core.data.WriteResult.RolledBack ->
                    "Not unlinked: ${result.reason}. Your ledger is unchanged."
                is dev.beancount.core.data.WriteResult.Refused -> "Not unlinked: ${result.reason}"
            }
            app.ledgerState.load(app.workspace.mainFile(projectId).absolutePath)
        }
    }

    /** Hands one document to another app as a temporary read-only URI, and nothing else. */
    private fun shareDocument(app: BeancountApplication, projectId: String, path: String) {
        val file = app.workspace.projectDir(projectId).resolve(path)
        if (!file.isFile) {
            saveMessage = "That document is not in the project any more."
            return
        }
        val uri = androidx.core.content.FileProvider.getUriForFile(
            this,
            "${BuildConfig.APPLICATION_ID}.documents",
            file,
        )
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        runCatching { startActivity(Intent.createChooser(send, "Share document")) }
    }

    private fun openInBrowser(uri: Uri) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, uri)) }
    }

}

/** The sections of the More hub. Projects first: it is the one people look for. */
internal enum class MoreSection(val label: String) {
    PROJECTS("Projects"),
    DOCUMENTS("Documents"),
    BACKUP("Backup"),
    MIRROR("Mirror"),
    REMOTE("Remote"),
    PLUGINS("Plugins"),
    APPEARANCE("Appearance"),
    UPDATES("Updates"),
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


private enum class Surface(val label: String) {
    ADD("Add"),
    JOURNAL("Journal"),
    BALANCES("Balances"),
    REPORTS("Reports"),
    FILES("Files"),
    // The hub docs/ux.md 2 asks for: documents now, backup and settings as they arrive.
    // Projects lives here rather than in the bar so six destinations still fit a phone.
    MORE("More"),
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
    templates: List<EntryTemplate>,
    onSaveTemplate: (EntryTemplate) -> Unit,
    onDeleteTemplate: (String) -> Unit,
    onApplyKey: (String) -> Unit,
    onRemoveKey: () -> Unit,
    onClearEntitlementMessage: () -> Unit,
    entitlementMessage: String?,
    documentPages: CapturedPages,
    documents: List<String>,
    documentMessage: String?,
    onCapture: (JavaFile, (Boolean) -> Unit) -> Unit,
    captureFile: () -> JavaFile,
    onRotatePage: (Int) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onSaveDocument: (String) -> Unit,
    onDiscardPages: () -> Unit,
    onUnlinkDocument: (String) -> Unit,
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
    onInstallPlugin: () -> Unit,
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
    var showContribute by remember { mutableStateOf(false) }

    // null means "show the list of sections". Hoisted out of the More branch so the app bar
    // can send the user straight to Projects.
    var moreSection by remember { mutableStateOf<MoreSection?>(null) }

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
        bottomBar = {
            if (layout != PaneLayout.COMPACT) return@Scaffold
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
            TrialBanner(entitlement, onClick = { showContribute = true })
            SurfaceContent(
                selected = selected,
                moreSection = moreSection,
                onMoreSection = { moreSection = it },
                twoPane = layout == PaneLayout.EXPANDED,
                onContribute = { showContribute = true },
                documentPages = documentPages,
                documents = documents,
                documentMessage = documentMessage,
                onCapture = onCapture,
                captureFile = captureFile,
                onRotatePage = onRotatePage,
                onMovePage = onMovePage,
                onRemovePage = onRemovePage,
                onSaveDocument = onSaveDocument,
                onDiscardPages = onDiscardPages,
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
                onHideWidgetAmounts = onHideWidgetAmounts,
                pluginRows = pluginRows,
                pluginSafeMode = pluginSafeMode,
                pluginMessage = pluginMessage,
                onInstallPlugin = onInstallPlugin,
                onSetPluginEnabled = onSetPluginEnabled,
                onRemovePlugin = onRemovePlugin,
                onSetPluginSafeMode = onSetPluginSafeMode,
                recoveryNotice = recoveryNotice,
                onDismissRecovery = onDismissRecovery,
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

@Composable
private fun SurfaceContent(
    selected: Surface,
    onContribute: () -> Unit,
    documentPages: CapturedPages,
    documents: List<String>,
    documentMessage: String?,
    onCapture: (JavaFile, (Boolean) -> Unit) -> Unit,
    captureFile: () -> JavaFile,
    onRotatePage: (Int) -> Unit,
    onMovePage: (Int, Int) -> Unit,
    onRemovePage: (Int) -> Unit,
    onSaveDocument: (String) -> Unit,
    onDiscardPages: () -> Unit,
    onUnlinkDocument: (String) -> Unit,
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
    moreSection: MoreSection?,
    onMoreSection: (MoreSection?) -> Unit,
    twoPane: Boolean,
    themeChoice: dev.beancount.app.ui.theme.ThemeChoice,
    dynamicColour: Boolean,
    onThemeChoice: (dev.beancount.app.ui.theme.ThemeChoice) -> Unit,
    onDynamicColour: (Boolean) -> Unit,
    hideWidgetAmounts: Boolean,
    onHideWidgetAmounts: (Boolean) -> Unit,
    pluginRows: List<dev.beancount.app.plugins.PluginRow>,
    pluginSafeMode: Boolean,
    pluginMessage: String?,
    onInstallPlugin: () -> Unit,
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
    onSaveEntry: (TransactionDraft) -> Unit,
    templates: List<EntryTemplate>,
    onSaveTemplate: (EntryTemplate) -> Unit,
    onDeleteTemplate: (String) -> Unit,
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
                                templates = templates,
                                onSaveTemplate = onSaveTemplate,
                                onDeleteTemplate = onDeleteTemplate,
                            )
                        }
                    }
                }
            }

            Surface.FILES -> RawEditorScreen(
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

            Surface.MORE -> {
                val section = moreSection
                Column(modifier = Modifier.padding(padding)) {
                    // A list, not a row of chips. Seven sections never fitted across a phone,
                    // so the row scrolled horizontally and the last two were off-screen —
                    // reaching Plugins took three swipes, every time, and nothing on screen
                    // suggested they were there at all.
                    if (section == null) {
                        MoreMenu(onOpen = onMoreSection)
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
                            onDelete = onDeleteProject,
                            busyMessage = projectMessage,
                        )

                        MoreSection.UPDATES ->
                            dev.beancount.app.update.UpdateSection()

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

                        MoreSection.PLUGINS ->
                            if (!entitlement.allows(Feature.PLUGINS)) {
                                LockedFeature(
                                    title = "Plugins",
                                    tier = Feature.PLUGINS.required.label,
                                    onContribute = onContribute,
                                )
                            } else {
                                PluginsScreen(
                                    rows = pluginRows,
                                    safeMode = pluginSafeMode,
                                    policy = currentPluginPolicy(),
                                    onInstall = onInstallPlugin,
                                    onSetEnabled = onSetPluginEnabled,
                                    onRemove = onRemovePlugin,
                                    onSetSafeMode = onSetPluginSafeMode,
                                    message = pluginMessage,
                                )
                            }

                        MoreSection.REMOTE ->
                            if (!entitlement.allows(Feature.REMOTE_MODE)) {
                                LockedFeature(
                                    title = "Your own Fava server",
                                    tier = Feature.REMOTE_MODE.required.label,
                                    onContribute = onContribute,
                                )
                            } else {
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
                            }

                        MoreSection.MIRROR ->
                            if (!entitlement.allows(Feature.AUTO_MIRROR)) {
                                LockedFeature(
                                    title = "Mirror to a folder",
                                    tier = Feature.AUTO_MIRROR.required.label,
                                    onContribute = onContribute,
                                )
                            } else {
                                MirrorScreen(
                                    enabled = mirrorEnabled,
                                    folder = mirrorFolder,
                                    conflicts = mirrorConflicts,
                                    onChooseFolder = onChooseMirrorFolder,
                                    onEnabledChanged = onMirrorEnabledChanged,
                                    onSyncNow = onMirrorNow,
                                    onKeepMine = onKeepMine,
                                    onKeepTheirs = onKeepTheirs,
                                    message = mirrorMessage,
                                )
                            }

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

                        MoreSection.DOCUMENTS ->
                            if (!entitlement.allows(Feature.DOCUMENTS)) {
                                // Tier 2. The ledger, and any document already filed, stay
                                // readable and exportable — only capturing new ones is gated.
                                LockedFeature(
                                    title = "Documents and receipt capture",
                                    tier = Feature.DOCUMENTS.required.label,
                                    onContribute = onContribute,
                                )
                            } else {
                                DocumentsScreen(
                                    pages = documentPages,
                                    documents = documents,
                                    vocabulary = ledger?.let { LedgerVocabulary.from(it) }
                                        ?: LedgerVocabulary(
                                            emptyList(), emptyList(), emptyList(),
                                            emptyList(), emptyList(),
                                        ),
                                    onCapture = onCapture,
                                    captureFile = captureFile,
                                    onRotate = onRotatePage,
                                    onMove = onMovePage,
                                    onRemovePage = onRemovePage,
                                    onSave = onSaveDocument,
                                    onDiscard = onDiscardPages,
                                    onUnlink = onUnlinkDocument,
                                    onDeleteFile = onDeleteDocumentFile,
                                    onShare = onShareDocument,
                                    message = documentMessage,
                                )
                            }
                    }
                }
            }

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
                        onContribute = onContribute,
                        modifier = Modifier.padding(padding),
                    )
                } else if (remoteReports != null) {
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
 * Why the remote reports are not on screen.
 *
 * A WebView that refuses a load shows a blank white page, which is indistinguishable from a
 * server with nothing to say. This is the difference.
 */
@Composable
private fun RemoteReportProblem(message: String, modifier: Modifier = Modifier) {
    androidx.compose.foundation.layout.Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(12.dp),
    ) {
        androidx.compose.material3.Text(
            "Reports could not be loaded",
            style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
        )
        androidx.compose.material3.Text(
            message,
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        )
    }
}

/**
 * A locked feature says what it is, which tier unlocks it, and nothing else. No countdown,
 * no modal, no nagging (docs/ux.md §10) — and the user's data is never involved.
 */
@Composable
private fun LockedFeature(
    title: String,
    tier: String,
    onContribute: () -> Unit,
    modifier: Modifier = Modifier,
) {
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
        androidx.compose.material3.TextButton(onClick = onContribute) {
            Text(dev.beancount.core.entitlement.PurchaseRoute.EnterKeyOnly.label)
        }
    }
}

/** Which of the three layouts in docs/ux.md §7 the current window calls for. */
private enum class PaneLayout { COMPACT, MEDIUM, EXPANDED }

/**
 * The navigation rail, for windows too wide for a bottom bar.
 *
 * At 600dp and up a bottom bar wastes the shape of the screen: it puts the destinations
 * along the short edge of a landscape window and leaves a strip of empty space under a
 * ledger that would rather be taller. The rail moves them to the side, which is also where
 * a hand holding a tablet already is.
 */
@Composable
private fun LedgerNavigationRail(selected: Surface, onSelect: (Surface) -> Unit) {
    androidx.compose.material3.NavigationRail {
        Surface.entries.forEach { surface ->
            androidx.compose.material3.NavigationRailItem(
                selected = selected == surface,
                onClick = { onSelect(surface) },
                // The label goes in the icon slot. These destinations have no icons, and with
                // an empty icon slot the selection indicator draws around nothing while the
                // label sits outside it — the pill floated above the wrong word.
                icon = { Text(surface.label) },
                modifier = Modifier
                    .padding(vertical = 6.dp)
                    .semantics { contentDescription = surface.label },
            )
        }
    }
}

/**
 * The app bar: whose ledger this is, and whether it is healthy.
 *
 * ux.md §2 asks for project name and health here, and the reason is that neither was
 * anywhere before — the open project's name appeared only inside the Projects screen, so
 * anyone with two ledgers had no way to tell at a glance which one they were adding an
 * entry to.
 *
 * Inside More it doubles as a section header with a back arrow, because a list you cannot
 * get back to is a list you visit once.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun LedgerTopBar(
    projectName: String,
    health: String?,
    sectionTitle: String?,
    onBack: () -> Unit,
    onSwitchProject: () -> Unit,
) {
    androidx.compose.material3.TopAppBar(
        title = {
            Column {
                Text(
                    sectionTitle ?: projectName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                )
                if (sectionTitle == null && health != null) {
                    Text(health, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
            }
        },
        navigationIcon = {
            if (sectionTitle != null) {
                // Text rather than an arrow icon: androidx.compose.material.icons is a
                // whole artifact for one glyph, and "Back" needs no legend.
                TextButton(
                    onClick = onBack,
                    modifier = Modifier.semantics { contentDescription = "Back to More" },
                ) { Text("Back") }
            }
        },
        actions = {
            if (sectionTitle == null) {
                TextButton(
                    onClick = onSwitchProject,
                    modifier = Modifier.semantics {
                        contentDescription = "Switch project. Currently open: $projectName"
                    },
                ) { Text("Switch") }
            }
        },
    )
}

/**
 * One line saying whether the ledger is in good order.
 *
 * Parse errors are an ordinary state for a plain-text ledger, not a failure — you get them
 * halfway through typing an entry in the raw editor — so they are reported as a count, in
 * the same quiet line as everything else, and never as an error screen (docs/ux.md §2).
 */
private fun ledgerHealth(status: EngineStatus, ledger: LedgerSnapshot?): String? = when {
    ledger == null && status is EngineStatus.Failed -> "Engine unavailable"
    ledger == null -> "Loading…"
    ledger.errors.isEmpty() -> "${ledger.entryCount} entries · no errors"
    ledger.errors.size == 1 -> "${ledger.entryCount} entries · 1 error"
    else -> "${ledger.entryCount} entries · ${ledger.errors.size} errors"
}

/**
 * The sections behind More, as a list.
 *
 * Each carries a line of explanation: "Mirror" and "Remote" mean nothing on their own, and a
 * list of bare nouns makes people open things to find out what they are.
 */
@Composable
private fun MoreMenu(onOpen: (MoreSection) -> Unit) {
    val descriptions = mapOf(
        MoreSection.PROJECTS to "Open, create, import or delete a ledger",
        MoreSection.DOCUMENTS to "Receipts and statements filed against entries",
        MoreSection.BACKUP to "Write a backup, restore one, or schedule them",
        MoreSection.MIRROR to "Keep a copy in a folder you choose, kept in step",
        MoreSection.REMOTE to "Use a Fava server you host yourself",
        MoreSection.PLUGINS to "Extra checks and transformations, from files you supply",
        MoreSection.APPEARANCE to "Theme, dark mode and colours",
        MoreSection.UPDATES to "Check for a signed update to this app",
    )

    // Updates are the one section that is not in both editions: the Play build has no
    // self-update anything, by policy, so offering the entry would be offering a dead end.
    val sections = MoreSection.entries.filter {
        it != MoreSection.UPDATES || currentPluginPolicy().allowsSelfUpdate
    }

    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
        sections.forEach { entry ->
            androidx.compose.material3.ListItem(
                headlineContent = { Text(entry.label) },
                supportingContent = descriptions[entry]?.let { { Text(it) } },
                modifier = Modifier
                    .clickable { onOpen(entry) }
                    .semantics {
                        contentDescription = "${entry.label}. ${descriptions[entry].orEmpty()}"
                    },
            )
            HorizontalDivider()
        }
    }
}

/**
 * Something had to be put right before the app could start, and the user is told what.
 *
 * Dismissible, and only ever shown when something actually happened: a banner that appears
 * on every launch is a banner nobody reads. It is not a dialog either — the app has already
 * recovered by the time this is on screen, so there is nothing to decide.
 */
@Composable
private fun RecoveryBanner(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                message,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.semantics {
                    contentDescription = "Something was put right at startup: $message"
                },
            )
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.semantics { contentDescription = "Dismiss this notice" },
            ) { Text("Got it") }
        }
    }
}

/**
 * One quiet line about the trial, and the way to the Contribute screen.
 *
 * A line of text rather than a banner with a button: the trial ending is not an emergency,
 * and a countdown that demands attention every launch is the dark pattern this app promised
 * not to have (docs/distribution.md §4.7).
 */
@Composable
internal fun TrialBanner(
    entitlement: Entitlement,
    onClick: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val text = when (val grant = entitlement.grant) {
        is Grant.Trial -> {
            val days = entitlement.trialDaysRemaining ?: 0
            if (days <= 1) "Trial: last day — everything is unlocked"
            else "Trial: $days days left — everything is unlocked"
        }
        is Grant.TrialEnded ->
            "Trial ended. Your ledger is untouched and stays fully editable."
        // The tier, not the email: this line is on screen in every screenshot the user ever
        // shares, and the address a key was issued to belongs on the Contribute screen
        // (docs/distribution.md §4.5), not above the ledger.
        is Grant.Key -> "${grant.tier.label} key · tap to manage"
        Grant.Free -> return
    }
    Text(
        text = text,
        modifier = modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .semantics { contentDescription = "$text. Opens Contribute." },
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
