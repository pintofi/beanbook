package dev.beancount.core.data

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProjectRegistryTest {

    private val root: File = Files.createTempDirectory("registry").toFile()
    private val workspace = ProjectWorkspace(root.resolve("workspace"))
    private val registry = ProjectRegistry(workspace, root.resolve("state"))

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `the picker reads a title without parsing the ledger`() {
        workspace.createProject("household", title = "Household 2026")

        val summary = registry.summarise("household")

        assertEquals("Household 2026", summary.title)
        assertTrue(summary.fileCount >= 4)
        assertTrue(summary.describe().contains("files"))
    }

    @Test
    fun `a quoted title survives the round trip through the file`() {
        workspace.createProject("q", title = """Bob "B" Ltd""")
        assertEquals("""Bob "B" Ltd""", registry.summarise("q").title)
    }

    @Test
    fun `a project with no title falls back to its id`() {
        workspace.createProject("untitled", title = "untitled")
        workspace.mainFile("untitled").writeText("; no option here\n")
        assertEquals("untitled", registry.summarise("untitled").title)
    }

    @Test
    fun `the active project survives a restart`() {
        workspace.createProject("one")
        workspace.createProject("two")
        registry.setActive("two")

        val restarted = ProjectRegistry(workspace, root.resolve("state"))
        assertEquals("two", restarted.activeProjectId())
    }

    @Test
    fun `an active project that no longer exists reads as none`() {
        workspace.createProject("gone")
        registry.setActive("gone")
        registry.moveToTrash("gone")

        assertNull(registry.activeProjectId())
    }

    @Test
    fun `setting a nonexistent project active is refused`() {
        assertFailsWith<IllegalArgumentException> { registry.setActive("nope") }
    }

    @Test
    fun `deleting moves to the trash so the decision can be reversed`() {
        workspace.createProject("mistake", title = "Mistake")
        val ledger = workspace.mainFile("mistake").readText()

        val trashed = registry.moveToTrash("mistake")

        assertFalse(workspace.exists("mistake"), "the project must leave the workspace")
        assertTrue(trashed.resolve(ProjectWorkspace.MAIN_FILE).isFile, "files must be kept")
        assertEquals(1, registry.trashed().size)

        val restored = registry.restoreFromTrash(trashed)
        assertEquals("mistake", restored)
        assertTrue(workspace.exists("mistake"))
        assertEquals(ledger, workspace.mainFile("mistake").readText())
    }

    @Test
    fun `ids derived from titles are valid and never collide`() {
        val first = ProjectRegistry.idFor("My Household 2026!", emptySet())
        assertEquals("my-household-2026", first)

        val second = ProjectRegistry.idFor("My Household 2026!", setOf(first))
        assertEquals("my-household-2026-2", second)

        // Whatever the title, the id must satisfy ProjectWorkspace's strict validation.
        listOf("../escape", "  ", "Ünïcödé Ledger", "a".repeat(200)).forEach { title ->
            val id = ProjectRegistry.idFor(title, emptySet())
            workspace.createProject(id)
            assertTrue(workspace.exists(id), "generated id was rejected: '$id' from '$title'")
        }
    }

    @Test
    fun `summaries are newest first`() {
        workspace.createProject("older")
        Thread.sleep(1100)
        workspace.createProject("newer")

        assertEquals(listOf("newer", "older"), registry.summaries().map { it.id })
    }
}
