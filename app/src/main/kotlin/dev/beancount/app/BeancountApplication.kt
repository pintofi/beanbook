package dev.beancount.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import dev.beancount.app.engine.EngineManager
import dev.beancount.core.data.ProjectWorkspace
import dev.beancount.core.engine.PluginPolicy
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

    val engine: LocalEngineClient by lazy { LocalEngineClient(this) }

    val engineManager: EngineManager by lazy { EngineManager(engine, appScope) }

    override fun onCreate() {
        super.onCreate()
        // The engine follows the *process* lifecycle, not an activity's: it must survive
        // rotation but must not keep serving once the app is in the background
        // (docs/architecture.md §5).
        ProcessLifecycleOwner.get().lifecycle.addObserver(engineManager)
    }

    override fun onTerminate() {
        // Only called by the framework in tests, but leaving the engine running would
        // keep non-daemon cheroot threads alive (Phase 0 finding C7).
        engineManager.stopNow()
        appScope.cancel()
        super.onTerminate()
    }
}
