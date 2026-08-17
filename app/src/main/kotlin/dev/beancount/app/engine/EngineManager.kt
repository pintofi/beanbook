package dev.beancount.app.engine

import android.util.Log
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dev.beancount.core.engine.EngineClient
import dev.beancount.core.engine.EngineStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
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

    /** Starts the engine for [projectPath], or returns the running one. */
    fun start(projectPath: String) {
        shutdownJob?.cancel()
        shutdownJob = null
        currentProject = projectPath
        scope.launch {
            val result = engine.start(projectPath)
            if (result is EngineStatus.Failed) {
                Log.w(TAG, "engine failed to start: ${result.message}")
            }
        }
    }

    fun stopNow() {
        shutdownJob?.cancel()
        shutdownJob = null
        scope.launch { engine.stop() }
    }

    override fun onStart(owner: LifecycleOwner) {
        // Coming back to the foreground: cancel a pending shutdown, or restart if the
        // grace period already elapsed.
        val pending = shutdownJob
        shutdownJob = null
        pending?.cancel()
        if (status.value is EngineStatus.Stopped) {
            currentProject?.let { start(it) }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        shutdownJob?.cancel()
        shutdownJob = scope.launch {
            delay(gracePeriodMillis)
            Log.i(TAG, "grace period elapsed while backgrounded; stopping engine")
            engine.stop()
        }
    }

    companion object {
        private const val TAG = "EngineManager"

        /** Long enough to survive a rotation or a glance at another app. */
        const val DEFAULT_GRACE_MILLIS = 30_000L
    }
}
