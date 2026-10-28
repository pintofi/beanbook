package io.github.pintofi.beanbook.core.data

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
    fun `an empty or blank active-project file means no project is open and does not throw`() {
        workspace.createProject("household", title = "Household")
        val state = root.resolve("state").also { it.mkdirs() }

        state.resolve("active-project").writeText("")
        assertNull(registry.activeProjectId())

        state.resolve("active-project").writeText("  \n")
        assertNull(registry.activeProjectId())

        registry.setActive("household")
        assertEquals("household", registry.activeProjectId())
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
    fun `deleting the open project opens the most recently changed other one`() {
        listOf("old", "recent", "open").forEach { workspace.createProject(it) }
        // A project's age is its newest file, so every file in it is dated, not only the main one.
        mapOf("old" to 1_000_000_000_000, "recent" to 1_700_000_000_000, "open" to 1_600_000_000_000)
            .forEach { (id, time) ->
                workspace.projectDir(id).walkTopDown().filter { it.isFile }.forEach { it.setLastModified(time) }
            }
        registry.setActive("open")

        registry.moveToTrash("open")

        assertEquals("recent", registry.activeProjectId())
        assertEquals(listOf("old", "recent"), workspace.listProjects())
    }

    @Test
    fun `deleting a project that is not open leaves the open one open`() {
        listOf("one", "two", "three").forEach { workspace.createProject(it) }
        registry.setActive("two")

        registry.moveToTrash("one")

        assertEquals("two", registry.activeProjectId())
    }

    @Test
    fun `deleting the only project leaves none open`() {
        workspace.createProject("only")
        registry.setActive("only")

        registry.moveToTrash("only")

        assertNull(registry.activeProjectId())
        assertEquals(emptyList(), workspace.listProjects())
    }

    @Test
    fun `a folder with no ledger in it still takes its id`() {
        // What an interrupted import can leave: a folder with a project's name that is not a project.
        workspace.projectDir("primary-documents-hamza-beanbook").mkdirs()
        assertEquals(emptyList(), workspace.listProjects())

        val id = ProjectRegistry.idFor("primary:Documents/Hamza/Beanbook", workspace.takenIds())

        assertEquals("primary-documents-hamza-beanbook-2", id)
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

    @Test
    fun `the trash lists what is in it with its title and size`() {
        workspace.createProject("old", title = "Old books")
        registry.moveToTrash("old")

        val item = registry.trashSummaries().single()

        assertEquals("old", item.projectId)
        assertEquals("Old books", item.title)
        assertTrue(item.fileCount >= 1)
        assertTrue(item.totalBytes > 0)
    }

    @Test
    fun `a trashed project is restored by name and one can be deleted for good`() {
        workspace.createProject("a", title = "A")
        workspace.createProject("b", title = "B")
        registry.moveToTrash("a")
        Thread.sleep(2)
        registry.moveToTrash("b")
        val (b, a) = registry.trashSummaries()

        assertEquals("a", registry.restoreFromTrash(a.folder))
        assertTrue(workspace.exists("a"))

        assertTrue(registry.deleteFromTrash(b.folder))
        assertTrue(registry.trashSummaries().isEmpty())
        assertFalse(workspace.exists("b"))
    }

    @Test
    fun `restoring a project whose id was reused comes back under the next free id`() {
        workspace.createProject("ledger", title = "Ledger")
        val old = registry.moveToTrash("ledger")
        val oldText = old.resolve(ProjectWorkspace.MAIN_FILE).readText()
        workspace.createProject("ledger", title = "Ledger")
        val newerText = workspace.mainFile("ledger").readText()

        val restored = registry.restoreFromTrash(old)

        assertEquals("ledger-2", restored)
        assertEquals(oldText, workspace.mainFile("ledger-2").readText(), "the trashed files come back intact")
        assertEquals(newerText, workspace.mainFile("ledger").readText(), "the newer project is untouched")
        assertTrue(registry.trashSummaries().isEmpty(), "nothing is left in the trash")
    }

    @Test
    fun `restoring twice over the same id counts upward`() {
        workspace.createProject("p", title = "P")
        val first = registry.moveToTrash("p")
        Thread.sleep(2)
        workspace.createProject("p", title = "P")
        val second = registry.moveToTrash("p")
        workspace.createProject("p", title = "P")

        assertEquals("p-2", registry.restoreFromTrash(first))
        assertEquals("p-3", registry.restoreFromTrash(second))
    }

    @Test
    fun `emptying the trash removes every trashed project and nothing else`() {
        workspace.createProject("keep", title = "Keep")
        workspace.createProject("x", title = "X")
        workspace.createProject("y", title = "Y")
        registry.moveToTrash("x")
        registry.moveToTrash("y")

        assertEquals(2, registry.emptyTrash())

        assertTrue(registry.trashSummaries().isEmpty())
        assertTrue(workspace.exists("keep"))
    }

    @Test
    fun `a trash name cannot reach outside the trash`() {
        workspace.createProject("safe", title = "Safe")

        assertFailsWith<IllegalArgumentException> { registry.deleteFromTrash("../projects/safe") }
        assertFailsWith<IllegalArgumentException> { registry.deleteFromTrash("..") }

        assertTrue(workspace.exists("safe"))
    }
}
