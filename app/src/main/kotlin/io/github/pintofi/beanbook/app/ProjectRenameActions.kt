package io.github.pintofi.beanbook.app

import androidx.lifecycle.lifecycleScope
import io.github.pintofi.beanbook.core.data.ProjectRename
import io.github.pintofi.beanbook.core.data.WriteJournal
import io.github.pintofi.beanbook.core.data.WriteResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Gives a project a new name by writing `option "title"` in its main file, through the write
 * protocol, so the change is snapshotted, re-parsed and can be undone. The id and the folder stay
 * as they are.
 */
internal fun MainActivity.renameProject(app: BeancountApplication, projectId: String, title: String) {
    lifecycleScope.launch {
        val main = app.workspace.mainFile(projectId)
        val updated = withContext(Dispatchers.IO) {
            runCatching { ProjectRename.apply(main.readBytes(), title).getOrThrow() }
        }.getOrElse {
            app.undo.tell("Not renamed: ${it.message}. Your ledger is unchanged.")
            return@launch
        }
        val result = app.writer.replace(
            projectId = projectId,
            file = main,
            expectedSha = WriteJournal.sha256(main),
            newContent = updated,
            operation = "rename-project",
        )
        when (result) {
            is WriteResult.Committed -> app.undo.offerFor(projectId, result, "renamed the project")
            is WriteResult.RolledBack -> app.undo.tell("Not renamed: ${result.reason}. Your ledger is unchanged.")
            is WriteResult.Refused -> app.undo.tell("Not renamed: ${result.reason}")
        }
        app.projectsState.refresh()
        if (app.projectsState.activeId.value == projectId) app.ledgerState.load(main.absolutePath)
    }
}
