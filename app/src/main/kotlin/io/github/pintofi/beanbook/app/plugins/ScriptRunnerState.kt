package io.github.pintofi.beanbook.app.plugins

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.pintofi.beanbook.core.domain.PluginPolicy
import io.github.pintofi.beanbook.engine.local.PluginLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What a finished run produced, as the screen needs to show it. */
data class ScriptOutcome(
    val name: String,
    val ok: Boolean,
    val output: String,
    val error: String,
    val errorType: String,
    val cancelled: Boolean,
    val timedOut: Boolean,
    val durationMs: Long,
    val overranByMs: Long,
) {
    /**
     * What actually happened, said in the order a person cares about.
     *
     * `overranByMs` matters and is easy to skip past: a script blocked inside a single C call
     * cannot be interrupted, so the deadline is noticed only once it returns. Saying "stopped
     * after 30 s" when it really ran for 90 would be a lie the engine took care to avoid.
     */
    fun summary(): String = when {
        cancelled && overranByMs > 500 ->
            "Stopped. It ran ${overranByMs / 1000}s longer, inside a call that cannot " +
                "be interrupted."
        cancelled -> "Stopped at your request."
        timedOut && overranByMs > 500 ->
            "Out of time. It ran ${overranByMs / 1000}s past the deadline, inside a " +
                "call that cannot be interrupted."
        timedOut -> "Out of time."
        ok && output.isBlank() -> "Finished in ${durationMs}ms. It printed nothing."
        ok -> "Finished in ${durationMs}ms."
        else -> "$errorType: $error"
    }
}

/**
 * Running a user script against the open ledger (N10).
 *
 * The engine has been able to do this since M11 — with a deadline, cancellation, output
 * capture and redaction — and nothing in the app called it, so a paid feature shipped dead.
 *
 * Every call goes through [PluginLoader] to the `:engine` process. Nothing here starts Python,
 * which is the property the R9 process split exists to keep (docs/architecture.md §7.1).
 */
class ScriptRunnerState(
    private val context: Context,
    private val policy: PluginPolicy,
    private val scope: CoroutineScope,
    /**
     * What a script may import (B60): the plugins the user turned on and the packages they
     * fetched, asked for at run time rather than captured, because either can change between
     * runs -- which is the whole point of fetching one.
     */
    private val importable: () -> String = { "" },
) {
    private val loader by lazy { PluginLoader(context) }

    private val store by lazy { SavedScripts(context.filesDir.resolve("state")) }

    private val _saved = MutableStateFlow<List<SavedScript>>(emptyList())

    /** The scripts kept from earlier runs, so one can be run again without the picker (B49). */
    val saved: StateFlow<List<SavedScript>> = _saved.asStateFlow()

    fun refreshSaved() {
        scope.launch {
            _saved.value = withContext(Dispatchers.IO) { store.list() }
        }
    }

    /** Runs a script the user kept. */
    fun runSaved(id: String, timeoutSeconds: Double = DEFAULT_TIMEOUT) {
        val script = store.find(id)
        if (script == null) {
            _message.value = "That script is no longer saved."
            refreshSaved()
            return
        }
        run(script.file, script.name, timeoutSeconds, deleteAfterwards = false)
    }

    /** Forgets a saved script and deletes the copy. */
    fun forget(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { store.remove(id) }
            _message.value = "Removed from your saved scripts. Nothing else was touched."
            refreshSaved()
        }
    }

    private val _running = MutableStateFlow<String?>(null)
    /** The name of the script now running, or null. */
    val running: StateFlow<String?> = _running.asStateFlow()

    private val _outcome = MutableStateFlow<ScriptOutcome?>(null)
    val outcome: StateFlow<ScriptOutcome?> = _outcome.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    @Volatile private var handle: String? = null
    @Volatile private var ledgerPath: String = ""

    fun onProjectOpened(mainFile: File?) {
        ledgerPath = mainFile?.absolutePath.orEmpty()
        refreshSaved()
    }

    /**
     * Copies the picked script somewhere the engine process can read, keeps it, then runs it.
     *
     * A content URI is granted to *this* process, so the engine — a different process — cannot
     * open it. The copy is kept as a saved script rather than deleted, because a SAF grant
     * does not outlive the launch and the same script is usually run more than once (B49).
     */
    fun runFrom(uri: Uri, name: String, timeoutSeconds: Double = DEFAULT_TIMEOUT) {
        if (_running.value != null) {
            _message.value = "A script is already running."
            return
        }
        if (ledgerPath.isEmpty()) {
            _message.value = "Open a project first. A script runs against a ledger."
            return
        }

        scope.launch {
            val scratch = File(context.cacheDir, "scripts").apply { mkdirs() }
            val copy = File(scratch, "${UUID.randomUUID()}.py")
            val copied = withContext(Dispatchers.IO) {
                runCatching {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        copy.outputStream().use { output -> input.copyTo(output) }
                    } ?: throw java.io.IOException("that file could not be opened")
                }
            }
            if (copied.isFailure) {
                Log.w(TAG, "could not read the picked script", copied.exceptionOrNull())
                _message.value = "That file could not be read."
                copy.delete()
                return@launch
            }
            // Kept, then run from the kept copy. A SAF grant lasts for this launch, so a
            // script picked today could not be run again tomorrow without finding it in the
            // picker again (B49).
            val script = withContext(Dispatchers.IO) { store.save(copy, name) }
            copy.delete()
            refreshSaved()
            run(script.file, script.name, timeoutSeconds, deleteAfterwards = false)
        }
    }

    /**
     * Runs a script that is already a real file this process owns.
     *
     * @param deleteAfterwards for a scratch copy that is nobody's script. A saved one stays.
     */
    private fun run(
        file: File,
        name: String,
        timeoutSeconds: Double,
        deleteAfterwards: Boolean,
    ) {
        if (_running.value != null) {
            _message.value = "A script is already running."
            return
        }
        if (ledgerPath.isEmpty()) {
            _message.value = "Open a project first. A script runs against a ledger."
            return
        }
        val id = UUID.randomUUID().toString()
        handle = id
        _running.value = name
        _outcome.value = null
        _message.value = null

        scope.launch {
            val started = System.currentTimeMillis()
            try {
                val json = loader.runScript(
                    scriptPath = file.absolutePath,
                    ledgerPath = ledgerPath,
                    timeoutSeconds = timeoutSeconds,
                    allowBridge = policy.allowsAndroidBridge,
                    handle = id,
                    pluginDirs = importable(),
                )
                _outcome.value = json.fold(
                    onSuccess = { parse(name, it).also { outcome ->
                        Diagnostics.i(TAG, "script \"$name\" -> ${outcome.summary()}")
                    } },
                    onFailure = { problem ->
                        Log.w(TAG, "the engine could not run the script", problem)
                        ScriptOutcome(
                            name = name,
                            ok = false,
                            output = "",
                            error = problem.message ?: problem.javaClass.simpleName,
                            errorType = "Not run",
                            cancelled = false,
                            timedOut = false,
                            durationMs = 0,
                            overranByMs = 0,
                        )
                    },
                )
            } finally {
                if (deleteAfterwards) file.delete()
                // A script that fails on its first line finishes in milliseconds, and the
                // progress bar appeared and vanished within one frame -- which reads as the
                // screen glitching rather than as a script that ran (B60). Held just long
                // enough to be seen. The work is already done; this delays only the
                // indicator.
                val shown = System.currentTimeMillis() - started
                if (shown < MINIMUM_VISIBLE_MILLIS) {
                    kotlinx.coroutines.delay(MINIMUM_VISIBLE_MILLIS - shown)
                }
                _running.value = null
                handle = null
            }
        }
    }

    /** Asks the running script to stop. Fire and forget; the engine holds the binder thread. */
    fun cancel() {
        handle?.let { loader.cancel(scope, it) }
    }

    fun clearMessage() {
        _message.value = null
    }

    private fun parse(name: String, raw: String): ScriptOutcome {
        val json = JSONObject(raw)
        return ScriptOutcome(
            name = name,
            ok = json.optBoolean("ok"),
            output = json.optString("output"),
            error = json.optString("error"),
            errorType = json.optString("error_type"),
            cancelled = json.optBoolean("cancelled"),
            timedOut = json.optBoolean("timed_out"),
            durationMs = json.optDouble("duration_ms", 0.0).toLong(),
            overranByMs = json.optDouble("overran_by_ms", 0.0).toLong(),
        )
    }

    private companion object {
        const val TAG = "ScriptRunnerState"

        /** Long enough that a run is seen to have happened; short enough not to be a wait. */
        const val MINIMUM_VISIBLE_MILLIS = 450L
        const val DEFAULT_TIMEOUT = 30.0
    }
}
