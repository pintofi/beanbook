package io.github.pintofi.beanbook.engine.local

import android.util.Log
import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * The answers a ledger produced last time, kept so opening it again does not cost a full load.
 *
 * **Why this exists.** A 100k-entry ledger takes ~25 s to load on a desktop and ~30 s on a
 * phone, against the 8 s start-up budget, and profiling showed where it goes: parsing is
 * 4.2 s of it, **booking and validation are 85%**, and this repository's own post-processing
 * is 4%. Optimising our Python was never the lever.
 *
 * The lever is that the native screens do not need the ledger. The Journal shows a 200-row
 * window, Home shows a handful of totals, Balances shows one row per account — all of which
 * is [io.github.pintofi.beanbook.core.domain.LedgerSnapshot], and none of which grows with entry count.
 * So the derived answers are cached and startup stops depending on how big the books are.
 * Fava, queries and background refresh still do the full load; they are the things that
 * actually need every entry.
 *
 * **What makes a cached answer valid.** The fingerprint covers the *content* of every file
 * that fed the load, not their timestamps. Hashing 30 MB costs on the order of a hundred
 * milliseconds against a 25-second load, and mtime-and-size is exactly the shortcut that
 * shows a stale balance after an edit that happened to preserve both — which in an
 * accounting app is worse than being slow.
 *
 * The file list comes from the previous load's `included_files`, which is sound because the
 * include graph cannot change without changing the text of a file already in that list: a
 * new `include` line is itself an edit to a file being hashed.
 *
 * Plugin directories, the bridge flag and the journal window are in the fingerprint too,
 * because each of them changes what a load produces. So is [FORMAT], so that a change to the
 * engine's output shape invalidates every entry rather than being misread.
 */
internal class SnapshotCache(private val dir: File) {

    /** One file per ledger, holding the fingerprint it was produced from and the payload. */
    private fun entryFor(ledgerPath: String): File =
        File(dir, digestOf(ledgerPath.toByteArray()) + ".json")

    /**
     * The cached JSON for [ledgerPath], or `null` when there is none that still applies.
     *
     * Any surprise — a missing file, unreadable JSON, a fingerprint that no longer matches —
     * is a miss, never an error. The cost of a miss is the load that would have happened
     * anyway; the cost of trusting a bad entry is wrong figures on screen.
     */
    fun read(ledgerPath: String, inputs: Inputs): String? = runCatching {
        val entry = entryFor(ledgerPath)
        if (!entry.isFile) return null
        val stored = JSONObject(entry.readText())
        if (stored.optString("format") != FORMAT) return null

        val files = stored.optJSONArray("files")?.let { array ->
            List(array.length()) { array.getString(it) }
        } ?: return null

        val expected = fingerprint(files, inputs) ?: return null
        if (expected != stored.optString("fingerprint")) return null
        stored.optString("payload").takeIf { it.isNotEmpty() }
    }.getOrElse {
        Log.w(TAG, "ignoring unreadable cache entry", it)
        null
    }

    /**
     * Remembers [payload] as the answer for the files it was produced from.
     *
     * [files] is the load's own `included_files`, so a ledger that grew an include records
     * the wider set and is checked against it next time.
     *
     * A failure here is logged and dropped: not caching is a slow app, and there is nothing
     * a user could do about it that is worth an error on screen.
     */
    fun write(ledgerPath: String, files: List<String>, inputs: Inputs, payload: String) {
        runCatching {
            val print = fingerprint(files, inputs) ?: return
            dir.mkdirs()
            val entry = entryFor(ledgerPath)
            val document = JSONObject()
                .put("format", FORMAT)
                .put("fingerprint", print)
                .put("files", JSONArray(files))
                .put("payload", payload)
            // Written beside and renamed, so a kill mid-write cannot leave a truncated entry
            // that the next run reads as a whole one -- the same discipline the ledger write
            // protocol uses.
            val temporary = File(entry.parentFile, entry.name + ".part")
            temporary.writeText(document.toString())
            if (!temporary.renameTo(entry)) temporary.delete()
        }.onFailure { Log.w(TAG, "could not cache the snapshot", it) }
    }

    /** Everything outside the files themselves that changes what a load produces. */
    data class Inputs(
        val journalLimit: Int,
        val pluginDirs: String,
        val allowBridge: Boolean,
        /** The date range the snapshot was computed over; empty means the whole file. */
        val dateFrom: String = "",
        val dateTo: String = "",
        /**
         * The app build that made it. The engine's output is produced by code that ships with the
         * app, so an update can change what the same ledger produces, and a cached answer from
         * the build before would be served as if it had never changed. [FORMAT] covers a change
         * of shape that somebody remembered to record; this covers all of them.
         */
        val appVersion: Long = 0L,
    )

    /**
     * A digest over every input, or `null` if any file has gone.
     *
     * The path is hashed alongside the content so that two files swapping names is a
     * different fingerprint, and the length is included so a digest collision would also
     * have to preserve size.
     */
    private fun fingerprint(files: List<String>, inputs: Inputs): String? {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(FORMAT.toByteArray())
        // The range is part of the key: a cached snapshot of one period must never be
        // served for another, which would put the wrong figures on screen with no reload.
        digest.update(
            ("${inputs.journalLimit}|${inputs.pluginDirs}|${inputs.allowBridge}" +
                "|${inputs.dateFrom}|${inputs.dateTo}|${inputs.appVersion}").toByteArray(),
        )
        // What is in the plugin directories, not only which directories they are. A plugin is
        // code the load runs, so installing, editing or removing one changes what the same
        // ledger produces, and the ledger's own files have not changed.
        digest.update(pluginListing(inputs.pluginDirs).toByteArray())
        // Sorted, so the order the engine happened to report includes in cannot change the
        // fingerprint on its own.
        files.sorted().forEach { path ->
            val file = File(path)
            if (!file.isFile) return null
            digest.update(path.toByteArray())
            digest.update(file.length().toString().toByteArray())
            file.inputStream().use { stream ->
                val buffer = ByteArray(BUFFER)
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    digest.update(buffer, 0, read)
                }
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * The names, sizes and modification times directly inside each plugin directory. One level
     * only, as the engine's own cache key does: a site folder can hold a hundred megabytes of
     * packages, and walking it on every open would cost more than the cache saves.
     */
    private fun pluginListing(pluginDirs: String): String =
        pluginDirs.split(',').filter { it.isNotBlank() }.sorted().joinToString("\n") { directory ->
            val children = File(directory).listFiles().orEmpty().sortedBy { it.name }
            directory + children.joinToString("") { "|${it.name}:${it.length()}:${it.lastModified()}" }
        }

    private fun digestOf(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val TAG = "SnapshotCache"
        const val BUFFER = 64 * 1024

        /**
         * Bumped whenever the engine's JSON changes shape.
         *
         * Without it, adding a field would leave every existing entry parsing successfully
         * and silently missing it -- a screen quietly losing a column rather than a load
         * failing loudly.
         */
        const val FORMAT = "5"
    }
}
