package io.github.pintofi.beanbook.app

import android.content.Intent
import androidx.compose.material3.OutlinedButton
import androidx.compose.ui.unit.dp
import java.io.File
import android.net.Uri
import io.github.pintofi.beanbook.core.domain.DirectiveDraft
import java.time.LocalDate
import io.github.pintofi.beanbook.app.documents.reviewActions
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
        // Including the folders `option "documents"` names: Beancount files what it
        // finds in those, so a screen that read only the app's own folder reported them as
        // links to files that were not there.
        val roots = app.ledgerState.snapshot.value?.documentRoots.orEmpty()
        documents = app.documentCapture.existing(projectId, roots)
    }
}

/**
 * Files the captured pages as a PDF, then writes the `document` directive that points at
 * it — in that order, so the ledger never names a file that does not exist yet
 * (a link is never written before its file exists).
 */
internal fun MainActivity.attachDocument(
    app: BeancountApplication,
    projectId: String,
    account: String,
    label: String? = null,
) {
    app.documentCapture.save(projectId, account, label = label) { paths ->
        lifecycleScope.launch {
            // In files/documents.beancount rather than the main file, where a folder of
            // receipts filed over a year had been piling up between the options and plugins.
            val ledger = documentsLedger(app, projectId) {
                attachDocument(app, projectId, account, label)
            } ?: return@launch
            val projectDir = app.workspace.projectDir(projectId)
            val directives = paths.joinToString("") { relativePath ->
                DirectiveDraft.Document(
                    date = LocalDate.now().toString(),
                    account = account,
                    path = documentPathIn(ledger, projectDir, relativePath),
                ).render()
            }
            saveText(app, projectId, directives, operation = "attach-document", target = ledger) { outcome ->
                if (outcome.startsWith("Saved")) {
                    // Said on the Documents page, where the form has just cleared itself: the line
                    // stays as the one sign of what happened, until the next document is started.
                    // The count of ledger errors that "Saved, with 3 ledger error(s)" carried is
                    // about the ledger, not about the document just filed, and it read as if
                    // filing had gone wrong, so only what happened is said.
                    saveMessage = null
                    app.documentCapture.report(
                        "Saved ${paths.joinToString { it.substringAfterLast('/') }} and filed it under $account.",
                    )
                } else {
                    saveMessage = outcome
                    app.documentCapture.report(outcome)
                }
            }
            app.documentCapture.finish()
            refreshDocuments(app, projectId)
        }
    }
}

/**
 * The ledger file holding a real `document` directive, and [rewrite]'s result for it.
 *
 * [rewrite] returns `null` for a file with no such directive, which is what makes this
 * narrow: a file merely *mentioning* the path -- in a comment, or in a transaction's own
 * `document:` metadata -- is not a match, and rewriting it would corrupt an unrelated entry.
 *
 * Shared by unlinking and refiling because both must agree on which file to touch. A ledger
 * split by `include` may have many, and picking a different one for each operation would
 * unlink from one file what had been refiled in another.
 */
private fun findDocumentDirective(
    projectDir: File,
    path: String,
    rewrite: (text: String, path: String) -> String?,
): Pair<File, String>? =
    projectDir.walkTopDown()
        .filter { it.isFile && (it.extension == "beancount" || it.extension == "bean") }
        .mapNotNull { file ->
            val text = file.readText()
            // A directive names its file relative to the file it is written in, so the
            // one in files/documents.beancount says "../documents/…" for the same document.
            (rewrite(text, path) ?: pathAsNamedIn(file, projectDir, path)?.let { rewrite(text, it) })
                ?.let { file to it }
        }
        .firstOrNull()

/** [path], relative to the project, as a directive in [file] would write it; null if the same. */
internal fun pathAsNamedIn(file: File, projectDir: File, path: String): String? {
    if (File(path).isAbsolute) return null
    val named = File(projectDir, path).relativeTo(file.parentFile ?: projectDir).path
        .replace(File.separatorChar, '/')
    return named.takeIf { it != path }
}

/**
 * Where a `document` directive filing a document under an account is written:
 * files/documents.beancount, created and included from the main file the first time.
 *
 * Returns the file and the path the directive must use for [projectPath], which is relative to
 * the directive's own file -- "../documents/…" -- because that is how Beancount resolves it.
 */
internal suspend fun MainActivity.documentsLedger(
    app: BeancountApplication,
    projectId: String,
    /**
     * What to do once the include line is in place. Without it the person had to find
     * the filing button again and tap it a second time, and nothing on the Documents screen
     * said so: the page showed "Saved ….pdf" and the ledger never got its directive.
     */
    then: () -> Unit = {},
): File? {
    val main = app.workspace.mainFile(projectId)
    val target = File(app.workspace.projectDir(projectId), "files/documents.beancount")
    val include = target.relativeTo(main.parentFile ?: target.parentFile).path.replace(File.separatorChar, '/')
    val needsInclude = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (!target.isFile) {
            target.parentFile?.mkdirs()
            target.writeText("; Documents filed under an account, written by Beanbook.\n")
        }
        !Regex("""(?m)^\s*include\s+"${Regex.escape(include)}"""").containsMatchIn(main.readText())
    }
    if (needsInclude) {
        // Never silently: this changes which files the ledger is made of, so it is shown
        // and approved before it happens. Filing continues on the next attempt, after the line
        // is in place.
        val line = "include \"$include\""
        // Said where the save was tapped as well, so a form waiting on this is not left
        // showing "Saving…" behind the dialog.
        saveMessage = "Not saved yet: $include needs to be included in ${main.name} first."
        // The Documents page was left saying "Saved ….pdf" under a form that had not cleared, which
        // read as a save that had not finished. The PDF is saved; the filing is what waits.
        app.documentCapture.report(
            "The PDF is saved. Filing it under an account needs one line added to ${main.name}: confirm it to finish.",
        )
        pendingLedgerEdit = MainActivity.LedgerEdit(
            file = main.name,
            line = line,
            why = "Documents filed under an account are written to $include. Your ledger does " +
                "not include that file yet, so nothing would read them. Beanbook can add this " +
                "line at the end of ${main.name}; you can also add it yourself.",
            apply = {
                lifecycleScope.launch {
                    val result = app.writer.append(projectId, main, "$line\n", operation = "include-documents")
                    val added = result is io.github.pintofi.beanbook.core.data.WriteResult.Committed
                    saveMessage = if (added) {
                        "Added the include line. Filing the document now."
                    } else {
                        "The line could not be added, so nothing was filed."
                    }
                    app.documentCapture.report(saveMessage ?: "")
                    app.ledgerState.load(main.absolutePath)
                    if (added) then()
                }
            },
        )
        return null
    }
    return target
}

/** [projectPath] as a `document` directive in [ledger] names it. */
internal fun documentPathIn(ledger: File, projectDir: File, projectPath: String): String =
    pathAsNamedIn(ledger, projectDir, projectPath) ?: projectPath

/**
 * Removes the link, not the file.
 *
 * The directive is deleted from whichever included file holds it and the write goes
 * through the ordinary protocol, so an unlink is snapshotted and undoable like any other
 * edit. The PDF stays exactly where it was.
 */
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
        // on listing it as "Attached to ..." and offered no way to finish the job.
        //
        // Both are removed, in as many passes as it takes: a file may be named by several
        // entries, and a half-unlinked document is exactly the broken state this action
        // exists to clear.
        var removed = 0
        var failure: String? = null
        // A bare `invoice.pdf` in an entry names this file only if no other document in the
        // project is called the same; otherwise it is left for the user to make explicit.
        val leaf = path.substringAfterLast('/')
        val bareNameIsUnique = app.workspace.projectDir(projectId).walkTopDown()
            .count { it.isFile && it.name == leaf } <= 1
        while (removed < MAX_LINKS_PER_DOCUMENT) {
            val projectDir = app.workspace.projectDir(projectId)
            // Metadata first, then the directive. Under Fava's link_documents plugin an
            // entry's `document:` key must match a `document` directive, so removing the
            // directive while the key still named the file added "Document not found" to the
            // ledger, the write was rolled back as it should be, and Unlink appeared to do
            // nothing. In this order every step leaves a ledger that reads cleanly.
            val holder = findDocumentDirective(projectDir, path) { text, named ->
                io.github.pintofi.beanbook.core.domain.BeancountText
                    .removeDocumentMetadata(text, named, bareNameIsUnique = bareNameIsUnique)
            } ?: findDocumentDirective(projectDir, path) { text, named ->
                io.github.pintofi.beanbook.core.domain.BeancountText
                    .removeDocumentDirective(text, named, from = from)
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

        val outcome = when {
            failure != null -> "Not unlinked: $failure"
            removed == 0 && !bareNameIsUnique && app.workspace.projectDir(projectId).walkTopDown()
                .filter { it.isFile && (it.extension == "beancount" || it.extension == "bean") }
                .any {
                    io.github.pintofi.beanbook.core.domain.BeancountText
                        .namesDocumentByBareName(it.readText(), leaf)
                } ->
                "Not unlinked: an entry names \"$leaf\" without a folder, and another document " +
                    "has the same name, so it cannot tell which one is meant. Give the entry " +
                    "the full path first."
            removed == 0 -> "Nothing in the ledger links to $path."
            else -> "Unlinked $path. The file is still in the project."
        }
        saveMessage = outcome
        // Said on the Documents screen too, where the Unlink was tapped, the way a deleted
        // file is. The app-wide message alone left Organize looking unchanged.
        app.documentCapture.report(outcome)
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
 * Files an already-attached document under a different account.
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
        lifecycleScope.launch {
            val ledger = documentsLedger(app, projectId) {
                refileDocument(app, projectId, path, newAccount, from)
            } ?: return@launch
            val directive = DirectiveDraft.Document(
                date = LocalDate.now().toString(),
                account = newAccount,
                path = documentPathIn(ledger, app.workspace.projectDir(projectId), path),
            )
            saveText(app, projectId, directive.render(), operation = "file-document", target = ledger) { outcome ->
                saveMessage = outcome
                refreshDocuments(app, projectId)
            }
        }
        return
    }
    lifecycleScope.launch {
        val holder = findDocumentDirective(app.workspace.projectDir(projectId), path) { text, named ->
            runCatching {
                io.github.pintofi.beanbook.core.domain.BeancountText
                    .refileDocument(text, named, newAccount, from = from)
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
    val file = documentFile(app, projectId, path)
    // Only out of a documents folder, and only from inside it: the app's own, or one the
    // ledger names with `option "documents"`. The FileProvider is configured over
    // `projects/`, because it cannot express `projects/*/documents`, so this call site is
    // where the narrower rule actually lives: without it, any path this function is handed
    // becomes a content:// URI another app can read -- a ledger included.
    val roots = (listOf(io.github.pintofi.beanbook.core.domain.DocumentName.ROOT) +
        app.ledgerState.snapshot.value?.documentRoots.orEmpty())
        .map { File(projectDir, it).canonicalPath + File.separator }
    if (roots.none { file.canonicalPath.startsWith(it) }) {
        saveMessage = if (file.isFile) {
            "Only documents can be shared out of a project."
        } else {
            "That document is not in the project any more."
        }
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
        type = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    runCatching { startActivity(Intent.createChooser(send, "Share document")) }
}


/**
 * Whether the entry found at a line is the one the person was looking at.
 *
 * The list shows an entry as it was when the ledger was last read, and the position it carries
 * is a line number. If the file has changed since -- a sync, a mirror pull, an edit elsewhere --
 * the same line can now hold a different entry, and deleting or editing "the entry at line 40"
 * would take the wrong one. The slice's own first line must still start with the entry's date,
 * and name its payee and narration, before anything is computed from it.
 */
internal fun sliceMatches(
    entry: io.github.pintofi.beanbook.core.domain.JournalEntry,
    sliceText: String,
): Boolean {
    if (!sliceText.startsWith(entry.date)) return false
    // Beancount strings escape a quote and a backslash; the entry carries them unescaped.
    val plain = sliceText.replace("\\\"", "\"").replace("\\\\", "\\")
    fun named(value: String?) = value.isNullOrBlank() || plain.contains(value)
    if (named(entry.payee) && named(entry.narration)) return true
    // A plugin can rewrite a payee or a narration in memory, so the listed one is not what the
    // file says. The entry's own text is read instead, and it is the same entry when its date
    // and every posting it writes are among the listed entry's. Postings a plugin added are not
    // in the file, so the listed entry may have more; one with a different account or amount is
    // some other entry.
    val own = io.github.pintofi.beanbook.core.domain.StructuredEdit.draftFromSource(sliceText) ?: return false
    if (own.date != entry.date || own.postings.isEmpty()) return false
    return own.postings.all { written ->
        entry.postings.any { listed ->
            listed.account == written.account &&
                (written.amount == null ||
                    (listed.amount?.currency == written.amount?.currency &&
                        listed.amount?.number?.compareTo(written.amount!!.number) == 0))
        }
    }
}

/**
 * Whether the entry at [slice] is still exactly what [slice] says it is.
 *
 * Asks the engine for the entry at that line again and compares Fava's hash of it, so an edit
 * form that has been open for minutes cannot write its text over lines that now hold something
 * else.
 */
internal suspend fun stillThere(
    app: BeancountApplication,
    projectId: String,
    slice: io.github.pintofi.beanbook.engine.local.EntrySlice,
): Boolean {
    val mainFile = app.workspace.mainFile(projectId).absolutePath
    val now = runCatching { app.editor.sliceAt(mainFile, slice.file, slice.startLine) }.getOrNull()
    return now != null && now.sha256 == slice.sha256 && now.endLine == slice.endLine
}

/**
 * Deletes several entries at once, from the Journal's selection.
 *
 * Every entry is found in the files as they are now, then each file is rewritten once with its
 * entries taken out from the bottom up -- so removing one never moves the lines of the next --
 * through the same write protocol as a single delete: snapshot, re-parse, then report.
 */
internal fun MainActivity.deleteEntries(
    app: BeancountApplication,
    projectId: String,
    entries: List<io.github.pintofi.beanbook.core.domain.JournalEntry>,
) {
    if (entries.isEmpty()) return
    lifecycleScope.launch {
        val mainFile = app.workspace.mainFile(projectId).absolutePath
        // Two entries can share a position (a plugin points the ones it generates at the line
        // that caused them), and selecting one selects both. Removing the same lines twice
        // would take the entry after them as well, so each position is removed once.
        val wanted = entries.distinctBy { it.file to it.line }
        val slices = wanted.mapNotNull { entry ->
            val file = entry.file ?: return@mapNotNull null
            val line = entry.line ?: return@mapNotNull null
            runCatching { app.editor.sliceAt(mainFile, file, line) }.getOrNull()
                ?.takeIf { sliceMatches(entry, it.text) }
        }
        if (slices.size != wanted.size) {
            saveMessage = "Some of those entries could not be found in the files, or the files " +
                "have changed since the list was read. Nothing was deleted."
            return@launch
        }
        var deleted = 0
        var committedWrites = 0
        var lastCommitted: io.github.pintofi.beanbook.core.data.WriteResult.Committed? = null
        var failure: String? = null
        for ((path, inFile) in slices.groupBy { it.file }) {
            val target = java.io.File(path)
            val updated = runCatching {
                val lines = target.readText().split(Regex("(?<=\n)")).toMutableList()
                if (lines.lastOrNull()?.isEmpty() == true) lines.removeAt(lines.lastIndex)
                inFile.sortedByDescending { it.startLine }.forEach { slice ->
                    io.github.pintofi.beanbook.core.domain.BeancountText
                        .removeEntryLines(lines, slice.startLine, slice.endLine)
                }
                lines.joinToString("")
            }.getOrElse {
                failure = it.message ?: "the file could not be read"
                null
            } ?: break
            val result = app.writer.replace(
                projectId = projectId,
                file = target,
                expectedSha = io.github.pintofi.beanbook.core.data.WriteJournal.sha256(target),
                newContent = updated,
                operation = "delete-entries",
            )
            when (result) {
                is io.github.pintofi.beanbook.core.data.WriteResult.Committed -> {
                    deleted += inFile.size
                    committedWrites += 1
                    lastCommitted = result
                }
                is io.github.pintofi.beanbook.core.data.WriteResult.RolledBack -> { failure = result.reason; break }
                is io.github.pintofi.beanbook.core.data.WriteResult.Refused -> { failure = result.reason; break }
            }
        }
        saveMessage = when {
            failure == null -> if (deleted == 1) "Deleted 1 entry." else "Deleted $deleted entries."
            deleted == 0 -> "Nothing was deleted: $failure."
            else -> "Deleted $deleted of ${wanted.size} entries, then stopped: $failure."
        }
        // Undo is offered when the delete was one write: with several files only the last
        // could be taken back, which would leave the rest deleted.
        if (failure == null && committedWrites == 1) {
            lastCommitted?.let {
                app.undo.offerFor(projectId, it, if (deleted == 1) "deleted 1 entry" else "deleted $deleted entries")
            }
        } else {
            // Said over the Journal, where the delete was asked for: the save message is
            // shown only in the entry form, so a refused delete used to say nothing at all.
            saveMessage?.let(app.undo::tell)
        }
        app.ledgerState.load(mainFile)
    }
}

/**
 * Removes one transaction from the ledger.
 *
 * The extent is read from the file by `EntrySlices`, by the rule Fava cuts an entry out with:
 * from the line Beancount reported to the first blank line or line in column zero, comments and
 * metadata included. Deleting by matching text would guess at that, and guess wrong on the first
 * entry whose narration happened to appear twice.
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
            ?.takeIf { sliceMatches(entry, it.text) }
        if (slice == null) {
            saveMessage = "Could not find that entry in the file, or the file has changed since " +
                "the list was read. Nothing was changed."
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
        // The confirmation promised Undo; the bar keeps that promise. A refusal is said
        // there too, since the entry page closes and the form's message line is not on screen.
        if (result is io.github.pintofi.beanbook.core.data.WriteResult.Committed) {
            app.undo.offerFor(projectId, result, "deleted 1 entry")
        } else {
            saveMessage?.let(app.undo::tell)
        }
        app.ledgerState.load(mainFile)
    }
}

/**
 * Opens one entry for editing: as fields where that is provably safe, as text otherwise.
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
            ?.takeIf { sliceMatches(entry, it.text) }
        if (slice == null) {
            saveMessage = "Could not find that entry in the file, or the file has changed since " +
                "the list was read. Nothing was changed."
            return@launch
        }
        editingEntry = slice
        // Hand-written entries too: an entry whose only difference from the app's own
        // rendering is its spacing opens as fields, and the form says the spacing will change.
        editingDraft = io.github.pintofi.beanbook.core.domain.StructuredEdit
            .editableDraftOf(entry, slice.text)
    }
}

/**
 * Writes an edited entry back over the range it came from.
 *
 * The entry is looked up again first ([stillThere]): the form may have been open for minutes,
 * and an edit computed against lines that now hold something else is refused. The file's SHA,
 * taken before that check, is then enforced by the write protocol, so a change between the
 * check and the write is refused too rather than applied to the wrong bytes.
 */
internal fun MainActivity.saveEntryEdit(
    app: BeancountApplication,
    projectId: String,
    slice: io.github.pintofi.beanbook.engine.local.EntrySlice,
    newText: String,
    /**
     * Whether the entry form saved this. The form closes itself once the save has been
     * seen to land, the way Add does, and stays open with the reason when it did not; the text
     * dialog closes either way, as it always has.
     */
    fromForm: Boolean = false,
) {
    lifecycleScope.launch {
        val target = java.io.File(slice.file)
        // The file as it is before anything is checked or computed: the write is refused if it
        // is not this by the time it lands.
        val expectedSha = io.github.pintofi.beanbook.core.data.WriteJournal.sha256(target)
        if (!stillThere(app, projectId, slice)) {
            saveMessage = "Not saved: the entry changed in the file after you opened it. " +
                "Your ledger is unchanged; open the entry again to edit the current version."
            return@launch
        }
        val updated = runCatching {
            app.editor.contentWithReplacement(slice.file, slice.startLine, slice.endLine, newText)
        }.getOrElse {
            saveMessage = "Could not apply that edit: ${it.message}. Nothing was changed."
            return@launch
        }

        val result = app.writer.replace(
            projectId = projectId,
            file = target,
            expectedSha = expectedSha,
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
        if (!fromForm) {
            editingEntry = null
            editingDraft = null
        } else if (result is io.github.pintofi.beanbook.core.data.WriteResult.Committed) {
            // What the form's save sweep and close are keyed on, as for a new entry.
            entryCommits++
        }
        app.ledgerState.load(app.workspace.mainFile(projectId).absolutePath)
    }
}

/**
 * The entry editor, when one is open.
 *
 * An extension rather than a member: it reads the activity's editing state and nothing else,
 * and the activity is already the largest class in the app.
 */
@androidx.compose.runtime.Composable
internal fun MainActivity.EntryEditor(
    app: BeancountApplication,
    projectId: String?,
) {
    val slice = editingEntry ?: return
    if (projectId == null) return
    // An entry the form can hold is edited in the form, full screen; this dialog is the
    // text editor, for the entries it cannot hold and for anyone who asks for the text.
    if (editingDraft != null) return
    io.github.pintofi.beanbook.app.ui.EntryEditDialog(
        original = slice.text,
        onSave = { text -> saveEntryEdit(app, projectId, slice, text) },
        onDismiss = {
            editingEntry = null
            editingDraft = null
        },
    )
}

/**
 * The entry open in the form, when it is one the form can hold.
 *
 * Built here rather than in the activity, which is already the largest class in the app.
 */
internal fun MainActivity.entryEditSession(
    app: BeancountApplication,
    projectId: String?,
): EntryEditSession? {
    val slice = editingEntry ?: return null
    val editable = editingDraft ?: return null
    val id = projectId ?: return null
    return EntryEditSession(
        editing = io.github.pintofi.beanbook.app.ui.EntryEditing(
            original = editable.draft,
            reformats = editable.reformats,
            // Into the text dialog: the entry stays open, as text.
            onEditAsText = { editingDraft = null },
        ),
        onSave = { draft -> saveEntryEdit(app, id, slice, draft.render(), fromForm = true) },
        onClose = {
            editingEntry = null
            editingDraft = null
        },
    )
}

/**
 * Ends the engine process and starts a new one on the open project.
 *
 * An extension rather than a member: `MainActivity` is already the largest class in the app,
 * and this reads the same two collaborators every other action here does.
 */
internal fun MainActivity.restartEngine(app: BeancountApplication, projectId: String?) {
    val id = projectId ?: return
    // Quiet: this now follows every plugin install and removal on its own, and the
    // Plugins screen already says what was installed.
    app.engineManager.restart()
    app.engineRestart.cleared()
    lifecycleScope.launch {
        // The ledger is re-read afterwards because a plugin that has just been turned on
        // changes what a load produces -- errors it raises, directives it inserts.
        app.ledgerState.load(app.workspace.mainFile(id).absolutePath)
    }
}

/**
 * Ends every process the app runs and opens it again, reading everything from disk.
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
 * A document reference from the ledger, as a file on disk.
 *
 * Resolved by [io.github.pintofi.beanbook.core.data.DocumentLocator], which reads a reference
 * the ways Beancount and Fava do -- a directive's path, a bare file name in `document:`
 * metadata, a path relative to the entry's file -- and never leaves the project tree. When
 * nothing is found the unresolved path is returned, so the viewer can say which file it looked
 * for rather than showing nothing.
 */
internal fun MainActivity.documentFile(
    app: BeancountApplication,
    projectId: String?,
    path: String,
    entryFile: String? = null,
): java.io.File {
    val root = app.workspace.projectDir(projectId.orEmpty())
    val known = documents + app.ledgerState.snapshot.value?.documents.orEmpty().map { it.path }
    return io.github.pintofi.beanbook.core.data.DocumentLocator.locate(root, path, entryFile, known)
        ?: java.io.File(root, path.substringAfterLast('/'))
}

/**
 * Which entries attach which documents, by the path each one names.
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
 * The capture style chosen at the shutter, as the page model records it.
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
 * Everything a screen needs to photograph, edit and file a document, as one value.
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
                // rather than something that destroyed the original.
                if (file.length() > 0) {
                    app.documentCapture.add(file, pageStyleOf(style), crop = quad)
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
            review = app.documentCapture.reviewActions(),
            fileFor = { name -> app.documentCapture.fileFor(name) },
            plannedPaths = { account, label ->
                app.documentCapture.plannedPaths(account, label, attached = true)
            },
            attachedFiles = app.documentFileNames,
            onAttachFile = { uri ->
                lifecycleScope.launch { app.documentCapture.attach(contentResolver, uri) }
            },
            onRemoveFile = { app.documentCapture.removeFile(it) },
            onFile = { account, label, onFiled ->
                // The entry carries the link as its `document:` metadata, so no separate
                // `document` directive is written for it -- with the documents option,
                // Beancount finds the PDF in the folder anyway, and the directive only made the
                // same receipt appear twice. The one exception is a ledger using Fava's
                // link_documents plugin, which rejects metadata that no Document entry backs;
                // there the directive still goes to files/documents.beancount.
                val id = projectId.orEmpty()
                app.documentCapture.save(id, account, label = label, attached = true) { paths ->
                    // The application's scope, like the save that follows.
                    app.workScope.launch {
                        val main = app.workspace.mainFile(id)
                        val linksDocuments = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            runCatching { main.readText().contains("link_documents") }.getOrDefault(false)
                        }
                        if (!linksDocuments) {
                            onFiled(paths)
                            app.documentCapture.finish()
                            refreshDocuments(app, id)
                            return@launch
                        }
                        val ledger = documentsLedger(app, id) ?: return@launch
                        val projectDir = app.workspace.projectDir(id)
                        val directives = paths.joinToString("") { path ->
                            io.github.pintofi.beanbook.core.domain.DirectiveDraft.Document(
                                date = java.time.LocalDate.now().toString(),
                                account = account,
                                path = documentPathIn(ledger, projectDir, path),
                            ).render()
                        }
                        saveText(
                            app, id, directives,
                            operation = "attach-document",
                            target = ledger,
                            reload = false,
                            onResult = { outcome ->
                            // The entry is saved next, so a success here is not the end of
                            // the save and is not announced as one: the form would take
                            // "Saved." as its own result and stop showing that it is working.
                            if (!outcome.startsWith("Saved")) saveMessage = outcome
                            // Only when the directive is actually in the ledger. Reporting the
                            // path back on a rolled-back write would let the caller write an
                            // entry pointing at a document the file does not declare.
                            if (outcome.startsWith("Saved")) {
                                onFiled(paths)
                                app.documentCapture.finish()
                                refreshDocuments(app, id)
                            }
                            },
                        )
                    }
                }
            },
        )

/**
 * The diagnostics screen's data and actions.
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

/**
 * Reads the ledger again when a file under the open project changes on disk.
 *
 * The watcher reports the change; this decides what follows from it. The document list is
 * rebuilt with the ledger because half of it comes from the folder, and the folders being
 * watched are collected again because the change may have been a new included file.
 *
 * Fava's page in Reports follows the same signal, in `LedgerApp`, where the retained page is.
 */
@androidx.compose.runtime.Composable
internal fun MainActivity.FollowDiskChanges(app: BeancountApplication, activeId: String?) {
    // Watching starts when the app is in front and stops when it leaves: a watcher running in
    // the background would reload a ledger nobody is looking at, and an app that is not running
    // reads the files again when it next opens anyway.
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(owner, activeId) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_RESUME ->
                    activeId?.let { app.ledgerWatcher.watch(app.workspace.projectDir(it)) }
                androidx.lifecycle.Lifecycle.Event.ON_PAUSE -> app.ledgerWatcher.stop()
                else -> Unit
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            app.ledgerWatcher.stop()
        }
    }
    androidx.compose.runtime.LaunchedEffect(activeId) {
        if (activeId == null) return@LaunchedEffect
        app.ledgerChangedOnDisk.collect { change ->
            if (change > 0L) {
                app.ledgerWatcher.watch(app.workspace.projectDir(activeId))
                app.ledgerState.reload()
                refreshDocuments(app, activeId)
            }
        }
    }
}

/**
 * What the app is about to add to a ledger file, and the chance to say no.
 *
 * The app does not edit a person's Beancount files behind their back. The one line it ever
 * needs to add by itself -- an `include` that makes a new file part of the ledger -- is shown
 * as it will be written, in the file it will be written to, before anything is written.
 */
@androidx.compose.runtime.Composable
internal fun MainActivity.LedgerEditDialog() {
    val edit = pendingLedgerEdit ?: return
    androidx.compose.material3.AlertDialog(
        onDismissRequest = { pendingLedgerEdit = null },
        title = { androidx.compose.material3.Text("Add a line to ${edit.file}?") },
        text = {
            androidx.compose.foundation.layout.Column(
                verticalArrangement = androidx.compose.foundation.layout.Arrangement
                    .spacedBy(8.dp),
            ) {
                androidx.compose.material3.Text(edit.why)
                androidx.compose.material3.Text(
                    edit.line,
                    style = io.github.pintofi.beanbook.app.ui.theme.LedgerType.source,
                )
            }
        },
        confirmButton = {
            androidx.compose.material3.Button(
                onClick = {
                    val run = edit.apply
                    pendingLedgerEdit = null
                    run()
                },
            ) { androidx.compose.material3.Text("Add the line") }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { pendingLedgerEdit = null },
            ) { androidx.compose.material3.Text("Cancel") }
        },
    )
}

/**
 * Deletes a document file the person confirmed, and offers to undo it. The file is only moved
 * aside, for a month, so the Undo bar can put it back where it was.
 */
internal fun MainActivity.deleteDocumentFile(app: BeancountApplication, projectId: String, path: String) {
    app.documentCapture.deleteFile(projectId, path) { trashed ->
        trashed?.let {
            app.undo.offerRestore(projectId, "deleted ${path.substringAfterLast('/')}") {
                // The list was read without it, so it is read again once it is back.
                app.documentCapture.restoreFile(it).also { restored ->
                    if (restored) refreshDocuments(app, projectId)
                }
            }
        }
        refreshDocuments(app, projectId)
    }
}
