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

    /** The catalogue shipped in the APK, less what cannot work on a phone; empty if the asset cannot be read. */
    val catalogue: PluginCatalogue by lazy {
        runCatching {
            context.assets.open("catalog/plugins.json").bufferedReader()
                .use { PluginCatalogue.parse(it.readText()).offered() }
        }.onFailure { Diagnostics.w(TAG, "the plugin catalogue could not be read", it) }
            .getOrDefault(PluginCatalogue(emptyList()))
    }

    /** Whether this edition can install from the catalogue at all. */
    val canInstall: Boolean get() = ArchiveFetcher.AVAILABLE && policy.allowsPackageDownload

    private val _installed = MutableStateFlow(installer.installed())
    val installed: StateFlow<Map<String, String>> = _installed.asStateFlow()

    /** For each installed plugin whose last update left an earlier version to go back to, that version. */
    private val _previous = MutableStateFlow(previousVersions())
    val previous: StateFlow<Map<String, String>> = _previous.asStateFlow()

    private fun previousVersions(): Map<String, String> =
        installer.installed().keys.mapNotNull { id -> installer.previousVersion(id)?.let { id to it } }.toMap()

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

    /** Replaces the installed [id] with the version this app carries, keeping the installed one to go back to. */
    fun update(id: String) = request(id, upgrade = true)

    /** Works out what installing [id] needs, and shows it. Downloads no plugin code. */
    fun request(id: String) = request(id, upgrade = false)

    private fun request(id: String, upgrade: Boolean) {
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
        if (upgrade && id !in installer.installed()) return
        _status.value = CatalogueStatus.Installing(id)
        _message.value = null
        scope.launch {
            // Resolving and installing both use the network and take a while; both are held
            // until they end, even if the user leaves the app or the screen goes off.
            io.github.pintofi.beanbook.app.work.KeepWorking.around(
                context,
                if (upgrade) "Updating ${entry.name}" else "Installing ${entry.name}",
                page = io.github.pintofi.beanbook.app.OpenPage.PLUGINS,
                onUnprotected = {
                    _message.value = io.github.pintofi.beanbook.app.work.KeepWorking.KEEP_OPEN
                },
            ) {
                val resolution = withContext(Dispatchers.IO) { resolve(entry) }
                install(entry, resolution, upgrade)
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
    private suspend fun install(entry: PluginCatalogue.Entry, resolution: Resolution, upgrade: Boolean) {
        // The installed version is only touched once the new one has passed its checks, and only then is
        // there anything to go back to: a failure before that must leave the installed plugin alone.
        var swapped = false
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val install = checkNotNull(entry.install)
                val archive = ArchiveFetcher().fetch(install.archive).getOrThrow()
                val site = if (upgrade) {
                    installer.upgradeCode(entry, archive).getOrThrow().also { swapped = true }
                } else {
                    installer.installCode(entry, archive).getOrThrow()
                }
                overlay.apply(entry, site)
                if (resolution.wheels.isNotEmpty()) {
                    when (val result = fetcher(entry.id).install(resolution.wheels)) {
                        is FetchResult.Installed -> Unit
                        is FetchResult.Refused -> error(result.reason)
                        is FetchResult.Failed -> error(result.reason)
                    }
                }
                installer.markInstalled(entry)
            }.onFailure {
                when {
                    !upgrade -> installer.remove(entry.id)
                    swapped -> installer.rollback(entry.id)
                }
            }
        }
        _status.value = CatalogueStatus.Idle
        Diagnostics.i(TAG, "catalogue install ${entry.id} -> ${outcome.isSuccess}")
        outcome
            .onSuccess {
                onEngineStale()
                val done = if (upgrade) "Updated" else "Installed"
                _message.value = if (resolution.unavailable.isEmpty()) {
                    "$done ${entry.name}."
                } else {
                    "$done ${entry.name}. Parts that need ${resolution.unavailable.joinToString()} " +
                        "won't work, because those aren't available for Android."
                }
            }
            .onFailure {
                val what = if (upgrade) "update" else "install"
                _message.value = "Couldn't $what ${entry.name}: ${it.message ?: "the download failed"}. " +
                    if (upgrade) "It is as it was. Check your connection and try again." else
                        "Check your connection and try again."
            }
        _installed.value = installer.installed()
        _previous.value = previousVersions()
    }

    /** Goes back to the version an update replaced. */
    fun rollback(id: String) {
        val entry = catalogue.entries.firstOrNull { it.id == id }
        scope.launch {
            val back = withContext(Dispatchers.IO) {
                val version = installer.previousVersion(id)
                installer.rollback(id).map { version }
            }
            back
                .onSuccess {
                    onEngineStale()
                    _message.value = "Went back to ${entry?.name ?: id} ${it.orEmpty()}.".replace("  ", " ")
                }
                .onFailure { _message.value = "Couldn't go back: ${it.message}" }
            _installed.value = installer.installed()
            _previous.value = previousVersions()
        }
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
            _previous.value = previousVersions()
        }
    }

    fun ui(
        installed: Map<String, String>,
        status: CatalogueStatus,
        message: String?,
        previous: Map<String, String> = emptyMap(),
    ) = CatalogueUi(
        entries = catalogue.entries,
        installed = installed,
        previous = previous,
        status = status,
        message = message,
        canInstall = canInstall,
        onInstall = ::request,
        onRemove = ::remove,
        onUpdate = ::update,
        onRollback = ::rollback,
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
    val previousNow by previous.collectAsStateWithLifecycle()
    return ui(installedNow, statusNow, messageNow, previousNow)
}

sealed interface CatalogueStatus {
    data object Idle : CatalogueStatus
    data class Installing(val id: String) : CatalogueStatus
}

/** Everything the catalogue section of the Plugins screen takes. */
data class CatalogueUi(
    val entries: List<PluginCatalogue.Entry>,
    val installed: Map<String, String>,
    val previous: Map<String, String>,
    val status: CatalogueStatus,
    val message: String?,
    val canInstall: Boolean,
    val onInstall: (String) -> Unit,
    val onRemove: (String) -> Unit,
    val onUpdate: (String) -> Unit,
    val onRollback: (String) -> Unit,
) {
    companion object {
        val EMPTY = CatalogueUi(
            emptyList(), emptyMap(), emptyMap(), CatalogueStatus.Idle, null, false, {}, {}, {}, {},
        )
    }
}
