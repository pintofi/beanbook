package io.github.pintofi.beanbook.app

import io.github.pintofi.beanbook.core.data.ErrorPolicy
import io.github.pintofi.beanbook.core.data.WriteResult
import io.github.pintofi.beanbook.engine.local.FavaWrite
import java.io.File

/**
 * How each kind of write Fava asks for is committed. The engine names the operation; the app
 * decides the policy, and an operation not listed here is refused.
 *
 * The raw editor keeps what was typed even when the ledger no longer parses
 * ([ErrorPolicy.KEEP_AND_REPORT]): the person is mid-edit, and Fava lists the errors on its own.
 */
internal val favaWritePolicies: Map<String, ErrorPolicy> = mapOf(
    "editor-save" to ErrorPolicy.KEEP_AND_REPORT,
    // Editing an entry in its own box is the same typing as the editor, so what was typed is kept.
    "entry-edit" to ErrorPolicy.KEEP_AND_REPORT,
    // Written by Fava's forms, not typed: broken output is a bug, so a write that makes the ledger
    // worse is undone and refused.
    "entry-delete" to ErrorPolicy.ROLLBACK_ON_NEW_ERRORS,
    "add-entries" to ErrorPolicy.ROLLBACK_ON_NEW_ERRORS,
    "attach-document" to ErrorPolicy.ROLLBACK_ON_NEW_ERRORS,
)

/**
 * Commits a write Fava made, through the write protocol.
 *
 * Fava used to write the file itself, from the engine process, which skipped the lock, the
 * snapshot, the journal, the re-parse and the Undo entry. The engine now hands the new bytes
 * here. [FavaWrite.expectedSha] is the hash of the file's bytes as the engine read them, so a file
 * changed since is refused rather than overwritten.
 *
 * A write that came over Share on Wi-Fi is refused unless the share was started with editing
 * allowed: the engine checks that too, and this is the second check.
 *
 * @return "ok", or "error: " and a sentence for the editor to show.
 */
internal suspend fun BeancountApplication.commitFromFava(write: FavaWrite): String {
    val policy = favaWritePolicies[write.operation] ?: return "error: That kind of change is not available."
    if (write.viaShare && !sharingState.allowsSharedWrites()) {
        return "error: Editing from another device is off."
    }
    // The workspace and the engine can name one folder differently (/data/user/0 and /data/data),
    // and the journal keys a file by its path under the project, so the path is rebuilt from it.
    val canonical = File(write.file).canonicalFile
    val projectId = workspace.listProjects().firstOrNull { id ->
        canonical.path.startsWith(workspace.projectDir(id).canonicalPath + File.separator)
    } ?: return "error: That file is not in one of your projects."
    val target = File(
        workspace.projectDir(projectId),
        canonical.relativeTo(workspace.projectDir(projectId).canonicalFile).path,
    )
    // The journal says where the change came from, so the log and Undo show a change made from
    // another device as one.
    val operation = if (write.viaShare) "shared:${write.operation}" else write.operation
    return when (
        val result = writer.replaceBytes(
            projectId = projectId,
            file = target,
            expectedSha = write.expectedSha,
            newContent = write.content,
            operation = operation,
            policy = policy,
        )
    ) {
        is WriteResult.Committed -> "ok"
        is WriteResult.RolledBack -> "error: ${result.reason}"
        is WriteResult.Refused -> "error: ${result.reason}"
    }
}
