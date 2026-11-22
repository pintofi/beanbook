package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.PluginPolicy
import io.github.pintofi.beanbook.core.domain.PluginPreflight
import io.github.pintofi.beanbook.core.domain.PluginVerdict
import io.github.pintofi.beanbook.core.domain.judge
import java.io.File

/**
 * What a restore or an import does about plugins that arrive inside a project folder: none of them
 * arrives turned on.
 *
 * A plugin is code, and the list that says which ones run (`plugins/installed.json`) travels with the
 * project, together with the checksum it is compared against. A backup from another device, another
 * edition or somebody else would otherwise bring a plugin that is already on and already passes its
 * own check, and it would run at the next load. Turning one on is its own decision
 * (docs/threat-model.md 3.2), so every restored plugin is off, and each is judged again under this
 * edition's policy so one it does not allow can be said so instead of being turned on later.
 *
 * Also removed: the safe-mode flag and the crash marker, which describe the device they were made on.
 */
object RestoredPlugins {

    /** [turnedOff] were on in the backup; [notAllowed] are refused by this edition's policy. */
    data class Held(val turnedOff: List<String>, val notAllowed: List<String>) {
        val any: Boolean get() = turnedOff.isNotEmpty() || notAllowed.isNotEmpty()

        /** The sentence for the person, or null when there was nothing to say. */
        fun describe(): String? {
            if (!any) return null
            val off = if (turnedOff.isEmpty()) {
                null
            } else {
                (if (turnedOff.size == 1) "1 plugin" else "${turnedOff.size} plugins") +
                    " came back turned off (" + turnedOff.joinToString(", ") + "). A plugin is code: " +
                    "turn one on in Plugins only when you trust where it came from."
            }
            val refused = if (notAllowed.isEmpty()) {
                null
            } else {
                notAllowed.joinToString(", ") + (if (notAllowed.size == 1) " is" else " are") +
                    " not allowed in this edition and cannot be turned on."
            }
            return listOfNotNull(off, refused).joinToString(" ")
        }
    }

    /**
     * Applies the rules above to the project folder [projectRoot] before it is put in place. Without a
     * [policy] nothing is judged, but everything is still turned off.
     */
    fun hold(projectRoot: File, policy: PluginPolicy?): Held {
        val pluginsDir = File(projectRoot, "plugins")
        File(pluginsDir, "loading.marker").delete()
        File(pluginsDir, "safe-mode").delete()
        val store = PluginStore(projectRoot)
        // A list that cannot be read loads nothing, and is left exactly as it is for the person to fix.
        if (store.indexProblem() != null) return Held(emptyList(), emptyList())
        val wasOn = store.turnAllOff().map { it.name }
        val refused = store.list().filter { plugin ->
            val scan = runCatching { PluginPreflight.scan(store.directoryFor(plugin)) }.getOrNull()
            scan != null && policy != null && policy.judge(scan) is PluginVerdict.Rejected
        }.map { it.name }
        return Held(wasOn, refused)
    }
}
