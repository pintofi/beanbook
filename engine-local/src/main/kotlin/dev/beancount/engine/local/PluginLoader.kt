package dev.beancount.engine.local

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Loads user plugins through the embedded interpreter.
 *
 * The Kotlin side stays thin on purpose. Everything that decides *what user code may reach*
 * lives in `plugins.py`, because the mechanism it has to work with — Chaquopy's replacement
 * of `builtins.__import__` — is only visible from inside Python
 * (docs/milestones/m11.md, SPEC.md Q8).
 */
object PluginLoader {

    /** One plugin's worth of loading, with a durable marker naming it while it runs. */
    suspend fun load(
        context: Context,
        pluginDir: String,
        modules: String,
        allowBridge: Boolean,
        journalPath: String,
        journalLabel: String,
    ): String = withContext(Dispatchers.IO) {
        PythonRuntime.ensureStarted(context)
            .getModule("plugins")
            .callAttr(
                "load_plugins",
                pluginDir,
                modules,
                allowBridge,
                emptyArray<String>(),
                journalPath,
                journalLabel,
            )
            .toString()
    }

    /** Runs one script with a deadline. [handle] is what [cancelScript] cancels. */
    suspend fun runScript(
        context: Context,
        scriptPath: String,
        ledgerPath: String,
        timeoutSeconds: Double,
        allowBridge: Boolean,
        handle: String,
    ): String = withContext(Dispatchers.IO) {
        PythonRuntime.ensureStarted(context)
            .getModule("plugins")
            .callAttr(
                "run_script",
                scriptPath,
                ledgerPath,
                timeoutSeconds,
                allowBridge,
                emptyArray<String>(),
                handle,
            )
            .toString()
    }

    /**
     * Asks a running script to stop.
     *
     * Deliberately not suspending and deliberately not on the IO dispatcher: the script is
     * occupying a thread, and a cancel that queued behind it would never arrive.
     */
    fun cancelScript(context: Context, handle: String) {
        PythonRuntime.ensureStarted(context)
            .getModule("plugins")
            .callAttr("cancel_script", handle)
    }
}
