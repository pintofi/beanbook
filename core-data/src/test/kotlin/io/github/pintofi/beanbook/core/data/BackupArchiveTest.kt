package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class BackupArchiveTest {

    private val root: File = Files.createTempDirectory("backup").toFile()
    private val workspace = ProjectWorkspace(root)
    private val archives = BackupArchive(workspace)
    private val now = Instant.parse("2026-08-24T10:00:00Z")

    init {
        workspace.createProject("p", title = "Backed Up")
        File(workspace.projectDir("p"), "documents/Assets/Cash").apply { mkdirs() }
            .resolve("2026-08-24.abc.pdf").writeText("a receipt")
    }

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    private fun writeArchive(name: String = "backup.bcaz"): File {
        val file = File(root, name)
        file.outputStream().use {
            archives.write("p", it, appVersion = "0.1.0", projectTitle = "Backed Up", now = now)
        }
        return file
    }

    @Test
    fun `an archive carries the whole project and says what is in it`() {
        val archive = writeArchive()

        val manifest = assertNotNull(archives.peek(archive))
        assertEquals(BackupManifest.SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals("p", manifest.projectId)
        assertEquals("Backed Up", manifest.projectTitle)
        assertEquals(now, manifest.createdAt)
        assertTrue(manifest.files.any { it.path == "main.beancount" }, "${manifest.files}")
        assertTrue(
            manifest.files.any { it.path == "documents/Assets/Cash/2026-08-24.abc.pdf" },
            "documents must travel with the project: ${manifest.files}",
        )
        assertEquals(emptyList(), archives.verify(archive))
    }

    @Test
    fun `the manifest is readable text, openable years from now without this app`() {
        val archive = writeArchive()

        val text = ZipFile(archive).use { zip ->
            zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString()
        }

        assertTrue(text.contains("\"project_title\": \"Backed Up\""), text)
        assertTrue(text.contains("\"created_at\": \"2026-08-24T10:00:00Z\""), text)
        assertTrue(text.contains("\"schema_version\": 1"), text)
    }

    @Test
    fun `runtime state never travels in a backup, and a file merely called key does`() {
        // Anything of this kind that finds its way into a project tree must still be left out.
        val project = workspace.projectDir("p")
        File(project, ".cache/parse.cache").apply { parentFile.mkdirs() }.writeText("cache")
        File(project, ".journal/write.wal").apply { parentFile.mkdirs() }.writeText("journal")
        // The person's own file, which a name-based exclusion used to leave out without a word.
        File(project, "key").writeText("theirs")
        File(project, "main.beancount.tmp").writeText("half a write")

        val manifest = assertNotNull(archives.peek(writeArchive()))

        val paths = manifest.files.map { it.path }
        assertFalse(paths.any { it.contains(".cache") }, paths.toString())
        assertFalse(paths.any { it.contains(".journal") }, paths.toString())
        assertTrue(paths.contains("key"), "a file the person called key was left out of the backup")
        assertFalse(paths.any { it.endsWith(".tmp") }, paths.toString())
    }

    @Test
    fun `a byte flipped inside the archive is caught, naming the file`() {
        val archive = writeArchive()
        val tampered = File(root, "tampered.bcaz")
        rewrite(archive, tampered) { name, bytes ->
            if (name == "project/main.beancount") "different content entirely".toByteArray() else bytes
        }

        val problem = archives.verify(tampered).single()

        assertIs<ArchiveProblem.SizeMismatch>(problem)
        assertEquals("main.beancount", problem.path)
        assertTrue(problem.describe().contains("Nothing has been restored"), problem.describe())
    }

    @Test
    fun `content changed without changing its length is still caught`() {
        val archive = writeArchive()
        val tampered = File(root, "same-length.bcaz")
        rewrite(archive, tampered) { name, bytes ->
            if (name == "project/main.beancount") ByteArray(bytes.size) { 'x'.code.toByte() } else bytes
        }

        val problem = archives.verify(tampered).single()

        assertIs<ArchiveProblem.ChecksumMismatch>(problem)
        assertEquals("main.beancount", problem.path)
    }

    @Test
    fun `a file listed in the manifest but missing from the archive is caught`() {
        val archive = writeArchive()
        val incomplete = File(root, "incomplete.bcaz")
        rewrite(archive, incomplete) { name, bytes ->
            if (name == "project/main.beancount") null else bytes
        }

        val problem = archives.verify(incomplete).single()

        assertIs<ArchiveProblem.MissingFile>(problem)
        assertEquals("main.beancount", problem.path)
    }

    @Test
    fun `a file smuggled into the archive that the manifest does not list is refused`() {
        val archive = writeArchive()
        val extra = File(root, "extra.bcaz")
        rewrite(archive, extra, add = mapOf("project/../escape.sh" to "rm -rf".toByteArray()))

        val problem = archives.verify(extra).single()

        assertIs<ArchiveProblem.UnexpectedFile>(problem)
    }

    @Test
    fun `an archive from a newer app says so instead of failing obscurely`() {
        val archive = writeArchive()
        val future = File(root, "future.bcaz")
        rewrite(archive, future) { name, bytes ->
            if (name == "manifest.json") {
                bytes.decodeToString().replace("\"schema_version\": 1", "\"schema_version\": 99")
                    .toByteArray()
            } else {
                bytes
            }
        }

        val problem = archives.verify(future).single()

        assertIs<ArchiveProblem.UnsupportedSchema>(problem)
        assertTrue(problem.describe().contains("Update the app"), problem.describe())
    }

    @Test
    fun `something that is not an archive at all is named as such`() {
        val notAnArchive = File(root, "photo.jpg").apply { writeBytes(ByteArray(64) { 0x7f }) }

        assertIs<ArchiveProblem.Unreadable>(archives.verify(notAnArchive).single())
    }

    @Test
    fun `a zip without a manifest is not treated as a backup`() {
        val plain = File(root, "plain.zip")
        ZipOutputStream(plain.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("project/main.beancount"))
            zip.write("2026-08-24 open Assets:Cash".toByteArray())
            zip.closeEntry()
        }

        assertIs<ArchiveProblem.NotAnArchive>(archives.verify(plain).single())
    }

    @Test
    fun `extracting a good archive reproduces the project byte for byte`() {
        val archive = writeArchive()
        val target = File(root, "restored")

        assertEquals(emptyList(), archives.extract(archive, target))

        val original = workspace.projectDir("p")
        val restoredFiles = target.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(target).path }.sorted().toList()
        val originalFiles = original.walkTopDown().filter { it.isFile }
            .map { it.relativeTo(original).path }.sorted()
            .filterNot { BackupArchive.isExcluded(it.replace(File.separatorChar, '/')) }
            .toList()

        assertEquals(originalFiles, restoredFiles)
        restoredFiles.forEach { path ->
            assertEquals(
                File(original, path).readBytes().toList(),
                File(target, path).readBytes().toList(),
                "$path differs after restore",
            )
        }
    }

    @Test
    fun `a damaged archive extracts nothing at all`() {
        val archive = writeArchive()
        val tampered = File(root, "damaged.bcaz")
        rewrite(archive, tampered) { name, bytes ->
            if (name == "project/main.beancount") "damaged".toByteArray() else bytes
        }
        val target = File(root, "not-restored")

        val problems = archives.extract(tampered, target)

        assertTrue(problems.isNotEmpty())
        assertFalse(target.exists(), "a failed restore must leave nothing behind")
    }

    /** Rebuilds an archive, transforming or dropping entries — how a damaged file happens. */
    private fun rewrite(
        source: File,
        target: File,
        add: Map<String, ByteArray> = emptyMap(),
        transform: (String, ByteArray) -> ByteArray? = { _, bytes -> bytes },
    ) {
        ZipFile(source).use { zip ->
            ZipOutputStream(target.outputStream()).use { out ->
                zip.entries().asSequence().forEach { entry ->
                    val bytes = zip.getInputStream(entry).readBytes()
                    transform(entry.name, bytes)?.let { kept ->
                        out.putNextEntry(ZipEntry(entry.name))
                        out.write(kept)
                        out.closeEntry()
                    }
                }
                add.forEach { (name, bytes) ->
                    out.putNextEntry(ZipEntry(name))
                    out.write(bytes)
                    out.closeEntry()
                }
            }
        }
    }

    /** B89: Beancount's load cache is rebuilt on every load; mirroring it conflicted forever. */
    @Test
    fun beancountsLoadCacheIsNeverCopied() {
        assertTrue(BackupArchive.isExcluded(".main.beancount.picklecache"))
        assertTrue(BackupArchive.isExcluded("files/.main.beancount.0123456789abcdef.picklecache"))
        assertFalse(BackupArchive.isExcluded("main.beancount"))
    }
}
