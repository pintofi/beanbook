package io.github.pintofi.beanbook.app

import androidx.compose.runtime.getValue
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * The question asked when a change the person wrote would add errors to the ledger. Over the app, like
 * [LedgerEditDialog], and brought back for the open project when the app was stopped with it showing.
 */
@androidx.compose.runtime.Composable
internal fun KeepWithErrorsHost(app: BeancountApplication, activeId: String?) {
    val controller = app.heldWrites
    androidx.compose.runtime.LaunchedEffect(activeId) { activeId?.let(controller::restore) }
    val offer by controller.offer.collectAsStateWithLifecycle()
    offer?.let {
        io.github.pintofi.beanbook.app.ui.KeepWithErrorsDialog(
            offer = it,
            onKeep = controller::keep,
            onDiscard = controller::discard,
            onClose = controller::close,
        )
    }
}

/**
 * An edited entry that would add errors is not only refused: the person is asked, and may save it anyway.
 * The entry form stays open as typed until they answer, and closes when the change is made, as for any save.
 */
internal fun MainActivity.askToKeepWithErrors(
    app: BeancountApplication,
    projectId: String,
    result: io.github.pintofi.beanbook.core.data.WriteResult,
    fromForm: Boolean,
) {
    val held = (result as? io.github.pintofi.beanbook.core.data.WriteResult.RolledBack)
        ?.takeIf { it.cause == io.github.pintofi.beanbook.core.data.RollbackCause.NEW_ERRORS }?.held ?: return
    app.heldWrites.ask(held) { kept ->
        saveMessage = "Saved with ${kept.warnings.size} ${if (kept.warnings.size == 1) "error" else "errors"} " +
            "to review."
        app.undo.offerFor(projectId, kept, "saved the entry with errors")
        if (fromForm) entryCommits++
        lifecycleScope.launch { app.ledgerState.load(app.workspace.mainFile(projectId).absolutePath) }
    }
}
