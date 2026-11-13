package io.github.pintofi.beanbook.app.packages

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.pintofi.beanbook.app.plugins.probeFailure
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
     * Called when packages on disk no longer match what the running interpreter imported.
     * Python answers an import once and caches the answer, so a package fetched now
     * is not importable until a new process looks for it.
     */
    private val onEngineStale: () -> Unit = {},
    /** Ends the engine process, so what follows runs in a fresh one. */
    private val restartEngine: suspend () -> Unit = {},
    /** Imports [modules] from [dir] as a fresh copy in the engine, and returns its answer as text. */
    private val probe: suspend (dir: File, modules: List<String>) -> String? = { _, _ -> null },
) {
    init {
        OnDemandPackages.recoverOnce(root)?.let { Diagnostics.i(TAG, it) }
    }

    private val swap get() = io.github.pintofi.beanbook.core.data.PackageSwap(root)

    private val _updates = MutableStateFlow<List<PackageUpdate>>(emptyList())
    val updates: StateFlow<List<PackageUpdate>> = _updates.asStateFlow()

    private val _checking = MutableStateFlow(false)
    val checking: StateFlow<Boolean> = _checking.asStateFlow()

    private val lastCheckedFile = File(root.parentFile ?: root, "${root.name}-last-checked")
    private val _lastChecked = MutableStateFlow(
        lastCheckedFile.takeIf { it.isFile }?.readText()?.trim()?.toLongOrNull(),
    )
    val lastChecked: StateFlow<Long?> = _lastChecked.asStateFlow()

    private val _canGoBack = MutableStateFlow(swap.hasPrevious())
    val canGoBack: StateFlow<Boolean> = _canGoBack.asStateFlow()

    /**
     * Asks PyPI and Chaquopy's index whether the packages that were fetched have newer versions.
     * Only on a tap; the answer is kept until the next check.
     */
    fun checkForUpdates() {
        if (!policy.allowsPackageDownload || _checking.value || _status.value !is PackagesStatus.Idle) return
        _checking.value = true
        _message.value = null
        scope.launch {
            val outcome = withContext(Dispatchers.IO) { fetcher().checkUpdates() }
            outcome
                .onSuccess { found ->
                    _updates.value = found
                    val now = System.currentTimeMillis()
                    runCatching { lastCheckedFile.writeText(now.toString()) }
                    _lastChecked.value = now
                    _message.value = if (found.isEmpty()) {
                        "The packages you fetched are up to date."
                    } else {
                        "Updates available for ${found.size} ${if (found.size == 1) "package" else "packages"}."
                    }
                }
                .onFailure { _message.value = "Couldn't check for package updates: ${it.message ?: "no answer"}." }
            _checking.value = false
        }
    }

    fun dismissUpdates() {
        _updates.value = emptyList()
    }

    /**
     * Installs the packages the last check found, keeping the earlier ones. The new packages are
     * built beside the folder in use, switched to, and tried in a fresh engine process; if they do not
     * import, or the app is stopped before they were proved, the earlier ones are back.
     */
    fun updatePackages() {
        val found = _updates.value
        if (found.isEmpty() || _status.value !is PackagesStatus.Idle) return
        _status.value = PackagesStatus.Fetching("updates")
        scope.launch {
            val message = io.github.pintofi.beanbook.app.work.KeepWorking.around(
                context,
                "Updating packages",
                page = io.github.pintofi.beanbook.app.OpenPage.PLUGINS,
                onUnprotected = { _message.value = io.github.pintofi.beanbook.app.work.KeepWorking.KEEP_OPEN },
            ) { applyAndProve(found) }
            _status.value = PackagesStatus.Idle
            _message.value = message
            refresh()
        }
    }

    private suspend fun applyAndProve(found: List<PackageUpdate>): String {
        val fetcher = fetcher()
        val applied = withContext(Dispatchers.IO) { fetcher.applyUpdates(found) }
        when (applied) {
            is FetchResult.Refused -> return applied.reason
            is FetchResult.Failed -> return "Nothing changed: ${applied.reason}. The packages you had are still in use."
            is FetchResult.Installed -> Unit
        }
        val modules = withContext(Dispatchers.IO) { fetcher.importNames(found.map { it.wheel }) }
        restartEngine()
        val reason = if (modules.isEmpty()) null else probeFailure(probe(root, modules))
        return if (reason == null) {
            withContext(Dispatchers.IO) { swap.proven() }
            _updates.value = emptyList()
            _canGoBack.value = true
            onEngineStale()
            Diagnostics.i(TAG, "package update kept: ${found.size}")
            "Updated ${found.size} ${if (found.size == 1) "package" else "packages"}. " +
                "The earlier ones are kept to go back to."
        } else {
            withContext(Dispatchers.IO) { swap.rollback() }
            restartEngine()
            _canGoBack.value = swap.hasPrevious()
            onEngineStale()
            Diagnostics.w(TAG, "package update did not import, rolled back: $reason")
            "The updated packages didn't load ($reason), so the earlier ones are back in use."
        }
    }

    /** Brings back the packages the last update replaced. Downloads nothing. */
    fun goBack() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { swap.rollback() }
            _canGoBack.value = swap.hasPrevious()
            _updates.value = emptyList()
            result
                .onSuccess {
                    onEngineStale()
                    _message.value = "Went back to the earlier packages."
                }
                .onFailure { _message.value = "Couldn't go back: ${it.message}" }
            refresh()
        }
    }

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
            // Held until the download ends, even if the user leaves the app.
            val asked = pending.request.split(',', ' ', '\n').map { it.trim() }.filter { it.isNotBlank() }
            val result = io.github.pintofi.beanbook.app.work.KeepWorking.around(
                context,
                "Installing ${pending.request}",
                page = io.github.pintofi.beanbook.app.OpenPage.PLUGINS,
                onUnprotected = { _message.value = io.github.pintofi.beanbook.app.work.KeepWorking.KEEP_OPEN },
            ) {
                withContext(Dispatchers.IO) {
                    val fetcher = fetcher()
                    fetcher.install(pending.resolution.wheels, asked).also {
                        if (it is FetchResult.Installed) fetcher.preloadNativeLibraries()
                    }
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
                val earlier = swap.previous
                val bytes = (root.walkTopDown() + earlier.walkTopDown()).filter { it.isFile }.sumOf { it.length() }
                root.deleteRecursively()
                earlier.deleteRecursively()
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
    fun ui(
        installed: List<String>,
        status: PackagesStatus,
        message: String?,
        updates: List<PackageUpdate> = emptyList(),
        checking: Boolean = false,
        canGoBack: Boolean = false,
        lastChecked: Long? = null,
    ) = PackagesUi(
        installed, status, message, ::request, ::confirm, ::cancel, ::removeAll,
        updates = updates, checking = checking, canCheck = policy.allowsPackageDownload,
        canGoBack = canGoBack, lastChecked = lastChecked, onCheck = ::checkForUpdates,
        onUpdate = ::updatePackages, onDismissUpdates = ::dismissUpdates, onGoBack = ::goBack,
    )

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "PackagesState"
    }
}

/** The packages section as the screen takes it, collected from this state's flows. */
@androidx.compose.runtime.Composable
fun PackagesState.collectUi(): PackagesUi {
    val installedNow by installed.collectAsStateWithLifecycle()
    val statusNow by status.collectAsStateWithLifecycle()
    val messageNow by message.collectAsStateWithLifecycle()
    val updatesNow by updates.collectAsStateWithLifecycle()
    val checkingNow by checking.collectAsStateWithLifecycle()
    val canGoBackNow by canGoBack.collectAsStateWithLifecycle()
    val lastCheckedNow by lastChecked.collectAsStateWithLifecycle()
    return ui(installedNow, statusNow, messageNow, updatesNow, checkingNow, canGoBackNow, lastCheckedNow)
}
