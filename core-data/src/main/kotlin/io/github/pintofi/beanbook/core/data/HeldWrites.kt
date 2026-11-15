package io.github.pintofi.beanbook.core.data

import java.io.File

/**
 * The change a person is being asked about: made, found to add errors, undone, and kept here until they
 * choose Save anyway or Don't save. One per project, the latest.
 *
 * Kept on disk, not in memory, so a dialog that was open when the app was stopped comes back when the
 * project is opened again, with the same change behind it. It holds ledger text, which is the same kind of
 * data as drafts and the snapshots Undo uses: in the app's private folder, never in the project, never in
 * a backup or the mirror (the app does not allow Android backup). Cleared on either answer, and with the
 * project.
 */
class HeldWrites(private val workspace: ProjectWorkspace) {

    private fun meta(projectId: String) = File(workspace.heldDir, "${valid(projectId)}.json")
    private fun content(projectId: String) = File(workspace.heldDir, "${valid(projectId)}.content")

    private fun valid(projectId: String): String = projectId.also { workspace.projectDir(it) }

    /** Keeps [held], replacing any earlier one for its project. Durable before it returns. */
    fun put(held: HeldWrite) {
        workspace.heldDir.mkdirs()
        // The content first: a meta file without its content would offer a change that cannot be made.
        held.content?.let { Durable.writeBytes(content(held.projectId), it) } ?: content(held.projectId).delete()
        val json = Json.Obj(
            mapOf(
                "path" to Json.Str(held.relativePath),
                "sha256" to Json.Str(held.expectedSha),
                "operation" to Json.Str(held.operation),
                "delete" to Json.Bool(held.content == null),
                "errors" to Json.Arr(held.newErrors.map { Json.Str(it) }),
            ),
        )
        Durable.writeText(meta(held.projectId), json.write() + "\n")
        Durable.syncDirectory(workspace.heldDir)
    }

    /** The change waiting for [projectId]'s answer, or null when there is none or what is kept cannot be read. */
    fun get(projectId: String): HeldWrite? = runCatching {
        val file = meta(projectId).takeIf { it.isFile } ?: return null
        val root = Json.parse(file.readText()) as Json.Obj
        val delete = (root.fields["delete"] as? Json.Bool)?.value == true
        val bytes = if (delete) null else content(projectId).takeIf { it.isFile }?.readBytes() ?: return null
        HeldWrite(
            projectId = projectId,
            relativePath = root.string("path") ?: return null,
            expectedSha = root.string("sha256") ?: return null,
            content = bytes,
            operation = root.string("operation") ?: return null,
            newErrors = root.array("errors").orEmpty().mapNotNull { (it as? Json.Str)?.value },
        )
    }.getOrNull()

    /** Forgets the change kept for [projectId]. */
    fun clear(projectId: String) {
        meta(projectId).delete()
        content(projectId).delete()
    }
}
