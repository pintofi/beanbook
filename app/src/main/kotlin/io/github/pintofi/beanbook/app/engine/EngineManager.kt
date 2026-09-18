package io.github.pintofi.beanbook.app.engine

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import io.github.pintofi.beanbook.core.engine.EngineClient
import io.github.pintofi.beanbook.core.engine.EngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics
import kotlinx.coroutines.launch

/**
 * Owns the engine's lifetime.
 *
 * Two rules drive this class:
 *
 *  1. **The engine must be stoppable.** cheroot's worker threads are not daemon threads,
 *     so an engine that is never stopped keeps the process alive (Phase 0 finding C7).
 *  2. **The engine must not run when the app is not in front of the user.** It serves a
 *     local HTTP port; leaving it listening in the background is both a battery cost and
 *     an unnecessary attack surface (docs/threat-model.md §3.3). Widgets never start it.
 *
 * A short grace period avoids restarting the engine across a rotation or a quick app
 * switch, which would otherwise cost a full re-parse.
 */
class EngineManager(
    private val engine: EngineClient,
    private val scope: CoroutineScope,
    private val gracePeriodMillis: Long = DEFAULT_GRACE_MILLIS,
) : DefaultLifecycleObserver {

    val status: StateFlow<EngineStatus> = engine.status

    private var shutdownJob: Job? = null
    private var currentProject: String? = null

    /**
     * Directories Fava should be able to import from while serving — the enabled plugins.
     *
     * Asked for at start time rather than captured once, for the same reason the parse path
     * asks: which plugins are enabled changes between loads.
     */
    var pluginDirectories: () -> List<String> = { emptyList() }

    /** Whether the app is on screen, so an engine that is lost can be brought back at once. */
    @Volatile
    private var inForeground = false

    init {
        // Android ends the engine process when it wants the memory, most often while the app is
        // in the background. Coming back used to find the Reports tab pointing at a port nobody
        // was listening on ("Web page not available ... ERR_CONNECTION_REFUSED"). In front of
        // the user, a lost engine is started again; behind them, onStart does it on return.
        (engine as? io.github.pintofi.beanbook.engine.local.LocalEngineClient)?.onProcessLost = {
            Diagnostics.w(TAG, "the engine process ended on its own")
            if (inForeground) currentProject?.let { start(it) }
        }
    }

    /** Starts the engine for [projectPath], or returns the running one. */
    fun start(projectPath: String) {
        shutdownJob?.cancel()
        shutdownJob = null
        currentProject = projectPath
        scope.launch {
            // Fava resolves an extension by module name when it loads the ledger, so this has
            // to be set before start rather than after (docs/milestones/m14-part26.md).
            (engine as? io.github.pintofi.beanbook.engine.local.LocalEngineClient)?.pluginDirs =
                pluginDirectories()
            // Fava loads the ledger itself, so the documents folder has to be there before it
            // does, not only before the app's own read (B79).
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                io.github.pintofi.beanbook.core.data.DocumentRoots.ensure(java.io.File(projectPath))
            }
            val result = engine.start(projectPath)
            if (result is EngineStatus.Failed) {
                Diagnostics.w(TAG, "engine failed to start: ${result.message}")
            } else {
                Diagnostics.i(TAG, "engine started")
            }
        }
    }

    /**
     * Ends the engine process and starts it again on the same project (B49).
     *
     * What a "restart" has to mean here: not a new Fava server in the same interpreter, but
     * a new interpreter. A package fetched or a plugin installed after Python started is
     * invisible to it, because the module cache has already answered the question.
     */
    fun restart() {
        shutdownJob?.cancel()
        shutdownJob = null
        val project = currentProject ?: return
        scope.launch {
            (engine as? io.github.pintofi.beanbook.engine.local.LocalEngineClient)
                ?.restartProcess()
                ?: engine.stop()
            (engine as? io.github.pintofi.beanbook.engine.local.LocalEngineClient)?.pluginDirs =
                pluginDirectories()
            val result = engine.start(project)
            if (result is EngineStatus.Failed) {
                Diagnostics.w(TAG, "engine failed to restart: ${result.message}")
            } else {
                Diagnostics.i(TAG, "engine restarted in a new process")
            }
        }
    }

    /** Starts the current project again, after a failure the user asked to retry. */
    fun retry() {
        currentProject?.let { start(it) }
    }

    fun stopNow() {
        shutdownJob?.cancel()
        shutdownJob = null
        scope.launch { engine.stop() }
    }

    override fun onStart(owner: LifecycleOwner) {
        inForeground = true
        // Coming back to the foreground: cancel a pending shutdown, or restart if the
        // grace period already elapsed.
        val pending = shutdownJob
        shutdownJob = null
        pending?.cancel()
        // A failure is retried too: the engine may have failed only because it was being
        // reclaimed when it was asked for something.
        if (status.value is EngineStatus.Stopped || status.value is EngineStatus.Failed) {
            currentProject?.let { start(it) }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        inForeground = false
        shutdownJob?.cancel()
        shutdownJob = scope.launch {
            delay(gracePeriodMillis)
            Diagnostics.i(TAG, "grace period elapsed in the background; stopping the engine")
            engine.stop()
        }
    }

    companion object {
        private const val TAG = "EngineManager"

        /** Long enough to survive a rotation or a glance at another app. */
        const val DEFAULT_GRACE_MILLIS = 30_000L
    }
}
