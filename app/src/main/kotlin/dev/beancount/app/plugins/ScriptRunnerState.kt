package dev.beancount.app.plugins

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.beancount.core.domain.PluginPolicy
import dev.beancount.engine.local.PluginLoader
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
        ok -> "Finished in ${durationMs}ms."
        else -> "$errorType: $error"
    }
}

/**
 * Running a user script against the open ledger (SPEC.md Tier 3, N10).
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
) {
    private val loader by lazy { PluginLoader(context) }

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
    }

    /**
     * Copies the picked script somewhere the engine process can read, then runs it.
     *
     * A content URI is granted to *this* process, so the engine — a different process — cannot
     * open it. The copy lands in the app's cache and is deleted afterwards.
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

        val id = UUID.randomUUID().toString()
        handle = id
        _running.value = name
        _outcome.value = null
        _message.value = null

        scope.launch {
            val scratch = File(context.cacheDir, "scripts").apply { mkdirs() }
            val copy = File(scratch, "$id.py")
            try {
                val copied = withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { input ->
                            copy.outputStream().use { output -> input.copyTo(output) }
                        } ?: throw java.io.IOException("that file could not be opened")
                    }
                }
                copied.onFailure { problem ->
                    Log.w(TAG, "could not read the picked script", problem)
                    _message.value = "That file could not be read."
                    return@launch
                }

                val json = loader.runScript(
                    scriptPath = copy.absolutePath,
                    ledgerPath = ledgerPath,
                    timeoutSeconds = timeoutSeconds,
                    allowBridge = policy.allowsAndroidBridge,
                    handle = id,
                )
                _outcome.value = json.fold(
                    onSuccess = { parse(name, it) },
                    onFailure = { problem ->
                        Log.w(TAG, "the engine could not run the script", problem)
                        ScriptOutcome(
                            name = name,
                            ok = false,
                            output = "",
                            error = problem.message ?: problem.javaClass.simpleName,
                            errorType = "EngineUnavailable",
                            cancelled = false,
                            timedOut = false,
                            durationMs = 0,
                            overranByMs = 0,
                        )
                    },
                )
            } finally {
                copy.delete()
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
        const val DEFAULT_TIMEOUT = 30.0
    }
}
