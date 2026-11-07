package io.github.pintofi.beanbook.core.data

import java.io.File
import java.nio.file.Files
import java.time.Instant
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A backup can be restored as a project of its own, with no project to restore over. */
class RestoreAsNewTest {

    private val now = Instant.parse("2026-11-01T00:00:00Z")

    private val root: File = Files.createTempDirectory("restore-new").toFile()
    private val workspace = ProjectWorkspace(root)
    private val archives = BackupArchive(workspace)
    private val restores = RestorePlan(workspace)

    @AfterTest
    fun cleanUp() = root.deleteRecursively().let { }

    private fun backupOf(id: String, title: String): File {
        workspace.createProject(id, title = title)
        File(workspace.projectDir(id), "files").mkdirs()
        File(workspace.projectDir(id), "files/notes.txt").writeText("kept")
        val file = File(root, "$id.bcaz")
        file.outputStream().use {
            archives.write(id, it, appVersion = "1.0", projectTitle = title, now = now)
        }
        return file
    }

    @Test fun `a backup comes back as a new project named from its title`() {
        val archive = backupOf("household", "Household")
        workspace.projectDir("household").deleteRecursively()

        val result = assertIs<RestoredNew.Restored>(restores.restoreAsNew(archive))

        assertEquals("Household", result.manifest.projectTitle)
        assertTrue(workspace.projectDir(result.projectId).isDirectory)
        assertEquals("kept", File(workspace.projectDir(result.projectId), "files/notes.txt").readText())
        assertTrue(File(workspace.projectDir(result.projectId), "main.beancount").isFile)
    }

    @Test fun `a project already there is not touched, and the new one gets its own id`() {
        val archive = backupOf("household", "Household")
        File(workspace.projectDir("household"), "files/notes.txt").writeText("edited since")

        val result = assertIs<RestoredNew.Restored>(restores.restoreAsNew(archive))

        assertTrue(result.projectId != "household", result.projectId)
        assertEquals("edited since", File(workspace.projectDir("household"), "files/notes.txt").readText())
        assertEquals("kept", File(workspace.projectDir(result.projectId), "files/notes.txt").readText())
    }

    @Test fun `a file that is not a backup is refused and leaves nothing behind`() {
        val notABackup = File(root, "x.zip").apply { writeText("not a zip") }
        val before = workspace.projectsDir.list().orEmpty().toSet()

        assertIs<RestoredNew.Refused>(restores.restoreAsNew(notABackup))
        assertEquals(before, workspace.projectsDir.list().orEmpty().toSet())
    }
}
