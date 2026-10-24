package io.github.pintofi.beanbook.app.mirror

/**
 * What a file hashed to, remembered while its size and modified time stay the same (#66).
 *
 * A mirror pass compares every file by content, because clocks cannot be compared across
 * providers. That does not mean reading every file again on each return to the app: on one side,
 * a file that reports the same size and the same modified time as when it was last read is the
 * same file, which is the rule `make` and `rsync` live by.
 *
 * Two cases are not trusted. A provider that gives no modified time (zero) or no size (negative)
 * is always read. And a file modified within [RACY_MS] of being read is not remembered, because a
 * second write in the same instant, with the same size, would leave the stamp unchanged and the
 * old hash standing.
 */
internal class FileHashCache {

    private class Entry(val size: Long, val modified: Long, val sha: String)

    private val entries = HashMap<String, Entry>()

    /** The hash of the file named [key], from memory when [size] and [modified] match, else [compute]d. */
    fun hashOf(
        key: String,
        size: Long,
        modified: Long,
        now: Long = System.currentTimeMillis(),
        compute: () -> String,
    ): String {
        if (size < 0 || modified <= 0) return compute()
        entries[key]?.let { if (it.size == size && it.modified == modified) return it.sha }
        val sha = compute()
        if (modified + RACY_MS < now) entries[key] = Entry(size, modified, sha) else entries.remove(key)
        return sha
    }

    /** Forgets every file not in [keys], so a deleted file does not stay in memory. */
    fun retain(keys: Set<String>) {
        entries.keys.retainAll(keys)
    }

    private companion object {
        const val RACY_MS = 2_000L
    }
}
