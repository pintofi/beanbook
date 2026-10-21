package io.github.pintofi.beanbook.app

import androidx.lifecycle.lifecycleScope
import io.github.pintofi.beanbook.app.onboarding.Creation
import io.github.pintofi.beanbook.app.onboarding.NewLedgerSetup
import io.github.pintofi.beanbook.app.onboarding.SetupDraft
import io.github.pintofi.beanbook.app.onboarding.SetupStep
import io.github.pintofi.beanbook.app.ui.onboarding.SetupActions
import java.time.LocalDate
import kotlinx.coroutines.launch

// What the first-run setup asks of the app around it. Moved out of MainActivity, which detekt holds
// under its size limit, with the same arrangement as SaveActions.kt.

/** Creates the ledger the draft describes, and tells the setup how it went. */
private fun MainActivity.createLedger(app: BeancountApplication, draft: SetupDraft) {
    val setup = app.newLedgerSetup
    lifecycleScope.launch {
        setup.creating(Creation.Working("Writing the files and having Beancount read them"))
        val blueprint = draft.toBlueprint(LocalDate.now(), NewLedgerSetup.commodityNames(draft.currencies))
        app.projectsState.createFromBlueprint(blueprint)
            .onSuccess { id ->
                draft.mirrorFolder?.let { folder ->
                    app.mirror.useProject(id)
                    app.mirror.setFolder(folder)
                    app.mirror.setEnabled(true)
                }
                setup.finished(id, NewLedgerSetup.summary(blueprint))
            }
            .onFailure { setup.creating(Creation.Failed(it.message ?: "The ledger could not be created.")) }
    }
}

internal fun MainActivity.setupActions(app: BeancountApplication): SetupActions {
    val setup = app.newLedgerSetup
    return SetupActions(
        onCreate = { createLedger(app, setup.draft.value) },
        onCreateWithDefaults = {
            val name = setup.draft.value.name
            setup.update { NewLedgerSetup.fresh().copy(name = name, step = SetupStep.FINISH) }
            createLedger(app, setup.draft.value)
        },
        onOpenFolder = { folderPicker.launch(null) },
        onOpenArchive = { archivePicker.launch(arrayOf("application/zip", "*/*")) },
        onPickMirrorFolder = { mirrorFolderPicker.launch(null) },
        onAskNotifications = { askForNotifications(force = true) },
        onThemeChoice = { app.themeSettings.setChoice(it) },
        onFinish = { id, addEntry ->
            setup.reset()
            openProject(app, id)
            if (addEntry) requestedSurface = "ADD"
        },
    )
}
