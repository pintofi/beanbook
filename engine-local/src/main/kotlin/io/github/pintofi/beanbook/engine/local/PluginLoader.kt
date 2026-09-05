package io.github.pintofi.beanbook.engine.local

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Loading user plugins and running user scripts — **in the engine process**.
 *
 * This used to call `PythonRuntime.ensureStarted` directly, which started the interpreter in
 * whichever process asked. That was a hole in the process separation the app depends on: with
 * R9 resolved by keeping GPL-licensed Beancount and everything Python in `:engine`, an app
 * process that can start an interpreter is an app process that can load Beancount
 * (docs/architecture.md §7.1).
 *
 * So every entry point here goes through the same binder as the rest of the engine. The class
 * remains in `:engine-local` because that is the module that owns the boundary; what changed
 * is which side of it the work happens on.
 */
class PluginLoader(
    context: Context,
    private val connection: EngineConnection = EngineConnection(context),
) {

    /** One plugin's worth of loading, with a durable marker naming it while it runs. */
    suspend fun load(
        pluginDir: String,
        modules: String,
        allowBridge: Boolean,
        journalPath: String,
        journalLabel: String,
    ): Result<String> = connection.withEngine {
        it.loadPlugins(pluginDir, modules, allowBridge, journalPath, journalLabel)
    }

    /** Runs one script with a deadline. [handle] is what [cancel] cancels. */
    suspend fun runScript(
        scriptPath: String,
        ledgerPath: String,
        timeoutSeconds: Double,
        allowBridge: Boolean,
        handle: String,
    ): Result<String> = connection.withEngine {
        it.runScript(scriptPath, ledgerPath, timeoutSeconds, allowBridge, handle)
    }

    /**
     * Asks a running script to stop.
     *
     * Fire-and-forget on its own coroutine: the caller is usually a button, and the script it
     * is stopping is holding a binder thread. Waiting for the cancel to be acknowledged would
     * mean waiting for the thing being cancelled.
     */
    fun cancel(scope: CoroutineScope, handle: String) {
        scope.launch(Dispatchers.IO) {
            connection.withEngine { it.cancelScript(handle) }
        }
    }
}
