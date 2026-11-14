package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.BeancountText
import java.io.File

/**
 * Makes sure every folder `option "documents"` names exists.
 *
 * Beancount's `beancount.ops.documents` plugin reports *"Document root '…' does not exist"*
 * for a named folder that is not on disk, and Fava shows the same error on every page. A
 * ledger with no receipts yet is not wrong, and nothing is missing from it -- the folder is
 * simply empty, and some ways of moving a project (a zip, a sync service, git) do not carry
 * empty folders at all. Creating the folder is the fix a person would make by hand, and it
 * changes no byte of any ledger file.
 *
 * Roots resolve against the directory of the main file, which is what Beancount does. A root
 * that leads outside the project is left alone: this app never creates anything outside the
 * project tree (invariant 5), and a folder elsewhere on the device is the person's to make.
 */
object DocumentRoots {

    /**
     * The project a main file belongs to: the folder directly under `projects/` that holds it, so a main
     * file in a subfolder (a project imported from `books/hmw`) still counts the whole project as inside,
     * and `../documents` is a folder of the project and not outside it. A file elsewhere is its own folder's.
     */
    fun projectOf(mainFile: File): File {
        var folder = mainFile.absoluteFile.parentFile ?: return mainFile
        while (folder.parentFile != null) {
            if (folder.parentFile.name == "projects") return folder
            folder = folder.parentFile
        }
        return mainFile.absoluteFile.parentFile ?: mainFile
    }

    /** The folders this call created, relative to [projectDir]. Empty when none were missing. */
    fun ensure(mainFile: File, projectDir: File = projectOf(mainFile)): List<String> {
        val source = runCatching { mainFile.readText() }.getOrNull() ?: return emptyList()
        val base = mainFile.absoluteFile.parentFile ?: return emptyList()
        val project = projectDir.canonicalFile
        return BeancountText.documentRoots(source).mapNotNull { root ->
            val declared = File(root)
            val folder = (if (declared.isAbsolute) declared else File(base, root)).canonicalFile
            val inside = folder.path == project.path ||
                folder.path.startsWith(project.path + File.separator)
            when {
                !inside -> null
                folder.exists() -> null
                folder.mkdirs() -> folder.relativeTo(project).path.replace(File.separatorChar, '/')
                else -> null
            }
        }
    }
}
