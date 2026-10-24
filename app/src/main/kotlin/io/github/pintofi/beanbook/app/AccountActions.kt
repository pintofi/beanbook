package io.github.pintofi.beanbook.app

import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/** The saver for the Accounts page, or null when the ledger is on a server and cannot be rewritten here. */
internal fun MainActivity.accountDetailsSaver(
    app: BeancountApplication,
    projectId: String?,
    remote: Boolean,
): io.github.pintofi.beanbook.app.ui.EditAccountDetails? =
    if (remote || projectId == null) {
        null
    } else {
        { account, details -> saveAccountDetails(app, projectId, account, details) }
    }

/**
 * Saves the details (metadata) of an account that is already open, by rewriting its `open`
 * directive in place. Only the lines that changed are touched
 * ([io.github.pintofi.beanbook.core.domain.AccountDetails]); the directive is looked up again
 * first, the file's SHA is enforced by the write protocol, and Beancount reads the result before
 * it is kept, as for an entry edit.
 */
internal fun MainActivity.saveAccountDetails(
    app: BeancountApplication,
    projectId: String,
    account: io.github.pintofi.beanbook.core.domain.AccountBalance,
    details: Map<String, String>,
) {
    val file = account.openedFile
    val line = account.openedLine
    if (file == null || line == null) {
        saveMessage = "Not saved: this account's open line has no recorded position in the file."
        return
    }
    lifecycleScope.launch {
        val target = java.io.File(file)
        val expectedSha = io.github.pintofi.beanbook.core.data.WriteJournal.sha256(target)
        val mainFile = app.workspace.mainFile(projectId).absolutePath
        val slice = runCatching { app.editor.sliceAt(mainFile, file, line) }.getOrNull()
            ?.takeIf { it.text.contains(" open ${account.name}") }
        if (slice == null) {
            saveMessage = "Not saved: the open line of ${account.name} is no longer where it was. " +
                "Your ledger is unchanged."
            return@launch
        }
        val rewritten = io.github.pintofi.beanbook.core.domain.AccountDetails
            .rewrite(slice.text, account.metadata, details)
        val newText = when (rewritten) {
            is io.github.pintofi.beanbook.core.domain.AccountDetails.Result.Unchanged -> {
                saveMessage = "Nothing changed."
                return@launch
            }
            is io.github.pintofi.beanbook.core.domain.AccountDetails.Result.Refused -> {
                saveMessage = "Not saved: ${rewritten.reason}"
                return@launch
            }
            is io.github.pintofi.beanbook.core.domain.AccountDetails.Result.Changed -> rewritten.text
        }
        val updated = runCatching {
            app.editor.contentWithReplacement(slice.file, slice.startLine, slice.endLine, newText)
        }.getOrElse {
            saveMessage = "Could not apply that change: ${it.message}. Nothing was changed."
            return@launch
        }
        val result = app.writer.replace(
            projectId = projectId,
            file = target,
            expectedSha = expectedSha,
            newContent = updated,
            operation = "edit-account-details",
        )
        saveMessage = when (result) {
            is io.github.pintofi.beanbook.core.data.WriteResult.Committed ->
                "Saved the details of ${account.name}."
            is io.github.pintofi.beanbook.core.data.WriteResult.RolledBack ->
                "Not saved: ${result.reason}. Your ledger is unchanged."
            is io.github.pintofi.beanbook.core.data.WriteResult.Refused -> "Not saved: ${result.reason}"
        }
        if (result is io.github.pintofi.beanbook.core.data.WriteResult.Committed) {
            app.undo.offerFor(projectId, result, "changed the details of ${account.name}")
        }
        app.ledgerState.load(mainFile)
    }
}
