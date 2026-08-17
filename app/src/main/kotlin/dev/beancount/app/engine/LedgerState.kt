package dev.beancount.app.engine

import android.util.Log
import dev.beancount.core.domain.LedgerSnapshot
import dev.beancount.engine.local.LedgerReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Holds the parsed ledger for the native surfaces.
 *
 * Parsing happens on IO inside [LedgerReader]; the main thread only ever receives the
 * finished snapshot. A 25k-transaction ledger takes over a second to parse on a mid-range
 * device (docs/milestones/m3.md), so this is not a theoretical concern.
 */
class LedgerState(
    private val reader: LedgerReader,
    private val scope: CoroutineScope,
) {
    private val _snapshot = MutableStateFlow<LedgerSnapshot?>(null)
    val snapshot: StateFlow<LedgerSnapshot?> = _snapshot.asStateFlow()

    private val _failure = MutableStateFlow<String?>(null)
    val failure: StateFlow<String?> = _failure.asStateFlow()

    private var job: Job? = null

    fun load(ledgerPath: String) {
        job?.cancel()
        job = scope.launch {
            reader.read(ledgerPath)
                .onSuccess { snapshot ->
                    _failure.value = null
                    _snapshot.value = snapshot
                    Log.i(
                        TAG,
                        "ledger loaded entries=${snapshot.entryCount} " +
                            "accounts=${snapshot.accounts.size} errors=${snapshot.errors.size} " +
                            "load_ms=${snapshot.loadMillis}",
                    )
                }
                .onFailure { error ->
                    // A ledger that cannot be parsed at all is different from a ledger
                    // with errors; the latter still produces a snapshot.
                    _failure.value = error.message ?: error.javaClass.simpleName
                    Log.w(TAG, "ledger read failed", error)
                }
        }
    }

    private companion object {
        const val TAG = "LedgerState"
    }
}
