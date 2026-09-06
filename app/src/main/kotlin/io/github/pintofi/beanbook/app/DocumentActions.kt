package io.github.pintofi.beanbook.app

import android.content.Intent
import java.io.File
import android.net.Uri
import io.github.pintofi.beanbook.core.domain.DirectiveDraft
import java.time.LocalDate
import kotlinx.coroutines.launch
import androidx.lifecycle.lifecycleScope

/**
 * What the app does with a document: file one, unlink one, hand one to another app.
 *
 * Extensions on [MainActivity] rather than members of it. They belong to the activity — they
 * touch its state and its lifecycle scope — but they are one subject among several it owns,
 * and keeping them here is what stops that class growing without limit. detekt's LargeClass
 * is the thing that noticed; the reason to act on it is that nobody can read a 770-line
 * activity and say what it is responsible for.
 */

internal fun MainActivity.refreshDocuments(app: BeancountApplication, projectId: String) {
    lifecycleScope.launch { documents = app.documentCapture.existing(projectId) }
}

/**
 * Files the captured pages as a PDF, then writes the `document` directive that points at
 * it — in that order, so the ledger never names a file that does not exist yet
 * (CLAUDE.md, invariant 5).
 */
internal fun MainActivity.attachDocument(app: BeancountApplication, projectId: String, account: String) {
    app.documentCapture.save(projectId, account) { relativePath ->
        val directive = DirectiveDraft.Document(
            date = LocalDate.now().toString(),
            account = account,
            path = relativePath,
        )
        saveText(
            app,
            projectId,
            directive.render(),
            operation = "attach-document",
            target = app.workspace.mainFile(projectId),
        ) { outcome ->
            saveMessage = outcome
            // Said where the user is standing, not on a screen they have left.
            app.documentCapture.report("Filed ${relativePath.substringAfterLast('/')}. $outcome")
        }
        app.documentCapture.reset()
        refreshDocuments(app, projectId)
    }
}

/**
 * Removes the link, not the file.
 *
 * The directive is deleted from whichever included file holds it and the write goes
 * through the ordinary protocol, so an unlink is snapshotted and undoable like any other
 * edit. The PDF stays exactly where it was.
 */
/**
 * The ledger file holding a real `document` directive, and [rewrite]'s result for it.
 *
 * [rewrite] returns `null` for a file with no such directive, which is what makes this
 * narrow: a file merely *mentioning* the path -- in a comment, or in a transaction's own
 * `document:` metadata -- is not a match, and rewriting it would be the R2 defect again.
 *
 * Shared by unlinking and refiling because both must agree on which file to touch. A ledger
 * split by `include` may have many, and picking a different one for each operation would
 * unlink from one file what had been refiled in another.
 */
private fun findDocumentDirective(projectDir: File, rewrite: (String) -> String?): Pair<File, String>? =
    projectDir.walkTopDown()
        .filter { it.isFile && (it.extension == "beancount" || it.extension == "bean") }
        .mapNotNull { file -> rewrite(file.readText())?.let { file to it } }
        .firstOrNull()

internal fun MainActivity.unlinkDocument(
    app: BeancountApplication,
    projectId: String,
    path: String,
    from: String?,
) {
    lifecycleScope.launch {
        val holder = findDocumentDirective(app.workspace.projectDir(projectId)) {
            io.github.pintofi.beanbook.core.domain.BeancountText
                .removeDocumentDirective(it, path, from = from)
        }
        if (holder == null) {
            saveMessage = "Nothing in the ledger links to $path."
            return@launch
        }
        val (file, rewritten) = holder

        val result = app.writer.replace(
            projectId = projectId,
            file = file,
            expectedSha = io.github.pintofi.beanbook.core.data.WriteJournal.sha256(file),
            newContent = rewritten,
            operation = "unlink-document",
        )
        saveMessage = when (result) {
            is io.github.pintofi.beanbook.core.data.WriteResult.Committed ->
                "Unlinked $path. The file is still in the project."
            is io.github.pintofi.beanbook.core.data.WriteResult.RolledBack ->
                "Not unlinked: ${result.reason}. Your ledger is unchanged."
            is io.github.pintofi.beanbook.core.data.WriteResult.Refused -> "Not unlinked: ${result.reason}"
        }
        app.ledgerState.load(app.workspace.mainFile(projectId).absolutePath)
    }
}

/**
 * Files an already-attached document under a different account (B18).
 *
 * A refile is one directive rewritten in place, not an unlink followed by an attach: the
 * two-step version would leave the document unreferenced between the writes, and a failure
 * of the second would lose the link entirely rather than leave it where it was.
 *
 * [from] is the account it is filed under now, or `null` for a document the ledger does not
 * refer to yet -- in which case this files it for the first time by writing a directive.
 */
internal fun MainActivity.refileDocument(
    app: BeancountApplication,
    projectId: String,
    path: String,
    newAccount: String,
    from: String?,
) {
    // A document nothing refers to yet has no directive to move, so it gets one written.
    // The alternative -- offering "refile" and failing because there is nothing to refile --
    // would leave the unfiled state visible but unresolvable from the screen that shows it.
    if (from == null) {
        val directive = DirectiveDraft.Document(
            date = LocalDate.now().toString(),
            account = newAccount,
            path = path,
        )
        saveText(
            app,
            projectId,
            directive.render(),
            operation = "file-document",
            target = app.workspace.mainFile(projectId),
        ) { outcome ->
            saveMessage = outcome
            refreshDocuments(app, projectId)
        }
        return
    }
    lifecycleScope.launch {
        val holder = findDocumentDirective(app.workspace.projectDir(projectId)) {
            runCatching {
                io.github.pintofi.beanbook.core.domain.BeancountText
                    .refileDocument(it, path, newAccount, from = from)
            }.getOrNull()
        }
        if (holder == null) {
            saveMessage = "Nothing in the ledger files $path, or $newAccount is not an account."
            return@launch
        }
        val (file, rewritten) = holder
        val result = app.writer.replace(
            projectId = projectId,
            file = file,
            expectedSha = io.github.pintofi.beanbook.core.data.WriteJournal.sha256(file),
            newContent = rewritten,
            operation = "refile-document",
        )
        saveMessage = when (result) {
            is io.github.pintofi.beanbook.core.data.WriteResult.Committed -> "Filed $path under $newAccount."
            is io.github.pintofi.beanbook.core.data.WriteResult.RolledBack ->
                "Not refiled: ${result.reason}. Your ledger is unchanged."
            is io.github.pintofi.beanbook.core.data.WriteResult.Refused -> "Not refiled: ${result.reason}"
        }
        app.ledgerState.load(app.workspace.mainFile(projectId).absolutePath)
    }
}

/** Hands one document to another app as a temporary read-only URI, and nothing else. */
internal fun MainActivity.shareDocument(app: BeancountApplication, projectId: String, path: String) {
    val projectDir = app.workspace.projectDir(projectId)
    val documents = File(projectDir, "documents")
    val file = projectDir.resolve(path)

    // Only out of the documents directory, and only from inside it. The FileProvider is
    // configured over `projects/`, because it cannot express `projects/*/documents`, so this
    // call site is where the narrower rule actually lives: without it, any path this function
    // is handed becomes a content:// URI another app can read -- a ledger included (R9).
    if (!file.canonicalPath.startsWith(documents.canonicalPath + File.separator)) {
        saveMessage = "Only documents can be shared out of a project."
        return
    }
    if (!file.isFile) {
        saveMessage = "That document is not in the project any more."
        return
    }
    val uri = androidx.core.content.FileProvider.getUriForFile(
        this,
        "${BuildConfig.APPLICATION_ID}.documents",
        file,
    )
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "application/pdf"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { startActivity(Intent.createChooser(send, "Share document")) }
}


/**
 * Removes one transaction from the ledger (B11).
 *
 * The extent comes from the engine's `entry_slice`, which is Beancount's own idea of where an
 * entry starts and stops — including its metadata and any blank line inside it. Deleting by
 * matching text would guess at that, and guess wrong on the first entry whose narration
 * happened to appear twice.
 *
 * The write itself goes through `ProjectWriter`, so it is journalled, snapshotted, re-parsed
 * and undoable like any other edit — which is what makes the confirmation's promise true.
 */
internal fun MainActivity.deleteEntry(
    app: BeancountApplication,
    projectId: String,
    entry: io.github.pintofi.beanbook.core.domain.JournalEntry,
) {
    val file = entry.file
    val line = entry.line
    if (file == null || line == null) {
        saveMessage = "That entry has no recorded position in the file, so it cannot be removed here."
        return
    }

    lifecycleScope.launch {
        val mainFile = app.workspace.mainFile(projectId).absolutePath
        val slice = runCatching { app.editor.sliceAt(mainFile, file, line) }.getOrNull()
        if (slice == null) {
            saveMessage = "Could not find that entry in the file. Nothing was changed."
            return@launch
        }

        val target = java.io.File(slice.file)
        val updated = runCatching {
            app.editor.contentWithDeletion(slice.file, slice.startLine, slice.endLine)
        }.getOrElse {
            saveMessage = "Could not remove that entry: ${it.message}. Nothing was changed."
            return@launch
        }

        val result = app.writer.replace(
            projectId = projectId,
            file = target,
            expectedSha = io.github.pintofi.beanbook.core.data.WriteJournal.sha256(target),
            newContent = updated,
            operation = "delete-entry",
        )
        saveMessage = when (result) {
            is io.github.pintofi.beanbook.core.data.WriteResult.Committed ->
                "Deleted. Undo is available if that was not what you meant."
            is io.github.pintofi.beanbook.core.data.WriteResult.RolledBack ->
                "Not deleted: ${result.reason}. Your ledger is unchanged."
            is io.github.pintofi.beanbook.core.data.WriteResult.Refused -> "Not deleted: ${result.reason}"
        }
        app.ledgerState.load(mainFile)
    }
}

/**
 * Opens one entry for editing: as fields where that is provably safe, as text otherwise
 * (B11, B45).
 *
 * A form is friendlier and much more dangerous than a text box, because a draft models a
 * fixed set of things and would silently drop anything else the entry carries -- a comment
 * inside it, a shape the app has no field for, the user's own alignment. Invariant 4 forbids
 * that, so the question is settled per entry rather than assumed:
 * [io.github.pintofi.beanbook.core.domain.StructuredEdit.isLossless] renders the draft and
 * compares it with the bytes actually in the file. Identical means the draft is a complete
 * description and a form can edit it losslessly; different means the text editor is the only
 * honest option, and it stays available either way.
 */
internal fun MainActivity.beginEntryEdit(
    app: BeancountApplication,
    projectId: String,
    entry: io.github.pintofi.beanbook.core.domain.JournalEntry,
) {
    val file = entry.file
    val line = entry.line
    if (file == null || line == null) {
        saveMessage = "That entry has no recorded position in the file, so it cannot be edited here."
        return
    }
    lifecycleScope.launch {
        val mainFile = app.workspace.mainFile(projectId).absolutePath
        val slice = runCatching { app.editor.sliceAt(mainFile, file, line) }.getOrNull()
        if (slice == null) {
            saveMessage = "Could not find that entry in the file. Nothing was changed."
            return@launch
        }
        editingEntry = slice
        editingDraft = io.github.pintofi.beanbook.core.domain.StructuredEdit
            .losslessDraftOf(entry, slice.text)
    }
}

/**
 * Writes an edited entry back over the range it came from.
 *
 * The file's SHA is checked by the write protocol, so an edit computed against a file that
 * has since changed underneath -- a sync, a pull, an edit in the raw editor -- is refused
 * rather than applied to the wrong bytes.
 */
internal fun MainActivity.saveEntryEdit(
    app: BeancountApplication,
    projectId: String,
    slice: io.github.pintofi.beanbook.engine.local.EntrySlice,
    newText: String,
) {
    lifecycleScope.launch {
        val target = java.io.File(slice.file)
        val updated = runCatching {
            app.editor.contentWithReplacement(slice.file, slice.startLine, slice.endLine, newText)
        }.getOrElse {
            saveMessage = "Could not apply that edit: ${it.message}. Nothing was changed."
            return@launch
        }

        val result = app.writer.replace(
            projectId = projectId,
            file = target,
            expectedSha = io.github.pintofi.beanbook.core.data.WriteJournal.sha256(target),
            newContent = updated,
            operation = "edit-entry",
        )
        saveMessage = when (result) {
            // The write protocol re-parses before committing, so a committed edit is one
            // Beancount accepted -- an entry that no longer parses is rolled back, not saved.
            is io.github.pintofi.beanbook.core.data.WriteResult.Committed ->
                "Saved. Undo is available if that was not what you meant."
            is io.github.pintofi.beanbook.core.data.WriteResult.RolledBack ->
                "Not saved: ${result.reason}. Your ledger is unchanged."
            is io.github.pintofi.beanbook.core.data.WriteResult.Refused -> "Not saved: ${result.reason}"
        }
        editingEntry = null
        editingDraft = null
        app.ledgerState.load(app.workspace.mainFile(projectId).absolutePath)
    }
}

/**
 * The entry editor, when one is open (B11, B45).
 *
 * An extension rather than a member: it reads the activity's editing state and nothing else,
 * and the activity is already the largest class in the app.
 */
@androidx.compose.runtime.Composable
internal fun MainActivity.EntryEditor(
    app: BeancountApplication,
    projectId: String?,
    ledger: io.github.pintofi.beanbook.core.domain.LedgerSnapshot?,
) {
    val slice = editingEntry ?: return
    if (projectId == null) return
    io.github.pintofi.beanbook.app.ui.EntryEditHost(
        slice = slice,
        draft = editingDraft,
        ledger = ledger,
        onSave = { text -> saveEntryEdit(app, projectId, slice, text) },
        onEditAsText = { editingDraft = null },
        onDismiss = {
            editingEntry = null
            editingDraft = null
        },
    )
}
