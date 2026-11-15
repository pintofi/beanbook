package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.BeancountText
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale

/**
 * A project arriving from outside the app: a SAF folder tree, a zip archive, a plain
 * directory. Abstracted so every validation rule below is testable on the JVM, and the
 * Android SAF adapter stays thin enough to be obviously correct.
 */
interface ImportSource {
    /** Relative paths, using '/' separators, in no particular order. */
    fun entries(): List<SourceEntry>

    fun open(entry: SourceEntry): InputStream
}

data class SourceEntry(
    /**
     * Where the file lands in the project. Usually the same as [sourcePath]; they differ
     * when a shared wrapper directory is stripped, and the source must still be read by its
     * own name.
     */
    val path: String,
    val size: Long,
    val isSymlink: Boolean = false,
    val isDirectory: Boolean = false,
    /** The name this entry has inside the archive or folder. */
    val sourcePath: String = path,
)

/** Where an export writes to: a SAF tree, a directory, a zip stream. */
interface ExportTarget {
    fun openOutput(relativePath: String): OutputStream
    fun finish() {}
}

/**
 * Caps on untrusted input (docs/storage-and-backup.md §4). Defaults are generous for real
 * ledgers — the golden corpus's largest case is 7.7 MB — and still refuse an archive
 * designed to exhaust the device.
 */
data class ImportLimits(
    val maxTotalBytes: Long = 512L * 1024 * 1024,
    val maxEntries: Int = 20_000,
    val maxPathSegments: Int = 16,
    val maxNameLength: Int = 255,
)

/** Why an import was refused. Each maps to a message the user can act on. */
sealed interface ImportProblem {
    val path: String

    data class PathTraversal(override val path: String) : ImportProblem
    data class AbsolutePath(override val path: String) : ImportProblem
    data class Symlink(override val path: String) : ImportProblem
    data class CaseCollision(override val path: String, val existing: String) : ImportProblem
    data class DuplicateEntry(override val path: String) : ImportProblem
    data class TooDeep(override val path: String, val segments: Int) : ImportProblem
    data class NameTooLong(override val path: String) : ImportProblem
    data class TooLarge(override val path: String, val totalBytes: Long) : ImportProblem
    data class TooManyEntries(override val path: String, val count: Int) : ImportProblem
    data class ReservedName(override val path: String) : ImportProblem

    /** Describes the problem in the words the user sees. */
    fun describe(): String = when (this) {
        is PathTraversal -> "\"$path\" tries to escape the project folder"
        is AbsolutePath -> "\"$path\" is an absolute path"
        is Symlink -> "\"$path\" is a symbolic link"
        is CaseCollision -> "\"$path\" collides with \"$existing\" on case-insensitive storage"
        is DuplicateEntry -> "\"$path\" appears more than once"
        is TooDeep -> "\"$path\" is nested $segments levels deep"
        is NameTooLong -> "the name in \"$path\" is too long"
        is TooLarge -> "the project is larger than the import limit ($totalBytes bytes)"
        is TooManyEntries -> "the ledger links to $count files"
        is ReservedName -> "\"$path\" is a reserved name"
    }
}

data class ImportPlan(
    val files: List<SourceEntry>,
    val problems: List<ImportProblem>,
) {
    val isAcceptable: Boolean get() = problems.isEmpty()
}

sealed interface ImportResult {
    data class Imported(
        val projectId: String,
        val mainFile: File,
        val fileCount: Int,
    ) : ImportResult
    data class Rejected(val problems: List<ImportProblem>) : ImportResult
    data class NoLedgerFound(val checked: Int) : ImportResult
}

/**
 * Imports a project into the app-private workspace.
 *
 * Validation happens **before anything is written**, and an import always targets a fresh
 * project id, so a malicious or malformed archive can never overwrite an existing ledger
 * (docs/storage-and-backup.md §4).
 */
class ProjectImporter(
    private val workspace: ProjectWorkspace,
    private val limits: ImportLimits = ImportLimits(),
) {

    /**
     * Judges every file [source] offers before anything is written: the names, the sizes and the count.
     * The paths are kept as they are, folders and all, because they are the layout the person has and
     * what their includes mean.
     */
    fun plan(source: ImportSource): ImportPlan {
        val problems = mutableListOf<ImportProblem>()
        val accepted = mutableListOf<SourceEntry>()
        val seen = mutableMapOf<String, String>() // lowercase path -> first path seen
        var totalBytes = 0L

        val entries = source.entries().filterNot { it.isDirectory }
        if (entries.size > limits.maxEntries) {
            // Refused as a whole, without going through every entry: the cost of saying no to a
            // hostile source must not grow with the source.
            return ImportPlan(
                files = emptyList(),
                problems = listOf(ImportProblem.TooManyEntries("<ledger>", entries.size)),
            )
        }

        for (entry in entries) {
            val given = entry.path.replace('\\', '/')

            unsafeName(entry, given)?.let { problems += it; continue }
            // Dots and empty parts collapsed, so what is judged, kept and written is the path the file
            // will really have: "files/../notes.beancount" is "notes.beancount".
            val path = collapsed(given)
            val segments = path.split('/').filter { it.isNotEmpty() }
            if (segments.size > limits.maxPathSegments) {
                problems += ImportProblem.TooDeep(path, segments.size); continue
            }
            if (segments.any { it.length > limits.maxNameLength }) {
                problems += ImportProblem.NameTooLong(path); continue
            }
            if (segments.any { it.uppercase(Locale.ROOT).substringBefore('.') in RESERVED_NAMES }) {
                problems += ImportProblem.ReservedName(path); continue
            }

            val key = path.lowercase(Locale.ROOT)
            val existing = seen[key]
            if (existing != null) {
                problems += if (existing == path) {
                    ImportProblem.DuplicateEntry(path)
                } else {
                    // Android's storage is case-sensitive, but the desktop this project
                    // syncs with may not be; silently merging two files would lose one.
                    ImportProblem.CaseCollision(path, existing)
                }
                continue
            }
            seen[key] = path

            totalBytes += entry.size
            if (totalBytes > limits.maxTotalBytes) {
                problems += ImportProblem.TooLarge(path, totalBytes); break
            }

            accepted += entry.copy(path = path, sourcePath = entry.path)
        }

        return ImportPlan(files = accepted, problems = problems)
    }

    /** An entry delivered more bytes than the source said it would. */
    private class ArchiveOverrun(val path: String, val bytes: Long) : java.io.IOException(
        "\"$path\" delivered more than the $bytes bytes it declared"
    )

    /**
     * Imports into a **new** project id. Never overwrites, never merges.
     *
     * Exactly the files [source] offers come in, each at its own path, and the project records [main] as
     * its main file, which keeps its own name and place: nothing is renamed, wrapped, flattened or stripped.
     *
     * @param main the main file's path in the project.
     */
    fun import(
        source: ImportSource,
        projectId: String,
        main: String,
    ): ImportResult {
        val plan = plan(source)
        if (plan.problems.isNotEmpty()) return ImportResult.Rejected(plan.problems)
        if (plan.files.none { it.path == main }) return ImportResult.NoLedgerFound(plan.files.size)

        val target = workspace.projectDir(projectId)
        require(!target.exists()) { "project already exists: $projectId" }

        // Stage into a temporary directory, then move into place, so a failure part-way
        // through never leaves a half-imported project visible.
        val staging = File(target.parentFile, "$projectId.importing").apply {
            deleteRecursively()
            mkdirs()
        }

        var extracted = 0L
        try {
            plan.files.forEach { entry ->
                val destination = File(staging, entry.path)
                // Second line of defence: even if a rule above were wrong, the resolved
                // path must still be inside the staging directory.
                check(destination.canonicalPath.startsWith(staging.canonicalPath + File.separator)) {
                    "refusing to write outside the project: ${entry.path}"
                }
                destination.parentFile?.mkdirs()
                source.open(entry).use { input ->
                    destination.outputStream().use { output ->
                        extracted += copyBounded(
                            input = input,
                            output = output,
                            path = entry.path,
                            entryLimit = entry.size,
                            remainingTotal = limits.maxTotalBytes - extracted,
                        )
                    }
                }
            }

            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = false)
                staging.deleteRecursively()
            }
            // Recorded once the files are in place: a record for a file that is not there is refused, and a
            // project that cannot say where its main file is must not be left behind.
            try {
                workspace.setRoot(projectId, main)
            } catch (error: Throwable) {
                target.deleteRecursively()
                throw error
            }
        } catch (overrun: ArchiveOverrun) {
            // The source lied about its own size. Refused rather than thrown, so the user
            // gets the same message as any other rejected import.
            staging.deleteRecursively()
            return ImportResult.Rejected(listOf(ImportProblem.TooLarge(overrun.path, overrun.bytes)))
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }

        return ImportResult.Imported(projectId, workspace.mainFile(projectId), plan.files.size)
    }

    private fun copyBounded(
        input: InputStream,
        output: OutputStream,
        path: String,
        entryLimit: Long,
        remainingTotal: Long,
    ): Long {
        val allowed = minOf(entryLimit, remainingTotal)
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var written = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            written += read
            if (written > allowed) throw ArchiveOverrun(path, allowed)
            output.write(buffer, 0, read)
        }
        return written
    }

    /**
     * Why an entry may not be imported whatever else is true of it: a link, a path that starts at the
     * root of a file system, one that goes above the project, or one that cancels to the project folder
     * itself (not a file in it). Null when the name is fine so far.
     */
    private fun unsafeName(entry: SourceEntry, given: String): ImportProblem? = when {
        entry.isSymlink -> ImportProblem.Symlink(given)
        given.startsWith("/") || given.matches(WINDOWS_ABSOLUTE) -> ImportProblem.AbsolutePath(given)
        escapesRoot(given) || collapsed(given).isEmpty() -> ImportProblem.PathTraversal(given)
        else -> null
    }

    /** [path] with "." and empty parts dropped and each ".." taken together with the part before it. */
    private fun collapsed(path: String): String {
        val parts = ArrayDeque<String>()
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> parts.removeLastOrNull()
                else -> parts.addLast(segment)
            }
        }
        return parts.joinToString("/")
    }

    private fun escapesRoot(path: String): Boolean {
        var depth = 0
        for (segment in path.split('/')) {
            when (segment) {
                "", "." -> Unit
                ".." -> if (--depth < 0) return true
                else -> depth++
            }
        }
        return false
    }

    private companion object {
        val WINDOWS_ABSOLUTE = Regex("^[A-Za-z]:[/\\\\].*")
        val RESERVED_NAMES = setOf(
            "CON", "PRN", "AUX", "NUL",
            "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
            "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9",
        )
    }
}

/** Reads a plain directory as an [ImportSource]. */
class DirectoryImportSource(private val root: File) : ImportSource {

    override fun entries(): List<SourceEntry> =
        // Links are not followed (`Files.walk` does not by default): a link to a folder comes
        // back as a link, so it is refused as one. Walking into it would list what it points at
        // as ordinary files of the project, from anywhere on the device.
        java.nio.file.Files.walk(root.toPath()).use { walk ->
            walk.map { it.toFile() }
                .filter { it.isFile || java.nio.file.Files.isSymbolicLink(it.toPath()) }
                .map { file ->
                    SourceEntry(
                        path = file.relativeTo(root).path.replace(File.separatorChar, '/'),
                        size = file.length(),
                        isSymlink = java.nio.file.Files.isSymbolicLink(file.toPath()),
                    )
                }
                // Not Stream.toList(): that is Java 16, and Android 10 to 13 do not have it.
                .collect(java.util.stream.Collectors.toList())
        }

    override fun open(entry: SourceEntry): InputStream = File(root, entry.sourcePath).inputStream()
}

/** Writes a project into a plain directory. */
class DirectoryExportTarget(private val root: File) : ExportTarget {
    override fun openOutput(relativePath: String): OutputStream {
        val file = File(root, relativePath)
        file.parentFile?.mkdirs()
        return file.outputStream()
    }
}

/**
 * Exports the portable project tree — and nothing else.
 *
 * Caches, journals, snapshots and drafts live outside the project directory by design
 * (docs/storage-and-backup.md §1), so "everything under the project" is already the right
 * answer, and export stays free in every edition.
 */
class ProjectExporter(private val workspace: ProjectWorkspace) {

    fun exportFolder(projectId: String, target: ExportTarget): Int {
        val projectDir = workspace.projectDir(projectId)
        var count = 0
        projectDir.walkTopDown().filter { it.isFile }.forEach { file ->
            val relative = file.relativeTo(projectDir).path.replace(File.separatorChar, '/')
            target.openOutput(relative).use { output -> file.inputStream().use { it.copyTo(output) } }
            count++
        }
        target.finish()
        return count
    }
}
