package io.github.pintofi.beanbook.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The ledger could not be checked, which is not the same as the ledger being wrong.
 *
 * The engine runs in its own process. When it has been killed, or cannot be bound, the
 * validator has nothing to say about the file — and saying "the ledger could not be parsed"
 * is a claim about the user's data that is not true. The writer needs to tell the two apart
 * because the honest response differs: a real parse failure means the write broke something,
 * while this means nobody knows yet.
 */
class LedgerUnavailable(message: String) : Exception(message)

/**
 * Re-parses a project after a write. Implemented by the engine module; injected here so
 * the whole protocol is testable on the JVM without Python.
 */
fun interface LedgerValidator {
    /**
     * @return the errors Beancount reports, or a failure if the ledger could not be
     *   parsed at all. An empty list means the write is safe to commit.
     */
    suspend fun validate(mainFile: String): Result<List<String>>
}

/**
 * What to do when a write introduces new Beancount errors.
 *
 * This distinction exists because Beancount **never fails to parse**: it reports errors
 * and carries on, returning zero entries for unparseable input. So "could not parse" is
 * almost never the signal — the signal is *new errors that were not there before*
 *.
 */
enum class ErrorPolicy {
    /**
     * Roll back if the write introduces errors. For generated writes — guided entry,
     * imports, scripts — where broken output is our bug, not the user's intent.
     *
     * For a change the person wrote directly the rollback is not the last word: the result carries
     * the change ([HeldWrite]) and the app asks, and [ProjectWriter.keepWithErrors] makes it again
     * with the errors kept if they say so. Everything else stays rolled back.
     */
    ROLLBACK_ON_NEW_ERRORS,

    /**
     * Keep the write and report the errors. For the raw editor, where the user typed
     * exactly this text and refusing to save would lose their work; they see the errors
     * and fix them.
     */
    KEEP_AND_REPORT,
}

/** The prefix of the journal operation of a change kept with the errors it adds. */
private const val WITH_ERRORS = "with-errors:"

/** Why a write was undone. */
enum class RollbackCause {
    /** The change adds errors the ledger did not have. */
    NEW_ERRORS,

    /** The engine could not check the change, so it was undone. */
    UNCHECKED,

    /** Beancount could not read the ledger with the change. */
    UNREADABLE,

    /** The file could not be written. */
    IO,

    OTHER,
}

/**
 * A change that was undone because it adds errors, kept exactly as it was made so it can be made again if
 * the person decides the errors are acceptable. [content] is the whole new text of the file (null for a
 * delete), not a recipe: an append is held as the text it produced, so making it again cannot add the
 * entry twice. [expectedSha] is the hash of the file before the change; if the file is different when the
 * change is made again, it is refused as stale (invariant 6).
 */
class HeldWrite(
    val projectId: String,
    /** The file's path inside the project, with `/`. */
    val relativePath: String,
    val expectedSha: String,
    val content: ByteArray?,
    val operation: String,
    /** What the change added, as `file:line: message`. */
    val newErrors: List<String>,
)

/** What the caller asked for, and what actually happened. */
sealed interface WriteResult {
    data class Committed(val txnId: String, val warnings: List<String>) : WriteResult

    /**
     * The write was undone. The project is exactly as it was before.
     *
     * [cause] says why, because only one of the reasons is the person's to overrule: a change that adds
     * errors to the ledger is rolled back with [RollbackCause.NEW_ERRORS], and then [held] carries the
     * exact change that was undone so it can be made again, with the errors kept, if the person asks
     * ([ProjectWriter.keepWithErrors]). Every other cause has no [held]: there is no list of errors
     * to accept, or the ledger was never checked.
     */
    data class RolledBack(
        val reason: String,
        val errors: List<String>,
        val cause: RollbackCause = RollbackCause.OTHER,
        val held: HeldWrite? = null,
    ) : WriteResult

    /** Refused before anything was written. */
    data class Refused(val reason: String) : WriteResult
}

/**
 * The write protocol, in one place:
 *
 * ```
 * lock → snapshot → journal intent → tmp write + fsync → atomic rename
 *      → re-parse → commit → then report success
 * ```
 *
 * Every release-blocking data invariant (see the README, "How a save is written") is enforced here rather than by
 * convention, and each has a named test in ProjectWriterTest.
 */
class ProjectWriter(
    private val workspace: ProjectWorkspace,
    private val journal: WriteJournal,
    private val validator: LedgerValidator,
    private val undoDepth: Int = WriteJournal.DEFAULT_UNDO_DEPTH,
    /**
     * Told what each write did, for the app's own log.
     *
     * A line rather than a callback with structure: whoever reads it is a person trying to
     * work out what happened to their ledger, and "replace main.beancount op=edit-entry ->
     * RolledBack: …" is the sentence they need. Nothing here decides anything; a writer that
     * behaved differently when observed would be a worse writer.
     */
    private val observer: (String) -> Unit = {},
    /**
     * Told when a write starts (`true`) and when it has finished (`false`), whatever its
     * outcome.
     *
     * For the app's file watcher, which reloads the ledger when something *else* changes a
     * file. Without this every save also looked like an outside edit, and the engine parsed
     * the ledger again for each one on top of the write's own re-parse.
     */
    private val onWriting: (Boolean) -> Unit = {},
) {
    // Single writer per process. Concurrent writes to one ledger are a correctness
    // problem, not a throughput opportunity.
    private val lock = Mutex()

    /**
     * Replaces [expectedText] at [file] with [newText], refusing if the file changed
     * underneath (invariant 6: a retry must not duplicate or clobber).
     */
    suspend fun replace(
        projectId: String,
        file: File,
        expectedSha: String,
        newContent: String,
        operation: String = "replace",
        policy: ErrorPolicy = ErrorPolicy.ROLLBACK_ON_NEW_ERRORS,
    ): WriteResult =
        write(projectId, file, newContent.toByteArray(Charsets.UTF_8), expectedSha, operation, policy)
            .also { report(operation, file, it) }

    /**
     * Replaces [file] with raw [newContent], through the same protocol as [replace].
     *
     * A project holds more than its ledger — PDFs and other attachments sit beside it — and
     * those cannot go through the text API: a String round trip via UTF-8 replaces every byte
     * that is not valid UTF-8, so an attachment would arrive corrupted while the ledger still
     * parsed and nothing noticed.
     *
     * The ledger is still re-parsed afterwards. A pulled-in file can break a `document` link
     * or an `include`, and that is exactly what the re-parse is for.
     */
    suspend fun replaceBytes(
        projectId: String,
        file: File,
        expectedSha: String,
        newContent: ByteArray,
        operation: String = "replace-bytes",
        policy: ErrorPolicy = ErrorPolicy.ROLLBACK_ON_NEW_ERRORS,
    ): WriteResult = write(projectId, file, newContent, expectedSha, operation, policy)
        .also { report(operation, file, it) }

    /**
     * Deletes [file], through the same protocol as a write: the file is snapshotted first, so the
     * delete is journalled and [undoLast] puts it back byte for byte, and the ledger is re-parsed
     * afterwards. Deleting a file the ledger includes breaks the `include`, so that delete is rolled
     * back and the file is restored.
     *
     * Refused for the main ledger file, for a file that is not there, and for a stale [expectedSha].
     */
    suspend fun delete(
        projectId: String,
        file: File,
        expectedSha: String,
        operation: String = "delete",
        policy: ErrorPolicy = ErrorPolicy.ROLLBACK_ON_NEW_ERRORS,
    ): WriteResult = write(projectId, file, null, expectedSha, operation, policy)
        .also { report(operation, file, it) }

    /** Appends [text] to [file], creating it if needed. */
    suspend fun append(
        projectId: String,
        file: File,
        text: String,
        operation: String = "append",
        policy: ErrorPolicy = ErrorPolicy.ROLLBACK_ON_NEW_ERRORS,
    ): WriteResult {
        val existing = if (file.isFile) file.readText() else ""
        val separator = when {
            existing.isEmpty() -> ""
            existing.endsWith("\n\n") -> ""
            existing.endsWith("\n") -> "\n"
            else -> "\n\n"
        }
        return write(
            projectId = projectId,
            file = file,
            // Every entry is followed by one blank line, as Fava writes them, so the next entry
            // is already set apart and a delete can take that line with it.
            newContent = (existing + separator + text.trimEnd() + "\n\n").toByteArray(Charsets.UTF_8),
            expectedSha = if (file.isFile) WriteJournal.sha256(file) else "",
            operation = operation,
            policy = policy,
        ).also { report(operation, file, it) }
    }

    /**
     * Makes a change again that was undone for adding errors, this time keeping them.
     *
     * Only for a change the person wrote and has seen the errors of. It is the same write the first time
     * tried, from the bytes that were undone, under [ErrorPolicy.KEEP_AND_REPORT], and journalled as
     * `with-errors:` and the original operation so the log and Undo show what it was. The file is checked
     * against what it was before the first attempt, so a second tap, or an outside change in between, is
     * refused as stale and nothing is written twice.
     */
    suspend fun keepWithErrors(held: HeldWrite): WriteResult {
        val file = File(workspace.projectDir(held.projectId), held.relativePath)
        val operation = WITH_ERRORS + held.operation
        return write(held.projectId, file, held.content, held.expectedSha, operation, ErrorPolicy.KEEP_AND_REPORT)
            .also { report(operation, file, it) }
    }

    /**
     * Tells the observer what a write did, in one line and without the ledger's content
     *.
     *
     * The file's **name**, never its text, and the reason a write was refused or rolled back,
     * which is the app's own words rather than the user's data. A log that carried what was
     * being written would put somebody's finances in every report they sent.
     */
    private fun report(operation: String, file: File, result: WriteResult) {
        val outcome = when (result) {
            is WriteResult.Committed ->
                "Committed" + result.warnings.size.takeIf { it > 0 }?.let { " with $it ledger error(s)" }.orEmpty()
            is WriteResult.RolledBack -> "RolledBack: ${result.reason}"
            is WriteResult.Refused -> "Refused: ${result.reason}"
        }
        runCatching { observer("write ${file.name} op=$operation -> $outcome") }
    }

    private suspend fun write(
        projectId: String,
        file: File,
        newContent: ByteArray?,
        expectedSha: String,
        operation: String,
        policy: ErrorPolicy,
    ): WriteResult = lock.withLock {
        runCatching { onWriting(true) }
        try {
            writeLocked(projectId, file, newContent, expectedSha, operation, policy)
        } finally {
            runCatching { onWriting(false) }
        }
    }

    private suspend fun writeLocked(
        projectId: String,
        file: File,
        newContent: ByteArray?,
        expectedSha: String,
        operation: String,
        policy: ErrorPolicy,
    ): WriteResult {
        val projectDir = workspace.projectDir(projectId)
        if (!file.canonicalPath.startsWith(projectDir.canonicalPath + File.separator)) {
            // Invariant: a write may never escape the project (docs/threat-model.md §3.1).
            return WriteResult.Refused("${file.name} is outside the project")
        }
        if (newContent == null) {
            // A delete (null content). The main file is what Beancount is pointed at, and a file that
            // is not there has nothing to delete or to put back.
            if (file.canonicalFile == workspace.mainFile(projectId).canonicalFile) {
                return WriteResult.Refused("the main ledger file cannot be deleted")
            }
            if (!file.isFile) return WriteResult.Refused("${file.name} is already gone")
        }

        // Stale-write detection *before* the snapshot: nothing has changed yet, so there
        // is nothing to undo (invariant 3).
        val actualSha = if (file.isFile) WriteJournal.sha256(file) else ""
        if (actualSha != expectedSha) {
            return WriteResult.Refused(
                "the file was changed somewhere else since it was opened. Open it again " +
                    "and make the change there"
            )
        }

        // How many errors the ledger already had. Beancount never refuses to parse, so
        // this baseline is the only way to tell "the write broke something" from "the
        // ledger was already imperfect".
        var baselineMessages: List<String> = emptyList()
        val baseline = if (policy == ErrorPolicy.ROLLBACK_ON_NEW_ERRORS) {
            val before = validator.validate(workspace.mainFile(projectId).absolutePath)
            // Nothing has been written yet, so refusing here is free and honest. Carrying on
            // with a baseline of zero was worse than it looks: a ledger that already had
            // errors would then have the next perfectly good write rolled back for
            // "introducing" them.
            before.exceptionOrNull()?.let { failure ->
                if (failure is LedgerUnavailable) {
                    return WriteResult.Refused(
                        "Beanbook could not check your ledger before changing it. Nothing was " +
                            "changed; try again in a moment"
                    )
                }
            }
            before.getOrElse { emptyList() }.also { baselineMessages = it }.size
        } else {
            0
        }

        val record = journal.begin(
            projectId = projectId,
            operation = operation,
            files = listOf(file),
            baselineErrors = baseline,
            rollbackOnNewErrors = policy == ErrorPolicy.ROLLBACK_ON_NEW_ERRORS,
        )

        // From here the write is on its way to the ledger, and it has to be finished: either
        // committed after the re-parse or put back. A caller that is cancelled in the middle
        // (the screen closed, the activity recreated while the engine is still checking) must
        // not leave a changed file behind with nothing deciding what becomes of it, so nothing
        // after the intent is recorded can be cancelled.
        return withContext(NonCancellable) {
            complete(record, file, newContent, policy, baseline, baselineMessages, expectedSha, operation)
        }
    }

    private suspend fun complete(
        record: WriteJournal.Record,
        file: File,
        newContent: ByteArray?,
        policy: ErrorPolicy,
        baseline: Int,
        baselineMessages: List<String>,
        expectedSha: String,
        operation: String,
    ): WriteResult {
        val projectId = record.projectId
        return try {
            if (newContent == null) deleteDurably(file) else writeAtomically(file, newContent)
            journal.markRenamed(record)

            val validation = validator.validate(workspace.mainFile(projectId).absolutePath)
            val errors = validation.getOrElse { failure ->
                if (failure is LedgerUnavailable) {
                    // The write landed; nobody could check it. Under KEEP_AND_REPORT the
                    // user typed this text and losing it to an engine restart would be the
                    // one thing that policy exists to prevent, so it stays and the message
                    // says it is unchecked. A generated write is undone instead, because
                    // "we could not confirm this is safe" is not a reason to keep it.
                    return if (policy == ErrorPolicy.KEEP_AND_REPORT) {
                        journal.commit(record)
                        WriteResult.Committed(
                            txnId = record.txnId,
                            warnings = listOf(
                                "Saved, but the engine is not available so the ledger has " +
                                    "not been checked."
                            ),
                        )
                    } else {
                        journal.restoreSnapshot(record)
                        journal.markRolledBack(record)
                        WriteResult.RolledBack(
                            reason = "Beanbook could not check the change, so it was undone",
                            errors = listOfNotNull(failure.message),
                            cause = RollbackCause.UNCHECKED,
                        )
                    }
                }
                journal.restoreSnapshot(record)
                journal.markRolledBack(record)
                return WriteResult.RolledBack(
                    reason = "Beancount could not read the ledger with this change",
                    errors = listOfNotNull(failure.message),
                    cause = RollbackCause.UNREADABLE,
                )
            }

            if (policy == ErrorPolicy.ROLLBACK_ON_NEW_ERRORS && errors.size > baseline) {
                val added = newErrors(errors, baselineMessages, errors.size - baseline)
                journal.restoreSnapshot(record)
                journal.markRolledBack(record)
                return WriteResult.RolledBack(
                    reason = (errors.size - baseline).let {
                        if (it == 1) "it would add 1 error to your ledger" else "it would add $it errors to your ledger"
                    },
                    // What this write added, not the whole list: a ledger that already had
                    // errors would otherwise name them as though the new entry caused them.
                    errors = added,
                    cause = RollbackCause.NEW_ERRORS,
                    held = HeldWrite(
                        projectId = projectId,
                        relativePath = file.relativeTo(workspace.projectDir(projectId)).path.replace('\\', '/'),
                        expectedSha = expectedSha,
                        content = newContent,
                        operation = operation,
                        newErrors = added,
                    ),
                )
            }

            // Only now is it safe to report success (invariant 2).
            journal.commit(record)
            journal.prune(projectId, undoDepth)
            WriteResult.Committed(record.txnId, warnings = errors)
        } catch (io: IOException) {
            journal.restoreSnapshot(record)
            journal.markRolledBack(record)
            WriteResult.RolledBack(
                "the file could not be written (${io.message})", emptyList(), cause = RollbackCause.IO,
            )
        }
    }

    /**
     * Undoes the write [txnId] **only if it is still the latest**.
     *
     * An "Undo" shown after a save must not undo something else: a mirror pass or another
     * edit may have committed since, and restoring the older snapshot would silently discard
     * that too. Refused, with nothing changed, when anything came after.
     */
    suspend fun undoIfLatest(projectId: String, txnId: String): WriteResult {
        val latest = lock.withLock { journal.committed(projectId).firstOrNull()?.txnId }
        if (latest != txnId) {
            return WriteResult.Refused("something else has been saved since, so this cannot be undone safely")
        }
        return undoLast(projectId)
    }

    /**
     * Undo: restores the snapshot of the most recent committed write.
     *
     * Backed by durable snapshots, not UI state (invariant 8), so it survives process
     * death.
     */
    suspend fun undoLast(projectId: String): WriteResult = lock.withLock {
        val last = journal.committed(projectId).firstOrNull()
            ?: return WriteResult.Refused("nothing to undo")

        val record = journal.begin(
            projectId,
            "undo:${last.operation}",
            last.filesBefore.keys.map { File(workspace.projectDir(projectId), it) },
        )
        // Not cancellable once the snapshot is being put back, for the reason a write is not.
        withContext(NonCancellable) {
            journal.restoreSnapshot(last)
            journal.markRenamed(record)

            val validation = validator.validate(workspace.mainFile(projectId).absolutePath)
            if (validation.isFailure) {
                // Undoing produced an unparseable ledger: put it back and say so.
                journal.restoreSnapshot(record)
                journal.markRolledBack(record)
                WriteResult.RolledBack(
                    "undoing it would leave a ledger Beancount cannot read",
                    listOfNotNull(validation.exceptionOrNull()?.message),
                )
            } else {
                journal.markRolledBack(last)
                journal.commit(record)
                WriteResult.Committed(record.txnId, validation.getOrDefault(emptyList()))
            }
        }
    }

    /**
     * Runs at startup. An interrupted write is decided by re-parsing, never by guessing:
     *
     *  - if the result is no worse than the baseline recorded before the write, it is
     *    rolled **forward** and committed;
     *  - otherwise the snapshot is restored and the record rolled back.
     *
     * The baseline lives in the journal precisely because recovery runs in a *new*
     * process, which has no other way to know what the ledger looked like before.
     *
     * Either way the user is told (invariant 3: the previous valid project is always
     * recoverable).
     */
    suspend fun recover(projectId: String): List<RecoveryOutcome> = lock.withLock {
        journal.pending(projectId).map { record ->
            val validation = validator.validate(workspace.mainFile(projectId).absolutePath)
            val errors = validation.getOrNull()

            // The same judgement a completed write would have made, using the baseline
            // recorded before the process died. That includes the engine being unreachable:
            // a write the user typed is kept and reported as unchecked, exactly as it is when
            // the process does not die, and only a generated write is given up.
            val unchecked = validation.exceptionOrNull() is LedgerUnavailable
            val keep = if (record.rollbackOnNewErrors) {
                errors != null && errors.size <= record.baselineErrors
            } else {
                errors != null || unchecked
            }

            if (keep) {
                journal.commit(record)
                RecoveryOutcome(record.txnId, record.operation, rolledForward = true)
            } else {
                journal.restoreSnapshot(record)
                journal.markRolledBack(record)
                RecoveryOutcome(record.txnId, record.operation, rolledForward = false)
            }
        }
    }

    data class RecoveryOutcome(
        val txnId: String,
        val operation: String,
        val rolledForward: Boolean,
    )

    /** Removes [file] and syncs the directory, so the removal is as durable as a rename is. */
    private fun deleteDurably(file: File) {
        if (!file.delete()) throw IOException("could not delete ${file.name}")
        runCatching {
            file.parentFile?.let { parent ->
                FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
            }
        }
    }

    /**
     * tmp file → fsync → atomic rename → fsync the directory.
     *
     * The rename is what makes the write atomic: a reader sees either the whole old file
     * or the whole new one, never a partial write.
     */
    private fun writeAtomically(file: File, content: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")

        Durable.writeBytes(tmp, content)
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("could not rename ${tmp.name} onto ${file.name}")
        }
        // Durability of the rename itself: without this the directory entry can be lost.
        //
        // Through a channel, not a FileOutputStream. Opening a directory for writing fails
        // with EISDIR on Linux, so the previous form threw every time and runCatching
        // swallowed it -- the call had never once synced anything while the comment above it
        // claimed durability. Best-effort is still right (some filesystems refuse), but it
        // has to be able to succeed.
        runCatching {
            file.parentFile?.let { parent ->
                FileChannel.open(parent.toPath(), StandardOpenOption.READ).use { it.force(true) }
            }
        }
    }
}

/**
 * The errors in [after] that were not in [before], by text; if the texts do not tell them
 * apart (a line number moved), the last [count] -- a write appends, so they are the likeliest.
 */
internal fun newErrors(after: List<String>, before: List<String>, count: Int): List<String> {
    val remaining = before.toMutableList()
    val added = after.filter { message -> !remaining.remove(message) }
    return if (added.size == count) added else after.takeLast(count.coerceAtLeast(0))
}
