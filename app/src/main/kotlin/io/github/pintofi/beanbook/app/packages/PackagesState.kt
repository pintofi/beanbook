package io.github.pintofi.beanbook.app.packages

import android.content.Context
import android.util.Log
import io.github.pintofi.beanbook.core.domain.PluginPolicy
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Python packages the user has asked for, fetched on demand (docs/on-demand-packages.md).
 *
 * The app bundles Beancount, Fava and their closure and nothing else. Some plugins need more
 * — beangrow wants numpy, pandas and matplotlib; three of the four catalogued Fava extensions
 * want `pandas`, `PyYAML` or `packaging` — and bundling all of that for everyone would add
 * far more to every install than most people would ever use.
 *
 * Two properties this class exists to keep:
 *
 * 1. **Nothing is downloaded before the user has seen what and how much.** [request] resolves
 *    the closure and stops at [PackagesStatus.Confirming]; [confirm] is what fetches. A
 *    progress bar that appears after the decision is not a decision.
 * 2. **The Play edition never reaches the network.** Its `WheelFetcher` refuses, and the
 *    screen does not offer the control at all — but the refusal is the enforcement, not the
 *    hidden button.
 */
class PackagesState(
    private val context: Context,
    private val policy: PluginPolicy,
    private val scope: CoroutineScope,
    /**
     * Where fetched packages live: app-private, outside any project, and deliberately not in
     * the project tree — they are not the user's data and must never be exported with a
     * ledger or reach a backup.
     *
     * A parameter so a test can own its own directory. The default is the one the engine is
     * given, and tests that assert on the shared one are not testing themselves: the first
     * version of `PackagesStateTest` asserted that nothing had been fetched into it, and
     * failed because `BeangrowOnDeviceTest` had put 143 MB there earlier in the same run.
     */
    val root: File = OnDemandPackages.root(context),
    /**
     * Called when packages on disk no longer match what the running interpreter imported
     * (B49). Python answers an import once and caches the answer, so a package fetched now
     * is not importable until a new process looks for it.
     */
    private val onEngineStale: () -> Unit = {},
) {

    private fun fetcher() = WheelFetcher(
        root,
        allowed = policy.allowsPackageDownload,
        allowNative = policy.allowsNativeCode,
        assets = context.assets,
    )

    private val _installed = MutableStateFlow<List<String>>(emptyList())
    val installed: StateFlow<List<String>> = _installed.asStateFlow()

    private val _status = MutableStateFlow<PackagesStatus>(PackagesStatus.Idle)
    val status: StateFlow<PackagesStatus> = _status.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    fun refresh() {
        scope.launch {
            _installed.value = withContext(Dispatchers.IO) { fetcher().installed().sorted() }
        }
    }

    /**
     * Work out what fetching [names] would mean, and show it. Downloads nothing.
     */
    fun request(names: String) {
        val wanted = names.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotBlank() }
        if (wanted.isEmpty()) return
        // Asked before resolving so the Play edition says why in its own words, rather than
        // reporting the refusal as a failure to work out a dependency closure.
        if (!policy.allowsPackageDownload) {
            _message.value = "This edition cannot download packages. The version from the " +
                "project's own site can."
            return
        }
        _status.value = PackagesStatus.Resolving(names)
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { fetcher().resolve(wanted) }
            outcome.onSuccess { resolution ->
                if (resolution.wheels.isEmpty()) {
                    _status.value = PackagesStatus.Idle
                    _message.value = if (!policy.allowsPackageDownload) {
                        "This edition cannot download packages."
                    } else {
                        "Nothing to install: " + resolution.unavailable.joinToString(", ") +
                            " could not be found for this device."
                    }
                } else {
                    _status.value = PackagesStatus.Confirming(names, resolution)
                }
            }.onFailure {
                _status.value = PackagesStatus.Idle
                _message.value = "Could not work out what ${wanted.joinToString(", ")} " +
                    "needs: ${it.message ?: it::class.simpleName}"
            }
        }
    }

    /** Discard a pending confirmation without fetching anything. */
    fun cancel() {
        _status.value = PackagesStatus.Idle
    }

    /** Fetch the closure the user has just been shown and agreed to. */
    fun confirm() {
        val pending = _status.value as? PackagesStatus.Confirming ?: return
        _status.value = PackagesStatus.Fetching(pending.request)
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val fetcher = fetcher()
                fetcher.install(pending.resolution.wheels).also {
                    if (it is FetchResult.Installed) fetcher.preloadNativeLibraries()
                }
            }
            _status.value = PackagesStatus.Idle
            Diagnostics.i(TAG, "package fetch ${pending.request} -> ${result.javaClass.simpleName}")
            if (result is FetchResult.Installed) onEngineStale()
            _message.value = when (result) {
                is FetchResult.Installed ->
                    "Installed ${result.wheels.size} packages, ${result.bytes / 1_000_000} MB. " +
                        "Plugins can use them now."
                is FetchResult.Refused -> result.reason
                is FetchResult.Failed -> "Nothing was installed: ${result.reason}"
            }
            refresh()
        }
    }

    /**
     * Remove everything fetched. Deliberately all-or-nothing: the packages were resolved as a
     * closure, so removing one of them would leave the rest importable and broken.
     */
    fun removeAll() {
        scope.launch {
            val freed = withContext(Dispatchers.IO) {
                val bytes = root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                root.deleteRecursively()
                bytes
            }
            Log.i(TAG, "removed fetched packages, freed ${freed / 1_000_000} MB")
            onEngineStale()
            _message.value = "Removed the fetched packages, freeing ${freed / 1_000_000} MB. " +
                "Anything that needed them will say so the next time your ledger loads."
            refresh()
        }
    }

    /**
     * The bundle the screen takes, built here so the activity does not have to know which of
     * these are values and which are this object's own methods.
     */
    fun ui(installed: List<String>, status: PackagesStatus, message: String?) =
        PackagesUi(installed, status, message, ::request, ::confirm, ::cancel, ::removeAll)

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "PackagesState"
    }
}
