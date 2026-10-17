package io.github.pintofi.beanbook.app.mirror

import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** What happened to one file, or to a pass, when the mirror last ran. */
data class MirrorHistoryEntry(val atEpochMs: Long, val kind: Kind, val detail: String) {
    enum class Kind(val label: String) {
        SENT("Sent to the folder"),
        BROUGHT_IN("Brought in from the folder"),
        REMOVED("Removed from the folder"),
        CONFLICT("Both versions kept"),
        FAILED("Could not mirror"),
    }
}

/**
 * The last ten things the mirror did, newest first, kept per project beside the other mirror
 * settings so they survive the app being closed. Names only, never file contents.
 */
class MirrorHistory(private val stateDir: File) {

    private var project: String? = null
    private val _entries = MutableStateFlow<List<MirrorHistoryEntry>>(emptyList())
    val entries: StateFlow<List<MirrorHistoryEntry>> = _entries.asStateFlow()

    private fun file() = project?.let { File(stateDir, "mirror-history-$it") }

    /** Loads [id]'s history, and drops the previous project's. */
    fun use(id: String) {
        project = id
        _entries.value = runCatching {
            file()?.takeIf { it.isFile }?.readLines().orEmpty().mapNotNull(::parse)
        }.getOrDefault(emptyList()).take(LIMIT)
    }

    /** Adds [added] (oldest first as they happened) to the front and keeps the newest ten. */
    fun record(added: List<MirrorHistoryEntry>) {
        // The same thing again is not new activity: a pass that fails the same way on every app
        // start and every resume listed one problem five times. Compared with the newest few.
        val recent = _entries.value.take(DUPLICATE_WINDOW).map { it.kind to it.detail }.toSet()
        val fresh = added.filter { (it.kind to it.detail) !in recent }
        if (fresh.isEmpty()) return
        val all = (fresh.sortedByDescending { it.atEpochMs } + _entries.value).take(LIMIT)
        _entries.value = all
        runCatching {
            stateDir.mkdirs()
            file()?.writeText(
                all.joinToString("\n") {
                    "${it.atEpochMs}\t${it.kind.name}\t${it.detail.replace('\n', ' ')}"
                },
            )
        }
    }

    private fun parse(line: String): MirrorHistoryEntry? {
        val parts = line.split('\t', limit = 3)
        if (parts.size < 3) return null
        val kind = MirrorHistoryEntry.Kind.entries.firstOrNull { it.name == parts[1] } ?: return null
        return MirrorHistoryEntry(parts[0].toLongOrNull() ?: return null, kind, parts[2])
    }

    companion object {
        const val LIMIT = 10
        private const val DUPLICATE_WINDOW = 3
    }
}
