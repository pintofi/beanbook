package dev.beancount.app.storage

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import dev.beancount.core.data.ExportTarget
import dev.beancount.core.data.ImportSource
import dev.beancount.core.data.SourceEntry
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * The Storage Access Framework adapters.
 *
 * Deliberately thin: every validation and round-trip rule lives in `:core-data` and is
 * tested on the JVM, because SAF cannot be exercised without a user picking a folder.
 * What is left here is enumeration and stream plumbing — the part that has to be simple
 * enough to be read and believed.
 */
class SafImportSource(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : ImportSource {

    private val uris = mutableMapOf<String, Uri>()

    override fun entries(): List<SourceEntry> {
        uris.clear()
        val root = DocumentFile.fromTreeUri(
            /* context is not needed for enumeration once we hold the tree */
            ContextHolder.require(),
            treeUri,
        ) ?: return emptyList()

        val found = mutableListOf<SourceEntry>()
        fun walk(directory: DocumentFile, prefix: String) {
            directory.listFiles().forEach { child ->
                val name = child.name ?: return@forEach
                val path = if (prefix.isEmpty()) name else "$prefix/$name"
                if (child.isDirectory) {
                    walk(child, path)
                } else {
                    uris[path] = child.uri
                    found += SourceEntry(path = path, size = child.length())
                }
            }
        }
        walk(root, "")
        return found
    }

    override fun open(entry: SourceEntry): InputStream {
        val uri = uris[entry.path] ?: error("unknown entry: ${entry.path}")
        return resolver.openInputStream(uri) ?: error("provider refused to open ${entry.path}")
    }
}

/**
 * Writes a project into a user-chosen SAF tree.
 *
 * Failures are propagated rather than swallowed: SAF providers do fail, and a partial
 * export reported as success would be worse than an error
 * (docs/storage-and-backup.md §5).
 */
class SafExportTarget(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : ExportTarget {

    private val directories = mutableMapOf<String, DocumentFile>()

    override fun openOutput(relativePath: String): OutputStream {
        val root = DocumentFile.fromTreeUri(ContextHolder.require(), treeUri)
            ?: throw java.io.IOException("cannot open the chosen folder")

        val segments = relativePath.split('/')
        var directory = root
        var walked = ""
        segments.dropLast(1).forEach { segment ->
            walked = if (walked.isEmpty()) segment else "$walked/$segment"
            directory = directories.getOrPut(walked) {
                directory.findFile(segment)?.takeIf { it.isDirectory }
                    ?: directory.createDirectory(segment)
                    ?: throw java.io.IOException("cannot create folder $walked")
            }
        }

        val name = segments.last()
        val existing = directory.findFile(name)
        existing?.delete()
        val file = directory.createFile(mimeTypeFor(name), name)
            ?: throw java.io.IOException("cannot create $relativePath")

        // A DocumentsProvider may not honour the name it was given.
        //
        // `ExternalStorageProvider.createDocument` runs the name through
        // `FileUtils.buildUniqueFile`, which **appends the extension it considers correct for
        // the MIME type** whenever the supplied extension does not map to it. `.beancount` is
        // not a known extension for `text/plain`, so "main.beancount" was silently written as
        // "main.beancount.txt" -- and with it, every `include "files/accounts.beancount"` in
        // the exported copy pointed at a file that no longer existed by that name. That
        // breaks invariant 5 across export, and no `java.io.File` test could see it: it only
        // happens on the far side of a real provider (V14).
        //
        // `renameDocument` does not re-derive the extension, so the name is put back.
        if (file.name != name) {
            val renamed = DocumentsContract.renameDocument(resolver, file.uri, name)
                ?: throw java.io.IOException(
                    "the provider stored $relativePath as ${file.name} and would not rename it",
                )
            return resolver.openOutputStream(renamed)
                ?: throw java.io.IOException("provider refused to write $relativePath")
        }

        return resolver.openOutputStream(file.uri)
            ?: throw java.io.IOException("provider refused to write $relativePath")
    }

    private fun mimeTypeFor(name: String): String = when (File(name).extension.lowercase()) {
        "beancount", "bean", "txt", "csv" -> "text/plain"
        "pdf" -> "application/pdf"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        else -> "application/octet-stream"
    }
}

/**
 * `DocumentFile.fromTreeUri` needs a Context, which the adapters above should not have to
 * carry through every call. The application sets this once at start-up.
 */
object ContextHolder {
    @Volatile
    private var context: android.content.Context? = null

    fun install(value: android.content.Context) {
        context = value.applicationContext
    }

    fun require(): android.content.Context =
        context ?: error("ContextHolder.install() was never called")
}
