package io.github.pintofi.beanbook.core.data

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.Deflater
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
    val encryption: ArchiveEncryption = ArchiveEncryption.None,
    /**
     * The main file's path inside the project when it is not `main.beancount` (a project imported from a
     * file with another name or place), else null. Only a backup that has one is written as schema 2, so an
     * older app refuses it with its ordinary "newer version" message instead of restoring a project whose
     * main file it cannot find.
     */
    val rootFile: String? = null,
    /**
     * Beanbook's own data that is not part of the ledger, chosen when the backup was made: entry templates,
     * saved scripts, some preferences. Only a backup that holds some is written as schema 3, so an older app
     * refuses it with its ordinary "newer version" message instead of failing on files it does not know.
     * File names are not listed beyond the group and a fixed path, so a readable manifest says nothing about
     * what the templates or scripts are called.
     */
    val appData: List<AppDataEntry> = emptyList(),
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
        "encryption" to encryption.toJson(),
        *listOfNotNull(rootFile?.let { "root_file" to Json.of(it) }).toTypedArray(),
        *listOfNotNull(
            appData.takeIf { it.isNotEmpty() }?.let { entries ->
                "app_data" to Json.arr(
                    entries.map {
                        Json.obj(
                            "group" to Json.of(it.group.wire),
                            "path" to Json.of(it.path),
                            "size" to Json.of(it.size),
                            "sha256" to Json.of(it.sha256),
                        )
                    },
                )
            },
        ).toTypedArray(),
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
        /**
         * The newest format this app reads. 2 adds `root_file`; 3 adds `app_data` and puts a sealed archive's
         * project files under `project/`. A backup that needs neither is still written as 1.
         */
        const val SCHEMA_VERSION = 3

        /** The format a backup is written in: 3 only when it holds app data, else 2 only when it needs `root_file`. */
        fun schemaFor(rootFile: String?, hasAppData: Boolean = false): Int =
            if (hasAppData) 3 else if (rootFile == null) 1 else 2
        const val MANIFEST_NAME = "manifest.json"
        const val PROJECT_PREFIX = "project/"

        /** Where app data sits in a schema 3 archive, beside `project/`. */
        const val APP_DATA_PREFIX = "appdata/"

        /** The single sealed blob that replaces `project/…` when a passphrase is set. */
        const val PAYLOAD_NAME = "project.enc"

        /** The `app_data` list: empty when there is none, null when an entry is not one this app allows. */
        private fun appDataFrom(root: Json.Obj): List<AppDataEntry>? = root.array("app_data")?.map { item ->
            val entry = item as? Json.Obj ?: return null
            val group = AppDataGroup.fromWire(entry.string("group") ?: return null) ?: return null
            val path = entry.string("path") ?: return null
            // The group says what the path may look like; anything else is a name made up by the archive.
            if (!group.accepts(path)) return null
            AppDataEntry(group, path, entry.long("size") ?: return null, entry.string("sha256") ?: return null)
        }.orEmpty()

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

            val schema = root.long("schema_version")?.toInt() ?: return null
            val appData = appDataFrom(root) ?: return null
            // An older format has no such section, so a manifest that claims one is not one of ours.
            if (appData.isNotEmpty() && schema < 3) return null

            return BackupManifest(
                schemaVersion = schema,
                appData = appData,
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
                encryption = ArchiveEncryption.fromJson(root["encryption"]) ?: return null,
                rootFile = root.string("root_file")
                    ?.let { ProjectWorkspace.validRoot(it) ?: return null },
            )
        }
    }
}

/**
 * The kinds of Beanbook's own data a backup can carry beside the ledger, each with the only paths it may use
 * inside `appdata/`. Closed on purpose: a path in a manifest is input, so it must be one of these shapes.
 */
enum class AppDataGroup(val wire: String) {
    /** Entry templates, kept in the format `TemplateStore` writes, only the ones that were chosen. */
    TEMPLATES("templates"),

    /** Saved scripts, one `.py` file each. Code: restored as saved, never run. */
    SCRIPTS("scripts"),

    /** Appearance, Home layout, valuation basis: one small file each. */
    PREFERENCES("preferences"),

    /** The accent or dynamic colour of this project. */
    COLOUR("colour"),

    /** The Fava server address and user name. Never the password. */
    REMOTE("remote"),

    /**
     * Plugins live in the project, so they are never listed in a manifest as app data. The group exists so the
     * checklist can offer them one by one; a manifest naming it is refused.
     */
    PLUGINS("plugins"),

    /**
     * Which catalogue plugins were installed, by name and version. Only the list: the plugins and their
     * packages are downloaded again from the Plugins screen, never by a restore.
     */
    CATALOGUE("catalogue");

    /** Whether [path], relative to `appdata/`, is one this group may hold. */
    fun accepts(path: String): Boolean = when (this) {
        TEMPLATES -> path == "templates.templates"
        COLOUR -> path == "colour"
        REMOTE -> path == "remote.json"
        PLUGINS -> false
        CATALOGUE -> path == "catalogue.json"
        PREFERENCES -> path in PREFERENCE_FILES.map { "preferences/$it" }
        SCRIPTS -> SCRIPT_PATH.matches(path)
    }

    companion object {
        /** The preference files that can travel, by the name each has in the archive. */
        val PREFERENCE_FILES = listOf("appearance", "home-cards", "balances-basis")
        private val SCRIPT_PATH = Regex("scripts/[A-Za-z0-9][A-Za-z0-9._-]{0,63}\\.py")

        fun fromWire(wire: String): AppDataGroup? = entries.firstOrNull { it.wire == wire }
    }
}

/** One app-data file in a manifest: its group, its path under `appdata/`, and what it must hash to. */
data class AppDataEntry(val group: AppDataGroup, val path: String, val size: Long, val sha256: String)

/** An app-data file to be written into a backup, or read out of one. [path] is relative to `appdata/`. */
class AppDataPayload(val group: AppDataGroup, val path: String, val bytes: ByteArray) {
    init {
        require(group.accepts(path)) { "not a place app data may go: $path" }
        require(bytes.size <= BackupArchive.MAX_APP_DATA_FILE_BYTES) { "$path is too large to back up" }
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
    data object PassphraseNeeded : ArchiveProblem
    data object WrongPassphrase : ArchiveProblem

    /** Something in the archive is bigger than this app reads into memory; see [BackupArchive.MAX_MANIFEST_BYTES]. */
    data class TooLarge(val what: String, val limit: Long) : ArchiveProblem

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
        PassphraseNeeded ->
            "This backup is encrypted. Enter the passphrase it was made with — nobody, " +
                "including us, can open it without one."
        WrongPassphrase ->
            "That passphrase does not open this backup. Nothing has been restored."
        is TooLarge ->
            "The backup's $what is larger than the ${limit / (1024 * 1024)} MiB this app reads at once, " +
                "so it was not opened. Nothing has been restored."
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
 *    drafts and mirror state are excluded: they are this device's runtime state, and what
 *    is left out is only ever that. A file is never left out because of what the person
 *    happened to call it.
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
        /** Set to encrypt the payload. The manifest stays readable either way. */
        passphrase: CharArray? = null,
        derivation: KeyDerivation? = null,
        cost: KdfCost = KdfCost(),
        kdf: String = ArchiveEncryption.ARGON2ID,
        /** Beanbook's own data to carry beside the ledger, already chosen; empty for an ordinary backup. */
        appData: List<AppDataPayload> = emptyList(),
        /** Ids of plugins to leave out: their files, and their lines in the plugin list. */
        leaveOutPlugins: Set<String> = emptySet(),
    ): BackupManifest {
        require(workspace.projectDir(projectId).isDirectory) { "no such project: $projectId" }
        require(appData.size <= MAX_APP_DATA_FILES) { "too many app-data files" }
        require(appData.map { it.path }.distinct().size == appData.size) { "the same app-data file twice" }

        // The manifest lists every file's size and checksum and comes first in the archive, so
        // it is worked out before the files are copied -- and a file saved in between would then
        // make the archive disagree with its own manifest, which is the backup that cannot be
        // restored. So the archive is built beside the destination, each file is checked against
        // the manifest as it is copied, and a file that changed makes the whole backup start
        // again. Only a finished, consistent archive reaches [out].
        val scratch = workspace.cacheDir.apply { mkdirs() }
        var lastChanged: String? = null
        repeat(MAX_ATTEMPTS) {
            val staged = File.createTempFile("backup", ".bcaz", scratch)
            try {
                val manifest = staged.outputStream().buffered().use { sink ->
                    writeOnce(
                        projectId, sink, appVersion, engineVersions, projectTitle, now,
                        passphrase, derivation, cost, kdf, appData, leaveOutPlugins,
                    )
                }
                staged.inputStream().use { it.copyTo(out) }
                return manifest
            } catch (changed: FileChangedWhileCopying) {
                lastChanged = changed.path
            } finally {
                staged.delete()
            }
        }
        throw java.io.IOException(
            "\"$lastChanged\" kept changing while the backup was being made, so no backup was written. " +
                "Try again when nothing is saving.",
        )
    }

    /** Set by tests, to change a file at the moment the manifest has been worked out and the copying has not begun. */
    internal var betweenHashingAndCopying: (() -> Unit)? = null

    private class FileChangedWhileCopying(val path: String) : java.io.IOException("$path changed during the backup")

    private fun writeOnce(
        projectId: String,
        out: OutputStream,
        appVersion: String,
        engineVersions: Map<String, String>,
        projectTitle: String,
        now: Instant,
        passphrase: CharArray?,
        derivation: KeyDerivation?,
        cost: KdfCost,
        kdf: String,
        appData: List<AppDataPayload>,
        leaveOutPlugins: Set<String>,
    ): BackupManifest {
        val root = workspace.projectDir(projectId)

        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path.replace(File.separatorChar, '/') }
            .filterNot { isExcluded(it) }
            .filterNot { path -> leaveOutPlugins.any { path.startsWith("plugins/$it/") } }
            .sorted()
            .toList()

        // The plugin list, minus the plugins left out, is held in memory and written in place of the file.
        val replaced = mutableMapOf<String, ByteArray>()
        if (leaveOutPlugins.isNotEmpty() && PLUGIN_INDEX in files) {
            pluginIndexWithout(File(root, PLUGIN_INDEX), leaveOutPlugins)?.let { replaced[PLUGIN_INDEX] = it }
        }
        val entries = files.map { path ->
            val file = File(root, path)
            replaced[path]?.let { BackupManifest.Entry(path, it.size.toLong(), sha256(it)) }
                ?: BackupManifest.Entry(path, file.length(), sha256(file))
        }

        betweenHashingAndCopying?.invoke()
        val expected = entries.associateBy { it.path }

        val rootFile = workspace.rootPath(projectId).takeIf { !ProjectWorkspace.isDefaultRoot(it) }

        // App data is held in memory (each file is small and capped), so its size and checksum are exact.
        val appEntries = appData.sortedBy { it.path }.map {
            AppDataEntry(it.group, it.path, it.bytes.size.toLong(), sha256(it.bytes))
        }
        val schema = BackupManifest.schemaFor(rootFile, appData.isNotEmpty())

        fun manifestWith(encryption: ArchiveEncryption) = BackupManifest(
            schemaVersion = schema,
            appData = appEntries,
            rootFile = rootFile,
            appVersion = appVersion,
            engineVersions = engineVersions,
            createdAt = now,
            projectId = projectId,
            projectTitle = projectTitle,
            // Checksums are always of the *plaintext* files, so verifying a restored archive
            // is the same check whether or not it was encrypted.
            files = entries,
            encryption = encryption,
        )

        if (passphrase == null) {
            val manifest = manifestWith(ArchiveEncryption.None)
            ZipOutputStream(out).use { zip ->
                // The manifest goes in first, so a partially readable archive still says
                // what it was meant to contain.
                zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST_NAME))
                zip.write(manifest.toJson().write().toByteArray())
                zip.closeEntry()

                files.forEach { path ->
                    zip.putNextEntry(ZipEntry(BackupManifest.PROJECT_PREFIX + path))
                    copyOne(root, path, expected.getValue(path), replaced[path], zip)
                    zip.closeEntry()
                }
                appData.sortedBy { it.path }.forEach { item ->
                    zip.putNextEntry(ZipEntry(BackupManifest.APP_DATA_PREFIX + item.path))
                    zip.write(item.bytes)
                    zip.closeEntry()
                }
            }
            return manifest
        }

        requireNotNull(derivation) { "encrypting needs a key derivation" }

        // The project is packed into an inner zip and that whole blob is sealed. One payload
        // rather than per-file encryption: file names, sizes and the shape of someone's
        // accounts are as revealing as the amounts inside them. The inner zip is sealed as it is
        // written, in chunks, so the project is never held in memory (#54).
        val sealer = PayloadCipher.begin(passphrase, derivation, kdf, cost)
        val manifest = manifestWith(sealer.encryption)

        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST_NAME))
            zip.write(manifest.toJson().write().toByteArray())
            zip.closeEntry()
            // Sealed bytes do not compress, and trying costs time on a large project.
            zip.setLevel(Deflater.NO_COMPRESSION)
            zip.putNextEntry(ZipEntry(BackupManifest.PAYLOAD_NAME))
            // Schema 3 names what sits where, so app data cannot collide with a project file; earlier ones have
            // only project files and keep the bare names they were always written with.
            val prefixed = schema >= 3
            ZipOutputStream(sealer.wrap(zip)).use { inner ->
                files.forEach { path ->
                    inner.putNextEntry(ZipEntry(if (prefixed) BackupManifest.PROJECT_PREFIX + path else path))
                    copyOne(root, path, expected.getValue(path), replaced[path], inner)
                    inner.closeEntry()
                }
                appData.sortedBy { it.path }.forEach { item ->
                    inner.putNextEntry(ZipEntry(BackupManifest.APP_DATA_PREFIX + item.path))
                    inner.write(item.bytes)
                    inner.closeEntry()
                }
            }
            zip.closeEntry()
        }
        return manifest
    }

    /** The manifest of [archive] without verifying its contents, for showing what it is. */
    fun peek(archive: File): BackupManifest? = runCatching {
        ZipFile(archive).use { zip ->
            checkEntryCount(zip)
            val entry = zip.getEntry(BackupManifest.MANIFEST_NAME) ?: return null
            BackupManifest.fromJson(Json.parse(readEntry(zip, entry, MAX_MANIFEST_BYTES, "manifest").decodeToString()))
        }
    }.getOrNull()

    /**
     * Checks an archive from end to end: the manifest parses, the schema is one this app
     * understands, every listed file is present at the right size and checksum, and nothing
     * unlisted is hiding inside.
     */
    fun verify(
        archive: File,
        passphrase: CharArray? = null,
        derivation: KeyDerivation? = null,
    ): List<ArchiveProblem> = withPayload(archive, passphrase, derivation) { manifest, payload ->
        val problems = mutableListOf<ArchiveProblem>()
        val listed = manifest.files.associateBy { it.path }

        manifest.files.forEach { entry ->
            val stream = payload.open(entry.path)
            if (stream == null) {
                problems += ArchiveProblem.MissingFile(entry.path)
                return@forEach
            }
            // Streamed, and never past one byte beyond what the manifest says. An archive is
            // not trusted until it has been checked, so checking it must not be a way to make
            // the app allocate whatever the archive likes: an entry that claims to be small and
            // is not is refused after reading one byte too many, not after reading all of it.
            val (read, sha) = stream.use { hashAtMost(it, entry.size + 1) }
            if (read != entry.size) {
                problems += ArchiveProblem.SizeMismatch(entry.path, entry.size, read)
                return@forEach
            }
            if (sha != entry.sha256) {
                problems += ArchiveProblem.ChecksumMismatch(entry.path)
            }
        }

        // App data is held to the same checks: present, the size and checksum the manifest gives, and nothing
        // unlisted beside it.
        manifest.appData.forEach { entry ->
            val stream = payload.openAppData(entry.path)
            if (stream == null) {
                problems += ArchiveProblem.MissingFile(BackupManifest.APP_DATA_PREFIX + entry.path)
                return@forEach
            }
            if (entry.size > MAX_APP_DATA_FILE_BYTES) {
                problems += ArchiveProblem.TooLarge("app data ${entry.path}", MAX_APP_DATA_FILE_BYTES.toLong())
                stream.close()
                return@forEach
            }
            val (read, sha) = stream.use { hashAtMost(it, entry.size + 1) }
            if (read != entry.size) {
                problems += ArchiveProblem.SizeMismatch(BackupManifest.APP_DATA_PREFIX + entry.path, entry.size, read)
            } else if (sha != entry.sha256) {
                problems += ArchiveProblem.ChecksumMismatch(BackupManifest.APP_DATA_PREFIX + entry.path)
            }
        }

        // Anything present that the manifest does not vouch for is refused rather than
        // extracted: an archive is only trustworthy to the extent its manifest covers it.
        payload.names().forEach { name ->
            if (name !in listed) problems += ArchiveProblem.UnexpectedFile(name)
        }
        val listedAppData = manifest.appData.map { it.path }.toSet()
        payload.appDataNames().forEach { name ->
            if (name !in listedAppData) problems += ArchiveProblem.UnexpectedFile(BackupManifest.APP_DATA_PREFIX + name)
        }

        problems
    }

    /**
     * Extracts a verified archive into [target], which must not exist.
     *
     * Verification runs first and the extraction goes to a fresh directory, so this can
     * never damage a live project — activating the result is a separate, explicit step
     * (see `RestorePlan`).
     */
    fun extract(
        archive: File,
        target: File,
        passphrase: CharArray? = null,
        derivation: KeyDerivation? = null,
        /** Where to put the backup's app data, a folder of its own that must not exist; null leaves it unread. */
        appDataTarget: File? = null,
    ): List<ArchiveProblem> {
        val problems = verify(archive, passphrase, derivation)
        if (problems.isNotEmpty()) return problems
        require(!target.exists()) { "restore target already exists: $target" }
        require(appDataTarget == null || !appDataTarget.exists()) { "app-data target already exists: $appDataTarget" }

        val outcome = withPayload(archive, passphrase, derivation) { manifest, payload ->
            manifest.files.forEach { entry ->
                val destination = File(target, entry.path)
                // Belt and braces: the manifest is ours, but a path in it is still input.
                check(
                    destination.canonicalPath.startsWith(target.canonicalFile.path + File.separator),
                ) { "refusing to write outside the restore target: ${entry.path}" }
                destination.parentFile?.mkdirs()
                val stream = payload.open(entry.path) ?: error("missing ${entry.path}")
                destination.outputStream().use { out ->
                    stream.use { input ->
                        val copied = input.copyTo(out)
                        check(copied == entry.size) { "${entry.path} changed while it was being restored" }
                    }
                }
            }
            if (appDataTarget != null) {
                manifest.appData.forEach { entry ->
                    val destination = File(appDataTarget, entry.path)
                    check(
                        destination.canonicalPath.startsWith(appDataTarget.canonicalFile.path + File.separator),
                    ) { "refusing to write outside the app-data target: ${entry.path}" }
                    destination.parentFile?.mkdirs()
                    val stream = payload.openAppData(entry.path) ?: error("missing appdata/${entry.path}")
                    destination.outputStream().use { out -> stream.use { it.copyTo(out) } }
                }
            }
            emptyList()
        }
        if (outcome.isNotEmpty()) {
            target.deleteRecursively()
            appDataTarget?.deleteRecursively()
        }
        return outcome
    }

    /** The project files inside an archive, however they happen to be stored. */
    private interface Payload {
        fun open(path: String): InputStream?
        fun names(): Set<String>

        /** An app-data file, by its path under `appdata/`; null in an archive that has none. */
        fun openAppData(path: String): InputStream?

        /** The app-data files present, by their path under `appdata/`. */
        fun appDataNames(): Set<String>
    }

    /**
     * Opens an archive's payload — plain entries, or the decrypted blob — and hands it to
     * [block], so both kinds are then checked by exactly the same code.
     */
    private fun withPayload(
        archive: File,
        passphrase: CharArray?,
        derivation: KeyDerivation?,
        block: (BackupManifest, Payload) -> List<ArchiveProblem>,
    ): List<ArchiveProblem> {
        var decrypted: File? = null
        return runCatching {
            ZipFile(archive).use { zip ->
                checkEntryCount(zip)
                val manifestEntry = zip.getEntry(BackupManifest.MANIFEST_NAME)
                    ?: return listOf(ArchiveProblem.NotAnArchive)
                val manifest = BackupManifest.fromJson(
                    Json.parse(readEntry(zip, manifestEntry, MAX_MANIFEST_BYTES, "manifest").decodeToString()),
                ) ?: return listOf(ArchiveProblem.NotAnArchive)

                if (manifest.schemaVersion > BackupManifest.SCHEMA_VERSION) {
                    return listOf(ArchiveProblem.UnsupportedSchema(manifest.schemaVersion))
                }
                // A main file the backup does not hold is a backup that cannot be opened.
                if (manifest.rootFile != null && manifest.files.none { it.path == manifest.rootFile }) {
                    return listOf(ArchiveProblem.Unreadable("its main file ${manifest.rootFile} is not in the backup"))
                }

                when (val encryption = manifest.encryption) {
                    ArchiveEncryption.None -> block(manifest, plainPayload(zip, manifest.schemaVersion >= 3))

                    is ArchiveEncryption.Encrypted -> {
                        if (passphrase == null || derivation == null) {
                            return listOf(ArchiveProblem.PassphraseNeeded)
                        }
                        val sealed = zip.getEntry(BackupManifest.PAYLOAD_NAME)
                            ?: return listOf(ArchiveProblem.NotAnArchive)

                        // Staged in the app's own cache, never beside the archive. The
                        // archive can sit anywhere the user picked -- a Downloads folder, a
                        // synced folder, external storage -- and writing the whole decrypted
                        // project next to it put every account name and amount somewhere the
                        // user did not choose, for as long as the verify took.
                        //
                        // The temporary file is for `ZipFile`, which needs random access.
                        val scratch = workspace.cacheDir.apply { mkdirs() }
                        val temp = File.createTempFile("payload", ".zip", scratch)
                        decrypted = temp

                        try {
                            if (encryption.cipher == ArchiveEncryption.CHUNKED_CIPHER) {
                                // Streamed: the ciphertext is opened a chunk at a time and written
                                // straight to the temporary file (#54).
                                temp.outputStream().buffered().use { sink ->
                                    zip.getInputStream(sealed).use { source ->
                                        PayloadCipher.decryptTo(
                                            LimitedInput(source, MAX_STREAMED_PAYLOAD_BYTES, "encrypted project"),
                                            sink, passphrase, derivation, encryption,
                                        )
                                    }
                                }
                            } else {
                                // Archives from before chunking: sealed in one piece, so read whole.
                                temp.writeBytes(
                                    PayloadCipher.decrypt(
                                        ciphertext = readEntry(zip, sealed, MAX_PAYLOAD_BYTES, "encrypted project"),
                                        passphrase = passphrase,
                                        derivation = derivation,
                                        encryption = encryption,
                                    ),
                                )
                            }
                        } catch (ignored: PayloadCipher.WrongPassphrase) {
                            return listOf(ArchiveProblem.WrongPassphrase)
                        }

                        ZipFile(temp).use { inner ->
                            checkEntryCount(inner)
                            block(manifest, innerPayload(inner, manifest.schemaVersion >= 3))
                        }
                    }
                }
            }
        }.getOrElse { failure ->
            if (failure is TooLargeException) {
                listOf(ArchiveProblem.TooLarge(failure.what, failure.limit))
            } else {
                listOf(ArchiveProblem.Unreadable(failure.message ?: failure.javaClass.simpleName))
            }
        }.also {
            decrypted?.delete()
        }
    }

    private fun plainPayload(zip: ZipFile, schema3: Boolean) = object : Payload {
        override fun open(path: String): InputStream? =
            zip.getEntry(BackupManifest.PROJECT_PREFIX + path)?.let { zip.getInputStream(it) }

        override fun names(): Set<String> =
            zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name }
                .filterNot { it == BackupManifest.MANIFEST_NAME }
                // Before schema 3 there was no app data, so a name that looks like it is only an unlisted file.
                .filterNot { schema3 && it.startsWith(BackupManifest.APP_DATA_PREFIX) }
                .map { it.removePrefix(BackupManifest.PROJECT_PREFIX) }
                .toSet()

        override fun openAppData(path: String): InputStream? =
            if (!schema3) null else zip.getEntry(BackupManifest.APP_DATA_PREFIX + path)?.let { zip.getInputStream(it) }

        override fun appDataNames(): Set<String> =
            if (!schema3) emptySet() else zip.entries().asSequence()
                .filterNot { it.isDirectory }
                .map { it.name }
                .filter { it.startsWith(BackupManifest.APP_DATA_PREFIX) }
                .map { it.removePrefix(BackupManifest.APP_DATA_PREFIX) }
                .toSet()
    }

    private fun innerPayload(zip: ZipFile, schema3: Boolean) = object : Payload {
        override fun open(path: String): InputStream? =
            zip.getEntry(if (schema3) BackupManifest.PROJECT_PREFIX + path else path)?.let { zip.getInputStream(it) }

        override fun names(): Set<String> =
            zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }
                .filterNot { schema3 && it.startsWith(BackupManifest.APP_DATA_PREFIX) }
                .map { if (schema3) it.removePrefix(BackupManifest.PROJECT_PREFIX) else it }
                .toSet()

        override fun openAppData(path: String): InputStream? =
            if (!schema3) null else zip.getEntry(BackupManifest.APP_DATA_PREFIX + path)?.let { zip.getInputStream(it) }

        override fun appDataNames(): Set<String> =
            if (!schema3) emptySet() else zip.entries().asSequence().filterNot { it.isDirectory }.map { it.name }
                .filter { it.startsWith(BackupManifest.APP_DATA_PREFIX) }
                .map { it.removePrefix(BackupManifest.APP_DATA_PREFIX) }
                .toSet()
    }


    private class TooLargeException(val what: String, val limit: Long) :
        java.io.IOException("$what is over $limit bytes")

    /** Passes [source] through but fails with [TooLargeException] once more than [limit] bytes have been read. */
    private class LimitedInput(source: InputStream, private val limit: Long, private val what: String) :
        java.io.FilterInputStream(source) {
        private var count = 0L

        override fun read(): Int = super.read().also { if (it >= 0) add(1) }

        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) add(it) }

        private fun add(n: Int) {
            count += n
            if (count > limit) throw TooLargeException(what, limit)
        }
    }

    /**
     * Reads [entry] whole, but never more than [limit] bytes. The size the archive declares for an
     * entry is only a hint (a crafted file can understate it), so the read itself is counted too.
     */
    private fun readEntry(zip: ZipFile, entry: java.util.zip.ZipEntry, limit: Long, what: String): ByteArray {
        if (entry.size > limit) throw TooLargeException(what, limit)
        zip.getInputStream(entry).use { input ->
            val out = java.io.ByteArrayOutputStream(minOf(maxOf(entry.size, 0L), limit).toInt())
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                total += read
                if (total > limit) throw TooLargeException(what, limit)
                out.write(buffer, 0, read)
            }
            return out.toByteArray()
        }
    }

    /** Copies [file] into [sink], and throws if what was read is not the size and checksum the manifest was given. */
    private fun copyOne(root: File, path: String, entry: BackupManifest.Entry, bytes: ByteArray?, sink: OutputStream) {
        if (bytes != null) sink.write(bytes) else copyChecked(File(root, path), entry, sink)
    }

    /** The plugin list with the plugins in [ids] taken out; null when it cannot be read, so it is kept as it is. */
    private fun pluginIndexWithout(index: File, ids: Set<String>): ByteArray? {
        val root = Json.parse(index.readText()) as? Json.Obj ?: return null
        val kept = root.array("plugins")?.filter { (it as? Json.Obj)?.string("id") !in ids } ?: return null
        val json = Json.Obj(root.fields + ("plugins" to Json.Arr(kept)))
        return (json.write() + "\n").toByteArray()
    }

    private fun copyChecked(file: File, expected: BackupManifest.Entry, sink: OutputStream) {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
                sink.write(buffer, 0, read)
                size += read
            }
        }
        if (size != expected.size || digest.digest().toHex() != expected.sha256) {
            throw FileChangedWhileCopying(expected.path)
        }
    }

    private fun checkEntryCount(zip: ZipFile) {
        if (zip.size() > MAX_ENTRIES) throw TooLargeException("list of files", MAX_ENTRIES.toLong())
    }

    companion object {
        /** A manifest lists each file in about 150 bytes, so this is room for tens of thousands of files. */
        const val MAX_MANIFEST_BYTES = 8L * 1024 * 1024

        /**
         * Archives sealed in one piece (made before #54) are held in memory, then decrypted into
         * memory again, so this is what a phone's heap can take. A bigger one is refused up front.
         */
        const val MAX_PAYLOAD_BYTES = 256L * 1024 * 1024

        /**
         * What a chunked encrypted project may take on disk (#54). It is streamed, so memory is not
         * the limit; this only stops a crafted archive filling the cache.
         */
        const val MAX_STREAMED_PAYLOAD_BYTES = 8L * 1024 * 1024 * 1024

        const val MAX_ENTRIES = 100_000
        private const val PLUGIN_INDEX = "plugins/installed.json"

        /** One app-data file: a template list, a script or a preference is small, so a large one is refused. */
        const val MAX_APP_DATA_FILE_BYTES = 1024 * 1024

        /** How many app-data files one backup may hold. */
        const val MAX_APP_DATA_FILES = 256

        /** How many times a backup starts again because a file was saved while it was being made. */
        private const val MAX_ATTEMPTS = 3

        /**
         * Runtime state that must never travel in a backup.
         *
         * The journal, snapshots and drafts describe *this device's* in-flight work; mirror
         * state describes this device's view of a folder that may not exist elsewhere. They
         * live outside the project tree already — this is the second line of defence, for
         * anything that finds its way in.
         */
        private val EXCLUDED_DIRECTORIES = setOf(".journal", ".snapshots", ".drafts", ".cache")
        private val EXCLUDED_NAMES = setOf("mirror-state")
        private val DEVICE_PLUGIN_STATE = setOf("loading.marker", "safe-mode")

        fun isExcluded(relativePath: String): Boolean {
            val segments = relativePath.split('/')
            if (segments.any { it in EXCLUDED_DIRECTORIES }) return true
            if (segments.last() in EXCLUDED_NAMES) return true
            // The plugin safe-mode flag and crash marker describe the device they were made on.
            if (segments.size >= 2 && segments[segments.size - 2] == "plugins" &&
                segments.last() in DEVICE_PLUGIN_STATE
            ) {
                return true
            }
            // Beancount's load cache: rebuilt on every load, so mirroring or backing it up
            // produced a new conflict every time and restored nothing worth having.
            if (segments.last().endsWith(".picklecache")) return true
            // Temporary files from an interrupted write; the real file is beside them.
            return segments.last().endsWith(".tmp") || segments.last().endsWith(".part")
        }

        /**
         * Reads up to [limit] bytes of [input] and returns how many there were and their
         * SHA-256, holding nothing in memory but one buffer.
         */
        internal fun hashAtMost(input: InputStream, limit: Long): Pair<Long, String> {
            val digest = MessageDigest.getInstance("SHA-256")
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < limit) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - total).toInt())
                if (read < 0) break
                digest.update(buffer, 0, read)
                total += read
            }
            return total to digest.digest().toHex()
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

/**
 * Whether [file] is a Beanbook backup (`.bcaz`): a zip that holds the manifest and either the
 * project or its sealed payload. A ledger zip is not, and importing a backup as one would bring
 * in its manifest and the `project/` folder as if they were ledger files.
 */
fun looksLikeBackup(file: File): Boolean = runCatching {
    ZipFile(file).use { zip ->
        zip.getEntry(BackupManifest.MANIFEST_NAME) != null && (
            zip.getEntry(BackupManifest.PAYLOAD_NAME) != null ||
                zip.entries().asSequence().any { it.name.startsWith(BackupManifest.PROJECT_PREFIX) }
            )
    }
}.getOrDefault(false)
