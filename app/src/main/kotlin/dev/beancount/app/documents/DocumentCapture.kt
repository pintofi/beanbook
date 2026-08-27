package dev.beancount.app.documents

import android.content.Context
import android.util.Log
import dev.beancount.core.data.DocumentStore
import dev.beancount.core.data.ProjectWorkspace
import dev.beancount.core.domain.CapturedPages
import java.io.File
import java.time.LocalDate
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
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

    fun add(file: File) {
        _pages.value = _pages.value.add(file.name)
    }

    fun rotate(index: Int, clockwise: Boolean = true) {
        _pages.value = _pages.value.rotate(index, clockwise)
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
                    val pdf = assembler.assemble(pages) { fileFor(it) }
                    store.store(projectId, account, date, pdf)
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
    suspend fun existing(projectId: String): List<String> =
        withContext(Dispatchers.IO) { store.list(projectId) }

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
    }
}
