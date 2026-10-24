package io.github.pintofi.beanbook.app.documents

import android.content.Context
import android.util.Log
import io.github.pintofi.beanbook.core.data.DocumentStore
import io.github.pintofi.beanbook.core.data.ProjectWorkspace
import io.github.pintofi.beanbook.core.domain.CapturedPages
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * A document being put together: the pages so far, and what happens when they are saved.
 *
 * The captures live in the app's cache directory until the document is filed. That is
 * deliberate — an abandoned capture should not leave anything in the project tree, and the
 * cache is the one place Android is allowed to reclaim on its own.
 *
 * Filing a document is two steps in a fixed order: **write the PDF, then write the ledger
 * entry that points at it**. A link is never written before the file it names exists, so a
 * ledger can be trusted not to refer to something that was never there (the
 * write protocol's ordering rule).
 */
class DocumentCapture(
    private val context: Context,
    private val store: DocumentStore,
    private val scope: CoroutineScope,
    private val assembler: PdfAssembler = PdfAssembler(),
) {
    private val captureDir = File(context.cacheDir, "captures")

    private val _pages = MutableStateFlow(CapturedPages())
    val pages: StateFlow<CapturedPages> = _pages.asStateFlow()

    /**
     * Files the person already had -- a PDF invoice, a picture -- attached as they are.
     *
     * Kept beside the pages rather than turned into pages: a PDF someone was sent is already the
     * document, and re-rendering it as photographs would lose its text.
     */
    private val _files = MutableStateFlow<List<AttachedFile>>(emptyList())
    val files: StateFlow<List<AttachedFile>> = _files.asStateFlow()

    /** One attached file: its copy in the capture folder, and what it was called. */
    data class AttachedFile(val source: String, val displayName: String, val extension: String)

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /**
     * Every capture added in this session, including pages since removed or retaken, so that
     * [finish] can delete the photographs of receipts a delete or a retake left behind.
     */
    private val sessionSources: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

    /** Replaces the pages if they are still [expected]. False when something changed them meanwhile. */
    fun replace(expected: CapturedPages, next: CapturedPages): Boolean {
        val replaced = _pages.compareAndSet(expected, next)
        if (replaced) next.pages.forEach { sessionSources += it.source }
        return replaced
    }

    /** A fresh file for the next capture. Names are sequential so page order starts sane. */
    fun nextCaptureFile(): File {
        captureDir.mkdirs()
        // Never a name already taken: two pictures brought in within the same millisecond
        // would otherwise be written to one file, and one page would silently become the other.
        var stamp = System.currentTimeMillis()
        while (File(captureDir, "page-$stamp.jpg").exists()) stamp += 1
        return File(captureDir, "page-$stamp.jpg")
    }

    /**
     * Adds a page, with the outline the scanner found for it when there is one.
     *
     * One update rather than an add followed by a crop. The scanner used to crop "the
     * last page" through a list it had read *before* adding, so the outline went to the page
     * before -- or, for the first page of a batch, to index -1, which is nothing -- and every
     * detected crop landed on the wrong page or none.
     */
    fun add(
        file: File,
        style: io.github.pintofi.beanbook.core.domain.PageStyle =
            io.github.pintofi.beanbook.core.domain.PageStyle.COLOUR,
        crop: io.github.pintofi.beanbook.core.domain.CropQuad? = null,
    ) {
        sessionSources += file.name
        val added = _pages.value.add(file.name, style)
        _pages.value = if (crop == null) added else added.crop(added.pages.lastIndex, crop)
        // A new document is starting, so what was said about the last one has done its job.
        _message.value = null
    }

    /** Renders one page differently. Nothing on disk changes; the PDF is what changes. */
    fun restyle(index: Int, style: io.github.pintofi.beanbook.core.domain.PageStyle) {
        _pages.value = _pages.value.restyle(index, style)
    }

    fun rotate(index: Int, clockwise: Boolean = true) {
        _pages.value = _pages.value.rotate(index, clockwise)
    }

    fun crop(index: Int, quad: io.github.pintofi.beanbook.core.domain.CropQuad?) {
        _pages.value = _pages.value.crop(index, quad)
    }

    fun move(from: Int, to: Int) {
        _pages.value = _pages.value.move(from, to)
    }

    /**
     * Drops a page from the document. The capture itself stays in the cache until the whole
     * capture session is discarded, so removing a page by accident is recoverable by
     * re-adding it rather than re-photographing it.
     */
    fun remove(index: Int) {
        _pages.value = _pages.value.remove(index)
    }

    fun fileFor(source: String): File = File(captureDir, source)

    /**
     * Copies a picked file into the capture folder, where it waits to be filed with the entry.
     *
     * Copied rather than referenced: the grant to a picked document lasts as long as the
     * activity that received it, and the entry may be saved after that. A file whose extension
     * cannot be a document name's (letters and digits, up to eight) is refused with the reason.
     */
    suspend fun attach(resolver: android.content.ContentResolver, uri: android.net.Uri) {
        val attached = withContext(Dispatchers.IO) {
            runCatching {
                val name = resolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
                    ?: uri.lastPathSegment.orEmpty()
                val extension = name.substringAfterLast('.', "").lowercase().takeIf { EXTENSION.matches(it) }
                    ?: android.webkit.MimeTypeMap.getSingleton()
                        .getExtensionFromMimeType(resolver.getType(uri))
                        ?.lowercase()?.takeIf { EXTENSION.matches(it) }
                    ?: error("\"$name\" has no file type a document can be filed as")
                captureDir.mkdirs()
                val target = File(captureDir, "file-${System.currentTimeMillis()}.$extension")
                resolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: error("the file could not be opened")
                check(target.length() > 0) { "the file is empty" }
                AttachedFile(target.name, name.ifBlank { target.name }, extension)
            }
        }
        attached
            .onSuccess {
                _files.value = _files.value + it
                _message.value = null
            }
            .onFailure { error ->
                Log.w(TAG, "could not attach a file", error)
                _message.value = "Could not attach that file: ${error.message}"
            }
    }

    fun removeFile(index: Int) {
        _files.value = _files.value.filterIndexed { at, _ -> at != index }
    }

    /** Everything that will be filed: the pages as one PDF, then each attached file as it is. */
    private suspend fun documents(): List<Pair<ByteArray, String>> = buildList {
        assembledBytes()?.let { add(it to "pdf") }
        _files.value.forEach { file ->
            add(withContext(Dispatchers.IO) { fileFor(file.source).readBytes() } to file.extension)
        }
    }

    /** One page as it will be filed, sized for the screen. */
    suspend fun preview(page: io.github.pintofi.beanbook.core.domain.CapturedPage): android.graphics.Bitmap? =
        withContext(Dispatchers.Default) {
            runCatching { assembler.renderPage(page, ::fileFor, maxEdge = PREVIEW_EDGE) }
                .onFailure { Log.w(TAG, "could not render a page preview", it) }
                .getOrNull()
        }

    private val assembling = kotlinx.coroutines.sync.Mutex()
    private var assembled: Pair<CapturedPages, ByteArray>? = null

    /**
     * The PDF the current pages make, assembled once per arrangement of them.
     *
     * The entry form shows the document's name in its preview before anything is saved, and
     * the name is a hash of these bytes -- so the preview and the save must use the same
     * assembly, not two that could differ. Null when there are no pages.
     */
    suspend fun assembledBytes(): ByteArray? {
        val pages = _pages.value
        if (pages.isEmpty) return null
        return assembling.withLock {
            assembled?.takeIf { it.first == pages }?.second
                ?: withContext(Dispatchers.IO) { assembler.assemble(pages) { fileFor(it) } }
                    .also { assembled = pages to it }
        }
    }

    /**
     * What the pages and files will be filed as under [account], relative to the project, in
     * the order [save] files them. Empty when there is nothing. The same names [save] uses
     * unless a *different* file already has one, which is what an ordinal is for.
     */
    suspend fun plannedPaths(
        account: String,
        label: String?,
        date: LocalDate = LocalDate.now(),
        attached: Boolean = false,
    ): List<String> {
        val documents = runCatching { documents() }
            .onFailure { Log.w(TAG, "could not prepare the documents for the preview", it) }
            .getOrNull() ?: return emptyList()
        return runCatching {
            documents.map { (bytes, extension) ->
                io.github.pintofi.beanbook.core.domain.DocumentName
                    .forContent(account, date, bytes, extension, label, attached).relativePath
            }
        }.getOrDefault(emptyList())
    }

    /**
     * Files the pages as one PDF, and each attached file as it is, against [account].
     *
     * @param onFiled called with the project-relative paths once every file is durably in
     *   place — the caller then writes the `document` directives through the usual write
     *   protocol. Nothing here touches the ledger.
     */
    fun save(
        projectId: String,
        account: String,
        date: LocalDate = LocalDate.now(),
        /** What to call it, if the user typed something. */
        label: String? = null,
        /** Attached to an entry rather than filed under [account]. */
        attached: Boolean = false,
        onFiled: (List<String>) -> Unit,
    ) {
        val pages = _pages.value
        if (pages.isEmpty && _files.value.isEmpty()) {
            _message.value = "Take a photo or attach a file first."
            return
        }

        scope.launch {
            // Assembling the PDF renders every page; held until it is on disk.
            val result = io.github.pintofi.beanbook.app.work.KeepWorking.around(
                context,
                "Filing ${if (pages.isEmpty) "a document" else "${pages.size} page(s)"}",
            ) {
                withContext(Dispatchers.IO) {
                    runCatching {
                        documents().map { (bytes, extension) ->
                            store.store(
                                projectId, account, date, bytes,
                                extension = extension, label = label, attached = attached,
                            )
                        }
                    }
                }
            }
            result
                .onSuccess { stored ->
                    _message.value = if (stored.all { it.alreadyExisted }) {
                        "Already saved as ${stored.joinToString { it.file }}. Nothing was duplicated."
                    } else {
                        "Saved ${stored.joinToString { it.file }}."
                    }
                    onFiled(stored.map { it.relativePath })
                }
                .onFailure { error ->
                    // The reason, not "save failed": a document that cannot be assembled is
                    // usually a page that could not be read, and the user can act on that.
                    Log.w(TAG, "document assembly failed", error)
                    _message.value = "Could not save the document: ${error.message}"
                }
        }
    }

    /** Reports the outcome of the ledger write that followed a save. */
    fun report(message: String) {
        _message.value = message
    }

    /**
     * Ends the session. [discardCaptures] deletes the originals; without it they stay.
     *
     * The message survives on purpose: this runs the moment a document is filed, and
     * clearing it here wiped the only confirmation the user was going to see.
     */
    fun reset(discardCaptures: Boolean = false) {
        sessionSources.clear()
        _pages.value = CapturedPages()
        _files.value = emptyList()
        if (discardCaptures) {
            scope.launch { withContext(Dispatchers.IO) { captureDir.deleteRecursively() } }
        }
    }

    /**
     * Ends the session after a document has been filed, and deletes the camera originals and
     * attached copies it used.
     *
     * The PDF in the project is the record by then, and a photograph of a receipt is personal
     * data sitting in a folder nobody looks at. Only the files this session named are deleted,
     * not the whole folder, so a new capture started straight away is left alone.
     */
    fun finish() {
        val used = (_pages.value.pages.map { it.source } + _files.value.map { it.source } + sessionSources).distinct()
        reset()
        scope.launch { withContext(Dispatchers.IO) { deleteCaptureFiles(captureDir, used) } }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Every document already in the project, newest name last. */
    suspend fun existing(projectId: String, extraRoots: List<String> = emptyList()): List<String> =
        withContext(Dispatchers.IO) { store.list(projectId, extraRoots) }

    /**
     * Takes the document out of the project, after the caller has confirmed that is what was
     * meant. It is kept for a month, so [onDone] gets what [restoreFile] needs to take it back, or
     * null when there was no such file.
     */
    fun deleteFile(projectId: String, relativePath: String, onDone: (DocumentStore.Trashed?) -> Unit) {
        scope.launch {
            val trashed = withContext(Dispatchers.IO) { store.trash(projectId, relativePath) }
            _message.value = if (trashed != null) {
                "Deleted $relativePath."
            } else {
                "Could not delete $relativePath: it is not in this project."
            }
            onDone(trashed)
        }
    }

    /** Puts a deleted document back. False when it is gone or something is there now. */
    suspend fun restoreFile(trashed: DocumentStore.Trashed): Boolean =
        withContext(Dispatchers.IO) { store.restore(trashed) }

    private companion object {
        const val TAG = "DocumentCapture"

        /** The extensions a document name accepts (`DocumentName`). */
        val EXTENSION = Regex("[a-z0-9]{1,8}")

        /** Enough for a phone screen at full width; the file itself is rendered larger. */
        const val PREVIEW_EDGE = 1600
    }
}

/** Deletes [names] from [dir]; a name that would leave [dir] is ignored. Returns how many went. */
internal fun deleteCaptureFiles(dir: File, names: List<String>): Int =
    names.count { name ->
        val file = File(dir, name)
        file.parentFile?.canonicalFile == dir.canonicalFile && file.delete()
    }
