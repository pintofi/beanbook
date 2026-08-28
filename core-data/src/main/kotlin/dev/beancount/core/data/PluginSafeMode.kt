package dev.beancount.core.data

import java.io.File

/**
 * What happened last time plugins were loaded, and what to do about it.
 *
 * A plugin that raises is caught, reported, and left enabled — failing is not misbehaving,
 * and disabling something for one bad import would be a poor trade. A plugin that takes the
 * **whole process** down is different: nothing runs afterwards to report it, the app dies on
 * every launch, and the user has no way in to turn anything off. That is the loop safe mode
 * exists to break.
 *
 * The marker is written from Python immediately before each import and flushed, so it
 * survives `os._exit`, an abort, or a segfault in a C extension
 * (`engine-local/src/main/python/plugins.py`).
 */
class PluginSafeMode(projectRoot: File) {

    private val pluginsDir = File(projectRoot, "plugins")
    private val markerFile = File(pluginsDir, "loading.marker")
    private val safeModeFile = File(pluginsDir, "safe-mode")

    /** The plugin and module that were mid-import when the process died, if one was. */
    data class Interrupted(val pluginId: String, val module: String)

    /** What a recovery did, so the app can say it plainly. */
    data class Recovery(val pluginId: String, val module: String, val disabled: Boolean) {
        fun describe(): String = if (disabled) {
            "\"$pluginId\" stopped the app while it was loading ($module), so it has been " +
                "turned off. Everything else is running normally. You can turn it back on " +
                "once you have a version that works."
        } else {
            "The app stopped last time while loading \"$pluginId\" ($module). That plugin " +
                "is no longer installed, so nothing needed turning off."
        }
    }

    fun journalPath(): String = markerFile.absolutePath

    fun beginLoad() {
        pluginsDir.mkdirs()
    }

    /**
     * @return what was mid-import when the process last died, or null after a clean run.
     */
    fun interrupted(): Interrupted? {
        if (!markerFile.isFile) return null
        val lines = markerFile.readText().split("\n")
        val pluginId = lines.getOrNull(0)?.trim().orEmpty()
        val module = lines.getOrNull(1)?.trim().orEmpty()
        if (pluginId.isEmpty() && module.isEmpty()) return null
        return Interrupted(pluginId, module)
    }

    /**
     * Turns off whatever was loading when the process died, and clears the marker.
     *
     * The marker is cleared **whatever happens**, including when the named plugin has since
     * been removed. Leaving it would mean recovering from the same crash forever, which is
     * its own kind of stuck.
     */
    fun recoverFromCrash(store: PluginStore): Recovery? {
        val interrupted = interrupted() ?: return null
        val known = store.list().any { it.id == interrupted.pluginId }
        if (known) store.setEnabled(interrupted.pluginId, false)
        markerFile.delete()
        return Recovery(interrupted.pluginId, interrupted.module, disabled = known)
    }

    /**
     * Safe mode proper: every plugin held back, whether or not it is enabled.
     *
     * Separate from disabling plugins one at a time, because the user's own choices should
     * survive it. Coming out of safe mode restores exactly what they had.
     */
    var isSafeMode: Boolean
        get() = safeModeFile.isFile
        set(value) {
            pluginsDir.mkdirs()
            if (value) safeModeFile.writeText("on") else safeModeFile.delete()
        }

    /** The plugins that should actually load right now. */
    /**
     * The plugins that may be handed to the engine.
     *
     * Enabled is not sufficient. Plugins live at `<project>/plugins/`, inside what auto-mirror
     * syncs and what a restore writes, so their bytes can change without the app ever seeing
     * them — and the policy that judged them ran at install time, on different bytes. A file
     * dropped into the user's mirrored folder was pulled in and loaded unexamined; in the Play
     * edition that is a route to the native code `PlayPluginPolicy` exists to refuse.
     *
     * `verify` already computed this and nothing consulted it. Now the load gate does: a
     * plugin whose contents no longer match what was installed is held back until the user
     * installs it again, which is the point at which a policy gets to judge it (R4).
     */
    fun pluginsToLoad(store: PluginStore): List<InstalledPlugin> =
        if (isSafeMode) {
            emptyList()
        } else {
            store.list()
                .filter { it.enabled }
                .filter { store.verify(it) is PluginIntegrity.Unchanged }
        }
}
