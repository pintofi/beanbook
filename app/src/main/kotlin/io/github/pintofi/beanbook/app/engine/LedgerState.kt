package io.github.pintofi.beanbook.app.engine

import android.util.Log
import io.github.pintofi.beanbook.core.domain.LedgerSnapshot
import io.github.pintofi.beanbook.engine.local.LedgerReader
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics
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

    private val _loading = MutableStateFlow(false)

    /**
     * True while a read is in flight, so a pull-to-refresh can show a spinner for as long as
     * the work actually takes. That is not a formality: a reload is seconds rather than
     * milliseconds on a large ledger, far past the point where a user assumes nothing
     * happened (B26).
     */
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    private var job: Job? = null

    /** The last path asked for, so [reload] can ask for it again. */
    private var lastPath: String? = null

    /**
     * Which load is the current one.
     *
     * A cancelled coroutine still runs its `finally`, so without this the load that was
     * *replaced* would clear the indicator belonging to the load that replaced it, and the
     * spinner would vanish while the work carried on.
     *
     * **Not covered by a test, deliberately.** Whether the superseded load's `finally` runs
     * before or after the replacement finishes is a race, so a test asserting the end state
     * passes either way — one was written, shown to pass with this check removed, and
     * deleted rather than left to imply cover it did not give (B26). Widening the window
     * enough to observe it reliably would mean a fixture slow enough to make the suite slow.
     */
    private var generation = 0

    /**
     * Where the enabled plugins live, and whether the bridge is allowed, at the moment of a
     * load. Supplied by the caller rather than held here: which plugins are on can change
     * between loads, and a stale answer would either run something the user turned off or
     * miss something they turned on.
     */
    fun interface PluginContext {
        /** Comma-separated directories, empty when there is nothing to load. */
        fun directories(): String
    }

    var pluginContext: PluginContext = PluginContext { "" }
    var allowBridge: Boolean = true

    private val _dateRange = MutableStateFlow("" to "")

    /** The inclusive ISO date range the open snapshot describes; empty means the whole file. */
    val dateRange: StateFlow<Pair<String, String>> = _dateRange.asStateFlow()

    /**
     * Narrows every figure to a period, the way Fava's time filter does (B53).
     *
     * A reload rather than a client-side filter: balances at cost and at market value are
     * Beancount's to compute, and recomputing them here from the journal would be a second,
     * worse implementation of the thing the engine exists to do.
     */
    fun setDateRange(from: String, to: String) {
        if (_dateRange.value == from to to) return
        _dateRange.value = from to to
        lastPath?.let { load(it) }
    }

    fun load(ledgerPath: String) {
        lastPath = ledgerPath
        job?.cancel()
        _loading.value = true
        val started = ++generation
        job = scope.launch {
            try {
                ensureDocumentRoots(ledgerPath)
                reader.read(
                    ledgerPath,
                    pluginDirs = pluginContext.directories(),
                    allowBridge = allowBridge,
                    dateFrom = _dateRange.value.first,
                    dateTo = _dateRange.value.second,
                )
                    .onSuccess { snapshot ->
                        _failure.value = null
                        _snapshot.value = snapshot
                        Diagnostics.i(
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
                        Diagnostics.w(TAG, "ledger read failed", error)
                    }
            } finally {
                if (started == generation) _loading.value = false
            }
        }
    }

    /**
     * Creates the folders `option "documents"` names before Beancount looks for them (B79).
     *
     * A ledger with no receipts yet used to load with "Document root does not exist" on every
     * screen, and in Fava too. Logged when it does something, because a folder appearing in a
     * project should be explainable.
     */
    private suspend fun ensureDocumentRoots(ledgerPath: String) {
        val created = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            io.github.pintofi.beanbook.core.data.DocumentRoots.ensure(java.io.File(ledgerPath))
        }
        if (created.isNotEmpty()) Diagnostics.i(TAG, "created documents folders: $created")
    }

    /**
     * Reads the open ledger again from disk.
     *
     * The app has no file watcher, so nothing notices a mirror pull, a restore, or an edit
     * made on a desktop in a synced folder. Before this, the only ways to see such a change
     * were to switch project and switch back, or to restart the app (B26).
     *
     * Does nothing when no ledger is open, rather than guessing at a path.
     */
    fun reload() {
        lastPath?.let(::load)
    }

    private companion object {
        const val TAG = "LedgerState"
    }
}
