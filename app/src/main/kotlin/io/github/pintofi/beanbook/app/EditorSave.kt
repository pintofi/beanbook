package io.github.pintofi.beanbook.app

import io.github.pintofi.beanbook.core.data.ErrorPolicy
import io.github.pintofi.beanbook.core.data.WriteResult
import java.io.File

/**
 * Commits a save made in Fava's file editor (Reports, the editor page) through the write protocol.
 *
 * Fava used to write the file itself, from the engine process, which skipped the lock, the
 * snapshot, the journal, the re-parse and the Undo entry. The engine now hands the new bytes here.
 * [expectedSha] is the hash of the file's bytes as the engine read them, so a file changed since
 * is refused rather than overwritten.
 *
 * The raw editor keeps what was typed even when the ledger no longer parses
 * ([ErrorPolicy.KEEP_AND_REPORT]): the person is mid-edit, and Fava lists the errors on its own.
 *
 * @return "ok", or "error: " and a sentence for the editor to show.
 */
internal suspend fun BeancountApplication.saveFromEditor(
    file: String,
    content: ByteArray,
    expectedSha: String,
): String {
    // The workspace and the engine can name one folder differently (/data/user/0 and /data/data),
    // and the journal keys a file by its path under the project, so the path is rebuilt from it.
    val canonical = File(file).canonicalFile
    val projectId = workspace.listProjects().firstOrNull { id ->
        canonical.path.startsWith(workspace.projectDir(id).canonicalPath + File.separator)
    } ?: return "error: That file is not in one of your projects."
    val target = File(
        workspace.projectDir(projectId),
        canonical.relativeTo(workspace.projectDir(projectId).canonicalFile).path,
    )
    return when (
        val result = writer.replaceBytes(
            projectId = projectId,
            file = target,
            expectedSha = expectedSha,
            newContent = content,
            operation = "editor-save",
            policy = ErrorPolicy.KEEP_AND_REPORT,
        )
    ) {
        is WriteResult.Committed -> "ok"
        is WriteResult.RolledBack -> "error: ${result.reason}"
        is WriteResult.Refused -> "error: ${result.reason}"
    }
}
