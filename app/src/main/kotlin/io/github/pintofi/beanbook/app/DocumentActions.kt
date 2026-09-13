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
    lifecycleScope.launch {
        // Including the folders `option "documents"` names (B70): Beancount files what it
        // finds in those, so a screen that read only the app's own folder reported them as
        // links to files that were not there.
        val roots = app.ledgerState.snapshot.value?.documentRoots.orEmpty()
        documents = app.documentCapture.existing(projectId, roots)
    }
}

/**
 * Files the captured pages as a PDF, then writes the `document` directive that points at
 * it — in that order, so the ledger never names a file that does not exist yet
 * (CLAUDE.md, invariant 5).
 */
internal fun MainActivity.attachDocument(
    app: BeancountApplication,
    projectId: String,
    account: String,
    label: String? = null,
) {
    app.documentCapture.save(projectId, account, label = label) { relativePath ->
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
        // A document is linked in one of two ways, and unlinking used to stop after the
        // first: a `document` directive files it under an account, and a `document:` metadata
        // key attaches it to a transaction -- which is what the Add form writes. Removing the
        // directive and stopping left the entry still pointing at the file, so Organize went
        // on listing it as "Attached to ..." and offered no way to finish the job (B69).
        //
        // Both are removed, in as many passes as it takes: a file may be named by several
        // entries, and a half-unlinked document is exactly the broken state this action
        // exists to clear.
        var removed = 0
        var failure: String? = null
        while (removed < MAX_LINKS_PER_DOCUMENT) {
            val projectDir = app.workspace.projectDir(projectId)
            val holder = findDocumentDirective(projectDir) {
                io.github.pintofi.beanbook.core.domain.BeancountText
                    .removeDocumentDirective(it, path, from = from)
            } ?: findDocumentDirective(projectDir) {
                io.github.pintofi.beanbook.core.domain.BeancountText
                    .removeDocumentMetadata(it, path)
            } ?: break

            val (file, rewritten) = holder
            val result = app.writer.replace(
                projectId = projectId,
                file = file,
                expectedSha = io.github.pintofi.beanbook.core.data.WriteJournal.sha256(file),
                newContent = rewritten,
                operation = "unlink-document",
            )
            when (result) {
                is io.github.pintofi.beanbook.core.data.WriteResult.Committed -> removed += 1
                is io.github.pintofi.beanbook.core.data.WriteResult.RolledBack -> {
                    failure = "${result.reason}. Your ledger is unchanged."
                }
                is io.github.pintofi.beanbook.core.data.WriteResult.Refused -> failure = result.reason
            }
            if (failure != null) break
        }

        saveMessage = when {
            failure != null -> "Not unlinked: $failure"
            removed == 0 -> "Nothing in the ledger links to $path."
            else -> "Unlinked $path. The file is still in the project."
        }
        app.ledgerState.load(app.workspace.mainFile(projectId).absolutePath)
        // The list is built from the folder as well as the ledger, and nothing refreshed it:
        // an unlinked document kept its old row until the screen was left and re-entered.
        refreshDocuments(app, projectId)
    }
}

/**
 * How many links to one document a single unlink will clear.
 *
 * A bound rather than a plain `while (true)`: the loop is driven by a rewrite that must
 * change the file each time, and a rewriter that ever returned the text unchanged would spin
 * for ever writing it. Ten is far past any real ledger and cheap to be wrong about.
 */
private const val MAX_LINKS_PER_DOCUMENT = 10

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
        refreshDocuments(app, projectId)
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
        // Hand-written entries too (B78): an entry whose only difference from the app's own
        // rendering is its spacing opens as fields, and the form says the spacing will change.
        editingDraft = io.github.pintofi.beanbook.core.domain.StructuredEdit
            .editableDraftOf(entry, slice.text)
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
        draft = editingDraft?.draft,
        reformats = editingDraft?.reformats == true,
        ledger = ledger,
        onSave = { text -> saveEntryEdit(app, projectId, slice, text) },
        onEditAsText = { editingDraft = null },
        onDismiss = {
            editingEntry = null
            editingDraft = null
        },
    )
}

/**
 * Ends the engine process and starts a new one on the open project (B49).
 *
 * An extension rather than a member: `MainActivity` is already the largest class in the app,
 * and this reads the same two collaborators every other action here does.
 */
internal fun MainActivity.restartEngine(app: BeancountApplication, projectId: String?) {
    val id = projectId ?: return
    saveMessage = "Restarting the engine…"
    app.engineManager.restart()
    app.engineRestart.cleared()
    lifecycleScope.launch {
        // The ledger is re-read afterwards because a plugin that has just been turned on
        // changes what a load produces -- errors it raises, directives it inserts.
        app.ledgerState.load(app.workspace.mainFile(id).absolutePath)
        saveMessage = "The engine restarted with what is installed now."
    }
}

/**
 * Ends every process the app runs and opens it again, reading everything from disk (B77).
 *
 * The engine restart above keeps the app's own process, which is right after installing a
 * plugin and not enough when the app itself is in a bad state -- a stuck screen, a keyboard
 * inset that never came back -- where the only fix used to be swiping the app away.
 *
 * Safe by construction rather than by timing: every write goes through the journalled
 * protocol, so a process that ends mid-write is recovered from its snapshot on the next start
 * (invariant 3), and the relaunch is started before the processes end so the task comes
 * straight back rather than to the launcher.
 */
internal fun MainActivity.restartApp() {
    val manager = getSystemService(android.app.ActivityManager::class.java)
    val mine = android.os.Process.myPid()
    // Our own uid's processes only: the platform lists nothing else to an ordinary app, and
    // `killProcess` refuses a pid that is not ours.
    manager?.runningAppProcesses
        ?.filter { it.pid != mine && it.processName.startsWith(packageName) }
        ?.forEach { android.os.Process.killProcess(it.pid) }
    val relaunch = android.content.Intent.makeRestartActivityTask(
        android.content.ComponentName(this, MainActivity::class.java),
    )
    startActivity(relaunch)
    finishAffinity()
    Runtime.getRuntime().exit(0)
}

/**
 * A `document` directive's path as a file on disk (B57).
 *
 * Directives name a document relative to the project root, which is what makes a project
 * openable on a desktop; the viewer needs the file. Resolved against the open project rather
 * than trusted as a path, so a directive cannot point the reader outside the project tree
 * (invariant 5).
 */
internal fun MainActivity.documentFile(
    app: BeancountApplication,
    projectId: String?,
    path: String,
): java.io.File {
    val root = app.workspace.projectDir(projectId.orEmpty())
    val resolved = java.io.File(root, path)
    return if (resolved.canonicalPath.startsWith(root.canonicalPath)) {
        resolved
    } else {
        java.io.File(root, path.substringAfterLast('/'))
    }
}

/**
 * Which entries attach which documents, by the path each one names (B57).
 *
 * The reconciliation between folder and ledger knows about `document` **directives**; a
 * receipt attached while entering a transaction is a `document:` key on that entry instead,
 * and without this the Organize list called such a file unfiled while an entry was pointing
 * straight at it.
 */
internal fun documentAttachments(
    ledger: io.github.pintofi.beanbook.core.domain.LedgerSnapshot?,
): Map<String, List<String>> {
    val out = mutableMapOf<String, MutableList<String>>()
    ledger?.journal.orEmpty().forEach { entry ->
        entry.linkedDocuments.forEach { path ->
            val label = listOfNotNull(entry.date, entry.description.takeIf { it.isNotBlank() })
                .joinToString(" ")
            out.getOrPut(path.substringAfterLast('/')) { mutableListOf() }.add(label)
        }
    }
    return out
}

/**
 * The capture style chosen at the shutter, as the page model records it (B58).
 *
 * Two enums rather than one because `:core-domain` may not learn about OpenCV and `:scanner`
 * may not be what the page model depends on; this is the seam, and it is one function.
 */
internal fun pageStyleOf(
    style: io.github.pintofi.beanbook.scanner.ScanStyle,
): io.github.pintofi.beanbook.core.domain.PageStyle = when (style) {
    io.github.pintofi.beanbook.scanner.ScanStyle.DOCUMENT ->
        io.github.pintofi.beanbook.core.domain.PageStyle.DOCUMENT
    io.github.pintofi.beanbook.scanner.ScanStyle.GREYSCALE ->
        io.github.pintofi.beanbook.core.domain.PageStyle.GREYSCALE
    io.github.pintofi.beanbook.scanner.ScanStyle.COLOUR ->
        io.github.pintofi.beanbook.core.domain.PageStyle.COLOUR
}

/**
 * Everything a screen needs to photograph, edit and file a document, as one value (B58).
 *
 * Built here rather than in `MainActivity`: the same callbacks are wanted by the documents
 * screen, by transaction entry and by the scanner, and threading a dozen lambdas through the
 * activity is what makes detekt's `LargeClass` bite on it.
 */
internal fun MainActivity.captureHandle(
    app: BeancountApplication,
    projectId: String?,
    documentPages: io.github.pintofi.beanbook.core.domain.CapturedPages,
    scanning: Boolean,
    setScanning: (Boolean) -> Unit,
): io.github.pintofi.beanbook.app.ui.DocumentCaptureHandle =
    io.github.pintofi.beanbook.app.ui.DocumentCaptureHandle(
            pages = documentPages,
            captureFile = { app.documentCapture.nextCaptureFile() },
            onCapture = { file ->
                if (file.length() > 0) app.documentCapture.add(file)
            },
            scanning = scanning,
            onStartScanning = { setScanning(true) },
            onStopScanning = { setScanning(false) },
            onScanned = { file, quad, style ->
                // Add first, then apply the outline the scanner found. The
                // crop is a description of the photograph, never a rewrite of
                // it: the file on disk stays the one the camera produced, so
                // a wrong detection is a drag away from being corrected
                // rather than something that destroyed the original (J3).
                if (file.length() > 0) {
                    app.documentCapture.add(file, pageStyleOf(style))
                    if (quad != null) {
                        app.documentCapture.crop(documentPages.pages.lastIndex, quad)
                    }
                }
            },
            onRotate = { app.documentCapture.rotate(it) },
            onRotateBy = { index, clockwise ->
                app.documentCapture.rotate(index, clockwise)
            },
            onRestyle = { index, style ->
                app.documentCapture.restyle(index, style)
            },
            onMove = { from, to -> app.documentCapture.move(from, to) },
            onRemovePage = { app.documentCapture.remove(it) },
            onCrop = { index, quad -> app.documentCapture.crop(index, quad) },
            fileFor = { name -> app.documentCapture.fileFor(name) },
            onFile = { account, label, onFiled ->
                // The PDF, then the `document` directive, then whoever asked -- in that
                // order (B66). Filing from the entry form used to write the file and the
                // transaction's `document:` metadata and no directive at all, which is the
                // one arrangement `fava.plugins.link_documents` rejects: it looks for a
                // Document entry to link the metadata to, finds none, and reports "Document
                // not found". That is a new error, so the write protocol rolled the entry
                // back and the user was told their ledger was unchanged -- for a receipt
                // that had been photographed, filed and named.
                app.documentCapture.save(projectId.orEmpty(), account, label = label) { path ->
                    val directive = io.github.pintofi.beanbook.core.domain.DirectiveDraft.Document(
                        date = java.time.LocalDate.now().toString(),
                        account = account,
                        path = path,
                    )
                    saveText(
                        app,
                        projectId.orEmpty(),
                        directive.render(),
                        operation = "attach-document",
                        target = app.workspace.mainFile(projectId.orEmpty()),
                    ) { outcome ->
                        saveMessage = outcome
                        // Only when the directive is actually in the ledger. Reporting the
                        // path back on a rolled-back write would let the caller write an
                        // entry pointing at a document the file does not declare, which is
                        // the very pair that fails to load.
                        if (outcome.startsWith("Saved")) {
                            onFiled(path)
                            app.documentCapture.reset()
                            refreshDocuments(app, projectId.orEmpty())
                        }
                    }
                }
            },
        )

/**
 * Which of the installed plugins' modules the open ledger actually names (B60).
 *
 * Read from the file every time rather than kept beside it: the ledger is the record of what
 * it loads, and somebody who writes `plugin "x"` in the raw editor must see the switch move.
 */
internal fun modulesUsedBy(
    app: BeancountApplication,
    projectId: String?,
    rows: List<io.github.pintofi.beanbook.app.plugins.PluginRow>,
): Set<String> {
    val id = projectId ?: return emptySet()
    val text = runCatching { app.workspace.mainFile(id).readText() }.getOrNull() ?: return emptySet()
    return rows.flatMap { it.plugin.modules }.filter { module ->
        io.github.pintofi.beanbook.core.domain.BeancountText
            .hasPluginDirective(text, module, extension = false) ||
            io.github.pintofi.beanbook.core.domain.BeancountText
                .hasPluginDirective(text, module, extension = true)
    }.toSet()
}

/**
 * Adds or removes the directive that makes the open ledger load [module] (B60).
 *
 * Through the write protocol like every other change to a file: locked, snapshotted,
 * re-parsed and only then reported. A plugin that breaks the load is rolled back with the
 * reason rather than leaving a ledger that will not open.
 */
internal fun MainActivity.useModule(
    app: BeancountApplication,
    projectId: String?,
    module: String,
    extension: Boolean,
    use: Boolean,
) {
    val id = projectId ?: return
    lifecycleScope.launch {
        val file = app.workspace.mainFile(id)
        val text = runCatching { file.readText() }.getOrNull() ?: return@launch
        val rewritten = if (use) {
            io.github.pintofi.beanbook.core.domain.BeancountText
                .withPluginDirective(text, module, extension)
        } else {
            io.github.pintofi.beanbook.core.domain.BeancountText
                .withoutPluginDirective(text, module, extension)
        }
        if (rewritten == null) {
            saveMessage = if (use) "$module is already named in this ledger." else "This ledger does not name $module."
            return@launch
        }
        val result = app.writer.replace(
            projectId = id,
            file = file,
            expectedSha = io.github.pintofi.beanbook.core.data.WriteJournal.sha256(file),
            newContent = rewritten,
            operation = if (use) "use-plugin" else "stop-using-plugin",
        )
        saveMessage = when (result) {
            is io.github.pintofi.beanbook.core.data.WriteResult.Committed ->
                if (use) {
                    "This ledger now loads $module. Restart the engine to run it."
                } else {
                    "This ledger no longer loads $module."
                }
            is io.github.pintofi.beanbook.core.data.WriteResult.RolledBack ->
                "Not changed: ${result.reason}. Your ledger is unchanged."
            is io.github.pintofi.beanbook.core.data.WriteResult.Refused -> "Not changed: ${result.reason}"
        }
        app.engineRestart.markNeeded()
        app.ledgerState.load(file.absolutePath)
    }
}

/**
 * The diagnostics screen's data and actions (B62).
 *
 * An extension rather than a member for the reason every other one here is: `MainActivity` is
 * the largest class in the app and detekt's `LargeClass` bites whenever anything is threaded
 * through it.
 *
 * Sharing goes through the same `FileProvider` a document does — a temporary read grant on
 * one file — rather than a giant `EXTRA_TEXT`, which some apps truncate and others refuse.
 */
@androidx.compose.runtime.Composable
internal fun MainActivity.diagnosticsUi(): io.github.pintofi.beanbook.app.ui.DiagnosticsUi {
    val diagnostics = io.github.pintofi.beanbook.app.diagnostics.Diagnostics
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    return io.github.pintofi.beanbook.app.ui.DiagnosticsUi(
        // Read when the screen asks, not here: this is built at start-up, and the log grows
        // for as long as the app runs.
        read = {
            io.github.pintofi.beanbook.app.ui.DiagnosticsRecord(
                lines = diagnostics.tail(),
                sizeBytes = diagnostics.sizeBytes(),
            )
        },
        onCopy = {
            clipboard.setText(androidx.compose.ui.text.AnnotatedString(diagnostics.report()))
            saveMessage = "The report is on the clipboard."
        },
        onShare = { shareDiagnostics() },
        onClear = { diagnostics.clear() },
        message = saveMessage,
    )
}

/** Hands the report to whatever the user picks, and to nothing otherwise. */
internal fun MainActivity.shareDiagnostics() {
    val report = io.github.pintofi.beanbook.app.diagnostics.Diagnostics.report()
    val file = java.io.File(cacheDir, "diagnostics").apply { mkdirs() }
        .resolve("beanbook-diagnostics.txt")
    val shared = runCatching {
        file.writeText(report)
        androidx.core.content.FileProvider.getUriForFile(this, "$packageName.documents", file)
    }.getOrNull()
    if (shared == null) {
        saveMessage = "The report could not be written, so nothing was shared."
        return
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_STREAM, shared)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Beanbook diagnostics")
        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { startActivity(android.content.Intent.createChooser(intent, "Share the report")) }
        .onFailure { saveMessage = "Nothing on this device could take the report." }
}
