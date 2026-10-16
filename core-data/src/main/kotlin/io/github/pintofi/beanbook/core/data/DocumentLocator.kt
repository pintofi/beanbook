package io.github.pintofi.beanbook.core.data

import java.io.File

/**
 * Finds the file a ledger's document reference means.
 *
 * A reference is not a path the app can simply join to the project. Beancount and Fava accept
 * three kinds, and a ledger mixes them:
 *
 * - a `document` directive's path, which Beancount resolves against **the file that declares
 *   it** and reports as absolute;
 * - a transaction's `document:` metadata, which `fava.plugins.link_documents` matches against
 *   the **file name** of a document it knows -- this app writes exactly that, the bare name,
 *   because it is what the plugin needs;
 * - a path relative to the file the entry is in, which the plugin also accepts.
 *
 * The viewer used to join every one of them to the project root, so a receipt attached from
 * the entry form -- a bare file name -- was looked for at the top of the project, was never
 * there, and "could not be shown".
 *
 * Every candidate is held to the project tree: a reference is text from a file that can be
 * edited by hand or imported, and `../` in it must not reach anything outside (invariant 5).
 */
object DocumentLocator {

    /**
     * The file [reference] names, or null when none of the readings finds one in the project.
     *
     * @param entryFile the ledger file the reference was written in, when known.
     * @param known documents the project already has: directive paths (absolute) and files
     *   found in its documents folders (relative to [projectDir]). Searched by name last.
     */
    fun locate(
        projectDir: File,
        reference: String,
        entryFile: String? = null,
        known: List<String> = emptyList(),
    ): File? {
        val project = projectDir.canonicalFile
        val trimmed = reference.trim()
        if (trimmed.isEmpty()) return null

        val given = File(trimmed)
        val direct = buildList {
            if (given.isAbsolute) add(given)
            entryFile?.let { File(it).absoluteFile.parentFile }?.let { add(File(it, trimmed)) }
            add(File(project, trimmed))
        }
        direct.firstOrNull { it.isInside(project) && it.isFile }?.let { return it.canonicalFile }

        val name = given.name
        return known.asSequence()
            .map { File(it).let { path -> if (path.isAbsolute) path else File(project, it) } }
            .filter { it.name == name }
            .firstOrNull { it.isInside(project) && it.isFile }
            ?.canonicalFile
    }

    private fun File.isInside(project: File): Boolean {
        val path = canonicalFile.path
        return path == project.path || path.startsWith(project.path + File.separator)
    }
}
