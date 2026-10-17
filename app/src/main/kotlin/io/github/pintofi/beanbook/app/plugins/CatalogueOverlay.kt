package io.github.pintofi.beanbook.app.plugins

import android.content.res.AssetManager
import io.github.pintofi.beanbook.app.diagnostics.Diagnostics
import io.github.pintofi.beanbook.core.data.PluginCatalogue
import java.io.File

/**
 * Applies `assets/overlays/<package>-<version>/` to a plugin installed from the catalogue.
 *
 * The overlays were written for packages the wheel fetcher brings in, and the fetcher applies
 * them. A plugin installed from the catalogue takes a different route -- its code comes from
 * its own archive -- so the overlay was never applied to it: `fava-portfolio-returns` vendors
 * beangrow, the vendored copy imports SciPy, SciPy has no Android build, and the install ended
 * with "Parts that need scipy could not be installed" for a file the app already has a
 * replacement for.
 *
 * Version-matched, like the fetcher's: an overlay for 2.7.0 is applied to 2.7.0 and to nothing
 * else, because an overlay that applies to whatever happens to be installed is a way to break a
 * package quietly.
 */
internal class CatalogueOverlay(private val assets: AssetManager) {

    /**
     * Copies this entry's overlay over its installed code, and returns the files replaced.
     *
     * Failure is reported, not thrown: the plugin is installed either way, and the overlay
     * only decides whether one of its files needs a package this device cannot have.
     */
    fun apply(entry: PluginCatalogue.Entry, site: File): List<String> {
        val prefix = directoryFor(entry) ?: return emptyList()
        val replaced = mutableListOf<String>()
        files(prefix).forEach { relative ->
            if (relative == REMOVES) return@forEach
            runCatching {
                val target = File(site, relative).canonicalFile
                check(target.path.startsWith(site.canonicalPath + File.separator)) {
                    "overlay file escapes the install folder: $relative"
                }
                target.parentFile?.mkdirs()
                val bytes = assets.open("$prefix/$relative").use { it.readBytes() }
                // Only what actually changes is reported, so "re-applied" means a repair and
                // not a start-up that rewrote identical files.
                if (!target.isFile || !target.readBytes().contentEquals(bytes)) {
                    target.writeBytes(bytes)
                    replaced += relative
                }
            }.onFailure { Diagnostics.w(TAG, "overlay file $relative not applied", it) }
        }
        if (replaced.isNotEmpty()) Diagnostics.i(TAG, "overlay applied to ${entry.id}: $replaced")
        return replaced
    }

    /**
     * The requirements this entry's overlay makes unnecessary, from its own `REMOVES.txt`.
     *
     * Read from beside the file that justifies them rather than listed in code, for the same
     * reason the fetcher reads them there: an exclusion away from its reason is unverifiable.
     */
    fun removes(entry: PluginCatalogue.Entry): Set<String> {
        val prefix = directoryFor(entry) ?: return emptySet()
        val text = runCatching {
            assets.open("$prefix/$REMOVES").bufferedReader().use { it.readText() }
        }.getOrNull() ?: return emptySet()
        return text.lineSequence()
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { it.lowercase().replace('_', '-') }
            .toSet()
    }

    /** The overlay directory for this entry, if the APK carries one at its exact version. */
    private fun directoryFor(entry: PluginCatalogue.Entry): String? {
        val install = entry.install ?: return null
        val names = buildList {
            install.importName?.let { add(it) }
            install.paths.firstOrNull { it.isNotEmpty() }?.let { add(it.substringAfterLast('/')) }
            add(entry.id.replace('-', '_'))
        }
        return names.distinct()
            .map { "overlays/$it-${install.version}" }
            .firstOrNull { files(it).isNotEmpty() }
    }

    /** Every file under [prefix], as paths relative to it. Assets have no recursive listing. */
    private fun files(prefix: String): List<String> {
        val here = runCatching { assets.list(prefix) }.getOrNull().orEmpty()
        return here.flatMap { name ->
            val child = "$prefix/$name"
            val deeper = files(child)
            if (deeper.isEmpty()) listOf(name) else deeper.map { "$name/$it" }
        }
    }

    private companion object {
        const val TAG = "CatalogueOverlay"
        const val REMOVES = "REMOVES.txt"
    }
}
