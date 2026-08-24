package dev.beancount.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dev.beancount.app.engine.EngineManager
import dev.beancount.app.engine.EntitlementState
import dev.beancount.app.engine.EditorController
import dev.beancount.app.engine.LedgerState
import dev.beancount.app.engine.ProjectsState
import dev.beancount.app.engine.TemplatesState
import dev.beancount.core.data.DraftStore
import dev.beancount.core.data.ProjectImporter
import dev.beancount.core.data.ProjectRegistry
import dev.beancount.core.data.ProjectWorkspace
import dev.beancount.core.data.ProjectWriter
import dev.beancount.core.data.TemplateStore
import dev.beancount.core.data.WriteJournal
import dev.beancount.core.engine.PluginPolicy
import dev.beancount.core.entitlement.Entitlements
import dev.beancount.core.entitlement.FileEntitlementStorage
import java.time.Instant
import dev.beancount.engine.local.BeancountEditor
import dev.beancount.engine.local.EngineConnection
import dev.beancount.engine.local.BeancountValidator
import dev.beancount.engine.local.LedgerReader
import dev.beancount.engine.local.LocalEngineClient
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

    val engineManager: EngineManager by lazy { EngineManager(engine, appScope) }

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
        dev.beancount.app.storage.ContextHolder.install(this)
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
