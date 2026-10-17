package io.github.pintofi.beanbook.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.pintofi.beanbook.app.backup.BackupController
import io.github.pintofi.beanbook.app.backup.BackupSettings
import io.github.pintofi.beanbook.app.engine.EngineManager
import io.github.pintofi.beanbook.app.mirror.MirrorController
import io.github.pintofi.beanbook.app.mirror.MirrorSettings
import io.github.pintofi.beanbook.app.remote.RemoteState
import io.github.pintofi.beanbook.app.engine.EditorController
import io.github.pintofi.beanbook.app.engine.LedgerState
import io.github.pintofi.beanbook.app.engine.ProjectsState
import io.github.pintofi.beanbook.app.engine.TemplatesState
import io.github.pintofi.beanbook.app.documents.DocumentCapture
import io.github.pintofi.beanbook.core.data.DocumentStore
import io.github.pintofi.beanbook.core.data.DraftStore
import io.github.pintofi.beanbook.core.data.ProjectImporter
import io.github.pintofi.beanbook.core.data.ProjectRegistry
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.data.ProjectWriter
import io.github.pintofi.beanbook.core.data.TemplateStore
import io.github.pintofi.beanbook.core.data.WriteJournal
import io.github.pintofi.beanbook.core.domain.PluginPolicy
import java.time.Instant
import io.github.pintofi.beanbook.engine.local.BeancountEditor
import io.github.pintofi.beanbook.engine.local.EngineConnection
import io.github.pintofi.beanbook.engine.local.BeancountValidator
import io.github.pintofi.beanbook.engine.local.LedgerReader
import io.github.pintofi.beanbook.engine.local.LocalEngineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

/**
 * Application-scoped wiring.
 *
 * The edition's [PluginPolicy] comes from the flavor source set, so shared code never
 * branches on build flavor and the play build cannot reach expert-only behaviour by
 * accident.
 */
class BeancountApplication : Application() {

    val pluginPolicy: PluginPolicy by lazy { currentPluginPolicy() }

    private val appScope = CoroutineScope(SupervisorJob())

    /**
     * Where a write the user asked for runs: the application's lifetime, on the main
     * thread, rather than an activity's. A save launched from an activity's own scope was
     * cancelled with the activity -- rotating, or Android reclaiming a screen left in the
     * background -- and the entry the user had tapped Save on was never written.
     */
    val workScope = CoroutineScope(SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate)

    val workspace: ProjectWorkspace by lazy { ProjectWorkspace(filesDir) }

    /**
     * One binding to the engine process, shared by everything that talks to it. Each
     * client defaulting to its own connection meant four bindings to the same service —
     * visible on device as "engine process connected" logged repeatedly.
     */
    val engineConnection: EngineConnection by lazy { EngineConnection(this) }

    val engine: LocalEngineClient by lazy { LocalEngineClient(this, connection = engineConnection) }

    /** Sharing Reports on Wi-Fi, behind a sign-in. Off until the user turns it on. */
    val sharingState: io.github.pintofi.beanbook.app.sharing.SharingState by lazy {
        io.github.pintofi.beanbook.app.sharing.SharingState(
            this,
            engine,
            engine.status,
            workScope,
            io.github.pintofi.beanbook.app.sharing.SharingSettings(filesDir.resolve("state")),
        )
    }

    val engineManager: EngineManager by lazy {
        EngineManager(engine, appScope).apply {
            heldInBackground = { sharingState.wanted }
            // The same directories the parse path is given, so a Fava extension the user
            // installed is importable while Fava serves as well as while the ledger is read.
            pluginDirectories = {
                pluginsState.enabledDirectories().split(",").filter { it.isNotBlank() }
            }
        }
    }

    val ledgerState: LedgerState by lazy {
        LedgerState(LedgerReader(this, connection = engineConnection), appScope)
    }

    val journal: WriteJournal by lazy { WriteJournal(workspace) }

    /** Runtime state, kept outside the project tree so it is never exported with a ledger. */
    val registry: ProjectRegistry by lazy {
        ProjectRegistry(workspace, filesDir.resolve("state"))
    }

    val drafts: DraftStore by lazy { DraftStore(workspace) }

    val editorController: EditorController by lazy {
        EditorController(workspace, writer, drafts, appScope) { id, result ->
            undo.offerFor(id, result, "saved the file")
        }
    }

    /** Offers to take back the last save, delete or file edit. */
    val undo: io.github.pintofi.beanbook.app.undo.UndoController by lazy {
        io.github.pintofi.beanbook.app.undo.UndoController(writer, workScope)
    }

    /** Entry templates for the open project; app state, never part of the ledger. */
    val templatesState: TemplatesState by lazy {
        TemplatesState(TemplateStore(workspace), appScope)
    }

    /**
     * Receipt capture and the documents already filed. Documents live inside the project
     * tree, so an export carries them (docs/storage-and-backup.md).
     */
    val documentCapture: DocumentCapture by lazy {
        DocumentCapture(
            this,
            DocumentStore(workspace),
            appScope,
            // The scanner renders a page in its chosen style at assembly time; a
            // device where OpenCV did not load returns the photograph unchanged, which is
            // the honest fallback rather than a failure.
            io.github.pintofi.beanbook.app.documents.PdfAssembler(
                styler = { bitmap, style ->
                    io.github.pintofi.beanbook.scanner.Scanner.restyle(
                        bitmap,
                        when (style) {
                            io.github.pintofi.beanbook.core.domain.PageStyle.DOCUMENT ->
                                io.github.pintofi.beanbook.scanner.ScanStyle.DOCUMENT
                            io.github.pintofi.beanbook.core.domain.PageStyle.GREYSCALE ->
                                io.github.pintofi.beanbook.scanner.ScanStyle.GREYSCALE
                            io.github.pintofi.beanbook.core.domain.PageStyle.COLOUR ->
                                io.github.pintofi.beanbook.scanner.ScanStyle.COLOUR
                        },
                    )
                },
            ),
        )
    }

    /** The names of files attached to the entry being written, for the form to show. */
    val documentFileNames: kotlinx.coroutines.flow.StateFlow<List<String>> by lazy {
        documentCapture.files
            .map { files -> files.map { it.displayName } }
            .stateIn(appScope, SharingStarted.Eagerly, emptyList())
    }

    /** Where scheduled backups go, and what happened on the last run. */
    val backupSettings: BackupSettings by lazy { BackupSettings(filesDir.resolve("state")) }

    val backupController: BackupController by lazy {
        BackupController(
            workspace = workspace,
            registry = registry,
            scope = appScope,
            appVersion = appVersion,
            engineVersions = { engine.versions() },
        )
    }

    /** Recorded in every backup manifest, so an archive says which app wrote it. */
    val appVersion: String get() = BuildConfig.VERSION_NAME

    /** Auto-mirror to a folder the user chose. Off until they turn it on. */
    val mirrorSettings: MirrorSettings by lazy { MirrorSettings(filesDir.resolve("state")) }

    val mirror: MirrorController by lazy {
        MirrorController(this, workspace, journal, writer, appScope, mirrorSettings)
    }

    /** Remote mode: a self-hosted Fava, its credentials, and the connection. */
    val remoteState: RemoteState by lazy {
        RemoteState(filesDir.resolve("state"), appScope)
    }

    /**
     * Whether the running interpreter is behind the plugins and packages on disk.
     *
     * Owned here rather than by either store, because both of them can put it out of date
     * and only one of them can be asked about it.
     */
    val engineRestart: io.github.pintofi.beanbook.app.engine.EngineRestartState by lazy {
        io.github.pintofi.beanbook.app.engine.EngineRestartState()
    }

    /**
     * Bumped whenever a ledger file changes on disk under the open project.
     *
     * A counter rather than an event, so anything showing the ledger -- the native screens,
     * the document list, Fava's page in Reports -- can follow it without the watcher needing
     * to know they exist.
     */
    val ledgerChangedOnDisk = kotlinx.coroutines.flow.MutableStateFlow(0L)

    /** Watches the open project for edits made by anything other than this app. */
    val ledgerWatcher: io.github.pintofi.beanbook.app.engine.LedgerWatcher by lazy {
        io.github.pintofi.beanbook.app.engine.LedgerWatcher(appScope) {
            ledgerChangedOnDisk.value += 1
        }
    }

    /**
     * Installed plugins for the open project.
     *
     * Takes the edition's policy from [currentPluginPolicy] once, here, so there is exactly
     * one place the Play boundary is chosen and the rest of the app cannot pick a different
     * one by accident.
     */
    val pluginsState: io.github.pintofi.beanbook.app.plugins.PluginsState by lazy {
        io.github.pintofi.beanbook.app.plugins.PluginsState(
            this,
            currentPluginPolicy(),
            appScope,
            onEngineStale = engineRestart::markNeeded,
        )
    }

    /**
     * Python packages fetched on demand. Same policy object as the plugin store, so the Play
     * edition's inability to download is decided in one place rather than two.
     */
    val packagesState: io.github.pintofi.beanbook.app.packages.PackagesState by lazy {
        io.github.pintofi.beanbook.app.packages.PackagesState(
            this,
            currentPluginPolicy(),
            appScope,
            onEngineStale = engineRestart::markNeeded,
        )
    }

    /** The curated plugin catalogue: listed in both editions, installable in Expert. */
    val catalogueState: io.github.pintofi.beanbook.app.plugins.CatalogueState by lazy {
        io.github.pintofi.beanbook.app.plugins.CatalogueState(
            this,
            currentPluginPolicy(),
            appScope,
            onEngineStale = engineRestart::markNeeded,
        )
    }

    /** Running a user script against the open ledger. Same policy, chosen once. */
    val scriptRunner: io.github.pintofi.beanbook.app.plugins.ScriptRunnerState by lazy {
        io.github.pintofi.beanbook.app.plugins.ScriptRunnerState(
            this,
            currentPluginPolicy(),
            appScope,
            importable = { pluginsState.enabledDirectories() },
        )
    }

    /**
     * How the app should look. Outside the project tree: a display preference belongs to the
     * device, not to a ledger, and must not travel in a backup or a mirror.
     */
    val themeSettings: io.github.pintofi.beanbook.app.ui.theme.ThemeSettings by lazy {
        io.github.pintofi.beanbook.app.ui.theme.ThemeSettings(filesDir.resolve("state"))
    }

    /** When to mention, once, that the project accepts donations. */
    val supportPrompt: io.github.pintofi.beanbook.core.data.SupportPrompt by lazy {
        io.github.pintofi.beanbook.core.data.SupportPrompt(filesDir.resolve("state"))
    }

    val projectsState: ProjectsState by lazy {
        ProjectsState(workspace, registry, ProjectImporter(workspace), appScope)
    }

    val editor: BeancountEditor by lazy { BeancountEditor(this, connection = engineConnection) }

    /**
     * Every write goes through here: lock, snapshot, journal, atomic rename, re-parse
     * with Beancount, and only then report success (the write protocol).
     */
    val writer: ProjectWriter by lazy {
        ProjectWriter(
            workspace,
            journal,
            BeancountValidator(this, connection = engineConnection),
            // Every write's outcome in the app's own log. What a person needs when a
            // save did not take: which file, which operation, and what the protocol said.
            observer = { line ->
                io.github.pintofi.beanbook.app.diagnostics.Diagnostics.i("ProjectWriter", line)
            },
            onWriting = { active -> ledgerWatcher.ownWrite(active) },
        )
    }

    override fun onCreate() {
        super.onCreate()
        // The engine follows the *process* lifecycle, not an activity's: it must survive
        // rotation but must not keep serving once the app is in the background
        // (docs/architecture.md §5).
        io.github.pintofi.beanbook.app.storage.ContextHolder.install(this)
        // First, so a crash during start-up is still recorded, and so the app's own log
        // covers the run from its first line.
        io.github.pintofi.beanbook.app.diagnostics.Diagnostics.install(this) {
            val status = engineManager.status.value
            "engine: ${status::class.simpleName}, edition: ${pluginPolicy.name}"
        }
        // The first launch is when the week before the one support notice starts.
        supportPrompt.recordLaunch(Instant.now())
        // What the trial and the contribution keys left behind: a stored key carries the
        // email it was issued to, and nothing reads it any more.
        filesDir.resolve("entitlement").deleteRecursively()
        filesDir.resolve("state/welcomed").delete()
        ProcessLifecycleOwner.get().lifecycle.addObserver(engineManager)
    }

    override fun onTerminate() {
        // Only called by the framework in tests, but leaving the engine running would
        // keep non-daemon cheroot threads alive.
        engineManager.stopNow()
        engineConnection.release()
        appScope.cancel()
        super.onTerminate()
    }
}
