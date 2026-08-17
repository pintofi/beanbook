package dev.beancount.core.data

import java.io.File
import java.nio.file.Files
import java.time.LocalDate
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ProjectWorkspaceTest {

    private val root: File = Files.createTempDirectory("workspace-test").toFile()
    private val workspace = ProjectWorkspace(root)

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    @Test
    fun `a new project has the documented layout`() {
        val main = workspace.createProject("household", today = LocalDate.of(2026, 1, 1))

        assertTrue(main.isFile)
        assertEquals("main.beancount", main.name)
        val dir = workspace.projectDir("household")
        listOf("files", "documents", "prices", "scripts", "plugins").forEach {
            assertTrue(dir.resolve(it).isDirectory, "missing directory: $it")
        }
        assertTrue(dir.resolve("files/accounts.beancount").isFile)
    }

    @Test
    fun `includes are relative, so the project stays portable`() {
        val main = workspace.createProject("household")
        val includes = main.readLines().filter { it.startsWith("include") }

        assertEquals(3, includes.size)
        assertTrue(includes.all { it.contains("\"files/") }, "includes must be relative: $includes")
        assertFalse(includes.any { it.contains(root.absolutePath) }, "absolute path leaked into the ledger")
    }

    @Test
    fun `the starter ledger declares every account it will need`() {
        val main = workspace.createProject("household")
        val accounts = main.parentFile.resolve("files/accounts.beancount").readText()

        listOf("Assets:Cash", "Expenses:Food:Groceries", "Income:Salary", "Equity:Opening-Balances")
            .forEach { assertTrue(accounts.contains("open $it"), "missing open for $it") }
    }

    @Test
    fun `a title containing a quote does not corrupt the ledger`() {
        val main = workspace.createProject("q", title = "Bob\"s books")
        val option = main.readLines().first { it.startsWith("option \"title\"") }
        assertEquals("""option "title" "Bob\"s books"""", option)
    }

    @Test
    fun `runtime state lives outside the project tree`() {
        workspace.createProject("household")
        val projectFiles = workspace.projectDir("household").walkTopDown().map { it.name }.toSet()

        assertFalse(projectFiles.contains("cache"))
        assertFalse(projectFiles.contains("journal"))
        assertFalse(projectFiles.contains("snapshots"))
        assertFalse(workspace.cacheDir.startsWith(workspace.projectDir("household")))
    }

    @Test
    fun `path traversal in a project id is refused`() {
        listOf("../escape", "..", ".", "a/b", "a\\b", ".hidden", "", "  ").forEach { id ->
            assertFailsWith<IllegalArgumentException>("id should be rejected: '$id'") {
                workspace.projectDir(id)
            }
        }
    }

    @Test
    fun `creating the same project twice is refused rather than overwriting`() {
        workspace.createProject("household")
        assertFailsWith<IllegalArgumentException> { workspace.createProject("household") }
    }

    @Test
    fun `listing finds only directories that really contain a ledger`() {
        workspace.createProject("one")
        workspace.createProject("two")
        workspace.projectsDir.resolve("not-a-project").mkdirs()

        assertEquals(listOf("one", "two"), workspace.listProjects())
        assertTrue(workspace.exists("one"))
        assertFalse(workspace.exists("not-a-project"))
    }
}
