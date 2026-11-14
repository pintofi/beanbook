package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A backup records a main file that is not main.beancount, and an app that cannot read that refuses it. */
class BackupRootFileTest {

    private val base: File = Files.createTempDirectory("backuproot").toFile()
    private val workspace = ProjectWorkspace(base)
    private val archives = BackupArchive(workspace)
    private val plan = RestorePlan(workspace)
    private val now = Instant.parse("2026-11-15T10:00:00Z")

    @AfterTest fun cleanUp() = base.deleteRecursively().let { }

    private fun project(id: String, vararg files: Pair<String, String>) {
        files.forEach { (path, text) ->
            File(workspace.projectDir(id), path).apply { parentFile.mkdirs() }.writeText(text)
        }
    }

    private fun backup(id: String): File = File(base, "$id.bcaz").also { file ->
        file.outputStream().use { archives.write(id, it, appVersion = "1.0.5", now = now) }
    }

    private fun manifestText(archive: File): String =
        ZipFile(archive).use { zip -> zip.getInputStream(zip.getEntry("manifest.json")).readBytes().decodeToString() }

    @Test
    fun `a project whose main file is main dot beancount is still written as format 1 with no root`() {
        project("a", "main.beancount" to "")

        val manifest = archives.peek(backup("a"))!!

        assertEquals(1, manifest.schemaVersion)
        assertNull(manifest.rootFile)
        assertFalse(manifestText(File(base, "a.bcaz")).contains("root_file"))
    }

    @Test
    fun `a project with another main file is written as format 2 and says which file`() {
        project("a", "books/hmw" to "option \"title\" \"x\"\n")
        workspace.setRoot("a", "books/hmw")

        val archive = backup("a")
        val manifest = archives.peek(archive)!!

        assertEquals(2, manifest.schemaVersion)
        assertEquals("books/hmw", manifest.rootFile)
        assertTrue(archives.verify(archive).isEmpty())
    }

    @Test
    fun `an app that reads only format 1 refuses it as a newer format`() {
        project("a", "hmw" to "")
        workspace.setRoot("a", "hmw")
        val manifest = archives.peek(backup("a"))!!

        // The check every app makes: a format above the one it reads is not restored.
        val readsUpTo = 1
        assertTrue(manifest.schemaVersion > readsUpTo)
        assertEquals(
            ArchiveProblem.UnsupportedSchema(2).describe().contains("newer version of the app"), true,
        )
    }

    @Test
    fun `restoring as new gives the new project the recorded main file`() {
        project("a", "books/hmw" to "option \"title\" \"Mine\"\n", "books/more" to "")
        workspace.setRoot("a", "books/hmw")
        val archive = backup("a")
        workspace.projectDir("a").deleteRecursively()
        workspace.clearRoot("a")

        val restored = assertIs<RestoredNew.Restored>(plan.restoreAsNew(archive))

        assertEquals("books/hmw", workspace.rootPath(restored.projectId))
        assertTrue(workspace.exists(restored.projectId))
    }

    @Test
    fun `restoring over a project replaces its record with the backup's, and clears it for an ordinary backup`() {
        project("a", "main.beancount" to "plain\n")
        val ordinary = backup("a")
        project("b", "books/hmw" to "x\n")
        workspace.setRoot("b", "books/hmw")
        val special = backup("b")

        // Restoring the ordinary backup over a project that had a record clears it...
        val withRecord = "c"
        project(withRecord, "books/hmw" to "x\n", "main.beancount" to "old\n")
        workspace.setRoot(withRecord, "books/hmw")
        val stagedOrdinary = assertIs<RestoreStaged.Ready>(plan.stage(withRecord, ordinary))
        plan.activate(withRecord, stagedOrdinary.staging, now, stagedOrdinary.manifest)
        assertEquals("main.beancount", workspace.rootPath(withRecord))

        // ...and the special one sets it.
        val stagedSpecial = assertIs<RestoreStaged.Ready>(plan.stage(withRecord, special))
        plan.activate(withRecord, stagedSpecial.staging, now.plusSeconds(5), stagedSpecial.manifest)
        assertEquals("books/hmw", workspace.rootPath(withRecord))
    }

    @Test
    fun `a manifest that names a main file the backup does not hold is refused`() {
        project("a", "hmw" to "")
        workspace.setRoot("a", "hmw")
        val good = backup("a")
        val forged = File(base, "forged.bcaz")
        ZipFile(good).use { zip ->
            java.util.zip.ZipOutputStream(forged.outputStream()).use { out ->
                zip.entries().asSequence().forEach { entry ->
                    out.putNextEntry(java.util.zip.ZipEntry(entry.name))
                    val bytes = zip.getInputStream(entry).readBytes()
                    out.write(
                        if (entry.name == "manifest.json") {
                            bytes.decodeToString()
                                .replace(Regex("\"root_file\"\\s*:\\s*\"hmw\""), "\"root_file\":\"missing\"")
                                .toByteArray()
                        } else {
                            bytes
                        },
                    )
                    out.closeEntry()
                }
            }
        }

        val problems = archives.verify(forged)

        assertTrue(problems.any { it is ArchiveProblem.Unreadable }, problems.toString())
    }

    @Test
    fun `a main file path that leaves the project is not a manifest`() {
        val manifest = BackupManifest.fromJson(
            Json.parse(
                """{"schema_version":2,"app_version":"1","engine_versions":{},""" +
                    """"created_at":"2026-11-15T10:00:00Z","project_id":"a","project_title":"a",""" +
                    """"root_file":"../x","encryption":{"mode":"none"},"files":[]}""",
            ),
        )

        assertNull(manifest)
    }
}
