package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.LinkedLedger
import io.github.pintofi.beanbook.core.domain.LinkedSource
import java.io.InputStream

/**
 * The files of a [LinkedLedger] as an [ImportSource], laid out as they are in the project.
 *
 * The walker names paths from the main file's own folder, with `..` for folders above it. The project's
 * base is the highest folder any linked file is in, so [mainFolders] says the names of the folders from that
 * base down to the main file's own folder (as many as the ledger reaches up), and every path is rebuilt from
 * them: `../shared.bean` from `books/hmw` is `shared.bean`, and `hmw` is `books/hmw`. Nothing is moved,
 * renamed or flattened.
 */
class LinkedImportSource(
    private val source: LinkedSource,
    private val ledger: LinkedLedger,
    mainFolders: List<String>,
) : ImportSource {

    init {
        require(mainFolders.size == ledger.levelsUp) {
            "the ledger reaches ${ledger.levelsUp} folders above the main file; ${mainFolders.size} were named"
        }
    }

    private val chain = mainFolders

    /** Where [path], in the main file's coordinates, is in the project. */
    val projectPaths: Map<String, String> = ledger.files.associate { it.path to rebase(it.path) }

    /** The main file's path in the project. */
    val mainPath: String get() = checkNotNull(projectPaths[ledger.main]) { "the main file was not read" }

    override fun entries(): List<SourceEntry> =
        ledger.files.map { SourceEntry(path = projectPaths.getValue(it.path), size = it.size, sourcePath = it.path) }

    private val coordinates: Map<String, String> = projectPaths.entries.associate { it.value to it.key }

    /** Read by the file's own place, not its place in the project: the importer's checks rewrite `sourcePath`. */
    override fun open(entry: SourceEntry): InputStream {
        val own = coordinates[entry.path] ?: throw java.io.FileNotFoundException(entry.path)
        return source.open(own) ?: throw java.io.FileNotFoundException(own)
    }

    private fun rebase(path: String): String = rebaseLinkedPath(path, chain)
}

/**
 * [path], in the main file's coordinates (`..` for folders above it), as a path in the project, given the
 * folder names [chain] from the project's base down to the main file's own folder.
 */
fun rebaseLinkedPath(path: String, chain: List<String>): String {
    val parts = path.split('/')
    val up = parts.takeWhile { it == ".." }.size
    return (chain.take((chain.size - up).coerceAtLeast(0)) + parts.drop(up)).joinToString("/")
}

/** Imports the ledger a [LedgerLinks] walk found, keeping each file's name and place. */
fun ProjectImporter.importLinked(
    source: LinkedSource,
    ledger: LinkedLedger,
    mainFolders: List<String>,
    projectId: String,
): ImportResult {
    val adapter = LinkedImportSource(source, ledger, mainFolders)
    return import(adapter, projectId, preferredMain = adapter.mainPath, keepMain = true)
}
