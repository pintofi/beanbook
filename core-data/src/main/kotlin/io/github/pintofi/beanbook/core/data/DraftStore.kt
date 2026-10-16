package io.github.pintofi.beanbook.core.data

import java.io.File

/**
 * Unsaved editor buffers, kept on disk (docs/architecture.md §5).
 *
 * Android kills backgrounded processes without warning, and losing half an hour of typed
 * corrections would be indistinguishable from data loss to the person it happens to. So a
 * draft is written when the editor goes away and restored — visibly — when it comes back.
 *
 * A draft records the hash of the file it was based on. If the file changed underneath
 * while the draft sat there, restoring it blindly would silently revert someone else's
 * edit, so the mismatch is surfaced instead.
 */
class DraftStore(private val workspace: ProjectWorkspace) {

    data class Draft(
        val projectId: String,
        val relativePath: String,
        val text: String,
        val baseSha: String,
        val savedAt: Long,
    ) {
        /** True when the file has changed since this draft was taken. */
        fun isStaleAgainst(currentSha: String): Boolean = baseSha != currentSha
    }

    fun save(projectId: String, relativePath: String, text: String, baseSha: String) {
        val file = draftFile(projectId, relativePath)
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        // Header first, then the body: one file, no separate index to fall out of step.
        tmp.writeText("$baseSha\n${System.currentTimeMillis()}\n$text")
        tmp.renameTo(file)
    }

    fun load(projectId: String, relativePath: String): Draft? {
        val file = draftFile(projectId, relativePath)
        if (!file.isFile) return null
        val content = file.readText()
        val firstBreak = content.indexOf('\n')
        val secondBreak = content.indexOf('\n', firstBreak + 1)
        if (firstBreak < 0 || secondBreak < 0) return null
        return Draft(
            projectId = projectId,
            relativePath = relativePath,
            text = content.substring(secondBreak + 1),
            baseSha = content.substring(0, firstBreak),
            savedAt = content.substring(firstBreak + 1, secondBreak).toLongOrNull() ?: 0L,
        )
    }

    fun clear(projectId: String, relativePath: String) {
        draftFile(projectId, relativePath).delete()
    }

    fun listFor(projectId: String): List<String> =
        workspace.draftsDir.resolve(projectId).walkTopDown()
            .filter { it.isFile && it.extension == "draft" }
            .map { it.relativeTo(workspace.draftsDir.resolve(projectId)).path.removeSuffix(".draft") }
            .toList()

    private fun draftFile(projectId: String, relativePath: String): File {
        val base = workspace.draftsDir.resolve(projectId).canonicalFile
        val file = base.resolve("$relativePath.draft").canonicalFile
        // The path is a ledger file's name, which can come from an include in a project that was
        // imported. A draft is written wherever this says, so it has to stay in the drafts folder.
        require(file.path.startsWith(base.path + File.separator)) { "not a path inside the project: $relativePath" }
        return file
    }
}
