package io.github.pintofi.beanbook.app.undo

import io.github.pintofi.beanbook.core.data.ProjectWriter
import io.github.pintofi.beanbook.core.data.WriteResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One write that can still be taken back, and the words for it (B97). */
data class UndoOffer(val projectId: String, val txnId: String, val what: String)

/**
 * Offers to take back the last write the person made.
 *
 * Backed by the durable snapshots the write protocol already takes (invariant 8), not by UI
 * state: the screen only shows the offer. At most one is open, replaced by every newer write
 * and dropped on switching project; [ProjectWriter.undoIfLatest] refuses if anything else has
 * been saved since, so an old offer can never undo the wrong thing.
 */
class UndoController(
    private val writer: ProjectWriter,
    private val scope: CoroutineScope,
) {
    private val _offer = MutableStateFlow<UndoOffer?>(null)
    val offer: StateFlow<UndoOffer?> = _offer.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    /** The outcome of the last undo, for the screen to say once. */
    val message: StateFlow<String?> = _message.asStateFlow()

    /** Offers to undo [result] if it committed; any other result clears a stale offer. */
    fun offerFor(projectId: String, result: WriteResult, what: String) {
        _offer.value = (result as? WriteResult.Committed)
            ?.let { UndoOffer(projectId, it.txnId, what) }
    }

    fun dismiss() {
        _offer.value = null
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Takes the offered write back, then calls [afterwards] (to reload the ledger). */
    fun undo(afterwards: () -> Unit) {
        val current = _offer.value ?: return
        _offer.value = null
        scope.launch {
            _message.value = when (val result = writer.undoIfLatest(current.projectId, current.txnId)) {
                is WriteResult.Committed -> "Undone: ${current.what}."
                is WriteResult.RolledBack -> "Not undone: ${result.reason}. Your ledger is unchanged."
                is WriteResult.Refused -> "Not undone: ${result.reason}."
            }
            afterwards()
        }
    }
}
