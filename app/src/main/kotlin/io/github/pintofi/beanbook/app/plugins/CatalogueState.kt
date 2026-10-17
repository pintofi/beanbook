package io.github.pintofi.beanbook.app.plugins

import android.content.Context
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics
import io.github.pintofi.beanbook.app.packages.ArchiveFetcher
import io.github.pintofi.beanbook.app.packages.FetchResult
import io.github.pintofi.beanbook.app.packages.Resolution
import io.github.pintofi.beanbook.app.packages.WheelFetcher
import io.github.pintofi.beanbook.core.data.CatalogueInstaller
import io.github.pintofi.beanbook.core.data.PluginCatalogue
import io.github.pintofi.beanbook.core.domain.PluginPolicy
import java.io.File
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The curated plugin catalogue on the Plugins screen: install one, remove one.
 *
 * Each plugin is installed with the Python packages it needs into a folder of its own
 * ([CatalogueInstaller]), so installing or removing one never touches another. [request]
 * installs at once and reports only the outcome.
 *
 * The Play edition lists the catalogue and installs nothing from it: its [ArchiveFetcher] and
 * `WheelFetcher` both refuse, and the screen says to add a plugin from a file instead.
 */
class CatalogueState(
    private val context: Context,
    private val policy: PluginPolicy,
    private val scope: CoroutineScope,
    val root: File = rootOf(context),
    private val onEngineStale: () -> Unit = {},
) {
    private val installer = CatalogueInstaller(root)
    private val overlay = CatalogueOverlay(context.assets)

    /** The catalogue shipped in the APK; empty if the asset cannot be read. */
    val catalogue: PluginCatalogue by lazy {
        runCatching {
            context.assets.open("catalog/plugins.json").bufferedReader().use { PluginCatalogue.parse(it.readText()) }
        }.onFailure { Diagnostics.w(TAG, "the plugin catalogue could not be read", it) }
            .getOrDefault(PluginCatalogue(emptyList()))
    }

    /** Whether this edition can install from the catalogue at all. */
    val canInstall: Boolean get() = ArchiveFetcher.AVAILABLE && policy.allowsPackageDownload

    private val _installed = MutableStateFlow(installer.installed())
    val installed: StateFlow<Map<String, String>> = _installed.asStateFlow()

    private val _status = MutableStateFlow<CatalogueStatus>(CatalogueStatus.Idle)
    val status: StateFlow<CatalogueStatus> = _status.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        reapplyOverlays()
    }

    /**
     * Applies the shipped overlays to plugins installed before they existed.
     *
     * An overlay was only applied at install time, so `fava-portfolio-returns` installed
     * earlier kept the SciPy import SciPy-free code now replaces, and kept failing with "No
     * module named 'scipy'" until it was removed and installed again. Applying is a copy of
     * files the APK carries, version-matched, and idempotent: running it for an install that
     * already has them changes nothing.
     */
    private fun reapplyOverlays() {
        runCatching {
            installer.installed().forEach { (id, version) ->
                val entry = catalogue.entries.firstOrNull { it.id == id } ?: return@forEach
                if (entry.install?.version != version && version.isNotEmpty()) return@forEach
                val replaced = overlay.apply(entry, installer.siteFor(id))
                if (replaced.isNotEmpty()) {
                    Diagnostics.i(TAG, "overlay re-applied to $id")
                    // The ledger snapshot carries which extensions import, and it is cached
                    // against the ledger's files, not the plugin's: a repaired plugin kept
                    // showing the failure it had been repaired for.
                    File(context.cacheDir, "ledger-snapshots").deleteRecursively()
                }
            }
        }.onFailure { Diagnostics.w(TAG, "overlays could not be re-applied", it) }
    }

    /** The folders to put on `sys.path`: one per installed plugin. */
    fun directories(): List<String> =
        installer.installed().keys.map { installer.siteFor(it).absolutePath }

    private fun fetcher(id: String) = WheelFetcher(
        installer.siteFor(id),
        allowed = policy.allowsPackageDownload,
        allowNative = policy.allowsNativeCode,
        assets = context.assets,
    )

    /** Works out what installing [id] needs, and shows it. Downloads no plugin code. */
    fun request(id: String) {
        val entry = catalogue.entries.firstOrNull { it.id == id } ?: return
        if (!canInstall) {
            _message.value = "This edition does not download plugins. Add ${entry.name} from a " +
                "file, or use the Expert edition, which installs it from here."
            return
        }
        if (entry.install == null) {
            _message.value = "${entry.name} has no source the app can install from."
            return
        }
        // Straight to installing: a confirmation listing download sources and package
        // names asked people to judge detail they had no way to judge. What they see is the
        // progress and, if it fails, why.
        // One install at a time: a second tap while the first runs would race it in the same
        // folder, and the failure path of either one removes the plugin.
        if (_status.value is CatalogueStatus.Installing) return
        _status.value = CatalogueStatus.Installing(id)
        _message.value = null
        scope.launch {
            // Resolving and installing both use the network and take a while; both are held
            // until they end, even if the user leaves the app or the screen goes off.
            io.github.pintofi.beanbook.app.work.KeepWorking.around(
                context,
                "Installing ${entry.name}",
                onUnprotected = {
                    _message.value = io.github.pintofi.beanbook.app.work.KeepWorking.KEEP_OPEN
                },
            ) {
                val resolution = withContext(Dispatchers.IO) { resolve(entry) }
                install(entry, resolution)
            }
        }
    }

    /**
     * Each requirement resolved on its own, so one with no Android build is recorded as
     * unavailable rather than failing the others -- the same partial answer the packages
     * section gives (docs/on-demand-packages.md).
     */
    private fun resolve(entry: PluginCatalogue.Entry): Resolution {
        val wheels = LinkedHashMap<String, io.github.pintofi.beanbook.app.packages.Wheel>()
        val unavailable = mutableListOf<String>()
        // What the overlay has already removed from the code is not fetched and not reported
        // as missing: the file that imported it is replaced during this same install.
        val removed = overlay.removes(entry)
        entry.install?.requires.orEmpty()
            .filterNot { it.lowercase().replace('_', '-') in removed }
            .forEach { requirement ->
                fetcher(entry.id).resolve(listOf(requirement))
                    .onSuccess { result ->
                        result.wheels.forEach { wheels.putIfAbsent(it.name, it) }
                        unavailable += result.unavailable
                    }
                    .onFailure { unavailable += requirement }
            }
        return Resolution(wheels.values.toList(), unavailable.distinct().sorted())
    }

    /** Downloads, verifies and installs [entry] and what it needs. */
    private suspend fun install(entry: PluginCatalogue.Entry, resolution: Resolution) {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val install = checkNotNull(entry.install)
                val archive = ArchiveFetcher().fetch(install.archive).getOrThrow()
                val site = installer.installCode(entry, archive).getOrThrow()
                overlay.apply(entry, site)
                if (resolution.wheels.isNotEmpty()) {
                    when (val result = fetcher(entry.id).install(resolution.wheels)) {
                        is FetchResult.Installed -> Unit
                        is FetchResult.Refused -> error(result.reason)
                        is FetchResult.Failed -> error(result.reason)
                    }
                }
                installer.markInstalled(entry)
            }.onFailure { installer.remove(entry.id) }
        }
        _status.value = CatalogueStatus.Idle
        Diagnostics.i(TAG, "catalogue install ${entry.id} -> ${outcome.isSuccess}")
        outcome
            .onSuccess {
                onEngineStale()
                _message.value = if (resolution.unavailable.isEmpty()) {
                    "Installed ${entry.name}."
                } else {
                    "Installed ${entry.name}. Parts that need ${resolution.unavailable.joinToString()} " +
                        "won't work, because those aren't available for Android."
                }
            }
            .onFailure {
                _message.value = "Couldn't install ${entry.name}: ${it.message ?: "the download failed"}. " +
                    "Check your connection and try again."
            }
        _installed.value = installer.installed()
    }

    /** Removes [id] and every package it brought, and nothing else. */
    fun remove(id: String) {
        val entry = catalogue.entries.firstOrNull { it.id == id }
        scope.launch {
            val freed = withContext(Dispatchers.IO) { installer.remove(id) }
            onEngineStale()
            _message.value = "Removed ${entry?.name ?: id}."
            Diagnostics.i(TAG, "catalogue remove $id freed ${freed / 1_000} kB")
            _installed.value = installer.installed()
        }
    }

    fun ui(installed: Map<String, String>, status: CatalogueStatus, message: String?) = CatalogueUi(
        entries = catalogue.entries,
        installed = installed,
        status = status,
        message = message,
        canInstall = canInstall,
        onInstall = ::request,
        onRemove = ::remove,
    )

    companion object {
        private const val TAG = "CatalogueState"

        /** Where catalogue plugins live: app-private, outside every project, never backed up. */
        fun rootOf(context: Context): File = File(context.filesDir, "catalogue")
    }
}

/** The catalogue as the screen takes it, collected from this state's flows. */
@androidx.compose.runtime.Composable
fun CatalogueState.collectUi(): CatalogueUi {
    val installedNow by installed.collectAsStateWithLifecycle()
    val statusNow by status.collectAsStateWithLifecycle()
    val messageNow by message.collectAsStateWithLifecycle()
    return ui(installedNow, statusNow, messageNow)
}

sealed interface CatalogueStatus {
    data object Idle : CatalogueStatus
    data class Installing(val id: String) : CatalogueStatus
}

/** Everything the catalogue section of the Plugins screen takes. */
data class CatalogueUi(
    val entries: List<PluginCatalogue.Entry>,
    val installed: Map<String, String>,
    val status: CatalogueStatus,
    val message: String?,
    val canInstall: Boolean,
    val onInstall: (String) -> Unit,
    val onRemove: (String) -> Unit,
) {
    companion object {
        val EMPTY = CatalogueUi(emptyList(), emptyMap(), CatalogueStatus.Idle, null, false, {}, {})
    }
}
