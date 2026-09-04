package dev.beancount.app.plugins

import android.content.Context
import android.net.Uri
import android.util.Log
import dev.beancount.core.data.InstallResult
import dev.beancount.core.data.InstalledPlugin
import dev.beancount.core.data.PluginIntegrity
import dev.beancount.core.data.PluginSafeMode
import dev.beancount.core.data.PluginStore
import dev.beancount.core.domain.PluginPolicy
import dev.beancount.core.domain.PluginVerdict
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One plugin as the screen needs to show it. */
data class PluginRow(
    val plugin: InstalledPlugin,
    val integrity: PluginIntegrity,
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
        refresh()
    }

    private fun store(): PluginStore? = projectRoot?.let { PluginStore(it) }

    private fun safe(): PluginSafeMode? = projectRoot?.let { PluginSafeMode(it) }

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val store = store() ?: return@withContext
                _safeMode.value = safe()?.isSafeMode == true
                _rows.value = store.list().map { PluginRow(it, store.verify(it)) }
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
        // B37: `installed()` already refuses to name those packages, but this is the path
        // that actually reaches Python, and handing the directory over is what turned a
        // half-copied file into an import error. Withheld rather than repaired here --
        // recovery belongs to the fetcher, on the next install.
        val packages = dev.beancount.app.packages.OnDemandPackages.root(context)
            .takeIf {
                plugins.isNotEmpty() &&
                    it.isDirectory &&
                    !dev.beancount.app.packages.OnDemandPackages.isIncomplete(it)
            }
            ?.absolutePath

        return (plugins + listOfNotNull(packages)).joinToString(",")
    }

    fun setSafeMode(on: Boolean) {
        scope.launch {
            withContext(Dispatchers.IO) { safe()?.isSafeMode = on }
            _safeMode.value = on
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
            withContext(Dispatchers.IO) { store()?.setEnabled(id, enabled) }
            refresh()
        }
    }

    fun remove(id: String) {
        scope.launch {
            withContext(Dispatchers.IO) { store()?.remove(id) }
            _message.value = "Removed. Its files are gone from this project."
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
                    val name = displayName.ifBlank { "plugin.py" }
                    val target = File(scratch, name)
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    } ?: return@withContext InstallResult.Failed("that file could not be opened")

                    store.install(target, policy, sourceLabel = name)
                } catch (denied: SecurityException) {
                    Log.w(TAG, "no permission for the picked document", denied)
                    InstallResult.Failed("this app was not given permission to read that file")
                } finally {
                    scratch.deleteRecursively()
                }
            }
            _message.value = describe(result)
            refresh()
        }
    }

    private fun describe(result: InstallResult?): String = when (result) {
        null -> "Open a project first."
        is InstallResult.Installed ->
            "Installed \"${result.plugin.name}\". It is off until you turn it on."
        is InstallResult.Failed -> "That could not be installed: ${result.reason}."
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
