package io.github.pintofi.beanbook.app.diagnostics

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.pintofi.beanbook.app.BuildConfig
import io.github.pintofi.beanbook.core.data.DiagnosticLog
import io.github.pintofi.beanbook.core.data.LogLevel
import java.io.File

/**
 * The app's record of what it did, and the report a user can hand over.
 *
 * Nothing here leaves the device on its own. The app makes no network request in local mode
 * and reports nothing anywhere, which is the promise on the website and in the store
 * listing; a log that uploaded itself would break it. So the record is written locally, shown
 * on a screen, and **sent by the user, deliberately, through the share sheet** -- or not at
 * all.
 *
 * Two sources, because neither is enough alone:
 *
 *  * **This log**, written by the app's own calls, which survives the process and the run.
 *  * **logcat for this process**, which carries everything already written with
 *    `android.util.Log` -- including Chaquopy's and Fava's own output -- and which an app is
 *    allowed to read for itself without any permission. It is gone on reboot, so it is read
 *    when the report is built rather than mirrored into the file.
 */
object Diagnostics {

    private const val TAG = "Diagnostics"

    @Volatile private var log: DiagnosticLog? = null

    /** Extra lines for the report's header, filled in by whoever knows them. */
    @Volatile private var describeEngine: () -> String = { "engine: not started" }

    fun install(context: Context, engineDescription: () -> String = { "engine: not started" }) {
        log = DiagnosticLog(File(context.filesDir, "state"))
        describeEngine = engineDescription
        installCrashHandler()
        i(TAG, "app started: ${BuildConfig.VERSION_NAME} (${BuildConfig.BUILD_TYPE})")
    }

    fun d(tag: String, message: String) = write(LogLevel.DEBUG, tag, message)

    fun i(tag: String, message: String) = write(LogLevel.INFO, tag, message)

    fun w(tag: String, message: String, error: Throwable? = null) =
        write(LogLevel.WARN, tag, message + (error?.let { ": ${describe(it)}" } ?: ""))

    fun e(tag: String, message: String, error: Throwable? = null) =
        write(LogLevel.ERROR, tag, message + (error?.let { ": ${describe(it)}" } ?: ""))

    /** The lines a screen shows, newest last. */
    fun tail(limit: Int = DiagnosticLog.DEFAULT_TAIL): List<String> = log?.tail(limit).orEmpty()

    fun sizeBytes(): Long = log?.sizeBytes() ?: 0L

    fun clear() {
        log?.clear()
        i(TAG, "log cleared by the user")
    }

    /**
     * Everything a person would need to send: what the app is, what the device is, what the
     * engine is, and what happened.
     *
     * The header is first because it answers the questions any report gets asked back.
     */
    fun report(): String = buildString {
        appendLine("Beanbook ${BuildConfig.VERSION_NAME} (${BuildConfig.FLAVOR}, ${BuildConfig.BUILD_TYPE})")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
        appendLine("Device ${Build.MANUFACTURER} ${Build.MODEL}, ${Build.SUPPORTED_ABIS.firstOrNull()}")
        appendLine(DiagnosticLog.redact(runCatching { describeEngine() }.getOrDefault("engine: unknown")))
        appendLine()
        appendLine("--- app log ---")
        appendLine(log?.text().orEmpty().ifBlank { "(nothing recorded yet)" })
        appendLine("--- system log for this process ---")
        appendLine(systemLog().ifBlank { "(none available)" })
    }

    /**
     * logcat for this process only.
     *
     * An app may read its own log without `READ_LOGS`; `--pid` is what keeps it to this
     * process, and the redaction is applied here because these lines were written by code
     * that never saw this class -- upstream Python tracebacks included.
     */
    private fun systemLog(lines: Int = SYSTEM_LOG_LINES): String = runCatching {
        val process = ProcessBuilder(
            listOf(
                "logcat", "-d", "-v", "time", "-t", lines.toString(),
                "--pid=${android.os.Process.myPid()}",
            ),
        ).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().use { it.readText() }
        process.waitFor()
        DiagnosticLog.redact(text)
    }.onFailure { Log.w(TAG, "could not read this process's log", it) }.getOrDefault("")

    private fun write(level: LogLevel, tag: String, message: String) {
        // Still to logcat as well: a developer with a device attached should not have to open
        // a screen to see what the app is doing.
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
        log?.append(level, tag, message)
    }

    /**
     * Records a crash before the process goes, then lets the platform do what it would have.
     *
     * This is the entry that matters most: a user who can say what the app was doing when it
     * disappeared is a user whose bug can be found. Delegating to the previous handler
     * afterwards keeps the platform's own crash reporting -- and the debugger -- intact.
     */
    private fun installCrashHandler() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { write(LogLevel.ERROR, TAG, "crash on ${thread.name}: ${describe(error)}") }
            previous?.uncaughtException(thread, error)
        }
    }

    /** A throwable as a few lines rather than a wall: the type, the message, and where. */
    private fun describe(error: Throwable): String = buildString {
        append(error.javaClass.name)
        error.message?.let { append(": ").append(it) }
        error.stackTrace.take(STACK_FRAMES).forEach { append("\n    at ").append(it) }
        error.cause?.let { append("\n  caused by ").append(describe(it)) }
    }

    private const val STACK_FRAMES = 12
    private const val SYSTEM_LOG_LINES = 600
}
