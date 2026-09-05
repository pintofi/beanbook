package io.github.pintofi.beanbook.core.data

import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Re-parses a project after a write. Implemented by the engine module; injected here so
 * the whole protocol is testable on the JVM without Python.
 */
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
 * (measured on device, docs/milestones/m4.md).
 */
enum class ErrorPolicy {
    /**
     * Roll back if the write introduces errors. For generated writes — guided entry,
     * imports, scripts — where broken output is our bug, not the user's intent.
     */
    ROLLBACK_ON_NEW_ERRORS,

    /**
     * Keep the write and report the errors. For the raw editor, where the user typed
     * exactly this text and refusing to save would lose their work; they see the errors
     * and fix them.
     */
    KEEP_AND_REPORT,
}

/** What the caller asked for, and what actually happened. */
sealed interface WriteResult {
    data class Committed(val txnId: String, val warnings: List<String>) : WriteResult

    /** The write was undone. The project is exactly as it was before. */
    data class RolledBack(val reason: String, val errors: List<String>) : WriteResult

    /** Refused before anything was written. */
    data class Refused(val reason: String) : WriteResult
}

/**
 * The write protocol from CLAUDE.md, in one place:
 *
 * ```
 * lock → snapshot → journal intent → tmp write + fsync → atomic rename
 *      → re-parse → commit → then report success
 * ```
 *
 * Every release-blocking invariant in SPEC.md §5 is enforced here rather than by
 * convention, and each has a named test in ProjectWriterTest.
 */
class ProjectWriter(
    private val workspace: ProjectWorkspace,
    private val journal: WriteJournal,
    private val validator: LedgerValidator,
    private val undoDepth: Int = WriteJournal.DEFAULT_UNDO_DEPTH,
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
            newContent = (existing + separator + text.trimEnd() + "\n").toByteArray(Charsets.UTF_8),
            expectedSha = if (file.isFile) WriteJournal.sha256(file) else "",
            operation = operation,
            policy = policy,
        )
    }

    private suspend fun write(
        projectId: String,
        file: File,
        newContent: ByteArray,
        expectedSha: String,
        operation: String,
        policy: ErrorPolicy,
    ): WriteResult = lock.withLock {
        val projectDir = workspace.projectDir(projectId)
        if (!file.canonicalPath.startsWith(projectDir.canonicalPath + File.separator)) {
            // Invariant: a write may never escape the project (docs/threat-model.md §3.1).
            return WriteResult.Refused("refusing to write outside the project: ${file.name}")
        }

        // Stale-write detection *before* the snapshot: nothing has changed yet, so there
        // is nothing to undo (invariant 3).
        val actualSha = if (file.isFile) WriteJournal.sha256(file) else ""
        if (actualSha != expectedSha) {
            return WriteResult.Refused(
                "the file changed since it was read (expected ${expectedSha.take(8)}, " +
                    "found ${actualSha.take(8)})"
            )
        }

        // How many errors the ledger already had. Beancount never refuses to parse, so
        // this baseline is the only way to tell "the write broke something" from "the
        // ledger was already imperfect".
        val baseline = if (policy == ErrorPolicy.ROLLBACK_ON_NEW_ERRORS) {
            val before = validator.validate(workspace.mainFile(projectId).absolutePath)
            // Nothing has been written yet, so refusing here is free and honest. Carrying on
            // with a baseline of zero was worse than it looks: a ledger that already had
            // errors would then have the next perfectly good write rolled back for
            // "introducing" them.
            before.exceptionOrNull()?.let { failure ->
                if (failure is LedgerUnavailable) {
                    return WriteResult.Refused(
                        "the engine is not available, so the ledger could not be checked " +
                            "before writing. Nothing was changed."
                    )
                }
            }
            before.getOrElse { emptyList() }.size
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

        return try {
            writeAtomically(file, newContent)
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
                            reason = "the engine is not available, so the write could not be " +
                                "checked and was undone. Your ledger is unchanged.",
                            errors = listOfNotNull(failure.message),
                        )
                    }
                }
                journal.restoreSnapshot(record)
                journal.markRolledBack(record)
                return WriteResult.RolledBack(
                    reason = "the ledger could not be parsed after the write",
                    errors = listOfNotNull(failure.message),
                )
            }

            if (policy == ErrorPolicy.ROLLBACK_ON_NEW_ERRORS && errors.size > baseline) {
                journal.restoreSnapshot(record)
                journal.markRolledBack(record)
                return WriteResult.RolledBack(
                    reason = "the write introduced ${errors.size - baseline} new error(s)",
                    errors = errors,
                )
            }

            // Only now is it safe to report success (invariant 2).
            journal.commit(record)
            journal.prune(projectId, undoDepth)
            WriteResult.Committed(record.txnId, warnings = errors)
        } catch (io: IOException) {
            journal.restoreSnapshot(record)
            journal.markRolledBack(record)
            WriteResult.RolledBack("write failed: ${io.message}", emptyList())
        }
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
        journal.restoreSnapshot(last)
        journal.markRenamed(record)

        val validation = validator.validate(workspace.mainFile(projectId).absolutePath)
        return if (validation.isFailure) {
            // Undoing produced an unparseable ledger: put it back and say so.
            journal.restoreSnapshot(record)
            journal.markRolledBack(record)
            WriteResult.RolledBack(
                "undo would have left the ledger unparseable",
                listOfNotNull(validation.exceptionOrNull()?.message),
            )
        } else {
            journal.markRolledBack(last)
            journal.commit(record)
            WriteResult.Committed(record.txnId, validation.getOrDefault(emptyList()))
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
            // recorded before the process died.
            val keep = errors != null &&
                (!record.rollbackOnNewErrors || errors.size <= record.baselineErrors)

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

    /**
     * tmp file → fsync → atomic rename → fsync the directory.
     *
     * The rename is what makes the write atomic: a reader sees either the whole old file
     * or the whole new one, never a partial write.
     */
    private fun writeAtomically(file: File, content: ByteArray) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")

        FileOutputStream(tmp).use { out ->
            out.write(content)
            out.flush()
            out.fd.sync()
        }
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
