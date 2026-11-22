package io.github.pintofi.beanbook.app

import androidx.lifecycle.lifecycleScope
import io.github.pintofi.beanbook.core.domain.DirectiveDraft
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The file an account is opened or closed in, relative to the project. */
internal const val ACCOUNTS_FILE = "files/accounts.beancount"

/** What a freshly created accounts file starts with. */
internal const val ACCOUNTS_FILE_HEADER = "; Accounts opened and closed, written by Beanbook.\n"

/**
 * Whether the ledger reads [target]: the engine says it did, or [mainText] carries an include line for it.
 *
 * Both are asked, because the engine's list is from the last load and the line may have been added
 * since, and the line alone misses a file that is included from another file.
 */
internal fun isIncluded(target: File, include: String, readFiles: List<String>, mainText: String): Boolean {
    val canonical = runCatching { target.canonicalPath }.getOrDefault(target.path)
    if (readFiles.any { runCatching { File(it).canonicalPath }.getOrDefault(it) == canonical }) return true
    return Regex("""(?m)^\s*include\s+"${Regex.escape(include)}"""").containsMatchIn(mainText)
}

/**
 * Where an `open` or `close` goes: files/accounts.beancount, created, and included from the main file the
 * first time.
 *
 * An account whose `open` line is in another file keeps being edited there; only a new `open` or a `close`
 * is written here. Nothing already in the ledger is moved.
 *
 * Returns null while the file is not yet part of the ledger. That changes which files the ledger is made of,
 * so the line is shown and approved before it is written, and [then] runs the save again once it is in place.
 */
internal suspend fun MainActivity.accountsLedger(
    app: BeancountApplication,
    projectId: String,
    ledger: LedgerSnapshot?,
    then: () -> Unit = {},
): File? {
    val main = app.workspace.mainFile(projectId)
    val target = File(app.workspace.projectDir(projectId), ACCOUNTS_FILE)
    val include = target.relativeTo(main.parentFile ?: target.parentFile).path.replace(File.separatorChar, '/')
    // Nothing is created here. A new file in the ledger's folder, even with only a comment in it, is a
    // change to the ledger, so it is made only after the person has approved it (below), or by the
    // save they asked for.
    val needsInclude = withContext(Dispatchers.IO) {
        !isIncluded(target, include, ledger?.includedFiles.orEmpty(), main.readText())
    }
    if (!needsInclude) return target
    val creating = !target.isFile
    val line = "include \"$include\""
    saveMessage = "Not saved yet: $include needs to be included in ${main.name} first."
    pendingLedgerEdit = MainActivity.LedgerEdit(
        file = main.name,
        line = line,
        why = "Accounts you open or close are written to $include. Your ledger does not include that file yet, " +
            "so nothing would read them. Beanbook can add this line at the end of ${main.name}" +
            (if (creating) ", and create $include with one comment line: ${ACCOUNTS_FILE_HEADER.trim()}" else "") +
            "; you can also add it yourself. Accounts already opened in your ledger stay where they are.",
        apply = {
            lifecycleScope.launch {
                // The new file first, through the write protocol (so Undo takes it back), then the line
                // that makes the ledger read it.
                if (creating) {
                    app.writer.append(projectId, target, ACCOUNTS_FILE_HEADER, operation = "create-accounts-file")
                }
                val result = app.writer.append(projectId, main, "$line\n", operation = "include-accounts")
                val added = result is io.github.pintofi.beanbook.core.data.WriteResult.Committed
                saveMessage = if (added) {
                    "Added the include line. Saving the account now."
                } else {
                    "The line could not be added, so nothing was saved."
                }
                app.ledgerState.load(main.absolutePath)
                if (added) then()
            }
        },
    )
    return null
}

/** Saves a directive from the Add form: an `open` or `close` to the accounts file, anything else where it goes. */
internal fun MainActivity.saveDirective(
    app: BeancountApplication,
    projectId: String,
    ledger: LedgerSnapshot?,
    draft: DirectiveDraft,
) {
    val text = draft.render(currencyColumnOf(ledger))
    if (draft is DirectiveDraft.Open || draft is DirectiveDraft.Close) {
        lifecycleScope.launch {
            val target = accountsLedger(app, projectId, ledger) { saveDirective(app, projectId, ledger, draft) }
                ?: return@launch
            saveText(app, projectId, text, DIRECTIVE_OPERATION, target = target)
        }
    } else {
        saveText(app, projectId, text, DIRECTIVE_OPERATION, target = directiveTarget(app, projectId, ledger, draft))
    }
}
