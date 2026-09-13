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
 * ledger can be trusted not to refer to something that was never there (CLAUDE.md,
 * invariant 5).
 */
class DocumentCapture(
    context: Context,
    private val store: DocumentStore,
    private val scope: CoroutineScope,
    private val assembler: PdfAssembler = PdfAssembler(),
) {
    private val captureDir = File(context.cacheDir, "captures")

    private val _pages = MutableStateFlow(CapturedPages())
    val pages: StateFlow<CapturedPages> = _pages.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    /** A fresh file for the next capture. Names are sequential so page order starts sane. */
    fun nextCaptureFile(): File {
        captureDir.mkdirs()
        return File(captureDir, "page-${System.currentTimeMillis()}.jpg")
    }

    /**
     * Adds a page, with the outline the scanner found for it when there is one.
     *
     * One update rather than an add followed by a crop (B79). The scanner used to crop "the
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
        val added = _pages.value.add(file.name, style)
        _pages.value = if (crop == null) added else added.crop(added.pages.lastIndex, crop)
    }

    /** Renders one page differently (B58). Nothing on disk changes; the PDF is what changes. */
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

    /** One page as it will be filed, sized for the screen (B79). */
    suspend fun preview(page: io.github.pintofi.beanbook.core.domain.CapturedPage): android.graphics.Bitmap? =
        withContext(Dispatchers.Default) {
            runCatching { assembler.renderPage(page, ::fileFor, maxEdge = PREVIEW_EDGE) }
                .onFailure { Log.w(TAG, "could not render a page preview", it) }
                .getOrNull()
        }

    private val assembling = kotlinx.coroutines.sync.Mutex()
    private var assembled: Pair<CapturedPages, ByteArray>? = null

    /**
     * The PDF the current pages make, assembled once per arrangement of them (B79).
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
     * What the pages will be filed as under [account], relative to the project, or null when
     * there are none or they cannot be assembled. The same name [save] will use unless a
     * *different* file already has it, which is what an ordinal is for.
     */
    suspend fun plannedPath(account: String, label: String?, date: LocalDate = LocalDate.now()): String? {
        val bytes = runCatching { assembledBytes() }
            .onFailure { Log.w(TAG, "could not assemble the pages for the preview", it) }
            .getOrNull() ?: return null
        return runCatching {
            io.github.pintofi.beanbook.core.domain.DocumentName.forContent(account, date, bytes, label = label)
                .relativePath
        }.getOrNull()
    }

    /**
     * Assembles the pages into a PDF, files it against [account], and reports where it went.
     *
     * @param onFiled called with the project-relative path once the file is durably in
     *   place — the caller then writes the `document` directive through the usual write
     *   protocol. Nothing here touches the ledger.
     */
    fun save(
        projectId: String,
        account: String,
        date: LocalDate = LocalDate.now(),
        /** What to call it, if the user typed something (B57). */
        label: String? = null,
        onFiled: (String) -> Unit,
    ) {
        val pages = _pages.value
        if (pages.isEmpty) {
            _message.value = "Take at least one photo first."
            return
        }

        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pdf = checkNotNull(assembledBytes()) { "there are no pages to file" }
                    store.store(projectId, account, date, pdf, label = label)
                }
            }
            result
                .onSuccess { stored ->
                    _message.value = if (stored.alreadyExisted) {
                        "Already saved as ${stored.file} — nothing was duplicated."
                    } else {
                        "Saved ${pages.size} page${if (pages.size == 1) "" else "s"} as ${stored.file}."
                    }
                    onFiled(stored.relativePath)
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
        _pages.value = CapturedPages()
        if (discardCaptures) {
            scope.launch { withContext(Dispatchers.IO) { captureDir.deleteRecursively() } }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Every document already in the project, newest name last. */
    suspend fun existing(projectId: String, extraRoots: List<String> = emptyList()): List<String> =
        withContext(Dispatchers.IO) { store.list(projectId, extraRoots) }

    /** Deletes the document itself, after the caller has confirmed that is what was meant. */
    fun deleteFile(projectId: String, relativePath: String, onDone: (Boolean) -> Unit) {
        scope.launch {
            val deleted = withContext(Dispatchers.IO) { store.delete(projectId, relativePath) }
            _message.value = if (deleted) {
                "Deleted $relativePath."
            } else {
                "Could not delete $relativePath — it is not in this project."
            }
            onDone(deleted)
        }
    }

    private companion object {
        const val TAG = "DocumentCapture"

        /** Enough for a phone screen at full width; the file itself is rendered larger. */
        const val PREVIEW_EDGE = 1600
    }
}
