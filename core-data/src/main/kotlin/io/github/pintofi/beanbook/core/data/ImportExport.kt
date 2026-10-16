package io.github.pintofi.beanbook.core.data

import io.github.pintofi.beanbook.core.domain.BeancountText
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipFile

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
    /** Compressed size where the container reports one; used for zip-bomb detection. */
    val compressedSize: Long = size,
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
    val maxCompressionRatio: Int = 100,
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
    data class SuspiciousCompression(override val path: String, val ratio: Long) : ImportProblem
    data class ReservedName(override val path: String) : ImportProblem

    /**
     * The project's root ledger has another name, and a different file is already called
     * `main.beancount`. The importer points the app at `main.beancount`, so it cannot make a
     * copy of the real root there without replacing that file.
     */
    data class MainFileTaken(override val path: String) : ImportProblem

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
        is TooManyEntries -> "the archive contains $count files"
        is SuspiciousCompression -> "\"$path\" expands ${ratio}x, which looks like a zip bomb"
        is ReservedName -> "\"$path\" is a reserved name"
        is MainFileTaken ->
            "\"$path\" is already in this project, and it is not the root ledger. Rename it " +
                "and import again"
    }
}

data class ImportPlan(
    val files: List<SourceEntry>,
    val problems: List<ImportProblem>,
    val ledgerCandidates: List<String>,
) {
    val isAcceptable: Boolean get() = problems.isEmpty() && ledgerCandidates.isNotEmpty()
}

sealed interface ImportResult {
    data class Imported(val projectId: String, val mainFile: File, val fileCount: Int) : ImportResult
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

    fun plan(source: ImportSource): ImportPlan {
        val problems = mutableListOf<ImportProblem>()
        val accepted = mutableListOf<SourceEntry>()
        val seen = mutableMapOf<String, String>() // lowercase path -> first path seen
        var totalBytes = 0L

        val entries = source.entries().filterNot { it.isDirectory }
        if (entries.size > limits.maxEntries) {
            // Refused as a whole, without going through every entry: the cost of saying no to a
            // hostile archive must not grow with the archive.
            return ImportPlan(
                files = emptyList(),
                problems = listOf(ImportProblem.TooManyEntries("<archive>", entries.size)),
                ledgerCandidates = emptyList(),
            )
        }

        for (entry in entries) {
            val path = entry.path.replace('\\', '/')

            if (entry.isSymlink) {
                problems += ImportProblem.Symlink(path); continue
            }
            if (path.startsWith("/") || path.matches(WINDOWS_ABSOLUTE)) {
                problems += ImportProblem.AbsolutePath(path); continue
            }
            if (escapesRoot(path)) {
                problems += ImportProblem.PathTraversal(path); continue
            }
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

            if (entry.compressedSize > 0 && entry.size / entry.compressedSize > limits.maxCompressionRatio) {
                problems += ImportProblem.SuspiciousCompression(path, entry.size / entry.compressedSize)
                continue
            }

            totalBytes += entry.size
            if (totalBytes > limits.maxTotalBytes) {
                problems += ImportProblem.TooLarge(path, totalBytes); break
            }

            accepted += entry.copy(path = path, sourcePath = entry.path)
        }

        // Archives are usually made by zipping a folder, so everything sits under one
        // directory. Keeping that wrapper means the root ledger has to be *copied* to
        // main.beancount, leaving the project holding the same ledger twice — and edits to
        // the nested copy silently do nothing. Stripping a shared wrapper is uniform, so
        // relative includes and document links keep resolving.
        val unwrapped = stripSharedWrapper(accepted)

        return ImportPlan(
            files = unwrapped,
            problems = problems,
            ledgerCandidates = findLedgerCandidates(unwrapped.map { it.path }),
        )
    }

    private fun stripSharedWrapper(entries: List<SourceEntry>): List<SourceEntry> {
        if (entries.isEmpty()) return entries
        val wrapper = entries.first().path.substringBefore('/', missingDelimiterValue = "")
        if (wrapper.isEmpty()) return entries
        val prefix = "$wrapper/"
        if (!entries.all { it.path.startsWith(prefix) }) return entries
        return entries.map { it.copy(path = it.path.removePrefix(prefix), sourcePath = it.sourcePath) }
    }

    /**
     * Imports into a **new** project id. Never overwrites, never merges.
     *
     * @param preferredMain the ledger to treat as the root; defaults to the best candidate.
     */
    fun import(
        source: ImportSource,
        projectId: String,
        preferredMain: String? = null,
    ): ImportResult {
        val plan = plan(source)
        if (plan.problems.isNotEmpty()) return ImportResult.Rejected(plan.problems)
        if (plan.ledgerCandidates.isEmpty()) return ImportResult.NoLedgerFound(plan.files.size)

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

            // Now that the files are on disk -- and past every size check -- the include
            // graph can be read, which is the only way to tell a root from a file some other
            // ledger pulls in. Whole tree still imported: a `document` directive's target and
            // a file not yet included are part of the project and would be lost by taking
            // only what the root reaches.
            val main = preferredMain ?: chooseRoot(staging, plan.ledgerCandidates)

            // The imported project keeps its own shape; only the root file is normalised,
            // because Fava must be pointed at one file.
            if (main != ProjectWorkspace.MAIN_FILE) {
                if (File(staging, ProjectWorkspace.MAIN_FILE).exists()) {
                    staging.deleteRecursively()
                    return ImportResult.Rejected(
                        listOf(ImportProblem.MainFileTaken(ProjectWorkspace.MAIN_FILE)),
                    )
                }
                File(staging, main).copyTo(File(staging, ProjectWorkspace.MAIN_FILE))
            }

            if (!staging.renameTo(target)) {
                staging.copyRecursively(target, overwrite = false)
                staging.deleteRecursively()
            }
        } catch (overrun: ArchiveOverrun) {
            // The archive lied about its own size. Refused rather than thrown, so the user
            // gets the same message as any other rejected import.
            staging.deleteRecursively()
            return ImportResult.Rejected(listOf(ImportProblem.TooLarge(overrun.path, overrun.bytes)))
        } catch (error: Throwable) {
            staging.deleteRecursively()
            throw error
        }

        return ImportResult.Imported(projectId, workspace.mainFile(projectId), plan.files.size)
    }

    /**
     * Every `.beancount`/`.bean` file, best guess first, for offering the user a choice.
     *
     * This is a *name* heuristic and nothing more: main.beancount, then shallower paths,
     * then alphabetical. It cannot tell a root from an included file, because planning
     * happens before extraction and reading archive entries here would decompress content
     * before the size checks that make decompressing it safe.
     *
     * The actual root is chosen after extraction by [chooseRoot], which can read the files.
     * An earlier version of this docstring claimed the include rule was implemented here.
     * It never was: no file was read.
     */
    private fun findLedgerCandidates(paths: List<String>): List<String> {
        val ledgers = paths.filter { it.endsWith(".beancount") || it.endsWith(".bean") }
        if (ledgers.isEmpty()) return emptyList()
        return ledgers.sortedWith(
            compareBy(
                { if (it == ProjectWorkspace.MAIN_FILE) 0 else 1 },
                { it.count { char -> char == '/' } },
                { it },
            )
        )
    }

    /**
     * The root ledger: the candidate no other ledger in the project includes.
     *
     * Falls back to the name heuristic's first choice when the include graph does not
     * settle it -- a project of several independent ledgers has no single root, and one
     * whose ledgers all include each other has no un-included file. Guessing consistently
     * is better than refusing an import that is otherwise fine.
     */
    private fun chooseRoot(staging: File, candidates: List<String>): String {
        val included = buildSet {
            candidates.forEach { candidate ->
                val file = File(staging, candidate)
                if (!file.isFile) return@forEach
                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                BeancountText.includedPaths(text).forEach { path ->
                    add(BeancountText.resolveInclude(from = candidate, include = path))
                }
            }
        }
        return candidates.firstOrNull { it !in included } ?: candidates.first()
    }

    /** An entry delivered more bytes than the container said it would. */
    private class ArchiveOverrun(val path: String, val bytes: Long) : java.io.IOException(
        "\"$path\" delivered more than the $bytes bytes it declared"
    )

    /**
     * Copies [input] to [output], refusing to go past what the entry declared.
     *
     * Every size check in [plan] reads the container's own metadata, which the archive
     * supplies and an attacker therefore chooses. Those figures decide whether an import is
     * allowed; this is what makes them true. Without it an archive could declare a kilobyte
     * per entry, pass every check, and inflate to whatever it liked while extracting.
     *
     * Bounded twice: by what this entry claimed, and by what is left of the whole-archive
     * budget, so many small honest-looking entries cannot add up past the limit either.
     */
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

/**
 * Reads a zip archive as an [ImportSource], without extracting it.
 *
 * Uses [ZipFile] rather than `ZipInputStream` deliberately: a streamed read takes sizes
 * from the local headers, which are frequently absent (`-1`) for deflated entries, and
 * that silently disables the zip-bomb and total-size checks. [ZipFile] reads the central
 * directory, where the real sizes are.
 *
 * A SAF archive therefore has to be copied into app storage before validation, because
 * [ZipFile] needs random access. That is the right order anyway: nothing is extracted
 * until the whole archive has been judged.
 */
class ZipImportSource(private val archive: File) : ImportSource, AutoCloseable {

    private val zip = ZipFile(archive)

    override fun entries(): List<SourceEntry> =
        zip.entries().asSequence().map { entry ->
            SourceEntry(
                path = entry.name,
                size = if (entry.size >= 0) entry.size else 0,
                compressedSize = if (entry.compressedSize > 0) entry.compressedSize else 1,
                isDirectory = entry.isDirectory,
            )
        }.toList()

    override fun open(entry: SourceEntry): InputStream {
        val found = zip.getEntry(entry.sourcePath)
            ?: error("entry disappeared from the archive: ${entry.sourcePath}")
        return zip.getInputStream(found)
    }

    override fun close() = zip.close()
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
                .toList()
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
