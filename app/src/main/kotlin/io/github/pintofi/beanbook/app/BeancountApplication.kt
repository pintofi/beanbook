package io.github.pintofi.beanbook.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import io.github.pintofi.beanbook.app.backup.BackupController
import io.github.pintofi.beanbook.app.backup.BackupSettings
import io.github.pintofi.beanbook.app.engine.EngineManager
import io.github.pintofi.beanbook.app.mirror.MirrorController
import io.github.pintofi.beanbook.app.mirror.MirrorSettings
import io.github.pintofi.beanbook.app.remote.RemoteState
import io.github.pintofi.beanbook.app.engine.EntitlementState
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
import io.github.pintofi.beanbook.core.entitlement.Entitlements
import io.github.pintofi.beanbook.core.entitlement.FileEntitlementStorage
import java.time.Instant
import io.github.pintofi.beanbook.engine.local.BeancountEditor
import io.github.pintofi.beanbook.engine.local.EngineConnection
import io.github.pintofi.beanbook.engine.local.BeancountValidator
import io.github.pintofi.beanbook.engine.local.LedgerReader
import io.github.pintofi.beanbook.engine.local.LocalEngineClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

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

    val workspace: ProjectWorkspace by lazy { ProjectWorkspace(filesDir) }

    /**
     * One binding to the engine process, shared by everything that talks to it. Each
     * client defaulting to its own connection meant four bindings to the same service —
     * visible on device as "engine process connected" logged repeatedly.
     */
    val engineConnection: EngineConnection by lazy { EngineConnection(this) }

    val engine: LocalEngineClient by lazy { LocalEngineClient(this, connection = engineConnection) }

    val engineManager: EngineManager by lazy {
        EngineManager(engine, appScope).apply {
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
        EditorController(workspace, writer, drafts, appScope)
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
            // The scanner renders a page in its chosen style at assembly time (B58); a
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

    /** Auto-mirror to a folder the user chose (Tier 2). Off until they turn it on. */
    val mirrorSettings: MirrorSettings by lazy { MirrorSettings(filesDir.resolve("state")) }

    val mirror: MirrorController by lazy {
        MirrorController(this, workspace, journal, writer, appScope, mirrorSettings)
    }

    /** Remote mode (Tier 3): a self-hosted Fava, its credentials, and the connection. */
    val remoteState: RemoteState by lazy {
        RemoteState(filesDir.resolve("state"), appScope)
    }

    /**
     * Whether the running interpreter is behind the plugins and packages on disk (B49).
     *
     * Owned here rather than by either store, because both of them can put it out of date
     * and only one of them can be asked about it.
     */
    val engineRestart: io.github.pintofi.beanbook.app.engine.EngineRestartState by lazy {
        io.github.pintofi.beanbook.app.engine.EngineRestartState()
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

    /** Running a user script against the open ledger (Tier 3). Same policy, chosen once. */
    val scriptRunner: io.github.pintofi.beanbook.app.plugins.ScriptRunnerState by lazy {
        io.github.pintofi.beanbook.app.plugins.ScriptRunnerState(this, currentPluginPolicy(), appScope)
    }

    /**
     * How the app should look. Outside the project tree: a display preference belongs to the
     * device, not to a ledger, and must not travel in a backup or a mirror.
     */
    val themeSettings: io.github.pintofi.beanbook.app.ui.theme.ThemeSettings by lazy {
        io.github.pintofi.beanbook.app.ui.theme.ThemeSettings(filesDir.resolve("state"))
    }

    /** Whether the app still has to introduce itself (B27). */
    val firstRun: io.github.pintofi.beanbook.app.ui.FirstRun by lazy {
        io.github.pintofi.beanbook.app.ui.FirstRun(filesDir.resolve("state"))
    }

    val projectsState: ProjectsState by lazy {
        ProjectsState(workspace, registry, ProjectImporter(workspace), appScope)
    }

    /**
     * Trial and contribution keys. Stored in app-private storage *outside* the project
     * tree, so entitlement state is never exported or backed up with a ledger.
     */
    val entitlements: Entitlements by lazy {
        Entitlements(FileEntitlementStorage(filesDir.resolve("entitlement")))
    }

    /**
     * The live entitlement, and the only place a key is applied. Held as state rather than a
     * value read once, so entering a key takes effect immediately instead of at next launch.
     */
    val entitlementState: EntitlementState by lazy {
        EntitlementState(entitlements, appScope)
    }

    val editor: BeancountEditor by lazy { BeancountEditor(this, connection = engineConnection) }

    /**
     * Every write goes through here: lock, snapshot, journal, atomic rename, re-parse
     * with Beancount, and only then report success (CLAUDE.md, write protocol).
     */
    val writer: ProjectWriter by lazy {
        ProjectWriter(workspace, journal, BeancountValidator(this, connection = engineConnection))
    }

    override fun onCreate() {
        super.onCreate()
        // The engine follows the *process* lifecycle, not an activity's: it must survive
        // rotation but must not keep serving once the app is in the background
        // (docs/architecture.md §5).
        io.github.pintofi.beanbook.app.storage.ContextHolder.install(this)
        // Starts the 30-day trial on first launch and records that the app was seen now.
        entitlementState.start(Instant.now())
        ProcessLifecycleOwner.get().lifecycle.addObserver(engineManager)
    }

    override fun onTerminate() {
        // Only called by the framework in tests, but leaving the engine running would
        // keep non-daemon cheroot threads alive (Phase 0 finding C7).
        engineManager.stopNow()
        engineConnection.release()
        appScope.cancel()
        super.onTerminate()
    }
}
