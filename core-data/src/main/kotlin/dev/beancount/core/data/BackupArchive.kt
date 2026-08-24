package dev.beancount.core.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * What a `.bcaz` archive says about itself (docs/storage-and-backup.md §7).
 *
 * The manifest is deliberately readable: someone holding an archive years from now, with no
 * app and no passphrase, should be able to open it in a text editor and tell what it is,
 * when it was made, and what should be inside.
 */
data class BackupManifest(
    val schemaVersion: Int,
    val appVersion: String,
    val engineVersions: Map<String, String>,
    val createdAt: Instant,
    val projectId: String,
    val projectTitle: String,
    val files: List<Entry>,
    val encryption: String = NONE,
) {
    data class Entry(val path: String, val size: Long, val sha256: String)

    val totalBytes: Long get() = files.sumOf { it.size }

    fun toJson(): Json = Json.obj(
        "schema_version" to Json.of(schemaVersion),
        "app_version" to Json.of(appVersion),
        "engine_versions" to Json.Obj(engineVersions.mapValues { Json.of(it.value) }),
        "created_at" to Json.of(createdAt.toString()),
        "project_id" to Json.of(projectId),
        "project_title" to Json.of(projectTitle),
        "encryption" to Json.of(encryption),
        "files" to Json.arr(
            files.map {
                Json.obj(
                    "path" to Json.of(it.path),
                    "size" to Json.of(it.size),
                    "sha256" to Json.of(it.sha256),
                )
            },
        ),
    )

    companion object {
        const val SCHEMA_VERSION = 1
        const val NONE = "none"
        const val MANIFEST_NAME = "manifest.json"
        const val PROJECT_PREFIX = "project/"

        fun fromJson(json: Json?): BackupManifest? {
            val root = json as? Json.Obj ?: return null
            val files = root.array("files")?.map { item ->
                val entry = item as? Json.Obj ?: return null
                Entry(
                    path = entry.string("path") ?: return null,
                    size = entry.long("size") ?: return null,
                    sha256 = entry.string("sha256") ?: return null,
                )
            } ?: return null

            return BackupManifest(
                schemaVersion = root.long("schema_version")?.toInt() ?: return null,
                appVersion = root.string("app_version") ?: return null,
                engineVersions = root.obj("engine_versions")
                    ?.fields
                    ?.mapValues { (_, value) -> (value as? Json.Str)?.value ?: "" }
                    .orEmpty(),
                createdAt = runCatching { Instant.parse(root.string("created_at")) }
                    .getOrNull() ?: return null,
                projectId = root.string("project_id") ?: return null,
                projectTitle = root.string("project_title") ?: return null,
                files = files,
                encryption = root.string("encryption") ?: NONE,
            )
        }
    }
}

/** Why an archive was refused. Each case is something the user can act on. */
sealed interface ArchiveProblem {
    data object NotAnArchive : ArchiveProblem
    data class UnsupportedSchema(val version: Int) : ArchiveProblem
    data class MissingFile(val path: String) : ArchiveProblem
    data class ChecksumMismatch(val path: String) : ArchiveProblem
    data class SizeMismatch(val path: String, val expected: Long, val actual: Long) : ArchiveProblem
    data class UnexpectedFile(val path: String) : ArchiveProblem
    data class Unreadable(val reason: String) : ArchiveProblem

    fun describe(): String = when (this) {
        NotAnArchive ->
            "That file is not a Beancount backup — it has no manifest."
        is UnsupportedSchema ->
            "This backup was written by a newer version of the app (format $version). " +
                "Update the app and try again; nothing has been changed."
        is MissingFile ->
            "The backup is incomplete: \"$path\" is listed in its manifest but not in the " +
                "archive. Nothing has been restored."
        is ChecksumMismatch ->
            "\"$path\" does not match its checksum, so this archive is damaged or was " +
                "altered. Nothing has been restored."
        is SizeMismatch ->
            "\"$path\" is $actual bytes but the manifest says $expected. Nothing has been " +
                "restored."
        is UnexpectedFile ->
            "The archive contains \"$path\", which its manifest does not list. Nothing has " +
                "been restored."
        is Unreadable -> "The backup could not be read: $reason. Nothing has been restored."
    }
}

/**
 * Writes and verifies `.bcaz` archives.
 *
 * Two rules run through everything here:
 *
 *  * **Verify before touching anything.** A restore checks the manifest, the schema version
 *    and every checksum *before* live data is touched. An archive that fails any of those
 *    leaves the device exactly as it was.
 *  * **Back up the project, not the machinery.** Caches, the write journal, snapshots,
 *    drafts, mirror state and the entitlement key are excluded: they are this device's
 *    runtime state, and a key in a backup is a key in someone else's hands.
 */
class BackupArchive(private val workspace: ProjectWorkspace) {

    /**
     * Writes a backup of [projectId] to [out].
     *
     * @return the manifest that was written, so the caller can report what went in.
     */
    fun write(
        projectId: String,
        out: OutputStream,
        appVersion: String,
        engineVersions: Map<String, String> = emptyMap(),
        projectTitle: String = projectId,
        now: Instant = Instant.now(),
    ): BackupManifest {
        val root = workspace.projectDir(projectId)
        require(root.isDirectory) { "no such project: $projectId" }

        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filterNot { isExcluded(it) }
            .sorted()
            .toList()

        val entries = files.map { path ->
            val file = File(root, path)
            BackupManifest.Entry(path, file.length(), sha256(file))
        }

        val manifest = BackupManifest(
            schemaVersion = BackupManifest.SCHEMA_VERSION,
            appVersion = appVersion,
            engineVersions = engineVersions,
            createdAt = now,
            projectId = projectId,
            projectTitle = projectTitle,
            files = entries,
        )

        ZipOutputStream(out).use { zip ->
            // The manifest goes in first and uncompressed, so a partially readable archive
            // still tells someone what it was meant to contain.
            zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST_NAME))
            zip.write(manifest.toJson().write().toByteArray())
            zip.closeEntry()

            files.forEach { path ->
                zip.putNextEntry(ZipEntry(BackupManifest.PROJECT_PREFIX + path))
                File(root, path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return manifest
    }

    /** The manifest of [archive] without verifying its contents, for showing what it is. */
    fun peek(archive: File): BackupManifest? = runCatching {
        ZipFile(archive).use { zip ->
            val entry = zip.getEntry(BackupManifest.MANIFEST_NAME) ?: return null
            BackupManifest.fromJson(Json.parse(zip.getInputStream(entry).readBytes().decodeToString()))
        }
    }.getOrNull()

    /**
     * Checks an archive from end to end: the manifest parses, the schema is one this app
     * understands, every listed file is present at the right size and checksum, and nothing
     * unlisted is hiding inside.
     */
    fun verify(archive: File): List<ArchiveProblem> = runCatching {
        ZipFile(archive).use { zip ->
            val manifestEntry = zip.getEntry(BackupManifest.MANIFEST_NAME)
                ?: return listOf(ArchiveProblem.NotAnArchive)
            val manifest = BackupManifest.fromJson(
                Json.parse(zip.getInputStream(manifestEntry).readBytes().decodeToString()),
            ) ?: return listOf(ArchiveProblem.NotAnArchive)

            if (manifest.schemaVersion > BackupManifest.SCHEMA_VERSION) {
                return listOf(ArchiveProblem.UnsupportedSchema(manifest.schemaVersion))
            }

            val problems = mutableListOf<ArchiveProblem>()
            val listed = manifest.files.associateBy { it.path }

            manifest.files.forEach { entry ->
                val zipEntry = zip.getEntry(BackupManifest.PROJECT_PREFIX + entry.path)
                if (zipEntry == null) {
                    problems += ArchiveProblem.MissingFile(entry.path)
                    return@forEach
                }
                val bytes = zip.getInputStream(zipEntry).readBytes()
                if (bytes.size.toLong() != entry.size) {
                    problems += ArchiveProblem.SizeMismatch(entry.path, entry.size, bytes.size.toLong())
                    return@forEach
                }
                if (sha256(bytes) != entry.sha256) {
                    problems += ArchiveProblem.ChecksumMismatch(entry.path)
                }
            }

            // Anything in the archive the manifest does not vouch for is refused rather than
            // extracted: an archive is only trustworthy to the extent the manifest covers it.
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name }
                .filterNot { it == BackupManifest.MANIFEST_NAME }
                .forEach { name ->
                    val path = name.removePrefix(BackupManifest.PROJECT_PREFIX)
                    if (!name.startsWith(BackupManifest.PROJECT_PREFIX) || path !in listed) {
                        problems += ArchiveProblem.UnexpectedFile(name)
                    }
                }

            problems
        }
    }.getOrElse { listOf(ArchiveProblem.Unreadable(it.message ?: it.javaClass.simpleName)) }

    /**
     * Extracts a verified archive into [target], which must not exist.
     *
     * Verification runs first and the extraction goes to a fresh directory, so this can
     * never damage a live project — activating the result is a separate, explicit step
     * (see `RestorePlan`).
     */
    fun extract(archive: File, target: File): List<ArchiveProblem> {
        val problems = verify(archive)
        if (problems.isNotEmpty()) return problems
        require(!target.exists()) { "restore target already exists: $target" }

        return runCatching<List<ArchiveProblem>> {
            ZipFile(archive).use { zip ->
                val manifest = BackupManifest.fromJson(
                    Json.parse(
                        zip.getInputStream(zip.getEntry(BackupManifest.MANIFEST_NAME))
                            .readBytes()
                            .decodeToString(),
                    ),
                )!!
                manifest.files.forEach { entry ->
                    val destination = File(target, entry.path)
                    // Belt and braces: the manifest is ours, but a path in it is still input.
                    check(
                        destination.canonicalPath.startsWith(target.canonicalFile.path + File.separator),
                    ) { "refusing to write outside the restore target: ${entry.path}" }
                    destination.parentFile?.mkdirs()
                    zip.getInputStream(zip.getEntry(BackupManifest.PROJECT_PREFIX + entry.path))
                        .use { input ->
                            destination.outputStream().use { output -> input.copyTo(output) }
                        }
                }
            }
            emptyList()
        }.getOrElse { failure ->
            target.deleteRecursively()
            listOf(ArchiveProblem.Unreadable(failure.message ?: failure.javaClass.simpleName))
        }
    }

    companion object {
        /**
         * Runtime state that must never travel in a backup.
         *
         * The journal, snapshots and drafts describe *this device's* in-flight work; mirror
         * state describes this device's view of a folder that may not exist elsewhere. They
         * live outside the project tree already — this is the second line of defence, for
         * anything that finds its way in.
         */
        private val EXCLUDED_DIRECTORIES = setOf(".journal", ".snapshots", ".drafts", ".cache")
        private val EXCLUDED_NAMES = setOf("mirror-state", "entitlement", "key")

        fun isExcluded(relativePath: String): Boolean {
            val segments = relativePath.split('/')
            if (segments.any { it in EXCLUDED_DIRECTORIES }) return true
            if (segments.last() in EXCLUDED_NAMES) return true
            // Temporary files from an interrupted write; the real file is beside them.
            return segments.last().endsWith(".tmp") || segments.last().endsWith(".part")
        }

        fun sha256(file: File): String = file.inputStream().use { sha256(it) }

        fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).toHex()

        private fun sha256(input: InputStream): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
            return digest.digest().toHex()
        }

        private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }
    }
}
