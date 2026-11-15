package io.github.pintofi.beanbook.app.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import androidx.documentfile.provider.DocumentFile
import io.github.pintofi.beanbook.core.domain.LinkedChild
import io.github.pintofi.beanbook.core.domain.LinkedSource
import io.github.pintofi.beanbook.core.domain.LinkedStat
import java.io.InputStream

/**
 * Just the file the person chose, when they have not allowed the folder around it. Everything it links to
 * is out of reach, so every link is reported as one that could not be followed.
 */
class SingleDocumentSource(
    private val resolver: ContentResolver,
    private val uri: Uri,
    private val name: String,
) : LinkedSource {

    override fun read(path: String): ByteArray? =
        if (path == name) open(path)?.use { it.readBytes() } else null

    override fun open(path: String): InputStream? = if (path == name) resolver.openInputStream(uri) else null

    override fun stat(path: String): LinkedStat? {
        if (path != name) return null
        val size = resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
        }
        return LinkedStat(size ?: 0L, false)
    }

    override fun children(path: String): List<LinkedChild>? = null
}

/**
 * The files below a folder the person allowed, read by path: a link names a file, and that file is looked
 * up by name in its own folder. A folder's entries are listed only where a link points into it (a glob, a
 * documents folder), the names are filtered at once by the walker, and nothing is kept or shown beyond
 * the call. Nothing is ever written.
 *
 * Paths are in the main file's coordinates, so [mainDir] is the main file's folder below [root], as folder
 * names, and `../x` reaches the folders above it up to [root] and no further.
 */
class TreeLinkedSource(
    private val resolver: ContentResolver,
    private val root: DocumentFile,
    private val mainDir: List<String>,
) : LinkedSource {

    private fun resolve(path: String): DocumentFile? {
        val parts = mainDir.toMutableList()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.size - 1)
                else -> parts += segment
            }
        }
        var current = root
        for (name in parts) current = current.findFile(name) ?: return null
        return current
    }

    override fun read(path: String): ByteArray? = open(path)?.use { it.readBytes() }

    override fun open(path: String): InputStream? =
        resolve(path)?.takeIf { it.isFile }?.let { resolver.openInputStream(it.uri) }

    override fun stat(path: String): LinkedStat? =
        resolve(path)?.let { LinkedStat(if (it.isFile) it.length() else 0L, it.isDirectory) }

    override fun children(path: String): List<LinkedChild>? =
        resolve(path)?.takeIf { it.isDirectory }?.listFiles()
            ?.mapNotNull { child -> child.name?.let { LinkedChild(it, child.isDirectory) } }
}

/** Where a file is inside a folder the person allowed, worked out from the two document ids. */
object DocumentIds {

    /**
     * The names from the allowed folder down to the file, from their document ids, or null when the ids do
     * not say (a provider whose ids are not paths). On local storage and most folder providers an id is
     * `volume:path/to/file`, so a file below a folder is its id with the folder's id and a `/` taken off.
     */
    fun below(treeRootId: String, fileId: String): List<String>? {
        val prefix = if (treeRootId.endsWith(":") || treeRootId.endsWith("/")) treeRootId else "$treeRootId/"
        if (!fileId.startsWith(prefix)) return null
        val rest = fileId.removePrefix(prefix).split('/').filter { it.isNotEmpty() }
        return rest.takeIf { it.isNotEmpty() }
    }
}
