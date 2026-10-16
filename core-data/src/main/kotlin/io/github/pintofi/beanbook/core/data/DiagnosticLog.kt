package io.github.pintofi.beanbook.core.data

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** How serious an entry is. Ordered, so a reader can be filtered to what went wrong. */
enum class LogLevel { DEBUG, INFO, WARN, ERROR }

/** One line of the app's own record of what it did. */
data class LogEntry(
    val atEpochMs: Long,
    val level: LogLevel,
    val tag: String,
    val message: String,
) {
    /** `2026-09-09 11:44:02.123 W/EngineManager: message` — the shape a person can scan. */
    fun render(): String =
        "${TIMESTAMP.format(Instant.ofEpochMilli(atEpochMs).atZone(ZoneId.systemDefault()))} " +
            "${level.name.first()}/$tag: $message"

    private companion object {
        /** Thread-safe, unlike `SimpleDateFormat`, which this log is written from many threads with. */
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)
    }
}

/**
 * The app's own log, kept on the device so a person can say what happened.
 *
 * There was no log at all: a user who met a bug had nothing to send, and nothing to read
 * themselves. `android.util.Log` writes to a buffer that is gone by the time anyone asks, and
 * the app uploads nothing — by design, and that is not changing — so the record has to live
 * where the user can reach it and hand it over deliberately.
 *
 * Pure Kotlin so the parts that can quietly ruin this — what gets written, what gets
 * redacted, what happens when the file fills — are testable without a device.
 *
 * **Redaction happens on the way in, not on the way out.** A secret that reaches the file is
 * a secret in every copy of it: in a backup, in a share sheet, in a screenshot of this
 * screen. The rule is "no secrets in logs", and the only way to keep it is for the
 * unredacted string never to be written.
 */
class DiagnosticLog(
    private val directory: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) {

    private val file: File get() = File(directory, "diagnostics.log")
    private val previous: File get() = File(directory, "diagnostics.1.log")
    private val lock = Any()

    /** Appends one entry, rotating first when the current file is full. */
    fun append(level: LogLevel, tag: String, message: String, atEpochMs: Long = System.currentTimeMillis()) {
        val line = LogEntry(atEpochMs, level, tag, redact(message)).render()
        synchronized(lock) {
            runCatching {
                directory.mkdirs()
                if (file.length() > maxBytes) rotate()
                file.appendText(line + "\n")
            }
        }
    }

    /**
     * The whole record, oldest first, across the rotation.
     *
     * Two files rather than one that is truncated: a crash tends to happen at the end of a
     * session, and truncating in place would throw away the run that led to it.
     */
    fun text(): String = synchronized(lock) {
        buildString {
            if (previous.isFile) append(runCatching { previous.readText() }.getOrDefault(""))
            if (file.isFile) append(runCatching { file.readText() }.getOrDefault(""))
        }
    }

    /** The last [limit] lines, newest last, for a screen that shows a tail. */
    fun tail(limit: Int = DEFAULT_TAIL): List<String> =
        text().lineSequence().filter { it.isNotBlank() }.toList().takeLast(limit)

    /** Empties the record. The user's own decision: this is their file, on their device. */
    fun clear() {
        synchronized(lock) {
            runCatching {
                file.delete()
                previous.delete()
            }
        }
    }

    /** How much room the record is taking, for a screen that says so before offering Clear. */
    fun sizeBytes(): Long = synchronized(lock) { file.length() + previous.length() }

    private fun rotate() {
        previous.delete()
        if (!file.renameTo(previous)) file.delete()
    }

    companion object {
        /** Two of these can exist at once, so the record costs at most twice this. */
        const val DEFAULT_MAX_BYTES = 256L * 1024

        const val DEFAULT_TAIL = 400

        private val RULES: List<Pair<Regex, String>> = listOf(
            // The engine's per-process token. It authenticates every request to the local
            // Fava, so it is the one string in this app that is worth anything to anybody.
            Regex("""bca_token=[A-Za-z0-9+/=_-]+""") to "bca_token=<redacted>",
            // The whole value, scheme included: `Authorization: Bearer <token>` is two words and
            // the second is the secret. A cookie header is a list, so the rest of the line goes.
            Regex("""(?i)\b(authorization)\s*[:=]\s*(?:(?:bearer|basic|token|digest)\s+)?\S+""") to "$1: <redacted>",
            Regex("""(?i)\b(set-cookie|cookie)\s*[:=]\s*[^\r\n]+""") to "$1: <redacted>",
            // What the user typed as a secret, however it was labelled. A passphrase can be
            // several words, so for those the rest of the line goes; a token is one.
            Regex("""(?i)\b(pass(?:word|phrase)|secret)\s*[:=]\s*[^\r\n]+""") to "$1=<redacted>",
            Regex("""(?i)\b(token|api[_-]?key)\s*[:=]\s*\S+""") to "$1=<redacted>",
            // Basic auth inside a URL, which is a password in a string that looks like a path.
            Regex("""://[^/\s:@]+:[^/\s@]+@""") to "://<redacted>@",
            // A home directory names a person. Upstream tracebacks carry them.
            Regex("""/(?:home|Users)/[^/\s"']+""") to "<home>",
        )

        /**
         * [message] with the things that must never be written taken out.
         *
         * Deliberately blunt. A log is read when something is already wrong, and a redaction
         * that removes a little too much costs a diagnosis; one that removes too little costs
         * a secret.
         */
        fun redact(message: String): String =
            RULES.fold(message) { text, (pattern, replacement) -> pattern.replace(text, replacement) }
    }
}
