package io.github.pintofi.beanbook.app.ui

/**
 * Whether an installed plugin loaded with the ledger (device audit 2026-10-10: installed plugins
 * showed a name and a Remove button, and nothing said that `fava-portfolio-returns` fails to
 * import every time the ledger loads).
 *
 * A slot for the card to fill, not a judgement the screen makes: the status comes from what the
 * engine and Fava reported when the ledger loaded ([pluginStatusFor]). Where nothing has been
 * reported a plugin is [Unknown], and an unknown status draws nothing -- the card
 * never claims a plugin works when nobody has said so.
 */
sealed interface PluginStatus {
    /** It loaded with the ledger, with no error naming it. */
    data object Loaded : PluginStatus

    /** It was named by the ledger and did not load; [reason] is what Beancount or Fava said. */
    data class Failed(val reason: String) : PluginStatus

    /** Nothing has said either way. */
    data object Unknown : PluginStatus
}

/**
 * What the last load said about a plugin that provides [modules] (B97 data, design-direction §7).
 *
 * Two sources, both from the load itself, never a guess:
 * - a Fava extension's import result, from [extensionHealth] (module to the import error, or
 *   null when it imports) -- Fava shows these only on its own Errors page;
 * - a Beancount plugin's import failure, which Beancount reports as an ordinary ledger error
 *   naming the module (`Error importing "beancount_interpolate.depreciate"`), among [errors].
 *
 * A module the ledger does not name, or any module while safe mode holds plugins back, says
 * nothing either way. Any failing module makes the plugin [PluginStatus.Failed] with the first
 * reason; at least one known-good module and no failure make it [PluginStatus.Loaded]; no data
 * at all is [PluginStatus.Unknown], which draws nothing.
 */
fun pluginStatusFor(
    modules: List<String>,
    extensionHealth: Map<String, String?>,
    ledgerPlugins: List<String>,
    errors: List<String>,
    safeMode: Boolean,
): PluginStatus {
    if (safeMode || modules.isEmpty()) return PluginStatus.Unknown
    var known = false
    modules.forEach { module ->
        if (module in extensionHealth) {
            known = true
            extensionHealth[module]?.let { return PluginStatus.Failed(it) }
        }
        if (module in ledgerPlugins) {
            known = true
            errors.firstOrNull { it.contains("\"$module\"") && it.contains("import", ignoreCase = true) }
                ?.let { return PluginStatus.Failed(it) }
        }
    }
    return if (known) PluginStatus.Loaded else PluginStatus.Unknown
}

/**
 * One Python package fetched for plugins, as a person would name it: "numpy 2.1.3", not the
 * three directories (`numpy`, `numpy-2.1.3.dist-info`, `numpy.libs`) it unpacks into.
 */
data class FetchedPackage(val name: String, val version: String?)

/**
 * The fetched-packages folder listing, grouped into packages (device audit 2026-10-10: the list
 * was a raw directory listing of twenty rows, `certifi-2026.7.22.dist-info` and `curl-cffi.libs`
 * among them).
 *
 * UI only: what is on disk is unchanged, and "Remove fetched packages" still removes all of it.
 * A `.dist-info` folder gives a package its published name and version and is not shown itself;
 * `.libs` and `.data` folders, private `_modules` and compiled `.so` files belong to the package
 * beside them; caches and `bin` are not packages. A package with no `.dist-info` is listed by
 * its folder name with no version, so
 * nothing on disk is ever hidden entirely.
 */
fun groupFetched(entries: List<String>): List<FetchedPackage> {
    val versions = mutableMapOf<String, Pair<String, String>>() // key -> (published name, version)
    val modules = linkedMapOf<String, String>() // key -> folder name
    entries.forEach { raw ->
        val entry = raw.trim().trimEnd('/')
        when {
            // Private modules and compiled extension files belong to the package that ships them.
            entry.isEmpty() || entry.startsWith("_") || entry.startsWith(".") || entry in NOT_PACKAGES -> Unit
            entry.endsWith(".so") || entry.endsWith(".pyd") || entry.endsWith(".pth") -> Unit
            entry.endsWith(".dist-info") || entry.endsWith(".egg-info") -> {
                val stem = entry.substringBeforeLast('.')
                val cut = stem.indexOfVersion()
                if (cut > 0) {
                    val name = stem.substring(0, cut - 1)
                    versions[key(name)] = name to stem.substring(cut)
                } else {
                    versions.putIfAbsent(key(stem), stem to "")
                }
            }
            entry.endsWith(".libs") || entry.endsWith(".data") -> Unit
            else -> {
                val module = entry.removeSuffix(".py")
                modules.putIfAbsent(key(module), module)
            }
        }
    }
    val keys = (versions.keys + modules.keys).toSortedSet()
    return keys.map { k ->
        val published = versions[k]
        FetchedPackage(
            name = published?.first ?: modules.getValue(k),
            version = published?.second?.ifBlank { null },
        )
    }.sortedBy { it.name.lowercase() }
}

/** PEP 503 normalisation: case, and runs of `-`, `_` and `.`, do not distinguish two names. */
private fun key(name: String): String = name.lowercase().replace(Regex("[-_.]+"), "-")

/** Where the version starts in "name-1.2.3": the first `-` followed by a digit, plus one. */
private fun String.indexOfVersion(): Int {
    for (i in 0 until length - 1) {
        if (this[i] == '-' && this[i + 1].isDigit()) return i + 1
    }
    return -1
}

private val NOT_PACKAGES = setOf("bin", "include", "share", "lib")
