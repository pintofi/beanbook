package io.github.pintofi.beanbook.app.plugins

import java.io.File
import java.security.MessageDigest

/** One script the user kept, with what is needed to say where it came from. */
data class SavedScript(
    val id: String,
    val name: String,
    val savedAtEpochMs: Long,
    val sha256: String,
    val file: File,
)

/**
 * The scripts the user has run, kept so they can be run again.
 *
 * Picking a file through SAF grants a URI to *this* process for *this* launch, so a script
 * used weekly had to be found in the picker every single time -- and the copy the runner made
 * was deleted the moment the run ended. Keeping it is the difference between a feature and a
 * demonstration.
 *
 * App-private storage, outside any project: a script is not the user's ledger data, and it
 * must not travel in an export, a backup or a mirror. It is also **code the user chose to
 * trust**, so each one keeps its name, the day it was saved, and a checksum -- the same three
 * facts the plugin list shows, for the same reason.
 */
class SavedScripts(stateDir: File) {

    private val root = File(stateDir, "scripts")

    fun list(): List<SavedScript> = root.listFiles()
        .orEmpty()
        .filter { it.isFile && it.extension == "py" }
        .map { file ->
            SavedScript(
                id = file.name,
                name = file.nameWithoutExtension,
                savedAtEpochMs = file.lastModified(),
                sha256 = sha256(file),
                file = file,
            )
        }
        .sortedBy { it.name.lowercase() }

    /**
     * Keeps [source] under [name], replacing an earlier script of the same name.
     *
     * Replacing rather than versioning: a person who picks `check.py` again has a new
     * `check.py`, and keeping both under invented names would leave them choosing between
     * two rows that claim to be the same script.
     */
    fun save(source: File, name: String): SavedScript {
        root.mkdirs()
        val target = File(root, fileNameFor(name))
        source.copyTo(target, overwrite = true)
        return SavedScript(target.name, target.nameWithoutExtension, target.lastModified(), sha256(target), target)
    }

    fun find(id: String): SavedScript? = list().firstOrNull { it.id == id }

    fun remove(id: String) {
        // Resolved against the store rather than taken as a path: the id comes back from the
        // UI, and a name with `..` in it would otherwise reach outside this directory.
        val target = File(root, File(id).name)
        if (target.parentFile == root) target.delete()
    }

    private fun fileNameFor(name: String): String {
        val cleaned = name.removeSuffix(".py")
            .map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it == '.') it else '-' }
            .joinToString("")
            .trim('-', '.')
            .take(64)
        return (cleaned.ifBlank { "script" }) + ".py"
    }

    private fun sha256(file: File): String =
        MessageDigest.getInstance("SHA-256").digest(file.readBytes())
            .joinToString("") { "%02x".format(it) }
}
