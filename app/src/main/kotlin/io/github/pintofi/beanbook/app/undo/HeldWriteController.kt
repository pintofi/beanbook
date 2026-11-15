package io.github.pintofi.beanbook.app.undo

import io.github.pintofi.beanbook.core.data.HeldWrite
import io.github.pintofi.beanbook.core.data.HeldWrites
import io.github.pintofi.beanbook.core.data.ProjectWriter
import io.github.pintofi.beanbook.core.data.WriteResult
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The change the person is being asked about, with what the dialog needs to say. */
data class HeldOffer(
    val held: HeldWrite,
    /** What was being done, as a phrase for the dialog: "Saving to transactions.beancount". */
    val what: String,
    val working: Boolean = false,
    /** Set when the change could not be made again; the dialog shows it and offers only Close. */
    val problem: String? = null,
)

/**
 * A change that was undone because it adds errors, held until the person chooses Save anyway or Don't save.
 *
 * One place raises the question and one answers it, so the guided writes do not each grow a dialog. The
 * change is held on disk ([HeldWrites]), so a dialog that was open when the app was stopped returns when
 * the project is opened again, and the state lives here and not in a screen, so a rotation keeps it.
 *
 * Only changes the person wrote directly are offered (see [overridable]). A mirror pass, a write from
 * another device, a line the app adds itself, a bulk write and Undo never ask: the person did not write
 * those, and a broken result is a bug or a risk, not a choice.
 */
class HeldWriteController(
    private val writer: ProjectWriter,
    private val store: HeldWrites,
    private val scope: CoroutineScope,
) {
    private val _offer = MutableStateFlow<HeldOffer?>(null)
    val offer: StateFlow<HeldOffer?> = _offer.asStateFlow()

    /** What to do once the change is made, set when the question is raised; lost with the process. */
    private var afterKept: ((WriteResult.Committed) -> Unit)? = null

    /** What to do after a change made when no screen is waiting for it (the app was restarted). */
    var afterRestored: (String, WriteResult.Committed) -> Unit = { _, _ -> }

    /** Holds [held] and asks. [afterKept] is what the screen that wrote it does once it is made. */
    fun ask(held: HeldWrite, afterKept: (WriteResult.Committed) -> Unit) {
        store.put(held)
        this.afterKept = afterKept
        _offer.value = HeldOffer(held, "Saving to ${File(held.relativePath).name}")
    }

    /** Brings back a question that was open when the app stopped, for [projectId]. */
    fun restore(projectId: String) {
        if (_offer.value != null) return
        val held = store.get(projectId) ?: return
        afterKept = null
        _offer.value = HeldOffer(held, "Saving to ${File(held.relativePath).name}")
    }

    /** Don't save: the ledger is as it was, which it already is. */
    fun discard() {
        val held = _offer.value?.held ?: return
        store.clear(held.projectId)
        afterKept = null
        _offer.value = null
    }

    /** Save anyway: the same change again, with its errors kept. */
    fun keep() {
        val current = _offer.value ?: return
        if (current.working || current.problem != null) return
        _offer.value = current.copy(working = true)
        scope.launch {
            val result = withContext(Dispatchers.IO) { writer.keepWithErrors(current.held) }
            val held = current.held
            when (result) {
                is WriteResult.Committed -> {
                    store.clear(held.projectId)
                    _offer.value = null
                    val run = afterKept
                    afterKept = null
                    if (run != null) run(result) else afterRestored(held.projectId, result)
                }
                is WriteResult.Refused -> {
                    // Stale, or the same change already made: nothing was written, and the person is
                    // told so rather than left with a dialog that does nothing.
                    store.clear(held.projectId)
                    _offer.value = current.copy(
                        working = false,
                        problem = "The file changed since. Nothing was saved. Open it again and make the change there.",
                    )
                }
                is WriteResult.RolledBack -> {
                    store.clear(held.projectId)
                    _offer.value = current.copy(working = false, problem = "Nothing was saved: ${result.reason}.")
                }
            }
        }
    }

    /** Close, after a problem was shown. */
    fun close() {
        _offer.value = null
    }

    companion object {
        /**
         * The writes the person made directly, in this app, on this phone: the only ones that may ask. Kept as
         * a list so adding one is a decision with a test, and not a side effect of a new caller.
         */
        val overridable: Set<String> = setOf(
            "quick-add", "add-directive", "edit-entry", "open-account", "edit-account-details", "delete-entry",
            "refile-document",
        )

        fun overridable(operation: String): Boolean = operation in overridable
    }
}
