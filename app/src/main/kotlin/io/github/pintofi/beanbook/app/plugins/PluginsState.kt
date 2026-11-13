package io.github.pintofi.beanbook.app.plugins

import android.content.Context
import android.net.Uri
import android.util.Log
import io.github.pintofi.beanbook.app.packages.ArchiveFetcher
import io.github.pintofi.beanbook.app.packages.UpdateChecker
import io.github.pintofi.beanbook.core.data.InstallRecord
import io.github.pintofi.beanbook.core.data.InstallResult
import io.github.pintofi.beanbook.core.data.UpstreamRef
import io.github.pintofi.beanbook.core.data.UpstreamRelease
import io.github.pintofi.beanbook.core.data.UpstreamReleases
import io.github.pintofi.beanbook.core.data.InstalledPlugin
import io.github.pintofi.beanbook.core.data.PluginIntegrity
import io.github.pintofi.beanbook.core.data.PluginSafeMode
import io.github.pintofi.beanbook.core.data.PluginIndexDamaged
import io.github.pintofi.beanbook.core.data.PluginStore
import io.github.pintofi.beanbook.core.data.ReplaceResult
import io.github.pintofi.beanbook.core.domain.PluginPolicy
import io.github.pintofi.beanbook.core.domain.PluginVerdict
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One plugin as the screen needs to show it. */
data class PluginRow(
    val plugin: InstalledPlugin,
    val integrity: PluginIntegrity,
    /** The copy a replace set aside, which "Go back" restores; null when there is none. */
    val previous: InstalledPlugin? = null,
)

/**
 * Installed plugins for the open project, and everything the screen does to them.
 *
 * Nothing here decides policy. The edition's [PluginPolicy] is passed in and applied by
 * `PluginStore.install`, so this class cannot quietly become a second place where the Play
 * boundary is enforced — or forgotten.
 */
class PluginsState(
    private val context: Context,
    private val policy: PluginPolicy,
    private val scope: CoroutineScope,
    /**
     * Called when the interpreter's idea of what is installed has gone out of date.
     *
     * Installing, removing or turning a plugin on changes what a load *would* import and
     * nothing about what the running Python has already imported.
     */
    private val onEngineStale: () -> Unit = {},
) {
    private var projectRoot: File? = null

    private val _rows = MutableStateFlow<List<PluginRow>>(emptyList())
    val rows: StateFlow<List<PluginRow>> = _rows.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    private val _safeMode = MutableStateFlow(false)
    val safeMode: StateFlow<Boolean> = _safeMode.asStateFlow()

    fun openProject(root: File) {
        projectRoot = root
        // Before anything is handed to the engine: a replace a killed process left half done is
        // finished or undone, so the plugin that loads is one that was approved.
        scope.launch { withContext(Dispatchers.IO) { store()?.recover() }; refresh() }
    }

    /** Where the copy a replace sets aside is kept: outside the project, so backups and mirrors skip it. */
    private fun keepRoot(root: File): File = File(context.filesDir, "plugin-keep/${root.name}")

    private fun store(): PluginStore? = projectRoot?.let { PluginStore(it, keepRoot(it)) }

    private fun safe(): PluginSafeMode? = projectRoot?.let { PluginSafeMode(it) }

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val store = store() ?: return@withContext
                _safeMode.value = safe()?.isSafeMode == true
                _rows.value = store.list().map { PluginRow(it, store.verify(it), store.previous(it.id)) }
                // A list that cannot be read looks like "no plugins", which it is not.
                store.indexProblem()?.let { _message.value = it }
            }
        }
    }

    /**
     * The directories to make importable for a ledger load.
     *
     * Empty in safe mode, and empty for anything the user has turned off — which is the whole
     * point of the toggle, and only actually true because a plugin's modules are evicted
     * after every load (`plugins.activated`).
     */
    fun enabledDirectories(): String {
        val root = projectRoot ?: return ""
        val store = PluginStore(root)
        val plugins = PluginSafeMode(root).pluginsToLoad(store)
            .map { store.directoryFor(it).absolutePath }

        // Packages the user fetched on demand (docs/on-demand-packages.md). They are
        // libraries rather than plugins — nothing imports them on its own — so they are
        // appended to whatever the plugins need rather than listed as plugins.
        //
        // Under safe mode this stays empty along with everything else. Not because a library
        // is dangerous, but because safe mode means "load nothing of mine", and a numpy that
        // shadowed something would make that promise false.
        //
        // A root whose last merge was interrupted is withheld too. It is the other half of
        // the same rule: `installed()` already refuses to name those packages, but this is the path
        // that actually reaches Python, and handing the directory over is what turned a
        // half-copied file into an import error. Withheld rather than repaired here --
        // recovery belongs to the fetcher, on the next install.
        // Not conditional on a plugin being installed. It used to be, and that is why
        // fetching a package to satisfy a *script* changed nothing: the script runs in the
        // engine process, the package sat in a directory that was never put on `sys.path`,
        // and the same `ModuleNotFoundError` came back -- so the install looked like it had
        // failed. Safe mode still withholds it, along with everything else.
        val packages = io.github.pintofi.beanbook.app.packages.OnDemandPackages.root(context)
            .also { io.github.pintofi.beanbook.app.packages.OnDemandPackages.recoverOnce(it) }
            .takeIf {
                !PluginSafeMode(root).isSafeMode &&
                    it.isDirectory &&
                    !io.github.pintofi.beanbook.app.packages.OnDemandPackages.isIncomplete(it)
            }
            ?.absolutePath

        // Plugins installed from the catalogue, each folder holding the plugin and what it
        // needs. Withheld under safe mode like everything else.
        val catalogue = if (PluginSafeMode(root).isSafeMode) {
            emptyList()
        } else {
            val installer = io.github.pintofi.beanbook.core.data.CatalogueInstaller(CatalogueState.rootOf(context))
            installer.installed().keys.map { installer.siteFor(it).absolutePath }
        }

        return (plugins + catalogue + listOfNotNull(packages)).joinToString(",")
    }

    fun setSafeMode(on: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { safe()?.isSafeMode = on }
            _safeMode.value = on
            onEngineStale()
            _message.value = if (on) {
                "Safe mode is on. No plugins load, and your choices are kept."
            } else {
                "Safe mode is off. Your plugins will load again the next time the ledger does."
            }
            refresh()
        }
    }

    fun setEnabled(id: String, enabled: Boolean) {
        scope.launch {
            val damaged = withContext(Dispatchers.IO) {
                runCatching { store()?.setEnabled(id, enabled) }.exceptionOrNull() as? PluginIndexDamaged
            }
            if (damaged != null) {
                _message.value = damaged.message
            } else {
                Diagnostics.i(TAG, "plugin $id enabled=$enabled")
                onEngineStale()
            }
            refresh()
        }
    }

    fun remove(id: String) {
        scope.launch {
            val damaged = withContext(Dispatchers.IO) {
                runCatching { store()?.remove(id) }.exceptionOrNull() as? PluginIndexDamaged
            }
            if (damaged != null) {
                _message.value = damaged.message
            } else {
                _message.value = "Removed. Its files are gone from this project."
                Diagnostics.i(TAG, "plugin $id removed")
                onEngineStale()
            }
            refresh()
        }
    }

    /**
     * Copies what the user picked out of SAF and installs it.
     *
     * The picked document is copied to a scratch file first: the pre-flight scan reads a real
     * file, and a `content://` URI is not one. The scratch copy goes whatever happens,
     * including when the install is refused.
     */
    fun installFrom(uri: Uri, displayName: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val store = store() ?: return@withContext null
                val scratch = File(context.cacheDir, "plugin-install-${System.nanoTime()}")
                    .apply { mkdirs() }
                try {
                    val name = io.github.pintofi.beanbook.app.storage.safeDisplayName(displayName, "plugin.py")
                    val target = File(scratch, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext InstallResult.Failed("that file could not be opened")

                    // Not overwriting: a file with the name of one already added is a newer version of
                    // it, and that has a button which keeps the copy being replaced.
                    store.install(target, policy, sourceLabel = name, overwrite = false)
                } catch (denied: SecurityException) {
                    Log.w(TAG, "no permission for the picked document", denied)
                    InstallResult.Failed("this app was not given permission to read that file")
                } finally {
                    scratch.deleteRecursively()
                }
            }
            _message.value = describe(result)
            Diagnostics.i(TAG, "plugin install from \"$displayName\" -> ${result?.javaClass?.simpleName}")
            if (result is InstallResult.Installed) onEngineStale()
            refresh()
        }
    }

    /**
     * Replaces the added plugin [id] with the file the user picked, keeping the copy it replaces.
     * The same copy-to-scratch and the same checks as [installFrom]; the new one starts off.
     */
    fun replaceFrom(id: String, uri: Uri, displayName: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                val store = store() ?: return@withContext null
                val scratch = File(context.cacheDir, "plugin-replace-${System.nanoTime()}").apply { mkdirs() }
                try {
                    val name = io.github.pintofi.beanbook.app.storage.safeDisplayName(displayName, "plugin.py")
                    val target = File(scratch, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext ReplaceResult.Failed("that file could not be opened")
                    store.replace(id, target, policy, sourceLabel = name)
                } catch (denied: SecurityException) {
                    Log.w(TAG, "no permission for the picked document", denied)
                    ReplaceResult.Failed("this app was not given permission to read that file")
                } finally {
                    scratch.deleteRecursively()
                }
            }
            _message.value = when (result) {
                null -> "Open a project first."
                is ReplaceResult.Replaced ->
                    "Replaced \"${result.plugin.name}\". It is off until you turn it on, and the copy you had is kept."
                ReplaceResult.Same -> "That is the file already added, so nothing changed."
                is ReplaceResult.Failed -> "Nothing changed: ${result.reason}."
                is ReplaceResult.Refused -> "Nothing changed.\n\n" + refusal(result.verdict)
            }
            Diagnostics.i(TAG, "plugin $id replace -> ${result?.javaClass?.simpleName}")
            if (result is ReplaceResult.Replaced) onEngineStale()
            refresh()
        }
    }

    // -- a source for a plugin added from a file ----------------------------------------------

    /** Whether this edition can look anything up for a plugin added from a file. */
    val canCheckSources: Boolean get() = UpdateChecker.AVAILABLE && ArchiveFetcher.AVAILABLE

    private val checker = UpdateChecker()

    /** Newer versions found for plugins added from a file, by plugin id, from the last check. */
    private val _found = MutableStateFlow<Map<String, UpstreamRelease>>(emptyMap())
    val found: StateFlow<Map<String, UpstreamRelease>> = _found.asStateFlow()

    /** The plugin a check or a replace from its source is running for. */
    private val _working = MutableStateFlow<String?>(null)
    val working: StateFlow<String?> = _working.asStateFlow()

    /**
     * Says where newer versions of [id] are looked for: a project on PyPI. Nothing is fetched until the
     * person checks, and nothing about the plugin was tested with this app.
     */
    fun attachSource(id: String, project: String) {
        val name = project.trim()
        if (!canCheckSources) return
        if (!Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,99}").matches(name)) {
            _message.value = "That is not a PyPI project name."
            return
        }
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { store()?.setUpstream(id, UpstreamRef.normalise(name)) } }
            _found.value = _found.value - id
            _message.value = "Newer versions of this plugin will be looked for on PyPI as $name."
            refresh()
        }
    }

    fun forgetSource(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { runCatching { store()?.setUpstream(id, null) } }
            _found.value = _found.value - id
            refresh()
        }
    }

    /** Asks PyPI for the newest version of [id]'s project. Only when the person taps. */
    fun checkSource(id: String) {
        val plugin = rows.value.firstOrNull { it.plugin.id == id }?.plugin ?: return
        val project = plugin.upstream ?: return
        if (!canCheckSources || _working.value != null) return
        _working.value = id
        _message.value = null
        scope.launch {
            val record = InstallRecord(
                name = plugin.name, version = plugin.upstreamVersion.orEmpty(), source = "pypi", upstream = project,
                archive = "", sha256 = "", verified = "not-recorded", installedAtEpochMs = plugin.installedAtEpochMs,
            )
            // With no version on record every release is newer: the person is shown the latest and
            // decides, rather than the app guessing what a hand-added file is.
            val outcome = withContext(Dispatchers.IO) { checker.check(record, plugin.upstreamVersion ?: "0") }
            outcome
                .onSuccess { release ->
                    _found.value = if (release == null) _found.value - id else _found.value + (id to release)
                    _message.value = when {
                        release == null -> "${plugin.name} is up to date with $project on PyPI."
                        plugin.upstreamVersion == null ->
                            "The latest $project on PyPI is ${release.version}. It was not tested with this app."
                        else -> "$project ${release.version} is available (you have ${plugin.upstreamVersion}). " +
                            "It was not tested with this app."
                    }
                }
                .onFailure { _message.value = "Couldn't check $project: ${it.message ?: "no answer"}." }
            _working.value = null
        }
    }

    /**
     * Replaces [id] with the version [checkSource] found: downloaded from PyPI's file host, its published
     * SHA-256 checked, then through the same read, policy check and kept earlier copy as any other replace.
     */
    fun replaceFromSource(id: String) {
        val release = _found.value[id] ?: return
        val plugin = rows.value.firstOrNull { it.plugin.id == id }?.plugin ?: return
        if (!canCheckSources || _working.value != null) return
        _working.value = id
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val store = store() ?: error("Open a project first")
                    val bytes = ArchiveFetcher().fetch(release.archive).getOrThrow()
                    val digest = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) }
                    check(release.sha256 == null || release.sha256 == digest) {
                        "the download is not the file PyPI published"
                    }
                    val scratch = File(context.cacheDir, "plugin-source-${System.nanoTime()}").apply { mkdirs() }
                    try {
                        val wheel = File(scratch, release.archive.substringAfterLast('/')).apply { writeBytes(bytes) }
                        store.replace(id, wheel, policy, sourceLabel = "pypi: ${plugin.upstream} ${release.version}")
                    } finally {
                        scratch.deleteRecursively()
                    }
                }
            }
            result
                .onSuccess { replaced ->
                    if (replaced is ReplaceResult.Replaced) {
                        withContext(Dispatchers.IO) { store()?.setUpstream(id, plugin.upstream, release.version) }
                        _found.value = _found.value - id
                        onEngineStale()
                        _message.value = "Replaced ${plugin.name} with ${plugin.upstream} ${release.version}. " +
                            "It is off until you turn it on, and the copy you had is kept."
                    } else {
                        _message.value = "Nothing changed: " + when (replaced) {
                            is ReplaceResult.Failed -> replaced.reason
                            is ReplaceResult.Refused -> refusal(replaced.verdict)
                            else -> "that is the file already added"
                        } + "."
                    }
                }
                .onFailure { _message.value = "Nothing changed: ${it.message ?: "the download failed"}." }
            _working.value = null
            refresh()
        }
    }

    /** Brings back the copy the last replace of [id] set aside. */
    fun goBack(id: String) {
        scope.launch {
            val result = withContext(Dispatchers.IO) { store()?.goBack(id) }
            _message.value = when (result) {
                null -> "Open a project first."
                is ReplaceResult.Replaced -> "Went back to the earlier copy of \"${result.plugin.name}\"."
                is ReplaceResult.Failed -> "Nothing changed: ${result.reason}."
                else -> "Nothing changed."
            }
            Diagnostics.i(TAG, "plugin $id go back -> ${result?.javaClass?.simpleName}")
            if (result is ReplaceResult.Replaced) onEngineStale()
            refresh()
        }
    }

    private fun describe(result: InstallResult?): String = when (result) {
        null -> "Open a project first."
        is InstallResult.Installed ->
            "Installed \"${result.plugin.name}\". It is off until you turn it on."
        is InstallResult.Failed -> "That could not be installed: ${result.reason}."
        is InstallResult.Exists ->
            "\"${result.plugin.name}\" is already added. To use a newer file, tap Replace with a file on its card; " +
                "the copy you have now is kept."
        is InstallResult.Refused -> refusal(result.verdict)
    }

    private fun refusal(verdict: PluginVerdict.Rejected): String =
        verdict.reasons.joinToString("\n\n") { it.describe() }

    fun clearMessage() {
        _message.value = null
    }

    private companion object {
        const val TAG = "PluginsState"
    }
}
