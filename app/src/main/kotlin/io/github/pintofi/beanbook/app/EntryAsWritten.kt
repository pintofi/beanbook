package io.github.pintofi.beanbook.app

/**
 * An entry of the ledger as its own text writes it, or null when its text cannot be found or read.
 *
 * For Use last on the entry form. The parsed entry has the amounts Beancount worked out, so an
 * amount the text leaves out would come back filled in; the text keeps it left out. The same proof
 * as editing an entry holds it: the draft is offered only if it renders back to that text.
 */
internal suspend fun entryAsWritten(
    app: BeancountApplication,
    projectId: String,
    entry: io.github.pintofi.beanbook.core.domain.JournalEntry,
): io.github.pintofi.beanbook.core.domain.TransactionDraft? {
    val file = entry.file ?: return null
    val line = entry.line ?: return null
    val mainFile = app.workspace.mainFile(projectId).absolutePath
    val slice = runCatching { app.editor.sliceAt(mainFile, file, line) }.getOrNull()
        ?.takeIf { sliceMatches(entry, it.text) } ?: return null
    return io.github.pintofi.beanbook.core.domain.StructuredEdit.editableDraftOf(entry, slice.text)?.draft
}
