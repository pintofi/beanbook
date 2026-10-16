package io.github.pintofi.beanbook.core.data

import java.io.File
import java.io.IOException
import java.security.MessageDigest

/**
 * The write-ahead journal and its snapshots (docs/storage-and-backup.md §3).
 *
 * The on-disk layout *is* the format — no parser, because a half-written journal record
 * must never be the thing that loses a ledger:
 *
 * ```
 * journal/<project>/<txn>/op          operation name
 * journal/<project>/<txn>/files       one "<sha-before> <relative path>" per line
 * journal/<project>/<txn>/state       intent | renamed | committed | rolledback
 * snapshots/<project>/<txn>/<path>    byte copies of every file about to change
 * ```
 *
 * `state` is written last and replaced atomically, so its presence and content are the
 * only things recovery has to trust.
 */
class WriteJournal(
    private val workspace: ProjectWorkspace,
    /** Milliseconds since the epoch; a parameter so a test can make the clock go backwards. */
    private val clock: () -> Long = System::currentTimeMillis,
) {

    enum class State { INTENT, RENAMED, COMMITTED, ROLLEDBACK }

    data class Record(
        val projectId: String,
        val txnId: String,
        val operation: String,
        /** relative path -> sha-256 of the file before the write ("" if it did not exist) */
        val filesBefore: Map<String, String>,
        val state: State,
        val dir: File,
        /**
         * How many errors the ledger had *before* this write, and whether new ones should
         * cause a rollback. Persisted because recovery runs in a new process that cannot
         * otherwise know what "before" looked like.
         */
        val baselineErrors: Int = 0,
        val rollbackOnNewErrors: Boolean = true,
    )

    fun begin(
        projectId: String,
        operation: String,
        files: List<File>,
        baselineErrors: Int = 0,
        rollbackOnNewErrors: Boolean = true,
    ): Record {
        val txnId = "%013d-%s".format(nextTimestamp(projectId), randomSuffix())
        val dir = journalDir(projectId, txnId).apply { mkdirs() }
        val projectDir = workspace.projectDir(projectId)

        val before = files.associate { file ->
            val relative = file.relativeTo(projectDir).path
            relative to if (file.isFile) sha256(file) else ""
        }

        // Snapshot before anything is touched: this is what rollback and undo restore. Flushed to
        // storage, with the folders that name it: the ledger is replaced only after this, and a
        // snapshot that was never flushed can be empty after a power loss, which would turn a
        // rollback into the loss it exists to prevent.
        val snapshotDir = snapshotDir(projectId, txnId).apply { mkdirs() }
        files.filter { it.isFile }.forEach { file ->
            val target = File(snapshotDir, file.relativeTo(projectDir).path)
            Durable.copy(file, target)
            Durable.syncDirectory(target.parentFile)
        }
        Durable.syncDirectory(snapshotDir)
        Durable.syncDirectory(snapshotDir.parentFile)

        Durable.writeText(File(dir, OP), operation)
        Durable.writeText(File(dir, BASELINE), "$baselineErrors ${if (rollbackOnNewErrors) "rollback" else "keep"}")
        Durable.writeText(
            File(dir, FILES),
            before.entries.joinToString("\n") { (path, sha) -> "$sha $path" },
        )
        writeState(dir, State.INTENT)
        Durable.syncDirectory(dir.parentFile)

        return Record(
            projectId = projectId,
            txnId = txnId,
            operation = operation,
            filesBefore = before,
            state = State.INTENT,
            dir = dir,
            baselineErrors = baselineErrors,
            rollbackOnNewErrors = rollbackOnNewErrors,
        )
    }

    fun markRenamed(record: Record) = writeState(record.dir, State.RENAMED)

    fun commit(record: Record) = writeState(record.dir, State.COMMITTED)

    fun markRolledBack(record: Record) = writeState(record.dir, State.ROLLEDBACK)

    /** Records that were interrupted: neither committed nor rolled back. */
    fun pending(projectId: String): List<Record> =
        journalRoot(projectId).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { read(projectId, it) }
            ?.filter { it.state == State.INTENT || it.state == State.RENAMED }
            ?.sortedBy { it.txnId }
            .orEmpty()

    /** Completed records, newest first — the undo stack. */
    fun committed(projectId: String): List<Record> =
        journalRoot(projectId).listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { read(projectId, it) }
            ?.filter { it.state == State.COMMITTED }
            ?.sortedByDescending { it.txnId }
            .orEmpty()

    fun snapshotDir(projectId: String, txnId: String): File =
        workspace.snapshotsDir.resolve(projectId).resolve(txnId)

    /**
     * Restores every snapshotted file over the live project. Used by both rollback and
     * undo — the difference is intent, not mechanism.
     */
    fun restoreSnapshot(record: Record) {
        val projectDir = workspace.projectDir(record.projectId)
        val snapshot = snapshotDir(record.projectId, record.txnId)
        record.filesBefore.forEach { (relative, shaBefore) ->
            restoreOne(File(projectDir, relative), File(snapshot, relative), relative, shaBefore)
        }
    }

    private fun restoreOne(live: File, saved: File, relative: String, shaBefore: String) {
        if (!saved.isFile) {
            removeOrFail(live, relative, shaBefore)
            return
        }
        requireIntact(saved, relative, shaBefore)
        putBack(saved, live, relative)
    }

    /**
     * The file did not exist before this write, so undoing means removing it. If it did exist
     * and the copy that was taken is gone, nothing can be restored, and saying otherwise would be
     * a false claim about the user's ledger.
     */
    private fun removeOrFail(live: File, relative: String, shaBefore: String) {
        if (shaBefore.isNotEmpty()) {
            throw IOException("the saved copy of $relative is missing, so it cannot be restored")
        }
        live.delete()
    }

    /**
     * The saved copy has to be exactly what was saved. A truncated or altered one would replace
     * a ledger that is intact with one that is not, and say it had put things back.
     */
    private fun requireIntact(saved: File, relative: String, shaBefore: String) {
        if (shaBefore.isNotEmpty() && sha256(saved) != shaBefore) {
            throw IOException("the saved copy of $relative is damaged, so it was not restored")
        }
    }

    /** Through a temp file, so a crash mid-restore cannot leave a truncated ledger behind. */
    private fun putBack(saved: File, live: File, relative: String) {
        live.parentFile?.mkdirs()
        val tmp = File(live.parentFile, "${live.name}.restore")
        Durable.copy(saved, tmp)
        if (!tmp.renameTo(live)) {
            tmp.delete()
            throw IOException("could not put $relative back")
        }
        Durable.syncDirectory(live.parentFile)
    }

    /**
     * Forgets the whole history of [projectId]: every record and every snapshot.
     *
     * For when the project's files have been replaced wholesale (a restore). The saved copies
     * describe the project as it was before, and Undo would put them back over what is there now.
     * Not for general use: an interrupted write is what [pending] and recovery are for.
     */
    fun forget(projectId: String) {
        journalRoot(projectId).deleteRecursively()
        workspace.snapshotsDir.resolve(projectId).deleteRecursively()
    }

    /** Drops records older than [keep], oldest first. Snapshots are the undo depth. */
    fun prune(projectId: String, keep: Int = DEFAULT_UNDO_DEPTH) {
        // Terminal records only. Sorting every directory by name and deleting the oldest
        // could take an INTENT or RENAMED record -- a write that was interrupted and has not
        // been recovered yet -- and its snapshot with it, which is the one thing recovery
        // needs.
        val all = journalRoot(projectId).listFiles()
            ?.filter { it.isDirectory }
            ?.filter { dir ->
                val state = read(projectId, dir)?.state
                state == State.COMMITTED || state == State.ROLLEDBACK
            }
            ?.sortedBy { it.name }
            ?: return
        val excess = all.size - keep
        if (excess <= 0) return
        all.take(excess).forEach { dir ->
            workspace.snapshotsDir.resolve(projectId).resolve(dir.name).deleteRecursively()
            dir.deleteRecursively()
        }
    }

    private fun read(projectId: String, dir: File): Record? {
        val stateFile = File(dir, STATE)
        if (!stateFile.isFile) return null
        val state = runCatching { State.valueOf(stateFile.readText().trim()) }.getOrNull()
            ?: return null
        val files = File(dir, FILES).takeIf { it.isFile }?.readLines().orEmpty()
            .filter { it.isNotBlank() }
            .associate { line ->
                val sha = line.substringBefore(' ')
                val path = line.substringAfter(' ')
                path to sha
            }
        val baseline = File(dir, BASELINE).takeIf { it.isFile }?.readText()?.trim().orEmpty()
        return Record(
            projectId = projectId,
            txnId = dir.name,
            operation = File(dir, OP).takeIf { it.isFile }?.readText().orEmpty(),
            filesBefore = files,
            state = state,
            dir = dir,
            baselineErrors = baseline.substringBefore(' ').toIntOrNull() ?: 0,
            rollbackOnNewErrors = !baseline.endsWith("keep"),
        )
    }

    private fun writeState(dir: File, state: State) {
        // Write, flush, rename, flush the folder: a reader never sees a partially written state
        // file, and the state it sees is the one that survives a power loss.
        val tmp = File(dir, "$STATE.tmp")
        Durable.writeText(tmp, state.name)
        if (!tmp.renameTo(File(dir, STATE))) throw IOException("could not record the state of the write")
        Durable.syncDirectory(dir)
    }

    /**
     * The timestamp part of the next transaction id.
     *
     * Ids sort by name, and the undo stack, recovery and pruning all trust that order. The
     * wall clock can go backwards (a manual change, a dead battery that resets it to the
     * epoch), and a write made after that would sort *before* older ones: Undo would then
     * restore the wrong snapshot. So an id is never earlier than the latest one that exists.
     */
    private fun nextTimestamp(projectId: String): Long {
        val latest = journalRoot(projectId).listFiles().orEmpty()
            .mapNotNull { it.name.substringBefore('-').toLongOrNull() }
            .maxOrNull() ?: Long.MIN_VALUE
        val now = clock()
        return if (now > latest) now else latest + 1
    }

    private fun journalRoot(projectId: String): File =
        workspace.journalDir.resolve(projectId).apply { mkdirs() }

    private fun journalDir(projectId: String, txnId: String): File =
        journalRoot(projectId).resolve(txnId)

    private fun randomSuffix(): String =
        (1..6).map { ALPHABET.random() }.joinToString("")

    companion object {
        const val DEFAULT_UNDO_DEPTH = 50
        private const val OP = "op"
        private const val FILES = "files"
        private const val BASELINE = "baseline"
        private const val STATE = "state"
        private const val ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789"

        fun sha256(file: File): String = sha256(file.readBytes())

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }
}
