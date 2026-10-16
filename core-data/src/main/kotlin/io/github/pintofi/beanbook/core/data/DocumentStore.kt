package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.DocumentName
import java.io.File
import java.time.LocalDate

/**
 * Documents on disk, inside the project tree.
 *
 * Documents live in the project rather than beside it because a ledger that refers to a
 * receipt has to keep referring to it after an export, a backup or a move to another device
 * (document links stay valid across export, backup and mirror). Exporting a project
 * therefore carries the receipts with it.
 *
 * Writes go through the same discipline as everything else that must survive a process
 * death: whole file to a temporary name, then an atomic rename into place. A half-written
 * receipt is never linked from an entry, because the link is only written afterwards.
 */
class DocumentStore(private val workspace: ProjectWorkspace) {

    /** Where a stored document ended up, and whether it was already there. */
    data class Stored(val relativePath: String, val alreadyExisted: Boolean) {
        val file: String get() = relativePath.substringAfterLast('/')
    }

    /**
     * Files [content] against [account] on [date].
     *
     * Saving the same bytes twice is a no-op that returns the existing path: the name is
     * derived from the content, so a retried save cannot leave a second copy behind
     * (invariant 6). Different content that happens to collide gets the next ordinal rather
     * than overwriting what is already there — losing a receipt to a hash collision would be
     * silent data loss.
     */
    fun store(
        projectId: String,
        account: String,
        date: LocalDate,
        content: ByteArray,
        extension: String = "pdf",
        /** What the user called it, if anything. Sanitised by `DocumentName`. */
        label: String? = null,
        /** Attached to an entry, so kept out of the account folders. */
        attached: Boolean = false,
    ): Stored {
        var name = DocumentName.forContent(account, date, content, extension, label, attached)
        val root = workspace.projectDir(projectId)

        while (true) {
            val target = File(root, name.relativePath)
            if (target.isFile) {
                // Same name: either the identical capture (nothing to do) or a genuine
                // collision (try the next ordinal).
                if (target.readBytes().contentEquals(content)) {
                    return Stored(name.relativePath, alreadyExisted = true)
                }
                name = name.next()
                continue
            }

            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, "${target.name}.part")
            // Flushed before it is named, and the folder after: the entry that links to this
            // document is written next, and a link to a file that came back empty after a power
            // loss is a broken link the user finds months later.
            Durable.writeBytes(tmp, content)
            check(tmp.renameTo(target)) { "could not put the document in place: ${name.relativePath}" }
            Durable.syncDirectory(target.parentFile)
            return Stored(name.relativePath, alreadyExisted = false)
        }
    }

    fun read(projectId: String, relativePath: String): ByteArray? =
        resolve(projectId, relativePath)?.takeIf { it.isFile }?.readBytes()

    fun exists(projectId: String, relativePath: String): Boolean =
        resolve(projectId, relativePath)?.isFile == true

    /**
     * Every document this project holds, relative to it, in name order.
     *
     * [extraRoots] are the folders `option "documents"` names. Beancount reads those
     * as well as anything a `document` directive points at, so a file in one of them is filed
     * as far as the ledger is concerned -- and a screen that listed only its own folder called
     * it missing. They are resolved against the project and ignored when they lead outside it,
     * which is the same containment rule every other path in this app is held to.
     */
    fun list(projectId: String, extraRoots: List<String> = emptyList()): List<String> {
        val root = workspace.projectDir(projectId)
        val folders = (listOf(DocumentName.ROOT) + extraRoots)
            .map { File(root, it) }
            .filter { it.isDirectory && isInside(it, root) }
            .distinctBy { it.canonicalPath }
        return folders
            .flatMap { folder ->
                folder.walkTopDown()
                    .filter { it.isFile && it.extension != "part" }
                    .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
                    .toList()
            }
            .distinct()
            .sorted()
    }

    /**
     * Deletes the file itself. **Unlinking is not this** — removing the reference from an
     * entry is an edit to the ledger and goes through `ProjectWriter` like any other; this
     * is the separate, explicitly-confirmed act of throwing the document away
     * (`DocumentRemoval`).
     */
    fun delete(projectId: String, relativePath: String): Boolean {
        val file = resolve(projectId, relativePath) ?: return false
        if (!file.isFile) return false
        val deleted = file.delete()
        // Tidy the account directory when it empties, so the tree does not fill with husks;
        // never touch documents/ itself, which is part of the project's shape, and never a
        // folder outside it: a document kept in another folder that `option "documents"` names
        // is in a folder the person made and named, and an empty one is theirs to remove.
        val documentsRoot = File(workspace.projectDir(projectId), DocumentName.ROOT).canonicalFile
        var parent = file.parentFile
        while (parent != null && isEmptyFolderUnder(parent, documentsRoot)) {
            parent.delete()
            parent = parent.parentFile
        }
        return deleted
    }

    /** An empty folder strictly inside [documentsRoot], which is the only kind a delete tidies. */
    private fun isEmptyFolderUnder(folder: File, documentsRoot: File): Boolean =
        folder != documentsRoot && isInside(folder, documentsRoot) &&
            folder.isDirectory && folder.list()?.isEmpty() == true

    /**
     * Resolves a project-relative path, refusing anything that leaves the project.
     *
     * A document link comes from the ledger, and a ledger can be edited by hand or imported
     * from elsewhere, so it is not trusted input: `../` in a link must not be able to read
     * or delete a file outside the project.
     */
    private fun resolve(projectId: String, relativePath: String): File? {
        val root = workspace.projectDir(projectId).canonicalFile
        val candidate = File(root, relativePath).canonicalFile
        return candidate.takeIf { isInside(candidate, root) }
    }

    /**
     * Whether [file] is [root] or somewhere under it. Compared on whole path segments: a plain
     * prefix test says the folder of a project called `proj2` is inside the project called
     * `proj`, and a ledger could then name another project's documents as its own.
     */
    private fun isInside(file: File, root: File): Boolean {
        val path = file.canonicalFile.path
        val base = root.canonicalFile.path
        return path == base || path.startsWith(base + File.separator)
    }
}
