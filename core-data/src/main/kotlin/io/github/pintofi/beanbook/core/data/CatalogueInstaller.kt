package io.github.pintofi.beanbook.core.data

import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipInputStream

/**
 * Installs catalogue plugins one by one, each in a folder of its own.
 *
 * `<root>/<id>/site` holds the plugin's code *and* the Python packages it needs, so removing
 * one plugin is deleting one folder: nothing it brought is shared with another plugin, and
 * nothing another plugin brought goes with it. The cost is that two plugins needing the same
 * library each carry a copy, which is the right trade for "install and remove independently".
 *
 * This class does the part that is the same everywhere -- verify the archive, take the code out
 * of it, record what is installed, remove it. Downloading is not here: it is the Expert
 * edition's to do, and the Play edition never downloads code.
 */
class CatalogueInstaller(private val root: File) {

    /** The folder the engine puts on `sys.path` for [id]. */
    fun siteFor(id: String): File = File(folderFor(id), "site")

    private fun folderFor(id: String): File {
        require(ID.matches(id)) { "\"$id\" is not a catalogue id" }
        return File(root, id)
    }

    /** Installed entries and the version each was installed at. Half-finished installs are not. */
    fun installed(): Map<String, String> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && ID.matches(it.name) }
            .mapNotNull { folder ->
                val version = File(folder, VERSION_FILE).takeIf { it.isFile }?.readText()?.trim()
                version?.let { folder.name to it }
            }
            .toMap()

    /**
     * Puts [entry]'s code from [archive] into its site folder.
     *
     * Refuses an archive whose SHA-256 is not the catalogue's, a member that would land outside
     * the folder, and an archive holding none of the paths the catalogue names. Staged and then
     * moved, so a failure leaves nothing behind that looks installed.
     */
    fun installCode(entry: PluginCatalogue.Entry, archive: ByteArray): Result<File> = runCatching {
        val install = checkNotNull(entry.install) { "${entry.name} has no install source" }
        val digest = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        check(digest == install.sha256) {
            "the download of ${entry.name} does not match the catalogue " +
                "(expected ${install.sha256.take(12)}…, got ${digest.take(12)}…)"
        }
        val folder = folderFor(entry.id)
        val staging = File(root, ".${entry.id}-staging")
        staging.deleteRecursively()
        val site = File(staging, "site").apply { mkdirs() }
        var copied = 0
        ZipInputStream(archive.inputStream()).use { zip ->
            generateSequence { zip.nextEntry }.forEach { member ->
                val name = member.name.removePrefix(install.strip)
                if (member.isDirectory || name == member.name && install.strip.isNotEmpty()) return@forEach
                val target = targetFor(name, install) ?: return@forEach
                val file = File(site, target).canonicalFile
                check(file.path.startsWith(site.canonicalPath + File.separator)) {
                    "archive member escapes the install folder: ${member.name}"
                }
                file.parentFile?.mkdirs()
                file.outputStream().use { zip.copyTo(it) }
                copied++
            }
        }
        check(copied > 0) { "the archive for ${entry.name} holds none of ${install.paths}" }
        folder.deleteRecursively()
        check(staging.renameTo(folder)) { "could not move ${entry.name} into place" }
        siteFor(entry.id)
    }.onFailure { File(root, ".${entry.id}-staging").deleteRecursively() }

    /** Records that [entry] is fully installed, after its packages are in as well. */
    fun markInstalled(entry: PluginCatalogue.Entry) {
        File(folderFor(entry.id), VERSION_FILE).writeText(entry.install?.version.orEmpty())
    }

    /** Deletes [id] and everything it brought. Returns the bytes freed. */
    fun remove(id: String): Long {
        val folder = folderFor(id)
        val bytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        folder.deleteRecursively()
        return bytes
    }

    /** Where an archive member goes inside `site`, or null when it is not part of the code. */
    private fun targetFor(name: String, install: PluginCatalogue.Install): String? {
        for (path in install.paths) {
            if (path.isEmpty()) {
                // The code is the whole archive root, installed as a package of its own name.
                val importName = install.importName ?: return null
                return "$importName/$name"
            }
            if (name == path) return path.substringAfterLast('/')
            if (name.startsWith("$path/")) return path.substringAfterLast('/') + "/" + name.removePrefix("$path/")
        }
        return null
    }

    private companion object {
        val ID = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,80}")
        const val VERSION_FILE = "installed-version"
    }
}
