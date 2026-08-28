package dev.beancount.app

import android.content.Intent
import android.net.Uri
import dev.beancount.core.domain.DirectiveDraft
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
internal fun MainActivity.unlinkDocument(app: BeancountApplication, projectId: String, path: String) {
    lifecycleScope.launch {
        val projectDir = app.workspace.projectDir(projectId)
        val candidates = projectDir.walkTopDown()
            .filter { it.isFile && (it.extension == "beancount" || it.extension == "bean") }
            .toList()

        val holder = candidates.firstOrNull { it.readText().contains(path) }
        if (holder == null) {
            saveMessage = "Nothing in the ledger links to $path."
            return@launch
        }

        val kept = holder.readLines().filterNot { line ->
            line.contains(path) && line.contains("document")
        }
        val result = app.writer.replace(
            projectId = projectId,
            file = holder,
            expectedSha = dev.beancount.core.data.WriteJournal.sha256(holder),
            newContent = kept.joinToString("\n").let { if (it.endsWith("\n")) it else it + "\n" },
            operation = "unlink-document",
        )
        saveMessage = when (result) {
            is dev.beancount.core.data.WriteResult.Committed ->
                "Unlinked $path. The file is still in the project."
            is dev.beancount.core.data.WriteResult.RolledBack ->
                "Not unlinked: ${result.reason}. Your ledger is unchanged."
            is dev.beancount.core.data.WriteResult.Refused -> "Not unlinked: ${result.reason}"
        }
        app.ledgerState.load(app.workspace.mainFile(projectId).absolutePath)
    }
}

/** Hands one document to another app as a temporary read-only URI, and nothing else. */
internal fun MainActivity.shareDocument(app: BeancountApplication, projectId: String, path: String) {
    val file = app.workspace.projectDir(projectId).resolve(path)
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
