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

    /**
     * Installed entries and the version each was installed at. Half-finished installs are not.
     *
     * The first call for a folder in this process first runs [recover], before anything here can be
     * in the middle of an update.
     */
    fun installed(): Map<String, String> {
        recoverOnce()
        return listInstalled()
    }

    private fun listInstalled(): Map<String, String> =
        root.listFiles().orEmpty()
            .filter { it.isDirectory && ID.matches(it.name) }
            .mapNotNull { folder ->
                val version = File(folder, VERSION_FILE).takeIf { it.isFile }?.readText()?.trim()
                version?.let { folder.name to it }
            }
            .toMap()

    /**
     * Puts back what a killed update or rollback left half done.
     *
     * An update moves the installed folder to `.<id>-previous`, moves the new one in, and only then
     * (after the plugin's packages are fetched) writes the version file that makes it count as
     * installed; a rollback moves the current folder to `.<id>-failed` and the earlier one in. A
     * process killed in between used to leave a plugin that was in neither list, so it vanished from
     * the screen and was never handed to the engine, with its working version sitting in a hidden
     * folder. Now, for each id:
     *
     *  - there is an earlier version and the current folder is missing or never got its version
     *    file: the update did not finish, so the earlier version is put back and the unfinished one
     *    is deleted;
     *  - staging and failed folders belong to no finished step and are deleted.
     *
     * A finished update (both folders have a version file) is left as it is: that is a version to go
     * back to. Returns the ids it put back.
     */
    fun recover(): List<String> {
        val restored = mutableListOf<String>()
        root.listFiles().orEmpty().filter { it.isDirectory && it.name.startsWith(".") }.forEach { hidden ->
            if (!hidden.name.endsWith("-previous")) return@forEach
            val id = hidden.name.removePrefix(".").removeSuffix("-previous")
            if (!ID.matches(id)) return@forEach
            val folder = File(root, id)
            val versionFile = File(folder, VERSION_FILE)
            val unfinished = !folder.exists() || (folder.isDirectory && !versionFile.isFile)
            if (unfinished && File(hidden, VERSION_FILE).isFile) {
                folder.deleteRecursively()
                if (hidden.renameTo(folder)) restored += id
            }
        }
        root.listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith(".") }
            .filter { it.name.endsWith("-staging") || it.name.endsWith("-failed") }
            .forEach { it.deleteRecursively() }
        return restored
    }

    private fun recoverOnce() {
        if (recoveredRoots.add(root.absolutePath)) recover()
    }

    /**
     * Puts [entry]'s code from [archive] into its site folder.
     *
     * Refuses an archive whose SHA-256 is not the catalogue's, a member that would land outside
     * the folder, and an archive holding none of the paths the catalogue names. Staged and then
     * moved, so a failure leaves nothing behind that looks installed.
     */
    fun installCode(entry: PluginCatalogue.Entry, archive: ByteArray): Result<File> = runCatching {
        val staging = stage(entry, archive)
        val folder = folderFor(entry.id)
        folder.deleteRecursively()
        check(staging.renameTo(folder)) { "could not move ${entry.name} into place" }
        siteFor(entry.id)
    }.onFailure { File(root, ".${entry.id}-staging").deleteRecursively() }

    /**
     * Puts a newer [entry] in place of the installed one, keeping the installed one to go back to.
     *
     * The same checks as [installCode]. The installed folder is moved aside to `.<id>-previous`, not
     * deleted, and the new one is moved in; if that fails the installed one is put back. One previous
     * version is kept: the one before it is deleted when the next update lands. Whatever follows (the
     * plugin's packages are fetched next) should end in [markInstalled], or in [rollback] if it fails.
     */
    fun upgradeCode(entry: PluginCatalogue.Entry, archive: ByteArray): Result<File> = runCatching {
        val staging = stage(entry, archive)
        val folder = folderFor(entry.id)
        val previous = previousFolder(entry.id)
        check(folder.isDirectory) { "${entry.name} is not installed, so there is nothing to update" }
        previous.deleteRecursively()
        check(folder.renameTo(previous)) { "could not set the installed ${entry.name} aside" }
        if (!staging.renameTo(folder)) {
            previous.renameTo(folder)
            error("could not move the new ${entry.name} into place; the installed one is as it was")
        }
        siteFor(entry.id)
    }.onFailure { File(root, ".${entry.id}-staging").deleteRecursively() }

    /** Whether an update of [id] left the version it replaced to go back to. */
    fun hasPrevious(id: String): Boolean = previousFolder(id).isDirectory

    /** The version [rollback] would bring back, or null when there is none. */
    fun previousVersion(id: String): String? =
        File(previousFolder(id), VERSION_FILE).takeIf { it.isFile }?.readText()?.trim()

    /**
     * Brings back the version an update replaced, and drops the one that replaced it.
     *
     * The two folders are exchanged, not copied over each other, and a failed exchange is undone, so
     * an installed plugin is never left half there.
     */
    fun rollback(id: String): Result<Unit> = runCatching {
        val folder = folderFor(id)
        val previous = previousFolder(id)
        check(previous.isDirectory) { "there is no earlier version of $id to go back to" }
        val failed = File(root, ".$id-failed")
        failed.deleteRecursively()
        if (folder.exists()) check(folder.renameTo(failed)) { "could not set the current $id aside" }
        if (!previous.renameTo(folder)) {
            failed.renameTo(folder)
            error("could not bring the earlier $id back; the current one is as it was")
        }
        failed.deleteRecursively()
    }

    /** Deletes the version kept to go back to. */
    fun discardPrevious(id: String) {
        previousFolder(id).deleteRecursively()
    }

    private fun previousFolder(id: String): File {
        require(ID.matches(id)) { "\"$id\" is not a catalogue id" }
        return File(root, ".$id-previous")
    }

    /** Verifies [archive] and takes the named code out of it into a staging folder, which it returns. */
    private fun stage(entry: PluginCatalogue.Entry, archive: ByteArray): File {
        val install = checkNotNull(entry.install) { "${entry.name} has no install source" }
        val digest = MessageDigest.getInstance("SHA-256").digest(archive).joinToString("") { "%02x".format(it) }
        check(digest == install.sha256) {
            "the download of ${entry.name} does not match the catalogue " +
                "(expected ${install.sha256.take(12)}…, got ${digest.take(12)}…)"
        }
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
        return staging
    }

    /** Records that [entry] is fully installed, after its packages are in as well. */
    fun markInstalled(entry: PluginCatalogue.Entry) {
        // Written beside and renamed over, so a kill never leaves an empty version file that
        // reads as an installed plugin of no version.
        val target = File(folderFor(entry.id), VERSION_FILE)
        val temporary = File(target.parentFile, "$VERSION_FILE.tmp")
        temporary.writeText(entry.install?.version.orEmpty())
        check(temporary.renameTo(target)) { "could not record that ${entry.name} is installed" }
    }

    /** Deletes [id], everything it brought, and the version kept to go back to. Returns the bytes freed. */
    fun remove(id: String): Long {
        val folder = folderFor(id)
        val bytes = folder.walkTopDown().filter { it.isFile }.sumOf { it.length() } +
            previousFolder(id).walkTopDown().filter { it.isFile }.sumOf { it.length() }
        folder.deleteRecursively()
        previousFolder(id).deleteRecursively()
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

        /** Folders [recover] has already run for in this process. */
        val recoveredRoots: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()
    }
}
