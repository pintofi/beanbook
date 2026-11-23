package io.github.pintofi.beanbook.core.data

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Beanbook's own data (templates, scripts, preferences) in a backup: chosen, verified, and kept apart. */
class BackupAppDataTest {

    private val base: File = Files.createTempDirectory("backupappdata").toFile()
    private val workspace = ProjectWorkspace(base)
    private val archives = BackupArchive(workspace)
    private val plan = RestorePlan(workspace)
    private val now = Instant.parse("2026-11-20T10:00:00Z")
    private val derive = KeyDerivation { passphrase, salt, _ ->
        MessageDigest.getInstance("SHA-256").digest(salt + String(passphrase).toByteArray())
    }
    private val schema3 = Regex("\"schema_version\"\\s*:\\s*3")
    private val cost = KdfCost(iterations = 1, memoryKib = 8, parallelism = 1)

    @AfterTest fun cleanUp() = base.deleteRecursively().let { }

    private fun project(id: String, vararg files: Pair<String, String>) {
        workspace.createProject(id, title = id)
        files.forEach { (path, text) ->
            File(workspace.projectDir(id), path).apply { parentFile.mkdirs() }.writeText(text)
        }
    }

    private val templates = AppDataPayload(AppDataGroup.TEMPLATES, "templates.templates", "rent\t1\n".toByteArray())
    private val script = AppDataPayload(AppDataGroup.SCRIPTS, "scripts/check.py", "print(1)\n".toByteArray())
    private val appearance =
        AppDataPayload(AppDataGroup.PREFERENCES, "preferences/appearance", "theme=dark\n".toByteArray())

    private fun backup(id: String, vararg appData: AppDataPayload, passphrase: String? = null): File =
        File(base, "$id-${System.nanoTime()}.bcaz").also { file ->
            file.outputStream().use {
                archives.write(
                    id, it, appVersion = "1.0.6", now = now, appData = appData.toList(),
                    passphrase = passphrase?.toCharArray(), derivation = passphrase?.let { derive }, cost = cost,
                )
            }
        }

    private fun manifestText(archive: File): String =
        ZipFile(archive).use { zip -> zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString() }

    @Test
    fun `a backup with no app data is written exactly as before`() {
        project("a", "main.beancount" to "")

        val archive = backup("a")

        assertEquals(1, archives.peek(archive)!!.schemaVersion)
        assertFalse(manifestText(archive).contains("app_data"))
        assertEquals(emptyList(), archives.verify(archive))
    }

    @Test
    fun `app data makes it format 3 and travels with the project, plain or sealed`() {
        project("a", "main.beancount" to "; ledger\n")
        for (passphrase in listOf(null, "correct horse")) {
            val archive = backup("a", templates, script, appearance, passphrase = passphrase)
            val manifest = archives.peek(archive)!!
            assertEquals(3, manifest.schemaVersion)
            assertEquals(3, manifest.appData.size)

            val chars = passphrase?.toCharArray()
            assertEquals(emptyList(), archives.verify(archive, chars, passphrase?.let { derive }), "$passphrase")

            val project = File(base, "out-$passphrase")
            val appData = File(base, "out-appdata-$passphrase")
            assertEquals(
                emptyList(),
                archives.extract(archive, project, chars, passphrase?.let { derive }, appDataTarget = appData),
            )
            assertEquals("; ledger\n", File(project, "main.beancount").readText())
            assertEquals("rent\t1\n", File(appData, "templates.templates").readText())
            assertEquals("print(1)\n", File(appData, "scripts/check.py").readText())
            assertEquals("theme=dark\n", File(appData, "preferences/appearance").readText())
        }
    }

    @Test
    fun `the readable manifest says which kinds there are and nothing about their names`() {
        project("a", "main.beancount" to "")
        val text = manifestText(backup("a", templates, script))

        assertTrue(text.contains("\"group\""))
        assertFalse(text.contains("rent"), "a template's name or content is not in the manifest")
        assertFalse(text.contains("print"))
    }

    @Test
    fun `only the chosen app data is written`() {
        project("a", "main.beancount" to "")

        val manifest = archives.peek(backup("a", script))!!

        assertEquals(listOf(AppDataGroup.SCRIPTS), manifest.appData.map { it.group })
    }

    @Test
    fun `a project file called appdata slash something does not collide with app data`() {
        project("a", "main.beancount" to "", "appdata/templates.templates" to "a project file\n")
        for (passphrase in listOf(null, "pw")) {
            val archive = backup("a", templates, passphrase = passphrase)
            val chars = passphrase?.toCharArray()
            val project = File(base, "c-$passphrase")
            val appData = File(base, "c-appdata-$passphrase")
            assertEquals(emptyList(), archives.extract(archive, project, chars, passphrase?.let { derive }, appData))
            assertEquals("a project file\n", File(project, "appdata/templates.templates").readText())
            assertEquals("rent\t1\n", File(appData, "templates.templates").readText())
        }
    }

    @Test
    fun `a changed app-data byte is a checksum mismatch and nothing is extracted`() {
        project("a", "main.beancount" to "")
        val archive = backup("a", templates)
        val tampered = rewrite(archive) { name, bytes ->
            if (name == "appdata/templates.templates") "rent\t2\n".toByteArray() else bytes
        }

        val problems = archives.verify(tampered)

        assertTrue(
            problems.any { it is ArchiveProblem.ChecksumMismatch && it.path == "appdata/templates.templates" },
            "$problems",
        )
        assertTrue(archives.extract(tampered, File(base, "x"), appDataTarget = File(base, "x-app")).isNotEmpty())
        assertFalse(File(base, "x-app").exists())
    }

    @Test
    fun `an app-data file the manifest does not list, or lists but lacks, is refused`() {
        project("a", "main.beancount" to "")
        val archive = backup("a", templates)

        val extra = addEntry(archive, "appdata/scripts/sneaky.py", "boom".toByteArray())
        assertTrue(
            archives.verify(extra)
                .any { it is ArchiveProblem.UnexpectedFile && it.path == "appdata/scripts/sneaky.py" },
        )

        val missing = rewrite(archive, drop = "appdata/templates.templates") { _, bytes -> bytes }
        assertTrue(archives.verify(missing).any { it is ArchiveProblem.MissingFile })
    }

    @Test
    fun `a manifest with app data below format 3, or a path off the group's shape, is not an archive`() {
        project("a", "main.beancount" to "")
        val archive = backup("a", templates)
        val text = manifestText(archive)

        val old = rewrite(archive) { name, bytes ->
            if (name == "manifest.json") text.replace(schema3, "\"schema_version\": 2").toByteArray() else bytes
        }
        assertNull(archives.peek(old), "app data in an older format")

        val traversal = rewrite(archive) { name, bytes ->
            if (name == "manifest.json") text.replace("templates.templates", "../escape").toByteArray() else bytes
        }
        assertNull(archives.peek(traversal), "a path outside the group's one name")

        val unknown = rewrite(archive) { name, bytes ->
            if (name == "manifest.json") text.replace("\"templates\"", "\"secrets\"").toByteArray() else bytes
        }
        assertNull(archives.peek(unknown), "a group this app does not know")
    }

    @Test
    fun `app data may only be put where its group allows`() {
        fun refused(group: AppDataGroup, path: String, size: Int = 1) =
            assertFailsWith<IllegalArgumentException> { AppDataPayload(group, path, ByteArray(size)) }
        refused(AppDataGroup.REMOTE, "remote-password")
        refused(AppDataGroup.SCRIPTS, "scripts/../x.py")
        refused(AppDataGroup.PREFERENCES, "preferences/backup-passphrase")
        refused(AppDataGroup.TEMPLATES, "templates.templates", BackupArchive.MAX_APP_DATA_FILE_BYTES + 1)
    }

    @Test
    fun `staging a restore keeps the app data apart from the project and discarding removes it`() {
        project("a", "main.beancount" to "")
        val archive = backup("a", templates)

        val staged = assertIs<RestoreStaged.Ready>(plan.stage("a", archive))

        val appData = staged.appDataStaging!!
        assertEquals("rent\t1\n", File(appData, "templates.templates").readText())
        assertFalse(File(staged.staging, "templates.templates").exists(), "app data is not inside the project")
        plan.discardStaging("a")
        assertFalse(appData.exists())
    }

    @Test
    fun `a backup without app data stages without an app-data folder`() {
        project("a", "main.beancount" to "")

        val staged = assertIs<RestoreStaged.Ready>(plan.stage("a", backup("a")))

        assertNull(staged.appDataStaging)
    }

    @Test
    fun `a plugin left out takes its files and its line in the plugin list with it`() {
        project(
            "a",
            "main.beancount" to "",
            "plugins/one/one.py" to "print(1)\n",
            "plugins/two/two.py" to "print(2)\n",
            "plugins/installed.json" to """
                {"schema":1,"plugins":[{"id":"one","name":"One"},{"id":"two","name":"Two"}]}
            """.trimIndent(),
        )
        val archive = File(base, "left-out.bcaz")
        archive.outputStream().use {
            archives.write("a", it, appVersion = "1.0.6", now = now, leaveOutPlugins = setOf("two"))
        }

        assertEquals(emptyList(), archives.verify(archive))
        val out = File(base, "left-out")
        assertEquals(emptyList(), archives.extract(archive, out))
        assertTrue(File(out, "plugins/one/one.py").isFile)
        assertFalse(File(out, "plugins/two").exists())
        val index = File(out, "plugins/installed.json").readText()
        assertTrue(index.contains("\"one\"") && !index.contains("\"two\""), index)
        assertTrue(File(workspace.projectDir("a"), "plugins/two/two.py").isFile, "the project itself is untouched")
    }

    /** The archive again with each entry passed through [change], and [drop] left out. */
    private fun rewrite(archive: File, drop: String? = null, change: (String, ByteArray) -> ByteArray): File {
        val out = File(base, "rw-${System.nanoTime()}.bcaz")
        ZipFile(archive).use { zip ->
            ZipOutputStream(out.outputStream()).use { sink ->
                zip.entries().asSequence().filter { it.name != drop }.forEach { entry ->
                    sink.putNextEntry(ZipEntry(entry.name))
                    sink.write(change(entry.name, zip.getInputStream(entry).readBytes()))
                    sink.closeEntry()
                }
            }
        }
        return out
    }

    private fun addEntry(archive: File, name: String, bytes: ByteArray): File {
        val out = File(base, "add-${System.nanoTime()}.bcaz")
        val copy = ByteArrayOutputStream()
        ZipFile(archive).use { zip ->
            ZipOutputStream(copy).use { sink ->
                zip.entries().asSequence().forEach { entry ->
                    sink.putNextEntry(ZipEntry(entry.name))
                    sink.write(zip.getInputStream(entry).readBytes())
                    sink.closeEntry()
                }
                sink.putNextEntry(ZipEntry(name))
                sink.write(bytes)
                sink.closeEntry()
            }
        }
        out.writeBytes(copy.toByteArray())
        return out
    }
}
