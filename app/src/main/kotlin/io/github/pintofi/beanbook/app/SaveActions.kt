package io.github.pintofi.beanbook.app

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

// Writing text to the open project, and what the form is told afterwards. Moved out of
// MainActivity, which detekt holds under its size limit, when the entry form learned to close
// itself on a committed save (B77).

/**
 * Where a new entry is written: the project's transactions file when the ledger reads it, and
 * the main file when it does not (B77).
 *
 * Entries always went to `files/transactions.beancount`, which the starter layout includes. A
 * ledger brought in from elsewhere has no such include, so an entry was written to a file
 * Beancount never opened: the re-parse found nothing wrong, the form said "Saved.", and the
 * entry was not in the journal. The main file is read by definition, so it is the fallback.
 */
internal fun entryTarget(
    app: BeancountApplication,
    projectId: String,
    ledger: io.github.pintofi.beanbook.core.domain.LedgerSnapshot?,
): java.io.File {
    val transactions = app.workspace.projectDir(projectId).resolve("files/transactions.beancount")
    val read = ledger?.includedFiles.orEmpty().map { java.io.File(it).canonicalPath }
    return if (transactions.canonicalPath in read) transactions else app.workspace.mainFile(projectId)
}

/** The two writes the entry form makes, and the only ones that close it (B77). */
internal const val ENTRY_OPERATION = "quick-add"
internal const val DIRECTIVE_OPERATION = "add-directive"
private val ENTRY_OPERATIONS = setOf(ENTRY_OPERATION, DIRECTIVE_OPERATION)

internal fun MainActivity.saveText(
    app: BeancountApplication,
    projectId: String,
    text: String,
    operation: String,
    // Which file the text is appended to. Entries go to the transactions file; a
    // `document` directive goes to the main file, because its path is relative to the
    // file that holds it and the documents directory sits beside the main file.
    target: java.io.File = app.workspace.projectDir(projectId).resolve("files/transactions.beancount"),
    onResult: (String) -> Unit = { saveMessage = it },
) {
    val ledgerPath = app.workspace.mainFile(projectId).absolutePath
    lifecycleScope.launch {
        saveMessage = "Saving…"
        val file = target
        val result = app.writer.append(projectId, file, text, operation = operation)
        if (result is io.github.pintofi.beanbook.core.data.WriteResult.Committed &&
            operation in ENTRY_OPERATIONS
        ) {
            entryCommits += 1
        }
        onResult(
            when (result) {
                is io.github.pintofi.beanbook.core.data.WriteResult.Committed ->
                    if (result.warnings.isEmpty()) "Saved."
                    else "Saved, with ${result.warnings.size} ledger error(s) to review."
                is io.github.pintofi.beanbook.core.data.WriteResult.RolledBack ->
                    "Not saved: ${result.reason}. Your ledger is unchanged."
                is io.github.pintofi.beanbook.core.data.WriteResult.Refused ->
                    "Not saved: ${result.reason}"
            },
        )
        // Re-read so the journal and balances show the new entry, then mirror: a pass
        // only ever runs after a commit has landed (docs/storage-and-backup.md 6).
        app.ledgerState.load(ledgerPath)
        app.mirror.syncAfterCommit(projectId)
    }
}

