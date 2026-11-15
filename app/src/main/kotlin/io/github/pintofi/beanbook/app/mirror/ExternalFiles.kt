package io.github.pintofi.beanbook.app.mirror

import android.content.ContentResolver
import androidx.documentfile.provider.DocumentFile
import io.github.pintofi.beanbook.app.storage.TreeLinkedSource
import io.github.pintofi.beanbook.core.data.BackupArchive
import io.github.pintofi.beanbook.core.data.rebaseLinkedPath
import io.github.pintofi.beanbook.core.domain.LedgerLinks
import io.github.pintofi.beanbook.core.domain.PluginPreflight

/**
 * The files of the mirror folder that belong to a project: the ones the project has, looked up by path, and
 * the ones its ledger files there now link to.
 *
 * A project holds exactly the files its ledger links to, so the folder beside it may hold much more (a
 * repository, other ledgers, scans). Those are never listed, hashed or offered: the mirror looks up the
 * paths it knows, and follows a link added on the other side (a new `include`, a new document) to the file
 * it names, which then arrives as an ordinary pull-in. A folder's entries are read only for the folders
 * that hold a project file or a linked one, and only the names that are needed are kept.
 */
class ExternalFiles(private val resolver: ContentResolver) {

    /** [known] project paths, and the files the ledger in the folder now links to, by project path. */
    fun find(root: DocumentFile, known: Collection<String>, main: String): Map<String, DocumentFile> {
        val found = lookUp(root, known.filter { allowed(it) })
        for ((path, document) in linked(root, main)) found.putIfAbsent(path, document)
        return found
    }

    /** One file by its project path, or null when it is not there. */
    fun findOne(root: DocumentFile, path: String): DocumentFile? =
        if (allowed(path)) lookUp(root, listOf(path))[path] else null

    /** Paths looked up as a group, so a folder with many project files is listed once. */
    private fun lookUp(root: DocumentFile, paths: Collection<String>): MutableMap<String, DocumentFile> {
        val found = linkedMapOf<String, DocumentFile>()
        val byFolder = paths.groupBy { it.substringBeforeLast('/', missingDelimiterValue = "") }
        val folders = HashMap<String, DocumentFile?>()
        fun folder(path: String): DocumentFile? = folders.getOrPut(path) {
            if (path.isEmpty()) {
                root
            } else {
                folder(path.substringBeforeLast('/', missingDelimiterValue = ""))
                    ?.findFile(path.substringAfterLast('/'))?.takeIf { it.isDirectory }
            }
        }
        for ((path, members) in byFolder) {
            val directory = folder(path) ?: continue
            val wanted = members.map { it.substringAfterLast('/') }.toSet()
            for (child in directory.listFiles()) {
                val name = child.name ?: continue
                if (name in wanted && !child.isDirectory) found[if (path.isEmpty()) name else "$path/$name"] = child
            }
        }
        return found
    }

    /** What the project's main file, as it is in the folder, links to. Empty when it is not there. */
    private fun linked(root: DocumentFile, main: String): Map<String, DocumentFile> {
        val chain = main.split('/').dropLast(1)
        val source = TreeLinkedSource(resolver, root, chain)
        val ledger = LedgerLinks(source, maxUp = chain.size).walk(main.substringAfterLast('/'))
        val paths = ledger.files.map { rebaseLinkedPath(it.path, chain) }.filter { allowed(it) }
        return lookUp(root, paths)
    }

    private fun allowed(path: String): Boolean =
        !BackupArchive.isExcluded(path) && !path.contains(".conflict-") &&
            // The names come from a provider this app does not control and are joined into a path under the
            // project: a name with ".." must never get that far.
            !PluginPreflight.escapesRoot(path)
}
